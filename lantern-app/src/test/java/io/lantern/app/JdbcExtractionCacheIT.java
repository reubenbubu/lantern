package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.ExtractorInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The extraction cache keeps each unit's facts by extractor and key, answers only for the
 * extractor's version, and deletes what no run used for 30 days (ADR 0092).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcExtractionCacheIT {

  @Autowired JdbcExtractionCache cache;
  @Autowired JdbcClient jdbc;

  @Test
  void storedFactsComeBackForTheSameVersionAndUnusedEntriesAreDeleted() {
    ExtractorInfo extractor = new ExtractorInfo("cache-test", "1");
    Fact fact =
        Fact.create(
            EntityId.parse("type:p.A"),
            "kind",
            FactObject.string("class"),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "cache-test", "1"),
            Evidence.of("shop", "src/A.java", new Evidence.LineRange(1, 1), "e".repeat(64)),
            1.0,
            Validity.from(4));

    cache.put(extractor, "k1", List.of(fact));
    cache.put(extractor, "old", List.of());
    jdbc.sql("UPDATE extraction_cache SET used_at = now() - interval '31 days' WHERE key = 'old'")
        .update();

    assertThat(cache.get(extractor, "k1")).contains(List.of(fact));
    assertThat(cache.get(new ExtractorInfo("cache-test", "2"), "k1")).isEmpty();
    assertThat(cache.get(extractor, "missing")).isEmpty();
    assertThat(cache.prune()).isEqualTo(1);
    assertThat(cache.get(extractor, "old")).isEmpty();
    assertThat(cache.get(extractor, "k1")).isPresent();
  }
}
