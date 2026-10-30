package io.lantern.extract.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.Expression;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code redis} fixture: golden facts with the code parsed
 * without a classpath (degraded mode), and the piece that reads keys.
 */
class RedisExtractorTest {

  private static final long SNAPSHOT = 7;

  @TempDir Path temp;

  @Test
  void parsed() throws IOException {
    Repo repo = Fixtures.repo("redis", temp.resolve("redis"), "redis");
    List<RepoBuild> inputs = List.of(new RepoBuild(repo, Fixtures.head(repo), null));

    List<Fact> facts = new RedisExtractor().extract(inputs, SNAPSHOT);

    Fixtures.checkEnvelope(
        facts, List.of(repo), SNAPSHOT, RedisExtractor.NAME, RedisExtractor.VERSION);
    assertThat(new RedisExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    Fixtures.assertGolden("redis/redis-parsed.txt", facts);
    // Neither a password nor a managed or test-only dependency reaches a fact.
    assertThat(Fixtures.render(facts))
        .doesNotContain("secret", "redisson", "jedis", "lettuce", "\"spring-data-redis\"")
        .doesNotContain("ScanOptions", "Lookups", "some-plugin");
  }

  @Test
  void keysAreWrittenWithStarsForWhatIsNotWrittenOut() {
    Expression prefix = new Expression.Literal("bff:session:");
    Expression id = new Expression.Param(0);

    assertThat(RedisExtractor.pattern(new Expression.Concat(List.of(prefix, id))))
        .isEqualTo("bff:session:*");
    assertThat(RedisExtractor.pattern(new Expression.Concat(List.of(prefix, id, prefix))))
        .isEqualTo("bff:session:*bff:session:");
    assertThat(RedisExtractor.pattern(id)).isNull();
    assertThat(
            RedisExtractor.pattern(
                new Expression.Invoke(
                    new Expression.Literal("org:%d:user:%d:permissions"),
                    "formatted",
                    List.of(id, id))))
        .isEqualTo("org:*:user:*:permissions");
  }
}
