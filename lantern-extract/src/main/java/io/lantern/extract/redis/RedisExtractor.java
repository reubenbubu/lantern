package io.lantern.extract.redis;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ConfigValue;
import io.lantern.extract.config.ModuleConfig;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the use of Redis into facts: what code does with Redis templates and operations (the keys
 * it names, when the code writes them out), Spring Data Redis hashes ({@code @RedisHash}), Spring
 * Session, Redis lock registries, caches, publish and subscribe and the gateway's {@code
 * RedisRateLimiter}; and the same uses that only config or a dependency shows. The product and
 * version Redis runs as come from the deployment extractor's containers. Which Redis server a unit
 * connects to is in its config ({@code spring.data.redis.*}), which the config extractor already
 * records; matching them is the linker's. See {@code docs/adr/0073-redis-data-store.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}), with the
 * same confidence, and config and POMs from git at the commit.
 */
public final class RedisExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "redis";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "3";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "redis-extractor";

  /** Attribute: the subject uses Redis; its value is the kind of use. */
  public static final String REDIS_USE = "redis-use";

  /** The kinds of use, as the value of {@link #REDIS_USE}. */
  public static final String TEMPLATE = "template";

  public static final String HASH = "hash";
  public static final String SESSION = "session";
  public static final String RATE_LIMITER = "rate-limiter";
  public static final String LOCK = "lock";
  public static final String CACHE = "cache";
  public static final String PUBSUB = "pubsub";
  public static final String CLIENT = "client";

  private static final String RESOURCE = "/vocabulary/redis-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String CORE = "org.springframework.data.redis.core.";
  private static final String REDIS_HASH = CORE + "RedisHash";
  private static final String SESSION_PACKAGE = "org.springframework.session.data.redis.";
  private static final String LOCK_REGISTRY =
      "org.springframework.integration.redis.util.RedisLockRegistry";
  private static final String RATE_LIMITER_TYPE =
      "org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter";
  private static final String CACHE_MANAGER = "org.springframework.data.redis.cache.";
  private static final String LISTENER_PACKAGE = "org.springframework.data.redis.listener.";
  private static final String LISTENER_CONTAINER =
      "org.springframework.data.redis.listener.RedisMessageListenerContainer";

  /**
   * The keys the gateway's RedisRateLimiter writes: {@code request_rate_limiter.{route.id}.tokens}.
   */
  private static final String RATE_LIMITER_KEY = "request_rate_limiter.*";

  private static final String SPRING_CACHE = "org.springframework.cache.";
  private static final String CACHE_ANNOTATION = SPRING_CACHE + "annotation.";
  private static final String JCACHE_ANNOTATION = "javax.cache.annotation.";
  private static final String ENABLE_CACHING = CACHE_ANNOTATION + "EnableCaching";

  /** Spring's and JCache's cache annotations on a method, and what each does with the cache. */
  private static final Map<String, String> CACHE_OPERATIONS =
      Map.of(
          CACHE_ANNOTATION + "Cacheable", "read",
          CACHE_ANNOTATION + "CachePut", "write",
          CACHE_ANNOTATION + "CacheEvict", "delete",
          JCACHE_ANNOTATION + "CacheResult", "read",
          JCACHE_ANNOTATION + "CachePut", "write",
          JCACHE_ANNOTATION + "CacheRemove", "delete",
          JCACHE_ANNOTATION + "CacheRemoveAll", "delete");

  /** The class annotations that name the caches of a class's methods that name none. */
  private static final Set<String> CACHE_DEFAULTS =
      Set.of(CACHE_ANNOTATION + "CacheConfig", JCACHE_ANNOTATION + "CacheDefaults");

  /** What a call on a Spring {@code Cache} does with it. */
  private static final Map<String, String> CACHE_CALLS =
      Map.ofEntries(
          Map.entry("get", "read"),
          Map.entry("retrieve", "read"),
          Map.entry("put", "write"),
          Map.entry("putIfAbsent", "write"),
          Map.entry("evict", "delete"),
          Map.entry("evictIfPresent", "delete"),
          Map.entry("clear", "delete"),
          Map.entry("invalidate", "delete"));

  /**
   * Dependencies, by artifact ID prefix, of the cache providers Spring Boot tries before Redis
   * (JCache providers such as Ehcache 3 and Caffeine's {@code jcache}, Hazelcast, Infinispan and
   * Couchbase). JCache's own {@code cache-api}, which its annotations need, picks no provider.
   */
  private static final List<String> EARLIER_CACHE_PROVIDERS =
      List.of(
          "ehcache",
          "jcache",
          "hazelcast",
          "infinispan",
          "couchbase",
          "spring-boot-starter-data-couchbase");

  /** The template accessors that name the data structure operations are made on. */
  private static final Map<String, String> STRUCTURES =
      Map.ofEntries(
          Map.entry("opsForValue", "value"),
          Map.entry("opsForHash", "hash"),
          Map.entry("opsForList", "list"),
          Map.entry("opsForSet", "set"),
          Map.entry("opsForZSet", "zset"),
          Map.entry("opsForStream", "stream"),
          Map.entry("opsForGeo", "geo"),
          Map.entry("opsForHyperLogLog", "hyperloglog"),
          Map.entry("boundValueOps", "value"),
          Map.entry("boundHashOps", "hash"),
          Map.entry("boundListOps", "list"),
          Map.entry("boundSetOps", "set"),
          Map.entry("boundZSetOps", "zset"),
          Map.entry("boundStreamOps", "stream"),
          Map.entry("boundGeoOps", "geo"));

  private static final Set<String> READS =
      Set.of(
          "get",
          "multiGet",
          "hasKey",
          "keys",
          "scan",
          "members",
          "range",
          "rangeByScore",
          "reverseRange",
          "size",
          "isMember",
          "entries",
          "values",
          "score",
          "rank",
          "count",
          "randomMember",
          "intersect",
          "union",
          "difference",
          "getExpire",
          "type",
          "read",
          "length");

  private static final Set<String> WRITES =
      Set.of(
          "set",
          "setIfAbsent",
          "setIfPresent",
          "multiSet",
          "multiSetIfAbsent",
          "put",
          "putAll",
          "putIfAbsent",
          "add",
          "addAll",
          "push",
          "leftPush",
          "leftPushAll",
          "rightPush",
          "rightPushAll",
          "pop",
          "leftPop",
          "rightPop",
          "increment",
          "decrement",
          "append",
          "expire",
          "expireAt",
          "persist",
          "rename",
          "trim");

  private static final Set<String> DELETES = Set.of("delete", "remove", "unlink", "getAndDelete");

  private static final Set<String> EXECUTES =
      Set.of("execute", "executePipelined", "executeWithStickyConnection");

  /** Dependencies that bring Spring Session's Redis store, by artifact ID. */
  private static final Set<String> SESSION_ARTIFACTS =
      Set.of(
          "spring-session-data-redis",
          "spring-boot-starter-session-data-redis",
          "spring-boot-session-data-redis");

  /**
   * Dependencies that bring Spring Data Redis, which Spring Boot's Redis cache needs, by artifact
   * ID; a plain Jedis, Lettuce or Redisson client does not.
   */
  private static final Set<String> SPRING_DATA_REDIS_ARTIFACTS =
      Set.of(
          "spring-boot-starter-data-redis",
          "spring-boot-starter-data-redis-reactive",
          "spring-boot-data-redis",
          "spring-data-redis",
          "redisson-spring-boot-starter",
          "spring-integration-redis",
          "spring-session-data-redis",
          "spring-boot-starter-session-data-redis",
          "spring-boot-session-data-redis");

  /** Dependencies that bring a Redis client, by artifact ID. */
  private static final Set<String> CLIENT_ARTIFACTS =
      Set.of(
          "spring-boot-starter-data-redis",
          "spring-boot-starter-data-redis-reactive",
          "spring-boot-data-redis",
          "spring-data-redis",
          "spring-integration-redis",
          "redisson-spring-boot-starter",
          "redisson",
          "jedis",
          "lettuce-core");

  private static final long MAX_BYTES = 4L * 1024 * 1024;

  private static final Pattern DEPENDENCY = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
  private static final Pattern ARTIFACT =
      Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
  private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
  private static final Pattern PLUGINS = Pattern.compile("(?s)<plugins>.*?</plugins>");
  private static final Pattern EXCLUSIONS = Pattern.compile("(?s)<exclusions>.*?</exclusions>");
  private static final Pattern SCOPE = Pattern.compile("<scope>\\s*([a-z]+)\\s*</scope>");
  private static final Pattern OPTIONAL = Pattern.compile("<optional>\\s*true\\s*</optional>");
  private static final Pattern MANAGEMENT =
      Pattern.compile("(?s)<dependencyManagement>.*?</dependencyManagement>");

  /** The vocabulary types this extractor's facts use. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = RedisExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories, reading their code first.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, ordered as for {@link #extract(JavaAnalysis, long)}
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(io.lantern.extract.java.JavaExtractor.analyze(repos), repos, snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link
   * io.lantern.extract.java.JavaExtractor#analyze}, and of the config and POMs of the repositories
   * it is in.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, evidence,
   *     attributes and ID
   * @throws IllegalStateException if git fails or times out while reading files
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    return extract(code, code.modules().stream().map(JavaAnalysis.Module::repo).toList(), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read, and of the config and POMs of all the
   * given repositories, those without code too.
   *
   * @param code the code of the snapshot's repositories
   * @param repos the repositories of the snapshot; those of the code are read whether listed or not
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, ordered as for {@link #extract(JavaAnalysis, long)}
   * @throws IllegalStateException if git fails or times out while reading files
   */
  public List<Fact> extract(JavaAnalysis code, List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(repos, "repos");
    Run run = new Run(Validity.from(snapshot));
    Set<RepoBuild> all = new LinkedHashSet<>(repos);
    for (JavaAnalysis.Module module : code.modules()) {
      all.add(module.repo());
      run.code(module);
      run.dependencies(module);
    }
    for (RepoBuild repo : all) {
      for (ModuleConfig config : ConfigExtractor.read(repo)) {
        run.config(config);
      }
    }
    run.caches();
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** One extraction: the facts so far. */
  private static final class Run {

    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    /** What decides each module's cache provider, and its cache uses until that is decided. */
    private final Map<EntityId, Caching> caching = new LinkedHashMap<>();

    private Caching caching(EntityId module) {
      return caching.computeIfAbsent(module, k -> new Caching());
    }

    Run(Validity validity) {
      this.validity = validity;
    }

    // -------------------------------------------------------------------------------------------
    // Code

    void code(JavaAnalysis.Module m) {
      for (TypeDecl type : m.structure().types()) {
        for (Annotation a : type.annotations()) {
          annotation(m, type, a);
        }
        cacheAnnotations(m, type);
      }
      for (Invocation i : m.structure().invocations()) {
        invocation(m, i);
      }
    }

    private void annotation(JavaAnalysis.Module m, TypeDecl type, Annotation a) {
      EntityId subject = typeId(type.binaryName());
      if (a.type().equals(REDIS_HASH)) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("api", a.type());
        String keyspace = first(a, "value");
        if (keyspace == null) {
          keyspace = first(a, "keyspace");
        }
        attributes.put("hash", keyspace != null ? keyspace : type.binaryName());
        String ttl = first(a, "timeToLive");
        if (ttl != null && !ttl.equals("-1")) {
          attributes.put("ttl", ttl + unit(first(a, "timeToLiveUnit")));
        }
        code(m, subject, HASH, attributes, a.where() != null ? a.where() : type.where());
      } else if (a.type().startsWith(SESSION_PACKAGE)
          && simpleName(a.type()).startsWith("EnableRedis")) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("api", a.type());
        String namespace = first(a, "redisNamespace");
        if (namespace != null) {
          attributes.put("namespace", namespace);
        }
        String interval = first(a, "maxInactiveIntervalInSeconds");
        if (interval != null) {
          attributes.put("ttl", interval + "s");
        }
        code(m, subject, SESSION, attributes, a.where() != null ? a.where() : type.where());
      }
    }

    /**
     * What code does with Redis: a template operation ({@code redisTemplate.opsForValue().set(key,
     * value)}), a lock registry, the rate limiter, a cache manager or a listener container.
     */
    private void invocation(JavaAnalysis.Module m, Invocation i) {
      String root = i.rootType();
      if (root.startsWith(CORE)) {
        String name = simpleName(root);
        if (name.endsWith("RedisTemplate")
            || name.endsWith("RedisOperations")
            || name.endsWith("Operations")) {
          template(m, i);
        }
        return;
      }
      List<Expression.Invoke> chain = i.expression().chain();
      Expression.Invoke first = chain.getFirst();
      if (root.startsWith(SPRING_CACHE) && !root.startsWith(CACHE_ANNOTATION)) {
        cacheCall(m, i);
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("api", root);
      if (root.equals(LOCK_REGISTRY)) {
        if (first.method().equals("<init>")) {
          Expression registry = first.arguments().size() > 1 ? first.arguments().get(1) : null;
          String key = registry == null ? null : pattern(registry);
          if (key != null) {
            attributes.put("key", key + ":*");
          }
          attributes.put("operation", "other");
        } else if (first.method().equals("obtain") || first.method().equals("executeLocked")) {
          attributes.put("operation", "lock");
        } else {
          return;
        }
        code(m, i.caller(), LOCK, attributes, i.where());
      } else if (root.equals(RATE_LIMITER_TYPE) && first.method().equals("<init>")) {
        attributes.put("operation", "other");
        attributes.put("key", RATE_LIMITER_KEY);
        code(m, i.caller(), RATE_LIMITER, attributes, i.where());
      } else if ((simpleName(root).equals("RedisCacheManager")
              || simpleName(root).equals("RedisCacheConfiguration"))
          && root.startsWith(CACHE_MANAGER)
          && !first.method().startsWith("get")) {
        if (simpleName(root).equals("RedisCacheManager")) {
          caching(m.id()).redisManager = true;
        }
        attributes.put("operation", "other");
        code(m, i.caller(), CACHE, attributes, i.where());
      } else if (listens(root, chain)) {
        attributes.put("operation", "read");
        code(m, i.caller(), PUBSUB, attributes, i.where());
      }
    }

    /** Whether a call chain on a type of the listener package subscribes to channels. */
    private static boolean listens(String root, List<Expression.Invoke> chain) {
      if (root.equals(LISTENER_CONTAINER)) {
        return chain.stream().anyMatch(c -> c.method().equals("addMessageListener"));
      }
      if (root.equals(LISTENER_PACKAGE + "ReactiveRedisMessageListenerContainer")) {
        return chain.stream().anyMatch(c -> Set.of("receive", "receiveLater").contains(c.method()));
      }
      return root.equals(LISTENER_PACKAGE + "adapter.MessageListenerAdapter");
    }

    /** A template operation: the data structure it is made on, its operation and its key. */
    private void template(JavaAnalysis.Module m, Invocation i) {
      String structure = null;
      String bound = null;
      for (Expression.Invoke call : i.expression().chain()) {
        String name = call.method();
        if (STRUCTURES.containsKey(name)) {
          structure = STRUCTURES.get(name);
          if (name.startsWith("bound") && !call.arguments().isEmpty()) {
            bound = pattern(call.arguments().getFirst());
          }
          continue;
        }
        boolean pubsub = name.equals("convertAndSend");
        String operation = pubsub ? "write" : operation(name);
        if (operation == null) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("api", i.rootType());
        attributes.put("operation", operation);
        if (structure != null) {
          attributes.put("structure", structure);
        }
        String key =
            bound != null
                ? bound
                : operation.equals("other") || call.arguments().isEmpty()
                    ? null
                    : pattern(call.arguments().getFirst());
        if (key != null) {
          attributes.put(pubsub ? "channel" : "key", key);
        }
        code(m, i.caller(), pubsub ? PUBSUB : TEMPLATE, attributes, i.where());
        return;
      }
    }

    /**
     * The cache annotations of a type's methods ({@code @Cacheable("books")}), with the caches its
     * {@code @CacheConfig} names for methods that name none, and its {@code @EnableCaching}; kept
     * until the module's cache provider is known.
     */
    private void cacheAnnotations(JavaAnalysis.Module m, TypeDecl type) {
      List<String> defaults = List.of();
      for (Annotation a : type.annotations()) {
        if (CACHE_DEFAULTS.contains(a.type())) {
          defaults = cacheNames(a);
        } else if (a.type().equals(ENABLE_CACHING)) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("api", a.type());
          attributes.put("operation", "other");
          pending(
              m,
              typeId(type.binaryName()),
              attributes,
              a.where() != null ? a.where() : type.where());
        }
      }
      // On a class, an operation applies to each of its public methods; it is recorded on the
      // class.
      cacheOperations(m, typeId(type.binaryName()), type.annotations(), defaults, type.where());
      for (ModuleStructure.Member member : type.members()) {
        cacheOperations(m, member.id(), member.annotations(), defaults, member.where());
      }
    }

    /** The cache operations among annotations, alone or inside {@code @Caching}. */
    private void cacheOperations(
        JavaAnalysis.Module m,
        EntityId subject,
        List<Annotation> annotations,
        List<String> defaults,
        Location fallback) {
      for (Annotation a : annotations) {
        List<Annotation> operations = new ArrayList<>();
        if (a.type().equals(CACHE_ANNOTATION + "Caching")) {
          a.nested().values().forEach(operations::addAll);
        } else {
          operations.add(a);
        }
        for (Annotation op : operations) {
          String operation = CACHE_OPERATIONS.get(op.type());
          if (operation == null) {
            continue;
          }
          List<String> names = cacheNames(op);
          Location at = a.where() != null ? a.where() : fallback;
          for (String name : names.isEmpty() ? defaults : names) {
            ObjectNode attributes = FactJson.newObject();
            attributes.put("api", op.type());
            attributes.put("operation", operation);
            attributes.put("cache", name);
            attributes.put("key", name + "::*");
            pending(m, subject, attributes, at);
          }
          if (names.isEmpty() && defaults.isEmpty()) {
            ObjectNode attributes = FactJson.newObject();
            attributes.put("api", op.type());
            attributes.put("operation", operation);
            pending(m, subject, attributes, at);
          }
        }
      }
    }

    /** The caches an annotation names: {@code cacheNames} or {@code value}, or JCache's one. */
    private static List<String> cacheNames(Annotation a) {
      List<String> names = new ArrayList<>();
      for (String element : List.of("cacheNames", "value", "cacheName")) {
        for (String v : a.values(element)) {
          if (!v.isBlank() && !names.contains(v.strip())) {
            names.add(v.strip());
          }
        }
      }
      return names;
    }

    /**
     * A call on a Spring {@code CacheManager} or {@code Cache}: {@code
     * cacheManager.getCache("books").evict(id)}, the cache it names and what it does with it.
     */
    private void cacheCall(JavaAnalysis.Module m, Invocation i) {
      // A cache manager the code builds itself (new CaffeineCacheManager(...)) is the provider.
      Expression.Invoke built = i.expression().chain().getFirst();
      if (simpleName(i.rootType()).endsWith("CacheManager")
          && !i.rootType().equals(SPRING_CACHE + "CacheManager")
          && (built.method().equals("<init>")
              || Set.of("create", "builder").contains(built.method()))) {
        caching(m.id()).otherManager = true;
        return;
      }
      String name = null;
      String operation = null;
      for (Expression.Invoke call : i.expression().chain()) {
        if (call.method().equals("getCache")) {
          name =
              call.arguments().isEmpty()
                      || !(call.arguments().getFirst() instanceof Expression.Literal l)
                  ? null
                  : l.value();
        } else if (CACHE_CALLS.containsKey(call.method())) {
          operation = CACHE_CALLS.get(call.method());
        }
      }
      if (operation == null) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("api", i.rootType());
      attributes.put("operation", operation);
      if (name != null) {
        attributes.put("cache", name);
        attributes.put("key", name + "::*");
      }
      pending(m, i.caller(), attributes, i.where());
    }

    /** A cache use, kept until the module's cache provider is known. */
    private void pending(
        JavaAnalysis.Module m, EntityId subject, ObjectNode attributes, Location at) {
      if (subject == null) {
        return;
      }
      RepoBuild repo = m.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              at.file().path(),
              at.start() > 0 ? new Evidence.LineRange(at.start(), at.end()) : null,
              at.file().contentHash());
      caching(m.id()).uses.add(new CacheUse(subject, attributes, evidence, m.confidence()));
    }

    /**
     * The kept cache uses of the modules whose cache provider is Redis: a Redis cache manager built
     * in code; else, with no other cache manager built in code, as Spring Boot picks it: {@code
     * spring.cache.type}, else Spring Data Redis with no provider Boot tries first; with the time
     * to live {@code spring.cache.redis.time-to-live} sets.
     */
    void caches() {
      for (Caching c : caching.values()) {
        boolean redis =
            c.redisManager
                || (!c.otherManager
                    && (c.type != null
                        ? c.type.equalsIgnoreCase("redis")
                        : c.springDataRedis && !c.earlierProvider));
        if (!redis) {
          continue;
        }
        for (CacheUse use : c.uses) {
          ObjectNode attributes = use.attributes().deepCopy();
          if (c.ttl != null && attributes.has("cache")) {
            attributes.put("ttl", c.ttl);
          }
          add(use.subject(), CACHE, attributes, use.evidence(), use.confidence());
        }
      }
    }

    private void code(
        JavaAnalysis.Module m, EntityId subject, String kind, ObjectNode attributes, Location at) {
      if (subject == null) {
        return;
      }
      RepoBuild repo = m.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              at.file().path(),
              at.start() > 0 ? new Evidence.LineRange(at.start(), at.end()) : null,
              at.file().contentHash());
      add(subject, kind, attributes, evidence, m.confidence());
    }

    // -------------------------------------------------------------------------------------------
    // Dependencies and config

    /** A client or Spring Session's Redis store among the module's own POM dependencies. */
    void dependencies(JavaAnalysis.Module m) {
      String path = m.directory().isEmpty() ? "pom.xml" : m.directory() + "/pom.xml";
      byte[] bytes = GitFiles.read(m.repo(), path, MAX_BYTES);
      if (bytes == null) {
        return;
      }
      String pom = new String(bytes, StandardCharsets.UTF_8);
      // Comments, plugins (their own dependencies), dependency management and exclusions do not
      // add a dependency of the module; they are blanked out, keeping the lines.
      String live = pom;
      for (Pattern ignored : List.of(COMMENT, PLUGINS, MANAGEMENT, EXCLUSIONS)) {
        live = blank(live, ignored);
      }
      Matcher dependency = DEPENDENCY.matcher(live);
      while (dependency.find()) {
        String body = dependency.group(1);
        Matcher artifact = ARTIFACT.matcher(body);
        Matcher scope = SCOPE.matcher(body);
        String scoped = scope.find() ? scope.group(1) : "compile";
        if (!artifact.find()
            || Set.of("test", "provided").contains(scoped)
            || OPTIONAL.matcher(body).find()) {
          continue;
        }
        String id = artifact.group(1);
        if (id.endsWith("-test")) {
          continue;
        }
        if (EARLIER_CACHE_PROVIDERS.stream().anyMatch(id::startsWith)) {
          caching(m.id()).earlierProvider = true;
        }
        String kind =
            SESSION_ARTIFACTS.contains(id)
                ? SESSION
                : CLIENT_ARTIFACTS.contains(id) ? CLIENT : null;
        if (kind == null) {
          continue;
        }
        if (SPRING_DATA_REDIS_ARTIFACTS.contains(id)) {
          caching(m.id()).springDataRedis = true;
        }
        int line = line(pom, dependency.start(1) + artifact.start());
        ObjectNode attributes = FactJson.newObject();
        attributes.put("api", id);
        attributes.put("source", "dependency");
        add(
            m.id(),
            kind,
            attributes,
            new Evidence(
                m.repo().repo().name(),
                m.repo().commit(),
                path,
                new Evidence.LineRange(line, line),
                sha256(bytes)),
            1.0);
      }
    }

    /** Spring Session, the rate limiter and the cache named in a module's config. */
    void config(ModuleConfig config) {
      SortedMap<String, ConfigValue> values = config.values(null);
      boolean limiter = false;
      boolean session = false;
      for (ConfigValue v : values.values()) {
        String key = v.key();
        String relaxed = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        String kind = null;
        boolean requestLimiter =
            v.value().strip().toLowerCase(Locale.ROOT).startsWith("requestratelimiter");
        if ((relaxed.contains("redisratelimiter") || requestLimiter) && !limiter) {
          kind = RATE_LIMITER;
          limiter = true;
        } else if (relaxed.equals("spring.cache.type")) {
          caching(config.module()).type = v.value().strip();
          if (!v.value().strip().equalsIgnoreCase("redis")) {
            continue;
          }
          kind = CACHE;
        } else if (relaxed.equals("spring.cache.redis.timetolive")) {
          caching(config.module()).ttl = v.value().strip();
          continue;
        } else if (!session
            && ((relaxed.equals("spring.session.storetype") && v.value().equalsIgnoreCase("redis"))
                || relaxed.startsWith("spring.session.redis.")
                || relaxed.startsWith("spring.session.data.redis."))) {
          kind = SESSION;
          session = true;
        }
        if (kind == null) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("source", "config");
        attributes.put("configKey", key);
        if (kind.equals(RATE_LIMITER)) {
          attributes.put("key", RATE_LIMITER_KEY);
        }
        ConfigValue.Where where = v.where();
        add(
            config.module(),
            kind,
            attributes,
            new Evidence(
                config.repo().repo().name(),
                config.repo().commit(),
                where.path(),
                where.line() > 0 ? new Evidence.LineRange(where.line(), where.line()) : null,
                where.contentHash()),
            1.0);
      }
    }

    // -------------------------------------------------------------------------------------------

    /** A cache use found in code. */
    private record CacheUse(
        EntityId subject, ObjectNode attributes, Evidence evidence, double confidence) {}

    /** What decides a module's cache provider, and the cache uses found in its code. */
    private static final class Caching {
      String type;
      String ttl;
      boolean redisManager;
      boolean otherManager;
      boolean springDataRedis;
      boolean earlierProvider;
      final List<CacheUse> uses = new ArrayList<>();
    }

    private void add(
        EntityId subject,
        String kind,
        ObjectNode attributes,
        Evidence evidence,
        double confidence) {
      Fact fact =
          Fact.create(
              subject,
              REDIS_USE,
              FactObject.string(kind),
              attributes,
              ORIGIN,
              evidence,
              confidence,
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Names

  /** The kind of operation a template method is, or null if it is not one on data. */
  private static String operation(String method) {
    if (READS.contains(method)) {
      return "read";
    }
    if (WRITES.contains(method)) {
      return "write";
    }
    if (DELETES.contains(method)) {
      return "delete";
    }
    return EXECUTES.contains(method) ? "other" : null;
  }

  /**
   * A Redis key as the code writes it, with {@code *} for what it does not write out ({@code
   * "bff:session:" + jti} is {@code bff:session:*}); null if nothing of it is written out.
   */
  static String pattern(Expression e) {
    StringBuilder b = new StringBuilder();
    append(b, e);
    String key = b.toString();
    return key.replace("*", "").isBlank() ? null : key;
  }

  private static void append(StringBuilder b, Expression e) {
    switch (e) {
      case Expression.Literal l -> b.append(l.value());
      case Expression.Concat c -> c.parts().forEach(p -> append(b, p));
      case Expression.Invoke i
          when i.method().equals("formatted") && i.receiver() instanceof Expression.Literal l ->
          b.append(placeholders(l.value()));
      case Expression.Invoke i
          when i.method().equals("format")
              && i.receiver() instanceof Expression.TypeRef
              && !i.arguments().isEmpty()
              && i.arguments().getFirst() instanceof Expression.Literal l ->
          b.append(placeholders(l.value()));
      default -> {
        if (b.isEmpty() || b.charAt(b.length() - 1) != '*') {
          b.append('*');
        }
      }
    }
  }

  private static String placeholders(String format) {
    return format.replaceAll("%[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]", "*").replaceAll("\\*+", "*");
  }

  private static String unit(String timeUnit) {
    return switch (timeUnit == null ? "SECONDS" : timeUnit) {
      case "MINUTES" -> "m";
      case "HOURS" -> "h";
      case "DAYS" -> "d";
      case "MILLISECONDS" -> "ms";
      default -> "s";
    };
  }

  private static String first(Annotation a, String element) {
    List<String> values = a.values(element);
    return values.isEmpty() || values.getFirst().isBlank() ? null : values.getFirst().strip();
  }

  private static String simpleName(String binaryName) {
    String name = binaryName.substring(binaryName.lastIndexOf('.') + 1);
    return name.substring(name.lastIndexOf('$') + 1);
  }

  /** The text with what a pattern matches replaced by spaces, newlines kept. */
  private static String blank(String text, Pattern pattern) {
    Matcher m = pattern.matcher(text);
    StringBuilder b = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(b, Matcher.quoteReplacement(m.group().replaceAll("[^\\n]", " ")));
    }
    m.appendTail(b);
    return b.toString();
  }

  /** The 1-based line of a character offset. */
  private static int line(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static EntityId typeId(String binaryName) {
    try {
      return new EntityId("type", binaryName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
