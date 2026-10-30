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

  private static final String STARTER =
      "<dependency><artifactId>spring-boot-starter-data-redis</artifactId></dependency>";

  /** JCache's API, which its annotations need; it picks no provider by itself. */
  private static final String JCACHE_API =
      "<dependency><artifactId>cache-api</artifactId></dependency>";

  private static String dependency(String artifact) {
    return "<dependency><artifactId>" + artifact + "</artifactId></dependency>";
  }

  /**
   * A module with the given dependencies and config, its cache annotations and calls ({@code
   * Books}, {@code Isbns}, {@code Prices}), and the given body of a {@code Setup} class.
   */
  private String caching(String name, String dependencies, String config, String setup)
      throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve(name),
            name,
            Map.of(
                "pom.xml",
                """
                <project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>
                <artifactId>shop</artifactId><version>1</version><dependencies>
                %s
                </dependencies></project>
                """
                    .formatted(dependencies),
                "src/main/resources/application.properties",
                config,
                "src/main/java/com/example/Setup.java",
                """
                package com.example;

                import org.springframework.cache.caffeine.CaffeineCacheManager;
                import org.springframework.data.redis.cache.RedisCacheManager;
                import org.springframework.data.redis.connection.RedisConnectionFactory;

                public class Setup {
                %s
                }
                """
                    .formatted(setup),
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
                """,
                "src/main/java/com/example/Isbns.java",
                """
                package com.example;

                import javax.cache.annotation.CacheDefaults;
                import javax.cache.annotation.CacheRemove;
                import javax.cache.annotation.CacheRemoveAll;

                @CacheDefaults(cacheName = "isbns")
                public class Isbns {
                  @CacheRemove
                  public void forget(long id) {}

                  @CacheRemoveAll
                  public void forgetAll() {}
                }
                """,
                "src/main/java/com/example/Prices.java",
                """
                package com.example;

                import org.springframework.cache.annotation.Cacheable;

                @Cacheable("prices")
                public class Prices {
                  public long price(long id) {
                    return 0;
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
    String facts =
        caching(
            "redis", STARTER + JCACHE_API, "spring.cache.redis.time-to-live=${CACHE_TTL:5m}\n", "");
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
  void jcacheRemovalsDefaultsAndClassLevelOperations() throws IOException {
    String facts = caching("jcache-redis", STARTER + JCACHE_API, "\n", "");
    assertThat(facts.lines())
        .contains(
            "method:com.example.Isbns#forget(long) redis-use cache {\"api\":\"javax.cache"
                + ".annotation.CacheRemove\",\"cache\":\"isbns\",\"key\":\"isbns::*\","
                + "\"operation\":\"delete\"} @jcache-redis:src/main/java/com/example/Isbns.java#9-9"
                + " 0.7",
            "method:com.example.Isbns#forgetAll() redis-use cache {\"api\":\"javax.cache"
                + ".annotation.CacheRemoveAll\",\"cache\":\"isbns\",\"key\":\"isbns::*\","
                + "\"operation\":\"delete\"} @jcache-redis:src/main/java/com/example/Isbns.java"
                + "#12-12 0.7",
            "type:com.example.Prices redis-use cache {\"api\":\"org.springframework.cache"
                + ".annotation.Cacheable\",\"cache\":\"prices\",\"key\":\"prices::*\","
                + "\"operation\":\"read\"} @jcache-redis:src/main/java/com/example/Prices.java"
                + "#5-5 0.7");
  }

  @Test
  void aRedisCacheManagerInCodeMakesTheProviderRedis() throws IOException {
    String manager =
        """
          Object caches(RedisConnectionFactory factory) {
            return RedisCacheManager.create(factory);
          }
        """;
    // Over spring.cache.type, and with only a plain client, which alone could not be the provider.
    assertThat(caching("manager", dependency("jedis"), "spring.cache.type=caffeine\n", manager))
        .contains("method:com.example.Books#book(long) redis-use cache");
  }

  @Test
  void cachesOfAnotherProviderAreNotRedisUses() throws IOException {
    String caffeine =
        """
          Object caches() {
            return new CaffeineCacheManager("prices");
          }
        """;
    // Another cache manager the code builds wins over Redis config and Spring Data Redis.
    assertThat(caching("built", STARTER, "spring.cache.type=redis\n", caffeine))
        .doesNotContain("redis-use cache {\"api\":\"org.springframework.cache");
    assertThat(caching("caffeine", STARTER, "spring.cache.type=caffeine\n", ""))
        .doesNotContain("redis-use cache");
    assertThat(caching("none", STARTER, "spring.cache.type=none\n", ""))
        .doesNotContain("redis-use cache");
    // A plain client brings no RedisConnectionFactory, so Boot's simple map cache is used.
    for (String client : List.of("jedis", "lettuce-core", "redisson")) {
      assertThat(caching("plain-" + client, dependency(client), "\n", ""))
          .contains("redis-use client {\"api\":\"" + client + "\"")
          .doesNotContain("redis-use cache");
    }
    // A provider Spring Boot tries before Redis.
    for (String provider :
        List.of(
            "ehcache",
            "jcache",
            "hazelcast",
            "infinispan-spring-boot3-starter-embedded",
            "spring-boot-starter-data-couchbase")) {
      assertThat(caching("before-" + provider, STARTER + dependency(provider), "\n", ""))
          .as(provider)
          .doesNotContain("redis-use cache");
    }
  }

  @Test
  void clientArtifactsAndTestStarters() throws IOException {
    String facts =
        caching(
            "artifacts",
            dependency("spring-boot-data-redis")
                + dependency("spring-integration-redis")
                + dependency("spring-boot-starter-data-redis-test"),
            "\n",
            "");
    assertThat(facts)
        .contains(
            "redis-use client {\"api\":\"spring-boot-data-redis\"",
            "redis-use client {\"api\":\"spring-integration-redis\"")
        .doesNotContain("spring-boot-starter-data-redis-test")
        // Spring Data Redis, so Redis is the provider.
        .contains("method:com.example.Books#book(long) redis-use cache");
  }
}
