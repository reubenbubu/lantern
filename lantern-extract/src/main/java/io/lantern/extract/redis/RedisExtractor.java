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
 * RedisRateLimiter}; the same uses that only config or a dependency shows; and the Redis images a
 * repository's compose files run, for the technology of the data store. Which Redis server a unit
 * connects to is in its config ({@code spring.data.redis.*}), which the config extractor already
 * records; matching them is the linker's. See {@code docs/adr/0073-redis-data-store.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}), with the
 * same confidence, and config, POMs and compose files from git at the commit.
 */
public final class RedisExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "redis";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "redis-extractor";

  /** Attribute: the subject uses Redis; its value is the kind of use. */
  public static final String REDIS_USE = "redis-use";

  /** Attribute: the subject module's repository runs a Redis image in a compose file. */
  public static final String REDIS_IMAGE = "redis-image";

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
  private static final String LISTENER_CONTAINER =
      "org.springframework.data.redis.listener.RedisMessageListenerContainer";

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

  /** Dependencies that bring a Redis client, by artifact ID. */
  private static final Set<String> CLIENT_ARTIFACTS =
      Set.of(
          "spring-boot-starter-data-redis",
          "spring-boot-starter-data-redis-reactive",
          "spring-data-redis",
          "redisson-spring-boot-starter",
          "jedis",
          "lettuce-core");

  private static final long MAX_BYTES = 4L * 1024 * 1024;

  private static final Pattern COMPOSE_FILE =
      Pattern.compile("(?i)(.*/)?(docker-)?compose([._-][^/]*)?\\.ya?ml");
  private static final Pattern IMAGE_LINE =
      Pattern.compile("^(\\s*)-?\\s*image:\\s*[\"']?([^\\s\"'#]+)");
  private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):\\s*(#.*)?$");
  private static final Pattern VARIABLE = Pattern.compile("\\$\\{[^:}]*:?-([^}]*)}");
  private static final Pattern DEPENDENCY = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
  private static final Pattern ARTIFACT =
      Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
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
   * io.lantern.extract.java.JavaExtractor#analyze}, and of the config, POMs and compose files of
   * the repositories it is in.
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
   * Extracts the facts of a snapshot's code already read, and of the config, POMs and compose files
   * of all the given repositories, those without code too: the compose files of a deployment
   * repository name the images of the system's data stores.
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
      run.compose(repo);
    }
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
        template(m, i);
        return;
      }
      List<Expression.Invoke> chain = i.expression().chain();
      Expression.Invoke first = chain.getFirst();
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
        code(m, i.caller(), RATE_LIMITER, attributes, i.where());
      } else if (simpleName(root).equals("RedisCacheManager")
          && root.startsWith(CACHE_MANAGER)
          && !first.method().startsWith("get")) {
        attributes.put("operation", "other");
        code(m, i.caller(), CACHE, attributes, i.where());
      } else if (root.equals(LISTENER_CONTAINER)
          && chain.stream().anyMatch(c -> c.method().equals("addMessageListener"))) {
        attributes.put("operation", "read");
        code(m, i.caller(), PUBSUB, attributes, i.where());
      }
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
      Set<int[]> managed = new LinkedHashSet<>();
      Matcher management = MANAGEMENT.matcher(pom);
      while (management.find()) {
        managed.add(new int[] {management.start(), management.end()});
      }
      Matcher dependency = DEPENDENCY.matcher(pom);
      while (dependency.find()) {
        int at = dependency.start();
        if (managed.stream().anyMatch(r -> at >= r[0] && at < r[1])) {
          continue;
        }
        String body = dependency.group(1);
        Matcher artifact = ARTIFACT.matcher(body);
        if (!artifact.find() || body.contains("<scope>test</scope>")) {
          continue;
        }
        String id = artifact.group(1);
        String kind =
            SESSION_ARTIFACTS.contains(id)
                ? SESSION
                : CLIENT_ARTIFACTS.contains(id) ? CLIENT : null;
        if (kind == null) {
          continue;
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
        if (relaxed.contains("redisratelimiter") && !limiter) {
          kind = RATE_LIMITER;
          limiter = true;
        } else if (relaxed.equals("spring.cache.type") && v.value().equalsIgnoreCase("redis")) {
          kind = CACHE;
        } else if (!session
            && ((relaxed.equals("spring.session.storetype") && v.value().equalsIgnoreCase("redis"))
                || relaxed.startsWith("spring.session.redis."))) {
          kind = SESSION;
          session = true;
        }
        if (kind == null) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("source", "config");
        attributes.put("configKey", key);
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
    // Compose

    /** The Redis images the repository's compose files run, as facts of the repository. */
    void compose(RepoBuild repo) {
      EntityId module = new EntityId("repository", repo.repo().name());
      for (String path : GitFiles.list(repo, "")) {
        if (!COMPOSE_FILE.matcher(path).matches()) {
          continue;
        }
        byte[] bytes = GitFiles.read(repo, path, MAX_BYTES);
        if (bytes == null) {
          continue;
        }
        String hash = sha256(bytes);
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int serviceIndent = Integer.MAX_VALUE;
        for (String line : lines) {
          Matcher key = KEY_LINE.matcher(line);
          if (key.matches() && !key.group(1).isEmpty()) {
            serviceIndent = Math.min(serviceIndent, key.group(1).length());
          }
        }
        for (int n = 0; n < lines.length; n++) {
          Matcher image = IMAGE_LINE.matcher(lines[n]);
          if (!image.find()) {
            continue;
          }
          String reference = resolveVariables(image.group(2));
          Image parsed = Image.of(reference);
          if (parsed == null) {
            continue;
          }
          String service = null;
          for (int back = n - 1; back >= 0 && service == null; back--) {
            Matcher key = KEY_LINE.matcher(lines[back]);
            if (key.matches() && key.group(1).length() == serviceIndent) {
              service = key.group(2);
            }
          }
          ObjectNode attributes = FactJson.newObject();
          attributes.put("technology", parsed.technology());
          if (service != null) {
            attributes.put("service", service);
          }
          facts.putIfAbsent(
              "image|" + module + "|" + path + "|" + n,
              Fact.create(
                  module,
                  REDIS_IMAGE,
                  FactObject.string(reference),
                  attributes,
                  ORIGIN,
                  new Evidence(
                      repo.repo().name(),
                      repo.commit(),
                      path,
                      new Evidence.LineRange(n + 1, n + 1),
                      hash),
                  1.0,
                  validity));
        }
      }
    }

    private static String resolveVariables(String reference) {
      return VARIABLE.matcher(reference).replaceAll("$1");
    }

    // -------------------------------------------------------------------------------------------

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

  /**
   * A Redis (or compatible) image reference.
   *
   * @param technology the product with its version, such as {@code Redis 8.4.0}
   */
  record Image(String technology) {

    private static final Pattern VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+){0,2})");

    /** The image a reference names if it is Redis, Valkey or Redis Stack, else null. */
    static Image of(String reference) {
      String ref = reference;
      int digest = ref.indexOf('@');
      if (digest >= 0) {
        ref = ref.substring(0, digest);
      }
      int slash = ref.lastIndexOf('/');
      String last = slash < 0 ? ref : ref.substring(slash + 1);
      int colon = last.indexOf(':');
      String name = (colon < 0 ? last : last.substring(0, colon)).toLowerCase(Locale.ROOT);
      String tag = colon < 0 ? "" : last.substring(colon + 1);
      String product =
          switch (name) {
            case "redis", "redis-server" -> "Redis";
            case "valkey" -> "Valkey";
            case "redis-stack", "redis-stack-server" -> "Redis Stack";
            default -> null;
          };
      if (product == null) {
        return null;
      }
      Matcher version = VERSION.matcher(tag);
      return new Image(version.find() ? product + " " + version.group(1) : product);
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
