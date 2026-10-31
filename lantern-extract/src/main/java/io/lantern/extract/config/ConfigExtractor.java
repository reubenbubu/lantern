package io.lantern.extract.config;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the {@code application*} and {@code bootstrap*} config of each Maven module into facts: the
 * module's config values as Spring Boot sees them by default, and those that differ with each
 * profile the config names active, with placeholders resolved. See {@code
 * docs/adr/0032-config-extractor.md}.
 *
 * <p>Other extractors use {@link #read} to resolve placeholders against a module's config.
 */
public final class ConfigExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "config";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "7";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "config-extractor";

  /** Attribute on a {@code config-value} fact: where the value comes from, when not a file. */
  public static final String SOURCE = "source";

  /** The {@link #SOURCE} of a default a library's {@code EnvironmentPostProcessor} adds. */
  public static final String ENVIRONMENT_POST_PROCESSOR = "environment-post-processor";

  /** The {@link #SOURCE} of a value a Spring Cloud Config repository serves. */
  public static final String CONFIG_REPOSITORY = "config-repository";

  /**
   * Attribute on a {@code config-value} fact from a config repository: the application its file is
   * named after, {@code application} for the files every application gets.
   */
  public static final String APPLICATION = "application";

  /** The prefix of a value Spring Cloud Config Server decrypts before serving it. */
  private static final String CIPHER = "{cipher}";

  /** The prefix of the keys that set up a Spring Cloud Config Server. */
  private static final String NATIVE_LOCATIONS =
      "spring\\.cloud\\.config\\.server\\.native\\.search-locations";

  private static final String CONFIG_SERVER_KEYS = "spring.cloud.config.server.";

  /** Attribute: a config value of the subject module. */
  public static final String CONFIG_VALUE = "config-value";

  /** The value recorded in place of a secret. */
  public static final String MASK = "******";

  /** Facts are read from the files exactly. */
  static final double CONFIDENCE = 1.0;

  private static final String RESOURCE = "/vocabulary/config-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /**
   * The last part of a key that holds a secret, without hyphens and underscores: ending in a
   * password, secret, token or a kind of key that is secret, or naming credentials. Keys that only
   * end in "key" are not secrets as a rule (a routing key, a cache key).
   */
  private static final Pattern SECRET =
      Pattern.compile(
          ".*(password|passwd|secret|credential).*"
              + "|.*(pwd|token|apikey|accesskey|privatekey|signingkey|encryptionkey)s?");

  /** Whole keys that hold a secret though their last part does not say so. */
  private static final Set<String> SECRET_KEYS = Set.of("encrypt.key");

  /** A value that is only placeholders without defaults, which says where a secret comes from. */
  private static final Pattern ONLY_PLACEHOLDERS = Pattern.compile("(\\$\\{[^:{}]+})+");

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = ConfigExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * The config of a repository's Maven modules at its commit, for resolving placeholders.
   *
   * @param repo the repository at its commit, with its build if there is one
   * @return the modules that have config, ordered by directory
   * @throws IllegalStateException if git fails or times out
   */
  public static List<ModuleConfig> read(RepoBuild repo) {
    Objects.requireNonNull(repo, "repo");
    return ConfigReader.read(repo);
  }

  /**
   * Resolves the {@code ${...}} placeholders in a text, such as an annotation's value, for code
   * that no module's config applies to: a placeholder with a default takes it, and any other is
   * kept as written.
   *
   * @param text the text
   */
  public static Resolution resolveWithoutConfig(String text) {
    Objects.requireNonNull(text, "text");
    return Placeholders.resolve(text, key -> null);
  }

  /**
   * Extracts the config facts of a snapshot's repositories.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws IllegalStateException if git fails or times out
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(repos, snapshot, Set.of());
  }

  /**
   * The config keys that the code uses as a JWT's HMAC secret or signing key: the keys of the
   * {@code jwt-secret-key} and {@code jwt-key-pair} facts (ADR 0085), for {@link #extract(List,
   * long, Set)}.
   *
   * @param identityFacts the identity extractor's facts; others are ignored
   */
  public static Set<String> jwtSecretKeys(List<Fact> identityFacts) {
    Set<String> keys = new java.util.TreeSet<>();
    for (Fact fact : identityFacts) {
      if ((fact.predicate().equals("jwt-secret-key") || fact.predicate().equals("jwt-key-pair"))
          && fact.attributes().has("keys")) {
        fact.attributes().get("keys").forEach(k -> keys.add(k.stringValue()));
      }
    }
    return keys;
  }

  /**
   * Extracts the config facts of a snapshot's repositories, masking the values of keys the code
   * uses as a JWT secret whatever they are called (ADR 0032, amendment for #335).
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @param secretKeys config keys known to hold a secret, such as {@link #jwtSecretKeys}
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws IllegalStateException if git fails or times out
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot, Set<String> secretKeys) {
    Objects.requireNonNull(repos, "repos");
    Set<String> known = normalizedKeys(secretKeys);
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> facts = new LinkedHashMap<>();
    Map<RepoBuild, List<ModuleConfig>> modules = new LinkedHashMap<>();
    for (RepoBuild repo : repos) {
      modules.put(repo, read(repo));
    }
    Set<String> names = applicationNames(modules.values());
    boolean configServer = hasConfigServer(modules.values());
    for (RepoBuild repo : repos) {
      List<ModuleConfig> configs = new ArrayList<>(modules.get(repo));
      if (configServer) {
        configs.addAll(ConfigReader.readRepository(repo, names));
        for (String folder : nativeFolders(modules.get(repo))) {
          configs.addAll(ConfigReader.readFolder(repo, folder, names));
        }
      }
      for (ModuleConfig config : configs) {
        SortedMap<String, ConfigValue> defaults = config.values(null);
        for (ConfigValue value : defaults.values()) {
          add(facts, config, value, null, validity, known, false);
        }
        for (String profile : config.profiles()) {
          for (ConfigValue value : config.values(profile).values()) {
            ConfigValue byDefault = defaults.get(value.key());
            if (byDefault == null
                || !byDefault.value().equals(value.value())
                || !byDefault.unresolved().equals(value.unresolved())) {
              add(facts, config, value, profile, validity, known, false);
            }
          }
        }
      }
      for (ModuleConfig config : ConfigReader.readDefaults(repo)) {
        for (ConfigValue value : config.values(null).values()) {
          add(facts, config, value, null, validity, known, true);
        }
      }
    }
    return facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  /**
   * The names the modules give their applications, in any profile: {@code spring.application.name}
   * and each of {@code spring.cloud.config.name}'s, those that resolve.
   */
  private static Set<String> applicationNames(Collection<List<ModuleConfig>> modules) {
    Set<String> names = new TreeSet<>();
    for (List<ModuleConfig> configs : modules) {
      for (ModuleConfig config : configs) {
        List<String> profiles = new ArrayList<>();
        profiles.add(null);
        profiles.addAll(config.profiles());
        for (String profile : profiles) {
          SortedMap<String, ConfigValue> values = config.values(profile);
          for (String key : List.of("spring.application.name", "spring.cloud.config.name")) {
            ConfigValue value = values.get(key);
            if (value != null && value.unresolved().isEmpty()) {
              for (String name : value.value().split(",")) {
                if (!name.isBlank()) {
                  names.add(name.strip());
                }
              }
            }
          }
        }
      }
    }
    return names;
  }

  /** Whether a module sets up a Spring Cloud Config Server, in any profile. */
  /**
   * The folders of a repository that its config servers' native backends serve from: each relative
   * {@code file:} location of {@code spring.cloud.config.server.native.search-locations}, in any
   * profile, taken from the module's folder (where the server is started), as folders of the
   * repository ending in {@code /}. Absolute, {@code classpath:} and {@code {application}}
   * locations are left out.
   */
  static Set<String> nativeFolders(List<ModuleConfig> configs) {
    Set<String> folders = new java.util.TreeSet<>();
    for (ModuleConfig config : configs) {
      List<String> profiles = new ArrayList<>();
      profiles.add(null);
      profiles.addAll(config.profiles());
      for (String profile : profiles) {
        for (ConfigValue value : config.values(profile).values()) {
          if (!value.key().matches(NATIVE_LOCATIONS + "(\\[\\d+])?")) {
            continue;
          }
          for (String location : value.value().split(",")) {
            String l = location.strip();
            if (!l.startsWith("file:") || l.contains("{") || l.contains("$")) {
              continue;
            }
            String path = l.substring("file:".length()).replaceFirst("^\\./", "");
            if (path.isEmpty() || path.startsWith("/") || path.contains("..")) {
              continue;
            }
            String base = config.directory().isEmpty() ? "" : config.directory() + "/";
            folders.add(base + (path.endsWith("/") ? path : path + "/"));
          }
        }
      }
    }
    return folders;
  }

  private static boolean hasConfigServer(Collection<List<ModuleConfig>> modules) {
    for (List<ModuleConfig> configs : modules) {
      for (ModuleConfig config : configs) {
        List<String> profiles = new ArrayList<>();
        profiles.add(null);
        profiles.addAll(config.profiles());
        for (String profile : profiles) {
          if (config.values(profile).keySet().stream()
              .anyMatch(k -> k.startsWith(CONFIG_SERVER_KEYS))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static void add(
      Map<String, Fact> facts,
      ModuleConfig config,
      ConfigValue value,
      String profile,
      Validity validity,
      Set<String> known,
      boolean fromCode) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("key", value.key());
    if (fromCode) {
      attributes.put(SOURCE, ENVIRONMENT_POST_PROCESSOR);
    }
    if (config.application() != null) {
      attributes.put(SOURCE, CONFIG_REPOSITORY);
      attributes.put(APPLICATION, config.application());
    }
    if (profile != null) {
      attributes.put("profile", profile);
    }
    // Secret options are removed before placeholders are resolved, so a substituted secret goes
    // whole, whatever it holds; the resolved text then loses what is left, such as URLs' user info.
    Predicate<String> nothingResolves = value.unresolved()::contains;
    String written = UrlCredentials.withoutCredentials(value.written(), nothingResolves);
    String object =
        written.equals(value.written())
            ? UrlCredentials.withoutCredentials(value.value(), nothingResolves)
            : UrlCredentials.withoutCredentials(
                config.resolve(profile, written).value(), nothingResolves);
    if (exposesSecret(value, known)
        || mayHoldCredential(object)
        || mayHoldCredential(written)
        || written.contains(CIPHER)
        || object.contains(CIPHER)) {
      object = MASK;
      attributes.put("masked", true);
    } else {
      if (!object.equals(value.value()) || !written.equals(value.written())) {
        // Credentials written inside a URL were removed.
        attributes.put("masked", true);
      }
      if (!written.equals(object)) {
        attributes.put("written", written);
      }
      if (!value.unresolved().isEmpty()) {
        ArrayNode unresolved = attributes.putArray("unresolved");
        value.unresolved().forEach(unresolved::add);
      }
    }
    ConfigValue.Where where = value.where();
    Evidence evidence =
        new Evidence(
            config.repo().repo().name(),
            config.repo().commit(),
            where.path(),
            where.line() > 0 ? new Evidence.LineRange(where.line(), where.line()) : null,
            where.contentHash());
    Fact fact =
        Fact.create(
            config.module(),
            CONFIG_VALUE,
            FactObject.string(object),
            attributes,
            ORIGIN,
            evidence,
            CONFIDENCE,
            validity);
    facts.putIfAbsent(fact.id(), fact);
  }

  /**
   * Whether recording the value would expose a secret: a value that a secret was substituted into,
   * or a secret's value unless it is only placeholders nothing resolves (environment variables),
   * which say where the secret comes from.
   */
  private static boolean exposesSecret(ConfigValue value, Set<String> known) {
    if (value.used().stream().anyMatch(k -> isSecret(k, known))) {
      return true;
    }
    return isSecret(value.key(), known)
        && !(ONLY_PLACEHOLDERS.matcher(value.written()).matches() && value.used().isEmpty());
  }

  /** Whether a URL in a text still looks like it holds a credential in a form not understood. */
  private static boolean mayHoldCredential(String text) {
    return UrlCredentials.containsUrl(text) && UrlCredentials.mayHoldCredential(text);
  }

  /**
   * Whether a config key names a secret, judged by its last part without list indexes, hyphens and
   * underscores: one naming a password, secret or credentials, or ending in a token or a kind of
   * key that is secret, singular or plural; or a key such as {@code encrypt.key} that holds one.
   */
  public static boolean isSecret(String key) {
    String plain = key.replaceAll("\\[\\d+]", "").toLowerCase(Locale.ROOT);
    if (SECRET_KEYS.contains(plain)) {
      return true;
    }
    String last = plain.substring(plain.lastIndexOf('.') + 1);
    return SECRET.matcher(last.replace("-", "").replace("_", "")).matches();
  }

  /**
   * Known secret keys as {@link #isSecret(String, Set)} compares them.
   *
   * @param secretKeys config keys known to hold a secret, such as {@link #jwtSecretKeys}
   */
  public static Set<String> normalizedKeys(Set<String> secretKeys) {
    Set<String> known = new java.util.HashSet<>();
    secretKeys.forEach(k -> known.add(normalized(k)));
    return known;
  }

  /**
   * Whether a key names a secret by its name ({@link #isSecret(String)}) or is one of the keys
   * known to hold a secret.
   *
   * @param known the keys, from {@link #normalizedKeys}
   */
  public static boolean isSecret(String key, Set<String> known) {
    return isSecret(key) || known.contains(normalized(key));
  }

  /** A key as compared with the code's: no list indexes, hyphens or underscores, lower case. */
  private static String normalized(String key) {
    return key.replaceAll("\\[\\d+]", "")
        .replace("-", "")
        .replace("_", "")
        .toLowerCase(Locale.ROOT);
  }
}
