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
import io.lantern.extract.ExtractorVocabularies;
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
    List<Row> rows = rows(connection);

    Map<String, String> claimOf = new HashMap<>();
    for (Row row : rows) {
      claimOf.put(
          row.id(),
          ClaimId.of(
              row.subject(),
              row.predicate(),
              row.object(),
              row.attributes(),
              keys(vocabulary, row.predicate(), row.attributes())));
    }

    Map<String, Optional<Map<String, String>>> trees = new HashMap<>();
    Map<String, List<Version>> versions = new LinkedHashMap<>();
    for (Row row : rows) {
      Fact fact =
          Fact.create(
                  row.subject(),
                  row.predicate(),
                  row.object(),
                  row.attributes(),
                  FactJson.originFromJson(row.origin()),
                  evidence(row.evidence(), folders, trees),
                  row.confidence(),
                  new Validity(row.from(), row.to()))
              .withLineage(Lineage.of(row.attributes(), claimOf::get));
      fact = fact.stored(claimOf.get(row.id()), fact.validity());
      versions
          .computeIfAbsent(fact.id(), id -> new ArrayList<>())
          .add(new Version(fact, row.from(), row.to()));
    }

    List<Version> joined = new ArrayList<>();
    versions.values().forEach(all -> joined.addAll(joined(all)));
    Map<String, Fact> claims = new LinkedHashMap<>();
    joined.forEach(v -> claims.putIfAbsent(v.fact().claimId(), v.fact()));
    try (PreparedStatement claim =
        connection.prepareStatement(
            """
            INSERT INTO claims (id, subject, predicate, object_kind, object, key_attributes)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
            """)) {
      for (Fact fact : claims.values()) {
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
      for (Version version : joined) {
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
      }
      observation.executeBatch();
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE facts");
    }
  }

  private static List<Row> rows(Connection connection) throws Exception {
    List<Row> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                """
                SELECT id, subject, predicate, object_kind, object, attributes::text,
                       origin::text, evidence::text, confidence,
                       lower(validity), upper(validity)
                FROM facts ORDER BY id, lower(validity)
                """)) {
      while (rs.next()) {
        long to = rs.getLong(11);
        Long end = rs.wasNull() ? null : to;
        rows.add(
            new Row(
                rs.getString(1),
                EntityId.parse(rs.getString(2)),
                rs.getString(3),
                new FactObject(FactObject.Kind.fromJsonName(rs.getString(4)), rs.getString(5)),
                (ObjectNode) FactJson.parse(rs.getString(6)),
                FactJson.parse(rs.getString(7)),
                FactJson.parse(rs.getString(8)),
                rs.getDouble(9),
                rs.getLong(10),
                end));
      }
    }
    return rows;
  }

  /** Every type the app's sources define, as the app loads them. */
  private static Vocabulary vocabulary() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    ExtractorVocabularies.bySource().values().forEach(types::addAll);
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
  private static Evidence evidence(
      JsonNode old, Map<String, Path> folders, Map<String, Optional<Map<String, String>>> trees) {
    String repo = old.path("repo").asString();
    String commit = old.path("commit").asString();
    String path = old.hasNonNull("path") ? old.get("path").asString() : null;
    JsonNode lines = old.get("lines");
    Evidence.LineRange range =
        lines == null || lines.isNull()
            ? null
            : new Evidence.LineRange(lines.get("start").asInt(), lines.get("end").asInt());
    String contentHash = old.hasNonNull("contentHash") ? old.get("contentHash").asString() : null;
    String blob = null;
    if (path != null) {
      blob =
          trees
              .computeIfAbsent(repo + "@" + commit, k -> GitTree.blobs(folders.get(repo), commit))
              .map(tree -> tree.get(path))
              .orElse(null);
    }
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
   * two with different confidences touch or overlap, the earlier ends where the later starts.
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
