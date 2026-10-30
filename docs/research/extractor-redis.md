# Redis and caches: what the Redis extractor reads

Research for #392 (Review the Redis extractor), milestone "Extractor revisit". It leads to an amendment of `docs/adr/0073-redis-data-store.md`, the Redis row of `docs/design.md` (Static analysis and extractors), and one sentence in `docs/quality-bar.md`.

**Depth: high.** The note decides two things: which uses of a cache make a data store worth drawing on the container diagram, and which source lines each fact must cite. A rule that is wrong in either direction does visible harm. Drawing an in-process Caffeine cache adds a false store and a false edge. Missing Spring Cache annotations loses a real edge: project-2's `event -> redis` today rests only on config. The rule must hold across Spring Data Redis, Spring Cache, Spring Session, the gateway, Redisson, Lettuce and Jedis. I read 20 pages in full, the source of 4 open-source projects, and the Redis code and config of every development set.

## Question

How do Java systems use Redis and other caches: Spring Data Redis (imperative and reactive), Spring Cache with Redis, Spring Session, Spring Cloud Gateway's rate limiter, Redisson, Lettuce, Jedis, Caffeine and Hazelcast? Which of these uses make a store worth drawing as a container-level data store? And what should a static extractor record, at which source lines, so that each use is evidenced at the line that declares it?

## What we learned

### Spring Cache: annotations are the main use, and Redis is only one possible provider

- **Five annotations.** Spring's declarative caching uses `@Cacheable` (populate), `@CachePut` (always run, then store), `@CacheEvict` (`allEntries`, `beforeInvocation`), `@Caching` (several operations grouped) and `@CacheConfig`.
  - `@CacheConfig` sits on the class and shares `cacheNames`, `keyGenerator`, `cacheManager` and `cacheResolver`. It "does not turn on any caching operation" by itself.
  - Each operation can name its own `cacheManager` or `cacheResolver`.
  - The key is SpEL (`key="#isbn"`), or `SimpleKeyGenerator` by default.
  - Annotations should go on public methods of concrete classes. Interface annotations work only in proxy mode.
  - Meta-annotations work: `@SlowService` carrying `@Cacheable`.
  - `Mono`, `Flux` and `CompletableFuture` return types are cached.
  - Nothing happens without `@EnableCaching`. By default, errors from cache operations are rethrown.
  - https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html
- **JCache annotations are also supported.** Spring supports `@CacheResult`, `@CachePut`, `@CacheRemove`, `@CacheRemoveAll` and `@CacheDefaults` from `javax.cache.annotation`, with `cacheName` singular. https://docs.spring.io/spring-framework/reference/integration/cache/jsr-107.html
- **How Spring Boot picks the provider.** Boot auto-configures a `CacheManager` only when the application defines no `CacheManager` bean and no `cacheResolver` bean. It then takes the first provider it finds, in this order:
  1. Generic
  2. JCache (EhCache 3, Hazelcast, Infinispan)
  3. Hazelcast
  4. Infinispan
  5. Couchbase
  6. **Redis**
  7. Caffeine
  8. Cache2k
  9. Simple, a `ConcurrentHashMap`

  `spring.cache.type` forces a provider, and `none` disables caching. Redis cache properties are `spring.cache.cache-names` and `spring.cache.redis.time-to-live`, plus a key prefix that is on by default. Caffeine uses `spring.cache.caffeine.spec`. Without any library, caching falls back to the in-memory Simple provider. https://docs.spring.io/spring-boot/reference/io/caching.html
- **Consequence.** A `@Cacheable` is a Redis use only when the module's cache provider is Redis. The same annotation in spring-petclinic is an in-process JCache/Caffeine cache:
  - `@Cacheable("vets")` on `VetRepository`
  - `@EnableCaching` with a `JCacheManagerCustomizer` in `system/CacheConfiguration.java`
  - `spring-boot-starter-cache`, `javax.cache:cache-api` and `caffeine` (runtime scope) in `pom.xml:46-85`
  - https://github.com/spring-projects/spring-petclinic
- **Key layout of a Redis cache.**
  - By default, "any `key` for a cache entry gets prefixed with the actual cache name followed by two colons (`::`)". A cache entry is therefore `<cacheName>::<key>`.
  - `prefixCacheNameWith` and `computePrefixWith` change the prefix. `entryTtl` (or a `TtlFunction`) sets the time to live.
  - The default writer is non-locking and clears a cache with `KEYS`. `RedisCacheManager.create` and `RedisCacheManager.builder(...)` are the entry points.
  - https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html

### Spring Data Redis: templates, repositories, pub/sub and streams

- **Connection and auto-configured beans.**
  - Boot reads `spring.data.redis.url`. When set, it overrides host, port, username and password. Otherwise Boot reads `host`, `port`, `database`, `username`, `password`, `ssl.enabled` and `ssl.bundle`. The default is `localhost:6379`.
  - The starter `spring-boot-starter-data-redis` uses Lettuce and serves imperative and reactive code. The `-reactive` starter exists for consistency.
  - Boot auto-configures `RedisConnectionFactory`, `StringRedisTemplate` and `RedisTemplate`.
  - **New:** in Boot 4.1, `@RedisListener("channel")` on a bean method creates a listener endpoint with a default `RedisMessageListenerContainer`.
  - https://docs.spring.io/spring-boot/reference/data/nosql.html (read in part)
- **Annotated listeners (Spring Data Redis 4.1).**
  - `@RedisListener(topic = "my-channel")` is repeatable and also accepts a pattern (`"my-order-channels*"`). It lives in `org.springframework.data.redis.annotation`.
  - `@EnableRedisListeners` enables it. `RedisListenerConfigurer` and `SimpleRedisListenerEndpoint.setTopic` register endpoints in code.
  - https://docs.spring.io/spring-data/redis/reference/redis/pubsub-annotated.html
- **Subscribing in code.**
  - `container.addMessageListener(listener, ChannelTopic.of("chatroom"))`, with `MessageListenerAdapter(delegate, "handleMessage")`.
  - `Topic.channel(...)` and `Topic.pattern(...)` name topics.
  - Reactive: `ReactiveRedisMessageListenerContainer.receive(...)` and `receiveLater(...)`, and `ReactiveRedisTemplate.listenToChannel(...)`.
  - Low level: `RedisConnection.subscribe` and `pSubscribe`.
  - https://docs.spring.io/spring-data/redis/reference/redis/pubsub-receiving.html
- **Publishing.** Publishing goes through `RedisOperations` (`convertAndSend`), `RedisConnection`, or `RedisMessageSendingTemplate`. https://docs.spring.io/spring-data/redis/reference/redis/pubsub.html
- **Streams.**
  - Appending: `opsForStream().add(StreamRecords...withStreamKey("my-stream"))` or `RedisConnection.xAdd`.
  - Synchronous reads: `StreamOperations.read`, `acknowledge`.
  - Consuming: `StreamMessageListenerContainer.create(...)` with `receive`, `receiveAutoAck`, `Consumer.from(group, consumer)` and `StreamOffset`. Reactive: `StreamReceiver.receive(...)`.
  - These classes are in `org.springframework.data.redis.stream`, not in `.listener`.
  - https://docs.spring.io/spring-data/redis/reference/redis/redis-streams.html
- **Repositories.**
  - `@RedisHash("people")` with `CrudRepository<Person, String>` stores each entity as a hash at `people:<id>`. Without a name, the keyspace is the class name.
  - A `KeyspaceConfiguration` (`@EnableRedisRepositories(keyspaceConfiguration=...)`) can also set the keyspace, but "the annotated keyspace supersedes any other configuration".
  - https://docs.spring.io/spring-data/redis/reference/redis/redis-repositories/usage.html, https://docs.spring.io/spring-data/redis/reference/redis/redis-repositories/keyspaces.html

### Spring Session and the gateway rate limiter

- **Spring Session.**
  - `@EnableRedisHttpSession` configures `RedisSessionRepository`. `@EnableRedisIndexedHttpSession` configures the indexed repository. `@EnableRedisWebSession` is the reactive variant. All three take `redisNamespace`.
  - Boot properties are `spring.session.redis.namespace` (default `spring:session`) and `spring.session.redis.repository-type`.
  - Keys are `spring:session:sessions:<id>` and `spring:session:expirations:<ts>`.
  - https://docs.spring.io/spring-session/reference/configuration/redis.html
- **Boot 4 renames.**
  - The session property prefix changed: "Properties that previously began with `spring.session.redis` now begin with `spring.session.data.redis`."
  - New starters: `spring-boot-starter-session-data-redis` and `spring-boot-starter-data-redis(-reactive)`.
  - New modules: `spring-boot-data-redis` and `spring-boot-session-data-redis`.
  - New test starters: `spring-boot-starter-data-redis-test`, `-reactive-test` and `spring-boot-starter-cache-test`.
  - https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide (read in part)
- **Gateway rate limiter.**
  - `RequestRateLimiter` with `RedisRateLimiter` needs `spring-boot-starter-data-redis-reactive`.
  - It is configured by `redis-rate-limiter.replenishRate`, `burstCapacity` and `requestedTokens`, with a `KeyResolver` bean. It "is not configurable with the 'shortcut' notation".
  - https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html
- **Rate limiter source.** `RedisRateLimiter.getKeys` builds `"request_rate_limiter.{" + routeId + "." + id + "}."` plus `tokens` and `timestamp`. On a Redis error, `onErrorResume` returns `[1, -1]`: the request is allowed, so the limiter fails open. https://github.com/spring-cloud/spring-cloud-gateway (`spring-cloud-gateway-server-webflux/.../ratelimit/RedisRateLimiter.java`)

### Clients used without Spring Data

- **Redisson.**
  - `redisson-spring-boot-starter` reads `spring.data.redis.*` (Boot 3+), `spring.redis.*` (Boot 2.7 and earlier), or `spring.redis.redisson.file` and `.config`.
  - It exposes `RedissonClient`, `RedissonReactiveClient` and `RedissonRxClient`, plus `RedisTemplate` and `ReactiveRedisTemplate`.
  - It provides a cache manager (`RedissonSpringCacheManager`), a session store through `RedissonConnectionFactory`, a transaction manager (`RedissonTransactionManager`), and the `redisson-spring-data-NN` artifacts.
  - https://redisson.pro/docs/integration-with-spring/ (read in part)
- **Redisson objects are named Redis objects.** Locks and synchronizers come from `redisson.getLock("myLock")`, `getFairLock`, `getReadWriteLock`, `getSpinLock`, `getFencedLock`, `getSemaphore` and `getCountDownLatch`. https://redisson.pro/docs/data-and-services/locks-and-synchronizers/
- **Redisson maps and near caches.** Maps come from `getMap`, `getMapCache` and `getLocalCachedMap(name, LocalCachedMapOptions)`. A local-cached map is a near cache in the JVM. `StoreMode.LOCALCACHE` keeps the data only locally but still uses Redis for updates and invalidation, so it is still a Redis use. https://redisson.pro/docs/data-and-services/collections/ (read in part)
- **Lettuce.** `RedisClient.create("redis://...")`, then `connect()` and `sync()` (and async or reactive), in package `io.lettuce.core`. https://redis.github.io/lettuce/getting-started/
- **Jedis.**
  - Jedis 7.2 adds `redis.clients.jedis.RedisClient.create(url)`, `RedisClusterClient` and `RedisSentinelClient`. These deprecate `UnifiedJedis`, `JedisPool`, `JedisPooled` and `JedisCluster`.
  - Commands are methods on the client: `set`, `get`, `hget`.
  - **Pitfall:** Lettuce and Jedis both have a class named `RedisClient`, so matching must use the binary name.
  - Client-side caching (RESP3) keeps a local copy but still talks to Redis.
  - https://redis.io/docs/latest/develop/clients/jedis/connect/
- **ShedLock.** Its Redis lock providers are a lock store, and so a Redis use. They are `RedisLockProvider(connectionFactory, ENV)` and `ReactiveRedisLockProvider` (in `shedlock-provider-redis-spring`), `JedisLockProvider` and `LettuceLockProvider`. https://github.com/lukas-krecan/ShedLock

### In-process caches versus cache servers

- **Caffeine is in-process.** It is a local Java cache library with eviction, loading and async variants. It has no remote or distributed storage; at most, writes propagate to an external resource that the application provides. https://github.com/ben-manes/caffeine/wiki
- **Hazelcast can be either.**
  - Boot first tries a Hazelcast **client**: a `ClientConfig` bean, `spring.hazelcast.config`, the `hazelcast.client.config` system property, or `hazelcast-client.xml` or `.yaml`.
  - Otherwise Boot starts an **embedded member** (a `Config` bean or `hazelcast.xml`). The data then lives in the application's own JVMs.
  - With caching enabled, the instance is wrapped in a `CacheManager`.
  - A client connects to a separate cluster, which is a store. An embedded member is not a separate container.
  - https://docs.spring.io/spring-boot/reference/io/hazelcast.html
- **What C4 says.**
  - A container is "an application or a data store … a runtime boundary around some code that is being executed or some data that is being stored".
  - JARs, modules and libraries organise the code inside an application; they are not containers.
  - Hosted data services count as containers.
  - C4 says nothing about caches as such. https://c4model.com/abstractions/container, https://c4model.com/faq
  - So, as an inference: a cache server reached over the network (Redis, a Hazelcast cluster used as a client, Memcached) is a data store. An in-process cache (Caffeine, ConcurrentMap, Ehcache on heap, an embedded Hazelcast member) is part of its container.

### How other tools do it

- **Code2DFD** (Schneider and Scandariato), the best tool in the nine-tool comparison.
  - It treats databases as services with a `database` stereotype and has an `in_memory_datastore` stereotype.
  - It finds data stores from compose images (`mongo:`, `mysql-server:`), Dockerfile `FROM` lines (mongo, postgres) and `datasource_url`/`host`/`uri` properties.
  - It skips URLs containing `mem:` (in-memory databases) and draws every flow as `jdbc`.
  - Its trace for an image-based store is "heuristic, based on image", with no line.
  - **It has no Redis or cache detection at all.**
  - Sources: https://arxiv.org/html/2304.12769v1 (read in part), and `technology_specific_extractors/databases/dbs_entry.py` and `database_connections/dbc_entry.py` in https://github.com/tuhh-softsec/code2DFD
- **The nine-tool comparison** (arXiv 2412.08352).
  - Ground-truth DFD nodes include databases, but no tool is scored on databases or caches separately, and caches are never discussed.
  - Lessons: deployment files give precision, and 65 components of the dataset appear in no deployment file, so recall needs source analysis.
  - https://arxiv.org/html/2412.08352
- **Lantern is past the state of the art here.** No published tool records cache use at code lines, so the rules below come from the framework docs and from real code.

### Real code: wrappers and shared libraries

From macrozheng/mall (https://github.com/macrozheng/mall):

- **A wrapper hides the call sites.** `mall-common` declares `RedisService` and `RedisServiceImpl`, which wrap `redisTemplate.opsForValue().set/get` and `delete`. The services call `redisService.set(key, …)`, with keys built from `@Value("${redis.key.admin}")` fields (`UmsAdminCacheServiceImpl.java:34-113`; config `redis.key.admin: 'ums:admin'`). Template call sites therefore show only `*` keys. The real key prefixes sit in config and in the callers.
- **A library brings Redis without using it.** `mall-search` depends on `mall-mbg`, which depends on `mall-common`. `mall-common` carries `spring-boot-starter-data-redis` and `RedisServiceImpl`. But `mall-search` sets no Redis config and lacks `mall-security`, whose `@EnableCaching @Configuration RedisConfig extends BaseRedisConfig` wires the beans. So a Redis client and Redis code on a unit's classpath, through a library, do not prove the unit uses Redis.

## Evidence from the test sets (project-0 to project-4)

Counts are for main sources unless marked. Project-0 and project-3 have no Redis and no cache use. The 4 `convertAndSend` hits in project-0 and 10 in project-4 are all `JmsTemplate`, `RabbitTemplate` or STOMP `SimpMessagingTemplate`. Type resolution has to keep these out of Redis, and today it does.

**project-1**
- **bff, value operations.** 6 template operations in total:
  - `SessionRedisService.java:32,36,40`: key `KEY_PREFIX + jti`, with `KEY_PREFIX` at `:24`, giving `bff:session:*`. The TTL is the argument `Duration.ofMinutes(sessionTtlMinutes)` from `@Value`.
  - `RefreshLockService.java:58-59,65,80`: `setIfAbsent(lockKey(jti), LOCK_VALUE, LOCK_TTL)`, a SET NX lock. The key comes from the helper `lockKey` at `:93-95`.
- **bff, Spring Session.** `spring-boot-starter-session-data-redis` at `bff/pom.xml:43`.
- **gateway, rate limiter.** `redis-rate-limiter.*` on 3 routes at `gateway/.../application.properties:21-50`.
- **Connection config.** `spring.data.redis.*` at bff `:7-15` and gateway `:104-107`.
- **A test-helper module in `src/main`.** `common-test/src/main/.../RedisTestContainer.java` (a Testcontainers `GenericContainer`) must not count. It uses no Redis API, so it does not.
- **Coverage map.** It needs `SessionRedisService.java:24-41` for `bff-session-store` and `bff -> redis`.

**project-2**
- **event-service, Spring Cache on Redis.**
  - `@EnableCaching` at `EventServiceApplication.java:9`.
  - `@Cacheable(cacheNames = PublicEventCache.DETAILS, key = "#eventId")` at `PublicEventQueryService.java:95`, on `detail(UUID)` at `:96`. The constant `DETAILS = "public-event-details"` is at `PublicEventCache.java:11`.
  - A programmatic eviction `cacheManager.getCache(DETAILS)` then `cache.evict(eventId)` at `PublicEventCache.java:20-22`. It is called from 14 sites in 4 services, for example `EventManagementService.java:128,166,190` and `InventoryService.java:64,107,151,219,277,305`.
  - `spring.cache.type: redis` at `event-service/.../application.yml:25`, and `spring.cache.redis.time-to-live: ${PUBLIC_EVENT_CACHE_TTL:5m}` at `:27`.
  - `spring-boot-starter-data-redis` at `event-service/pom.xml:26`, and `spring.data.redis.host/port` at `application.yml:19-21`.
  - The test profile sets `spring.cache.type: none` (`src/test/resources/application-test.yml:9`). It must not count.
- **api-gateway.** `new RedisRateLimiter(...)` three times at `GatewayRateLimitConfiguration.java:15,22,28`, reactive starter at `pom.xml:35`, config at `application.yml:8-10`, and readiness on Redis at `:76-78`.
- **The known miss.** The coverage map needs `PublicEventQueryService.java:95-96` for four items: `redis`, `ev-public`, `event -> redis`, and the event-service side of the store. No `redis-use` fact cites those lines. The `event -> redis` link exists today only through config.

**project-4**
- **Auth-Service.**
  - Template operations in `cache/UserPermissionRepository.java:40-42,50-52,58,63-65`. All keys come through the private helper `getFormatedKey`, which returns `USER_PERMISSION_FORMAT_KEY.formatted(o, u)` (`:70-72`). The constant `"organization:%d:user:%d:permissions"` lives in another repository: `crm-shared-lib/.../CacheConstants.java:5`. The failure keys add `":failure"`.
  - TTLs come from `@Value` fields (`:27-31`; `application.properties:41`).
  - `new RedisLockRegistry(factory, LOCK_REGISTRY_KEY)` at `RedisConfig.java:50`, and `lockRegistry.obtain(...)` at `:75`.
  - `spring-integration-redis` at `pom.xml:45`. This artifact is not in the extractor's client list.
  - `testcontainers-redis` is test scope at `pom.xml:115-116`.
- **api-gateway.** `ReactiveRedisTemplate.opsForValue().get(getFormatedKey(...))` at `UserPermissionRepository.java:17-18`. It uses the same shared key constant, which is what ties the two units to one keyspace.

**Totals.** 3 of 5 sets use Redis, in 5 units. The patterns are:

| Pattern | Sets |
| --- | --- |
| Value operations on templates (imperative and reactive) | 2 sets, 9 sites |
| Spring Cache (annotation, programmatic eviction, config) | 1 set |
| Gateway rate limiter (config in one set, code in the other) | 2 sets |
| Spring Session dependency | 1 set |
| Lock registry, and a SET NX lock | 1 set each |

None of the sets uses Redisson, Lettuce or Jedis directly, `@RedisHash`, pub/sub, streams, Caffeine or Hazelcast.

## What the extractor misses today

From reading `RedisExtractor.java` (version 2), `TrackedApis`, `ModuleStructure`, `RedisLinker` and ADR 0073:

1. **Spring Cache annotations are not read.** The extractor reads annotations on types only, never on members. `@Cacheable`, `@CachePut`, `@CacheEvict`, `@Caching`, `@CacheConfig`, `@EnableCaching` and the JCache annotations are ignored. This is the project-2 miss at `:95-96`. `Member.annotations()` already exists, with constants folded (`PublicEventCache.DETAILS` becomes `"public-event-details"`).
2. **Programmatic Spring Cache calls are not tracked.** `org.springframework.cache.` is not a tracked API, so `cacheManager.getCache(name)` followed by `evict`, `put`, `get` or `clear` is invisible (`PublicEventCache.java:20-22`).
3. **Cache config is only partly read.** Only `spring.cache.type=redis` is read. `spring.cache.redis.time-to-live`, `key-prefix`, `use-key-prefix` and `spring.cache.cache-names` are not.
4. **Nothing decides the cache provider.** An extractor that starts reading `@Cacheable` would misreport petclinic-style Caffeine and JCache caches as Redis. The linker would then add an "inferred" Redis store, because today any `redis-use` fact on a unit without config links it to the snapshot's one server or to `localhost:6379`.
5. **Clients outside Spring Data are not read.** There is nothing for Redisson (`org.redisson.api.RedissonClient#getLock/getMap/getBucket/getTopic/getStream`, `RedissonSpringCacheManager`), Lettuce (`io.lettuce.core.RedisClient.create`, command interfaces), Jedis (`redis.clients.jedis.*`) or ShedLock's Redis lock providers. Only their POM artifacts are read, as `client`.
6. **Streams are only half read.** Consumers are missed: `org.springframework.data.redis.stream.StreamMessageListenerContainer` and `StreamReceiver` are not tracked, and `RedisConnection.xAdd` and `publish` are not tracked either. Appending through `opsForStream().add` is read, but as kind `template`.
7. **`@RedisListener` and `@EnableRedisListeners` are not read** (Spring Data Redis 4.1, Boot 4.1).
8. **Keys built through a one-hop helper or a constant from another repository show as `*`.** Examples: `lockKey(jti)` in project-1, and `getFormatedKey` with `CacheConstants` in project-4. The project-1 reference names `bff:session:<jti>:refresh-lock`, and project-4's shared key is what proves the auth-service and gateway share a keyspace. `ModuleStructure.ownCalls()` gives the call; following the helper's return expression is a Java-extractor change.
9. **TTLs on template calls are dropped.** `set(k, v, Duration|long, TimeUnit)` and `setIfAbsent(k, v, Duration)` carry a TTL that is not recorded. `setIfAbsent` with a TTL is the SET NX lock idiom, but it is recorded as a plain write.
10. **Known keys are not recorded for the rate limiter and Spring Session.** The rate limiter uses `request_rate_limiter.*`. Spring Session's namespace comes from `redisNamespace`, `spring.session.(data.)redis.namespace`, or the default `spring:session:sessions:*`; the extractor reads the annotation's namespace but not the config's or the default.
11. **Artifact lists are out of date.** Missing are `spring-boot-data-redis` (Boot 4 module), `spring-integration-redis`, `redisson`, `redisson-spring-data-*`, `shedlock-provider-redis-*` and `spring-session-data-redis`; the last is only a session artifact today. The Boot 4 test starters (`spring-boot-starter-data-redis-test`) must stay excluded even when written without `<scope>test</scope>`.
12. **A dependency alone can make a link.** The linker links a unit that has only a `client` dependency fact. A starter reached through a shared library, as in mall-search, is no use. This is a linker rule, outside #392.
13. **Tests are thin:** 61 lines for 690. There are no cases for in-process caches, test profiles (`spring.cache.type: none`), or non-Redis `convertAndSend`.

What works: imperative and reactive template operations with literal or same-class constant keys, the lock registry, the rate limiter in both config and code, the Spring Session dependency and config, `@RedisHash`, and a listener container. Test sources and test-scope dependencies are excluded.

## Options

1. **Narrow fix: Spring Cache only.** Read the cache annotations on members and the `org.springframework.cache` calls, but only in modules whose cache provider is Redis.
   - For: fixes the one known miss with little code.
   - Against: Redisson, Lettuce, Jedis, streams and `@RedisListener` remain blind spots on real systems. The held-out sets, unseen, may use them.
2. **Full Redis fix.**
   - Contents: option 1, plus the clients outside Spring Data, streams, `@RedisListener`, ShedLock, TTL arguments, the known keys of the rate limiter and Spring Session, the cache config properties, and current artifact lists.
   - Where it sits: all inside the Redis extractor, with new tracked API prefixes.
   - For: covers what the docs and real code show, and each pattern gets a unit test or a project-0 case.
   - Against: more vocabulary (a `stream` kind), more tests, about M size.
3. **Option 2 plus a general cache-store extractor.** Hazelcast client, Memcached, remote Infinispan, Couchbase, and Caffeine recorded as in-process.
   - For: completeness.
   - Against: none of project-0 to project-4 uses these, so nothing on the development sets can verify it. It is a new extractor, which #392's scope excludes.

There are two ways to gate cache annotations on the provider:

- **(a) In the extractor, per module.** Emit `redis-use` facts for annotations only when the module shows Redis as its provider in main config or code.
  - For: simple, and keeps the linker unchanged.
  - Against: ignores profiles.
- **(b) In the linker, per view.** Record the annotations with a `provider` attribute and let the linker decide per view.
  - For: handles profiles correctly.
  - Against: it is a linker change, outside #392's scope.

## Recommendation

**Take option 2, trimmed to what the development sets show, with provider gate (a).** Put the rest into `Later` with project-0 cases, and the linker's part into its own issue.

**Rule:** A unit uses a data store only when its own runtime reaches a store server over the network. For Redis, that is one of:

- template or repository commands in its code
- a Spring cache whose provider is Redis
- Spring Session on Redis
- a gateway `RedisRateLimiter`
- a lock registry on Redis
- pub/sub code
- connection config naming a server

In-process caches are part of their container and never a data store: Caffeine, the simple `ConcurrentMap` cache, Ehcache on heap, and an embedded Hazelcast member. Test sources, test-scope dependencies and test starters are not uses, and neither is `spring.cache.type=none`.

A Spring cache counts as Redis when its module's main config sets `spring.cache.type=redis`. Without that property it counts when the module's code builds a Redis cache manager, or when the module has a Redis client among its own dependencies and none of the providers Spring Boot tries first (JCache, which brings Ehcache 3; Hazelcast; Infinispan; Couchbase).

**Decided for #392:**

- **Spring and JCache cache annotations** on methods: `@Cacheable`, `@CachePut`, `@CacheEvict`, `@Caching`, `@CacheResult`, `@CachePut` and `@CacheRemove(All)`.
  - Each gives a `cache` fact on the method, at the annotation, one per cache. Constants are folded, and the caches `@CacheConfig` or `@CacheDefaults` name are used for methods that name none.
  - The fact records the operation (`read`, `write` or `delete`), the key `<cache>::*`, and the TTL `spring.cache.redis.time-to-live` sets.
  - `@EnableCaching` gives a `cache` fact on its class. All of these are gated on the provider.
- **Programmatic cache calls.** `cacheManager.getCache(name)` followed by `get`, `put`, `evict` or `clear` gives a `cache` fact at the call, gated the same way. `org.springframework.cache.` joins the tracked APIs.
- **The rate limiter's keys.** The rate limiter facts carry the key `request_rate_limiter.*`.
- **Artifact lists.** They add `spring-boot-data-redis` (Boot 4), `spring-integration-redis` and `redisson`. Artifacts ending in `-test` are skipped.
- **Deferred to `Later`, each with a project-0 case first:**
  - Redisson, Lettuce and Jedis commands
  - stream consumers and `@RedisListener`
  - ShedLock's Redis providers
  - TTLs given to template calls, and `setIfAbsent` with a TTL as a lock
  - Spring Session's default and configured key namespace
  - meta-annotated cache annotations
  - keys built in a helper method, which is a Java extractor change
  - a general cache-store extractor: Hazelcast client, Memcached, Infinispan, Couchbase
- **Deferred to the linker:**
  - the cache provider per profile
  - no link from a client dependency alone
  - units that share a keyspace through a shared key constant

## Sources read in full

- https://docs.spring.io/spring-boot/reference/io/caching.html
- https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html
- https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html
- https://docs.spring.io/spring-framework/reference/integration/cache/jsr-107.html
- https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/requestratelimiter-factory.html
- https://docs.spring.io/spring-data/redis/reference/redis/pubsub.html
- https://docs.spring.io/spring-data/redis/reference/redis/pubsub-receiving.html
- https://docs.spring.io/spring-data/redis/reference/redis/pubsub-annotated.html
- https://docs.spring.io/spring-data/redis/reference/redis/redis-streams.html
- https://docs.spring.io/spring-session/reference/configuration/redis.html
- https://docs.spring.io/spring-data/redis/reference/redis/redis-repositories/usage.html
- https://docs.spring.io/spring-data/redis/reference/redis/redis-repositories/keyspaces.html
- https://docs.spring.io/spring-boot/reference/io/hazelcast.html
- https://github.com/ben-manes/caffeine/wiki
- https://redisson.pro/docs/data-and-services/locks-and-synchronizers/
- https://redis.github.io/lettuce/getting-started/
- https://redis.io/docs/latest/develop/clients/jedis/connect/
- https://c4model.com/abstractions/container
- https://c4model.com/faq
- https://arxiv.org/html/2412.08352 (both parts)
- https://github.com/lukas-krecan/ShedLock (README, Redis provider sections)
- Code: tuhh-softsec/code2DFD `technology_specific_extractors/databases/dbs_entry.py` and `database_connections/dbc_entry.py`
- Code: spring-projects/spring-petclinic `CacheConfiguration.java`, `pom.xml`
- Code: macrozheng/mall `BaseRedisConfig.java`, `RedisServiceImpl.java`, `UmsAdminCacheServiceImpl.java`, `mall-security/.../RedisConfig.java`, and the module POMs and `application*.yml`
- Code: spring-cloud/spring-cloud-gateway `RedisRateLimiter.java` (`getKeys`, error path)

## Sources not read or read only in part (carry no weight)

- https://docs.spring.io/spring-boot/reference/data/nosql.html: the Redis section only, the first 100k of 102k characters.
- https://redisson.pro/docs/integration-with-spring/: the first 100k of 130k characters.
- https://redisson.pro/docs/data-and-services/collections/: the first 100k of 221k characters.
- https://arxiv.org/html/2304.12769v1 (Code2DFD paper): the first 100k of 121k characters. Its detection logic was checked in the tool's source instead.
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide: the first 100k of 127k characters.
- https://github.com/redisson/redisson/wiki/7.-distributed-collections: the page had no API content.
- Seen in search results only: Visual Paradigm's and nitinkc's C4 guides (Redis drawn as a container), Bushong et al. CLOSER 2022, arXiv 2207.02988 and 2403.06941, specfy stack-analyser.