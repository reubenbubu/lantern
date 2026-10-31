package io.lantern.app;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.extract.ExtractionCache;
import io.lantern.extract.ExtractorInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The extraction cache in the table {@code extraction_cache} (ADR 0092): each unit's facts as fact
 * envelope JSON, with when the entry was last used, so that entries no run uses any more are
 * deleted.
 */
public final class JdbcExtractionCache implements ExtractionCache {

  /** How long an entry no run uses is kept. */
  public static final Duration KEPT = Duration.ofDays(30);

  private final JdbcClient jdbc;

  public JdbcExtractionCache(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<List<Fact>> get(ExtractorInfo extractor, String key) {
    Optional<String> stored =
        jdbc.sql(
                """
                UPDATE extraction_cache SET used_at = now()
                WHERE extractor = :extractor AND key = :key AND version = :version
                RETURNING facts
                """)
            .param("extractor", extractor.name())
            .param("key", key)
            .param("version", extractor.version())
            .query(String.class)
            .optional();
    return stored.map(JdbcExtractionCache::read);
  }

  @Override
  public void put(ExtractorInfo extractor, String key, List<Fact> facts) {
    ArrayNode array = FactJson.newObject().arrayNode();
    facts.forEach(fact -> array.add(FactJson.toJson(fact)));
    jdbc.sql(
            """
            INSERT INTO extraction_cache (extractor, version, key, facts, used_at)
            VALUES (:extractor, :version, :key, :facts, now())
            ON CONFLICT (extractor, key)
            DO UPDATE SET version = EXCLUDED.version, facts = EXCLUDED.facts, used_at = now()
            """)
        .param("extractor", extractor.name())
        .param("version", extractor.version())
        .param("key", key)
        .param("facts", array.toString())
        .update();
  }

  /**
   * Deletes the entries no run used for {@link #KEPT}.
   *
   * @return how many were deleted
   */
  @Override
  public int prune() {
    return jdbc.sql(
            "DELETE FROM extraction_cache WHERE used_at < now() - make_interval(days => :days)")
        .param("days", (int) KEPT.toDays())
        .update();
  }

  private static List<Fact> read(String json) {
    List<Fact> facts = new ArrayList<>();
    for (JsonNode node : FactJson.parse(json)) {
      facts.add(FactJson.fromJson(node));
    }
    return facts;
  }
}
