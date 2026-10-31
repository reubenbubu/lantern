package io.lantern.core.vocabulary;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactValidator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Stores the vocabulary in the {@code vocabulary_revisions} and {@code vocabulary_types} tables.
 *
 * <p>Every change creates a new revision, numbered 1, 2, 3 and so on; revision 0 is the empty
 * vocabulary. A revision stores the new definition of each type it changed, so the vocabulary as of
 * any revision can be read back. Changes must keep earlier facts valid (see {@link
 * Vocabulary#changes}); a type may change its parent.
 *
 * <p>A type belongs to the source that first defined it, the source of the earliest revision that
 * holds it. Only that source may change it; another source may apply the same definition again,
 * which changes nothing, but a different one is refused, so one source cannot take over or clash
 * with another's type.
 *
 * <p>As a {@link FactValidator} it checks facts against the latest revision.
 */
public class VocabularyRegistry implements FactValidator {

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;
  private volatile Vocabulary cached = Vocabulary.empty();

  public VocabularyRegistry(JdbcClient jdbc, TransactionOperations transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  /** The latest revision of the vocabulary. */
  public Vocabulary current() {
    long latest = latestRevision();
    Vocabulary vocabulary = cached;
    if (vocabulary.revision() != latest) {
      vocabulary = load(latest);
      cached = vocabulary;
    }
    return vocabulary;
  }

  /**
   * The vocabulary as of a revision.
   *
   * @throws IllegalArgumentException if there is no such revision
   */
  public Vocabulary at(long revision) {
    if (revision < 0 || revision > latestRevision()) {
      throw new IllegalArgumentException("No vocabulary revision " + revision);
    }
    Vocabulary vocabulary = cached;
    return vocabulary.revision() == revision ? vocabulary : load(revision);
  }

  /**
   * Adds or updates types. Types not in the vocabulary yet are added; types whose definition
   * differs are updated; unchanged definitions are ignored. If anything changes, all of it goes
   * into one new revision; if nothing changes, no revision is created.
   *
   * @param source who or what made the change, for example {@code built-in}, a plugin or a user
   * @param definitions the new definitions
   * @return the revision that holds the definitions: the new one, or the latest if nothing changed
   * @throws IllegalArgumentException if a change makes a type accept less than before, changes a
   *     type's kind, changes a type that belongs to another source (see {@link #owner}) or would
   *     leave the vocabulary inconsistent; nothing is stored then
   */
  public long apply(String source, Collection<VocabularyType> definitions) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(definitions, "definitions");
    if (source.isBlank()) {
      throw new IllegalArgumentException("Source of a vocabulary change must not be blank");
    }
    List<VocabularyType> copy = List.copyOf(definitions);
    Long revision =
        transactions.execute(
            status -> {
              // One writer at a time, so revisions are numbered without gaps or races.
              jdbc.sql("LOCK TABLE vocabulary_revisions IN EXCLUSIVE MODE").update();
              Vocabulary current = load(latestRevision());
              checkOwners(source, current, copy);
              List<VocabularyType> changes = current.changes(copy);
              if (changes.isEmpty()) {
                return current.revision();
              }
              Map<String, VocabularyType> merged = new TreeMap<>();
              current.types().forEach(t -> merged.put(t.name(), t));
              changes.forEach(t -> merged.put(t.name(), t));
              long next = current.revision() + 1;
              new Vocabulary(next, merged.values());
              jdbc.sql("INSERT INTO vocabulary_revisions (revision, source) VALUES (:r, :source)")
                  .param("r", next)
                  .param("source", source)
                  .update();
              for (VocabularyType type : changes) {
                jdbc.sql(
                        """
                        INSERT INTO vocabulary_types (name, revision, definition)
                        VALUES (:name, :r, CAST(:definition AS jsonb))
                        """)
                    .param("name", type.name())
                    .param("r", next)
                    .param("definition", type.toJson().toString())
                    .update();
              }
              return next;
            });
    return Objects.requireNonNull(revision, "revision");
  }

  /**
   * The source that owns a type: the source of the earliest revision that holds it.
   *
   * @return the source, or empty if no revision holds the type
   */
  public Optional<String> owner(String type) {
    Objects.requireNonNull(type, "type");
    return owners(List.of(type)).values().stream().findFirst();
  }

  /** Refuses definitions that differ from the current ones of types other sources own. */
  private void checkOwners(String source, Vocabulary current, List<VocabularyType> definitions) {
    Map<String, VocabularyType> differing = new TreeMap<>();
    for (VocabularyType d : definitions) {
      current.find(d.name()).filter(t -> !t.equals(d)).ifPresent(t -> differing.put(d.name(), d));
    }
    if (differing.isEmpty()) {
      return;
    }
    List<String> problems = new ArrayList<>();
    owners(List.copyOf(differing.keySet()))
        .forEach(
            (name, owner) -> {
              if (!owner.equals(source)) {
                problems.add(
                    "'%s' belongs to '%s' and '%s' defines it differently (%s's: %s, %s's: %s)"
                        .formatted(
                            name,
                            owner,
                            source,
                            owner,
                            current.find(name).orElseThrow().toJson(),
                            source,
                            differing.get(name).toJson()));
              }
            });
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
          "Vocabulary types clash between sources: " + String.join("; ", problems));
    }
  }

  private Map<String, String> owners(List<String> names) {
    Map<String, String> owners = new TreeMap<>();
    jdbc.sql(
            """
            SELECT DISTINCT ON (t.name) t.name, r.source
            FROM vocabulary_types t JOIN vocabulary_revisions r ON r.revision = t.revision
            WHERE t.name IN (:names)
            ORDER BY t.name, t.revision
            """)
        .param("names", names)
        .query((rs, row) -> owners.put(rs.getString(1), rs.getString(2)))
        .list();
    return owners;
  }

  /** Checks the fact against the latest revision, see {@link Vocabulary#validate}. */
  @Override
  public void validate(Fact fact) {
    current().validate(fact);
  }

  /**
   * Checks the facts against the latest revision, looked up once for all of them, see {@link
   * Vocabulary#validate}.
   */
  @Override
  public void validateAll(Collection<Fact> facts) {
    Vocabulary vocabulary = current();
    facts.forEach(vocabulary::validate);
  }

  /**
   * The key attributes of each fact by the latest revision, looked up once (ADR 0090). A predicate
   * the revision does not define keys on all its attributes.
   */
  @Override
  public Function<Fact, Set<String>> keyAttributes() {
    Vocabulary vocabulary = current();
    return fact ->
        vocabulary.find(fact.predicate()).isPresent()
            ? vocabulary.keyAttributes(fact.predicate())
            : new TreeSet<>(fact.attributes().propertyNames());
  }

  private long latestRevision() {
    return jdbc.sql("SELECT coalesce(max(revision), 0) FROM vocabulary_revisions")
        .query(Long.class)
        .single();
  }

  private Vocabulary load(long revision) {
    List<VocabularyType> types = new ArrayList<>();
    jdbc.sql(
            """
            SELECT DISTINCT ON (name) definition::text
            FROM vocabulary_types
            WHERE revision <= :r
            ORDER BY name, revision DESC
            """)
        .param("r", revision)
        .query(String.class)
        .list()
        .forEach(json -> types.add(VocabularyType.fromJson(FactJson.parse(json))));
    return new Vocabulary(revision, types);
  }
}
