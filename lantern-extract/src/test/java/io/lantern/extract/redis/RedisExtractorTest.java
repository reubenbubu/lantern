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
import java.util.Map;
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
        .doesNotContain("ScanOptions", "some-plugin");
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

  /** A module with a Redis client, its cache annotations and calls, and the given extra files. */
  private String caching(String name, String dependency, String config) throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve(name),
            name,
            Map.of(
                "pom.xml",
                """
                <project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>
                <artifactId>shop</artifactId><version>1</version><dependencies>
                <dependency><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
                %s
                </dependencies></project>
                """
                    .formatted(dependency),
                "src/main/resources/application.properties",
                config,
                "src/main/java/com/example/Books.java",
                """
                package com.example;

                import javax.cache.annotation.CacheResult;
                import org.springframework.cache.CacheManager;
                import org.springframework.cache.annotation.CacheConfig;
                import org.springframework.cache.annotation.CacheEvict;
                import org.springframework.cache.annotation.CachePut;
                import org.springframework.cache.annotation.Cacheable;
                import org.springframework.cache.annotation.Caching;
                import org.springframework.cache.annotation.EnableCaching;

                @EnableCaching
                @CacheConfig(cacheNames = "books")
                public class Books {
                  static final String AUTHORS = "authors";
                  private final CacheManager caches;

                  Books(CacheManager caches) {
                    this.caches = caches;
                  }

                  @Cacheable
                  public String book(long id) {
                    return "";
                  }

                  @Caching(evict = {@CacheEvict(AUTHORS), @CacheEvict(cacheNames = "books")})
                  @CachePut(cacheNames = AUTHORS)
                  public void rename(long id) {
                    caches.getCache(AUTHORS).evict(id);
                  }

                  @CacheResult(cacheName = "isbn")
                  public String isbn(long id) {
                    return "";
                  }
                }
                """));
    return Fixtures.render(
        new RedisExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT));
  }

  @Test
  void cachesAreRedisUsesWhenTheModulesProviderIsRedis() throws IOException {
    // With no spring.cache.type, Spring Boot picks Redis when its client is there and no
    // provider it tries first (JCache, Hazelcast, Infinispan, Couchbase) is.
    String facts = caching("redis", "", "spring.cache.redis.time-to-live=${CACHE_TTL:5m}\n");
    assertThat(facts.lines())
        .contains(
            "method:com.example.Books#book(long) redis-use cache {\"api\":\"org.s"
                + "pringframework.cache.annotation.Cacheable\",\"cache\":\"books\",\"ke"
                + "y\":\"books::*\",\"operation\":\"read\",\"ttl\":\"5m\"} @redis:src/m"
                + "ain/java/com/example/Books.java#22-22 0.7",
            "method:com.example.Books#isbn(long) redis-use cache {\"api\":\"javax"
                + ".cache.annotation.CacheResult\",\"cache\":\"isbn\",\"key\":\"isbn::*"
                + "\",\"operation\":\"read\",\"ttl\":\"5m\"} @redis:src/main/java/com/e"
                + "xample/Books.java#33-33 0.7",
            "method:com.example.Books#rename(long) redis-use cache {\"api\":\"org"
                + ".springframework.cache.annotation.CacheEvict\",\"cache\":\"authors\""
                + ",\"key\":\"authors::*\",\"operation\":\"delete\",\"ttl\":\"5m\"} @re"
                + "dis:src/main/java/com/example/Books.java#27-27 0.7",
            "method:com.example.Books#rename(long) redis-use cache {\"api\":\"org"
                + ".springframework.cache.annotation.CacheEvict\",\"cache\":\"books\",\""
                + "key\":\"books::*\",\"operation\":\"delete\",\"ttl\":\"5m\"} @redis:s"
                + "rc/main/java/com/example/Books.java#27-27 0.7",
            "method:com.example.Books#rename(long) redis-use cache {\"api\":\"org"
                + ".springframework.cache.annotation.CachePut\",\"cache\":\"authors\",\""
                + "key\":\"authors::*\",\"operation\":\"write\",\"ttl\":\"5m\"} @redis:"
                + "src/main/java/com/example/Books.java#28-28 0.7",
            "method:com.example.Books#rename(long) redis-use cache {\"api\":\"org"
                + ".springframework.cache.CacheManager\",\"cache\":\"authors\",\"key\":"
                + "\"authors::*\",\"operation\":\"delete\",\"ttl\":\"5m\"} @redis:src/m"
                + "ain/java/com/example/Books.java#30-30 0.7",
            "type:com.example.Books redis-use cache {\"api\":\"org.springframewor"
                + "k.cache.annotation.EnableCaching\",\"operation\":\"other\"} @redis:s"
                + "rc/main/java/com/example/Books.java#12-12 0.7");
  }

  @Test
  void cachesOfAnotherProviderAreNotRedisUses() throws IOException {
    assertThat(caching("caffeine", "", "spring.cache.type=caffeine\n"))
        .doesNotContain("redis-use cache");
    assertThat(caching("none", "", "spring.cache.type=none\n")).doesNotContain("redis-use cache");
    assertThat(
            caching("jcache", "<dependency><artifactId>cache-api</artifactId></dependency>", "\n"))
        .doesNotContain("redis-use cache");
  }
}
