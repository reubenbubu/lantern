package db.migration;

import io.lantern.core.fact.ClaimId;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Lineage;
import io.lantern.core.fact.Validity;
import io.lantern.core.repo.GitTree;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.Extractors;
import io.lantern.link.LinkVocabularies;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces the {@code facts} table with claims and observations (ADR 0091). Each stored fact
 * becomes an observation of its claim:
 *
 * <ul>
 *   <li>its claim ID comes from the key attributes the app's sources give its type now (ADR 0090);
 *       a type no source defines any more keys on all its attributes;
 *   <li>its evidence names the file's git blob, looked up at the fact's commit in the repository's
 *       registered folder, instead of the commit; a file that cannot be read keeps its content hash
 *       and has no blob;
 *   <li>its lineage is the claims of the facts its attributes name ({@link Lineage});
 *   <li>facts that become the same observation (one sighting at two commits) are joined into one
 *       row per run of adjacent or overlapping snapshots with one confidence.
 * </ul>
 */
public class V19__claims_and_observations extends BaseJavaMigration {

  private static final String SCHEMA =
      """
      CREATE TABLE claims (
          id             char(64) PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
          subject        text     NOT NULL,
          predicate      text     NOT NULL,
          object_kind    text     NOT NULL
              CHECK (object_kind IN ('entity', 'string', 'integer', 'decimal', 'boolean')),
          object         text     NOT NULL,
          key_attributes jsonb    NOT NULL DEFAULT '{}'::jsonb
              CHECK (jsonb_typeof(key_attributes) = 'object')
      );
      CREATE INDEX claims_subject_idx ON claims (subject, predicate);
      CREATE INDEX claims_predicate_idx ON claims (predicate);
      CREATE INDEX claims_object_idx ON claims (object, object_kind);

      CREATE TABLE observations (
          id         char(64)         NOT NULL CHECK (id ~ '^[0-9a-f]{64}$'),
          claim_id   char(64)         NOT NULL REFERENCES claims (id),
          origin     jsonb            NOT NULL
              CHECK (jsonb_typeof(origin) = 'object' AND origin ? 'kind' AND origin ? 'name'),
          attributes jsonb            NOT NULL DEFAULT '{}'::jsonb
              CHECK (jsonb_typeof(attributes) = 'object'),
          evidence   jsonb            NOT NULL
              CHECK (jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) >= 1
                     AND evidence->0 ? 'repo'),
          repo       text             GENERATED ALWAYS AS (evidence->0->>'repo') STORED,
          lineage    text[]           NOT NULL DEFAULT '{}',
          confidence double precision NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
          validity   int8range        NOT NULL
              CHECK (NOT isempty(validity) AND NOT lower_inf(validity) AND lower(validity) >= 0),
          CONSTRAINT observations_versions_do_not_overlap
              EXCLUDE USING gist (id WITH =, validity WITH &&)
      );
      CREATE INDEX observations_id_idx ON observations (id);
      CREATE INDEX observations_claim_idx ON observations (claim_id);
      CREATE INDEX observations_repo_idx ON observations (repo);
      CREATE INDEX observations_validity_idx ON observations USING gist (validity);
      """;

  /** A stored fact as V2 to V17 kept it. */
  private record Row(
      String id,
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      JsonNode origin,
      JsonNode evidence,
      double confidence,
      long from,
      Long to) {}

  /** One version of a migrated observation. */
  private record Version(Fact fact, long from, Long to) {}

  /** Rows read and inserted at a time. */
  private static final int BATCH = 1000;

  private static final String COLUMNS =
      """
      id, subject, predicate, object_kind, object, attributes::text, origin::text,
      evidence::text, confidence, lower(validity), upper(validity)
      """;

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (Statement statement = connection.createStatement()) {
      statement.execute(SCHEMA);
    }
    Vocabulary vocabulary = vocabulary();
    Map<String, Path> folders = new HashMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT name, path FROM repositories")) {
      while (rs.next()) {
        folders.put(rs.getString(1), Path.of(rs.getString(2)));
      }
    }
    Map<String, String> claimOf = referencedClaims(connection, vocabulary);

    // The facts are read a batch at a time, by repository and commit, so one git tree is held at
    // a time; what is kept is one fact per observation and its versions.
    Map<String, List<Version>> versions = new LinkedHashMap<>();
    Set<String> claims = new HashSet<>();
    String treeKey = null;
    Optional<Map<String, String>> tree = Optional.empty();
    try (PreparedStatement claim =
            connection.prepareStatement(
                """
                INSERT INTO claims (id, subject, predicate, object_kind, object, key_attributes)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """);
        Statement statement = connection.createStatement()) {
      statement.setFetchSize(BATCH);
      int pending = 0;
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT "
                  + COLUMNS
                  + " FROM facts ORDER BY evidence->>'repo', evidence->>'commit', id,"
                  + " lower(validity)")) {
        while (rs.next()) {
          Row row = row(rs);
          String key = row.evidence().path("repo").asString() + "@" + commit(row.evidence());
          if (!key.equals(treeKey)) {
            treeKey = key;
            tree =
                GitTree.blobs(
                    folders.get(row.evidence().path("repo").asString()), commit(row.evidence()));
          }
          Fact fact =
              Fact.create(
                      row.subject(),
                      row.predicate(),
                      row.object(),
                      row.attributes(),
                      FactJson.originFromJson(row.origin()),
                      evidence(row.evidence(), tree),
                      row.confidence(),
                      new Validity(row.from(), row.to()))
                  .withLineage(Lineage.of(row.attributes(), claimOf::get));
          fact = fact.stored(claimId(vocabulary, fact), fact.validity());
          List<Version> known = versions.computeIfAbsent(fact.id(), id -> new ArrayList<>());
          // Versions of one observation share its content; one fact of it is enough.
          Fact first = known.isEmpty() ? fact : known.getFirst().fact();
          known.add(new Version(first, row.from(), row.to()));
          if (claims.add(fact.claimId())) {
            claim.setString(1, fact.claimId());
            claim.setString(2, fact.subject().toString());
            claim.setString(3, fact.predicate());
            claim.setString(4, fact.object().kind().jsonName());
            claim.setString(5, fact.object().value());
            claim.setString(
                6,
                json(
                    split(
                        fact.attributes(),
                        keys(vocabulary, fact.predicate(), fact.attributes()),
                        true)));
            claim.addBatch();
            if (++pending == BATCH) {
              claim.executeBatch();
              pending = 0;
            }
          }
        }
      }
      claim.executeBatch();
    }

    try (PreparedStatement observation =
        connection.prepareStatement(
            """
            INSERT INTO observations (id, claim_id, origin, attributes, evidence, lineage,
                                      confidence, validity)
            VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS text[]), ?, int8range(?, ?, '[)'))
            """)) {
      int pending = 0;
      for (List<Version> all : versions.values()) {
        for (Version version : joined(all)) {
          Fact fact = version.fact();
          Set<String> keys = keys(vocabulary, fact.predicate(), fact.attributes());
          observation.setString(1, fact.id());
          observation.setString(2, fact.claimId());
          observation.setString(3, json(FactJson.toJson(fact.origin())));
          observation.setString(4, json(split(fact.attributes(), keys, false)));
          observation.setString(5, json(FactJson.toJson(fact.evidence())));
          observation.setString(6, "{" + String.join(",", fact.lineage()) + "}");
          observation.setDouble(7, fact.confidence());
          observation.setLong(8, version.from());
          if (version.to() == null) {
            observation.setNull(9, Types.BIGINT);
          } else {
            observation.setLong(9, version.to());
          }
          observation.addBatch();
          if (++pending == BATCH) {
            observation.executeBatch();
            pending = 0;
          }
        }
      }
      observation.executeBatch();
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE facts");
    }
  }

  /**
   * The claim IDs of the facts that other facts name in their lineage attributes ({@link
   * Lineage#ATTRIBUTES}), by their old fact ID: only those are needed to map lineage.
   */
  private static Map<String, String> referencedClaims(Connection connection, Vocabulary vocabulary)
      throws Exception {
    Set<String> referenced = new HashSet<>();
    try (Statement statement = connection.createStatement()) {
      statement.setFetchSize(BATCH);
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT attributes::text FROM facts WHERE attributes ?| array['"
                  + String.join("','", Lineage.ATTRIBUTES)
                  + "']")) {
        while (rs.next()) {
          Lineage.of(
              (ObjectNode) FactJson.parse(rs.getString(1)),
              id -> {
                referenced.add(id);
                return null;
              });
        }
      }
    }
    Map<String, String> claimOf = new HashMap<>();
    List<String> ids = new ArrayList<>(referenced);
    for (int start = 0; start < ids.size(); start += BATCH) {
      List<String> chunk = ids.subList(start, Math.min(ids.size(), start + BATCH));
      try (PreparedStatement select =
          connection.prepareStatement("SELECT " + COLUMNS + " FROM facts WHERE id = ANY (?)")) {
        select.setArray(1, connection.createArrayOf("text", chunk.toArray()));
        try (ResultSet rs = select.executeQuery()) {
          while (rs.next()) {
            Row row = row(rs);
            claimOf.put(
                row.id(),
                ClaimId.of(
                    row.subject(),
                    row.predicate(),
                    row.object(),
                    row.attributes(),
                    keys(vocabulary, row.predicate(), row.attributes())));
          }
        }
      }
    }
    return claimOf;
  }

  private static String claimId(Vocabulary vocabulary, Fact fact) {
    return ClaimId.of(
        fact.subject(),
        fact.predicate(),
        fact.object(),
        fact.attributes(),
        keys(vocabulary, fact.predicate(), fact.attributes()));
  }

  private static String commit(JsonNode evidence) {
    return evidence.path("commit").asString();
  }

  private static Row row(ResultSet rs) throws Exception {
    long to = rs.getLong(11);
    Long end = rs.wasNull() ? null : to;
    return new Row(
        rs.getString(1),
        EntityId.parse(rs.getString(2)),
        rs.getString(3),
        new FactObject(FactObject.Kind.fromJsonName(rs.getString(4)), rs.getString(5)),
        (ObjectNode) FactJson.parse(rs.getString(6)),
        FactJson.parse(rs.getString(7)),
        FactJson.parse(rs.getString(8)),
        rs.getDouble(9),
        rs.getLong(10),
        end);
  }

  /** Every type the app's sources define, as the app loads them. */
  private static Vocabulary vocabulary() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    Extractors.vocabularies().values().forEach(types::addAll);
    LinkVocabularies.bySource().values().forEach(types::addAll);
    return new Vocabulary(1, types);
  }

  /** The key attributes of a fact: its type's, or all its attributes for a type no source has. */
  private static Set<String> keys(Vocabulary vocabulary, String predicate, ObjectNode attributes) {
    return vocabulary.find(predicate).isPresent()
        ? vocabulary.keyAttributes(predicate)
        : new TreeSet<>(attributes.propertyNames());
  }

  /** The old evidence as one declared location, with the file's blob where git still has it. */
  private static Evidence evidence(JsonNode old, Optional<Map<String, String>> tree) {
    String repo = old.path("repo").asString();
    String path = old.hasNonNull("path") ? old.get("path").asString() : null;
    JsonNode lines = old.get("lines");
    Evidence.LineRange range =
        lines == null || lines.isNull()
            ? null
            : new Evidence.LineRange(lines.get("start").asInt(), lines.get("end").asInt());
    String contentHash = old.hasNonNull("contentHash") ? old.get("contentHash").asString() : null;
    String blob = path == null ? null : tree.map(files -> files.get(path)).orElse(null);
    return new Evidence(
        List.of(
            new Evidence.Location(
                repo,
                path,
                range,
                blob,
                blob == null ? contentHash : null,
                Evidence.Role.DECLARED)));
  }

  /**
   * The versions of one observation, joined where they touch or overlap with one confidence; where
   * two with different confidences touch or overlap, the later holds for its range and the earlier
   * for the rest of its own.
   */
  private static List<Version> joined(List<Version> all) {
    List<Version> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparingLong(Version::from));
    List<Version> joined = new ArrayList<>();
    for (Version version : sorted) {
      Version last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
      boolean touches = last != null && (last.to() == null || version.from() <= last.to());
      if (!touches) {
        joined.add(version);
      } else if (last.fact().confidence() == version.fact().confidence()) {
        Long to =
            last.to() == null || version.to() == null ? null : Math.max(last.to(), version.to());
        joined.set(joined.size() - 1, new Version(last.fact(), last.from(), to));
      } else {
        if (version.from() > last.from()) {
          joined.set(joined.size() - 1, new Version(last.fact(), last.from(), version.from()));
        } else {
          joined.remove(joined.size() - 1);
        }
        joined.add(version);
        // The earlier version's range after the later one's, if any, is kept.
        if (version.to() != null && (last.to() == null || last.to() > version.to())) {
          joined.add(new Version(last.fact(), version.to(), last.to()));
        }
      }
    }
    return joined;
  }

  private static ObjectNode split(ObjectNode attributes, Set<String> keys, boolean key) {
    ObjectNode part = FactJson.newObject();
    attributes
        .properties()
        .forEach(
            e -> {
              if (keys.contains(e.getKey()) == key) {
                part.set(e.getKey(), e.getValue());
              }
            });
    return part;
  }

  private static String json(JsonNode node) {
    return node.toString();
  }
}
