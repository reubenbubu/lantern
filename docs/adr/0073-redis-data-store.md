# 0073: Redis as a data store

Status: accepted (#259), pending the user's review (#24)

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
  placeholders as written, so units that write the same ones share it; such a link is marked
  `inferred` and has 0.8 of the confidence. A unit with Redis code or a Redis client dependency but
  no connection config uses Spring's default `localhost:6379`, marked `defaulted`, with 0.6 of the
  confidence. Passwords are never read.
- **The Redis extractor (`redis`, new) records how code and config use Redis** as `redis-use`
  facts, kind in the value: `template` (an operation on a `RedisTemplate`, `StringRedisTemplate` or
  reactive template, with the operation (`read`, `write`, `delete`, `other`), the data structure
  and the key as the code writes it, `*` for what it does not: `bff:session:*`), `hash`
  (`@RedisHash`, with its keyspace and time to live), `session` (`@EnableRedis*Session`,
  `spring.session.*` config, or a Spring Session Redis dependency), `rate-limiter` (a
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
  `redis-image` fact on the `repository:<name>` entity (a new entity type, for facts about files of
  no module). `${VAR:-default}` is resolved to its default. A unit's technology comes from the
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
