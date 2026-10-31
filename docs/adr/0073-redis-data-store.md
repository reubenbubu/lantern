# 0073: Redis as a data store

Status: accepted (#259), amended by ADR 0082 (#266) and for #392

## Context

The references of project-1 (the BFF's sessions and the gateway's rate limiter) and project-4 (the
gateway's permission cache, written by auth-service) each have Redis as a data store with a
connection from every unit that uses it. Lantern found none (ADR 0070): Redis is no JDBC
datasource, so the persistence extractor and the data linker never saw it.

## Decisions

- **Redis is a data store like a database.** The linker makes one `database:<server>/redis`
  entity per Redis server, and a `USES_DATABASE` link from each unit that connects to it, with the
  protocol `RESP`. Documents, flows and checks that handle databases therefore handle Redis with no
  change. The entity's `display-name` is `Redis`, or `Redis (<server>)` when the snapshot has
  several, and its `technology` the product and version of the compose image when known (below).
- **The server comes from config, as Spring Boot reads it:** `spring.data.redis.*`, or the older
  `spring.redis.*`: `url` (credentials left out, `rediss://` means TLS), else `cluster.nodes`, else
  `sentinel.master` and `sentinel.nodes`, else `host` (default `localhost`) and `port` (default
  6379). No new config fact is needed; the config extractor already records the values. Units on
  the same server share the store. A host or port that is a placeholder no config resolves (project-4
  sets `${redis.host}`, which only an environment variable fills) names the server by the
  placeholders as written, so units that write the same ones share it. This is the one exception
  to ADR 0042's rule that a datasource with a placeholder is never shared (#255): the reference
  shows one Redis for auth-service and api-gateway; such a link is marked
  `inferred` and has 0.8 of the confidence. A unit with Redis code or a Redis client dependency but
  no connection config of its own takes the one server the snapshot's config names, marked
  `inferred`; if none is configured, Spring's default `localhost:6379`, marked `defaulted`; if
  several are, it is left open. Both have 0.6 of the confidence. Passwords are never read.
- **The Redis extractor (`redis`, new) records how code and config use Redis** as `redis-use`
  facts, kind in the value: `template` (an operation on a `RedisTemplate`, `StringRedisTemplate` or
  reactive template, with the operation (`read`, `write`, `delete`, `other`), the data structure
  and the key as the code writes it, `*` for what it does not: `bff:session:*`), `hash`
  (`@RedisHash`, with its keyspace and time to live), `session` (`@EnableRedis*Session`,
  `spring.session.*` config (`spring.session.data.redis.*` in Spring Boot 4), or a Spring Session Redis dependency), `rate-limiter` (a
  `RedisRateLimiter` or `redis-rate-limiter` config on a gateway route), `lock`
  (`RedisLockRegistry`), `cache` (`RedisCacheManager`, `spring.cache.type=redis`), `pubsub`
  (`convertAndSend`, a listener container) and `client` (a Redis client among a module's own POM
  dependencies, not managed or test ones). The code is read through the Java extractor's
  invocations, so the `org.springframework.data.redis.core`, `.cache`, `.listener`,
  `org.springframework.integration.redis.util` and gateway `ratelimit` packages join the tracked
  APIs (their invocations are recorded; no Java fact changes). Keys the code builds in another
  method, and the type a template stores (a generic argument, which the structure reader erases),
  are not shown.
- **What a unit stores** goes on its link as `uses` (the kinds) and `stores` (for each kind and key:
  structure, time to live, operations), from the `redis-use` facts of the modules the unit runs, for
  the views where their code is active. The facts are listed in the link's `facts`.
- **The technology comes from compose files.** The extractor also reads every repository's
  compose files (`docker-compose*.yml`, `compose*.yaml`) in the snapshot, even a repository
  without modules or a build, and records each Redis, Valkey or Redis Stack image as a
  `redis-image` fact on the `repo:<name>` entity (the key summaries already use for a repository, now defined as an
  entity type, for facts about files of no module). Compose files under `src/test/` are skipped.
  The Deployment extractor (#266) is to absorb this compose reading, so Redis images are read in one
  place. `${VAR:-default}` is resolved to its default. A unit's technology comes from the
  images of its own repository, else any in the snapshot; when they agree it is the product and
  version (`Redis 8.4.0`), when they name one product with different versions only the product
  (project-4's local and dev compose differ), else `Redis`. Deployment manifests other than compose
  (kustomize, Helm) are for the Deployment extractor (#266).

## Consequences

- Project-1 gives one `Redis 8` store used by the bff (sessions under `bff:session:*`, Spring
  Session, locks) and the gateway (rate limiter); project-4 gives one `Redis` store used by
  auth-service (permission cache and locks) and api-gateway (reads).
- A Redis server written as different placeholders by different units is two stores. The Deployment
  extractor can later tie them to the compose service.
- The extractor and the linker are new, so `redis` starts at version 1 and `cross-repo-links` is
  raised.

## Amendment (#266): versions from the Deployment extractor

The Deployment extractor reads compose files, kustomize and Helm in place of the Redis extractor
(ADR 0082): the `redis-image` facts are gone (`redis` version 2), the store's technology comes from
the Redis, Valkey and Redis Stack containers the deployments run, and differing versions are listed
from oldest to newest (`Redis 7.4.4 / 8.4.0`) instead of leaving only the product.

## Amendment (#392): Spring Cache on Redis

Research: [`docs/research/extractor-redis.md`](../research/extractor-redis.md). A cache server
reached over the network is a data store; an in-process cache is part of its container
(`docs/quality-bar.md`). `redis` version 3:

- **Cache annotations and calls are `cache` uses**, kept per module until its cache provider is
  known and recorded only when that is Redis. They are:
  - Spring's `@Cacheable` (`read`), `@CachePut` (`write`) and `@CacheEvict` (`delete`), alone or
    inside `@Caching`, and JCache's `@CacheResult`, `@CachePut`, `@CacheRemove` and
    `@CacheRemoveAll`. Each gives a fact on the method, at the annotation, one per cache, with
    `cache` and the key `<cache>::*`. Constants are folded, and a class's `@CacheConfig` or
    `@CacheDefaults` names the caches of its methods that name none.
  - `@EnableCaching`, as a fact on its class.
  - A call on a `CacheManager` or `Cache` (`getCache(name)` with `get`, `put`, `evict` or
    `clear`), at the call. `org.springframework.cache.` joins the tracked APIs.

  `spring.cache.redis.time-to-live` gives each cache fact of the module its `ttl`.
- **The provider is Redis** when the module's code builds a Redis cache manager. Otherwise,
  when the code builds no other cache manager (`new CaffeineCacheManager(...)`), it is decided as
  Spring Boot picks it: by `spring.cache.type`, and without that property when the module has
  Spring Data Redis among its own dependencies (a plain Jedis, Lettuce or Redisson client has no
  `RedisConnectionFactory`) and no provider Boot tries first (a JCache provider such as Ehcache 3
  or Caffeine's `jcache`, Hazelcast, Infinispan, Couchbase; JCache's `cache-api` alone picks
  none). Any other `spring.cache.type`, `none` included, means no cache fact. Only the default
  view of the module's own config is read: a value set only in a profile file or served by a
  config repository is not seen (#426).
- **A class-level** `@Cacheable`, `@CachePut` or `@CacheEvict`, which applies to the class's
  public methods, gives its fact on the class.
- **The rate limiter's facts** carry the key `request_rate_limiter.*` its Redis script writes.
- **Client artifacts** add `spring-boot-data-redis` (Spring Boot 4), `spring-integration-redis`
  and `redisson`. Artifacts named `*-test` (Spring Boot 4's test starters) are skipped, like
  test-scoped ones.

Not read yet, each with a project-0 case first:

- Redisson, Lettuce and Jedis commands, streams, `@RedisListener` and ShedLock (#423).
- Keys from helper methods, call TTLs, Spring Session's namespace and meta-annotated cache
  annotations (#424).
- Cache servers other than Redis (#425).

Deciding the provider per view, and not linking a unit on a client dependency alone, are the
linker's (#426).

## Amendment (#567): config read in linking

The Redis extractor (version 4) reads no config. Each Spring or JCache cache use is a `cache-use`
fact as the code writes it, with what the module's code and POM say of its cache provider (a Redis
or other cache manager built in code, Spring Data Redis, a provider Spring Boot tries first).
Linking's first step (`RedisExtractor.fromConfig`, from the config facts) records the `redis-use`
facts config shows (Spring Session, the rate limiter, `spring.cache.type: redis`) and decides each
module's cache provider as before, with `spring.cache.type` and the time to live. The Redis server a
unit connects to was already read in linking from the config facts. `ValueViewsTest` checks that
the extractor's facts with what linking records are its whole golden file of before.

