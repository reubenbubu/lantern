package io.lantern.extract.config;

import io.lantern.core.fact.EntityId;
import io.lantern.extract.RepoBuild;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.core.env.Profiles;

/**
 * The {@code application*} and {@code bootstrap*} config of one Maven module, as Spring Boot reads
 * it with one profile active. See {@code docs/adr/0032-config-extractor.md}.
 *
 * <p>Without a profile, the default view is what Spring Boot sees when no profile is set: the
 * {@code default} profile is active. With a profile, that profile is active instead. Values are
 * read in Spring Boot's order of precedence, so a later one overrides an earlier one: bootstrap
 * before application config; files without a profile before profile files; {@code
 * src/main/resources} before {@code src/main/resources/config}; YAML before properties; and the
 * documents of one file in the order written.
 */
public final class ModuleConfig {

  /**
   * One document of a config file, in precedence order.
   *
   * @param profileFile the profile in the file's name ({@code application-local.yml}), or null
   * @param activation the profile expressions the document is limited to ({@code
   *     spring.config.activate.on-profile}), empty if it is not limited, or null if it never
   *     applies here (it is limited to a cloud platform, or its expression is not valid)
   * @param values the document's values by key, in the order written
   */
  record Document(String profileFile, List<String> activation, Map<String, Written> values) {}

  /** A value as written, and where. */
  record Written(String value, ConfigValue.Where where) {}

  private final RepoBuild repo;
  private final String directory;
  private final EntityId module;
  private final String application;
  private final List<Document> documents;
  private final SortedSet<String> profiles;
  private final Map<String, SortedMap<String, ConfigValue>> views = new LinkedHashMap<>();

  ModuleConfig(RepoBuild repo, String directory, EntityId module, List<Document> documents) {
    this(repo, directory, module, null, documents);
  }

  /**
   * The config a config repository serves one application.
   *
   * @param application the application the files are named after, {@code application} for the files
   *     every application gets, or null for a module's own config
   */
  ModuleConfig(
      RepoBuild repo,
      String directory,
      EntityId module,
      String application,
      List<Document> documents) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.module = Objects.requireNonNull(module, "module");
    this.application = application;
    this.documents = List.copyOf(documents);
    SortedSet<String> named = new TreeSet<>();
    for (Document d : documents) {
      if (d.profileFile() != null) {
        named.add(d.profileFile());
      }
      if (d.activation() != null) {
        for (String expression : d.activation()) {
          for (String token : expression.split("[^A-Za-z0-9_.\\-]+")) {
            if (!token.isEmpty()) {
              named.add(token);
            }
          }
        }
      }
    }
    named.remove(ConfigReader.DEFAULT_PROFILE);
    this.profiles = Collections.unmodifiableSortedSet(named);
  }

  /** The repository the module is in. */
  public RepoBuild repo() {
    return repo;
  }

  /** The module's directory relative to the top of the repository, {@code ""} for the top. */
  public String directory() {
    return directory;
  }

  /**
   * The module's entity ID, {@code module:<groupId>:<artifactId>}, or for a config repository's
   * files, the repository's, {@code repo:<name>}.
   */
  public EntityId module() {
    return module;
  }

  /**
   * The application a config repository's files serve ({@code application} for those every
   * application gets), or null for a module's own config.
   */
  public String application() {
    return application;
  }

  /**
   * The profiles the config names, in file names and document activations, except {@code default},
   * ordered.
   */
  public SortedSet<String> profiles() {
    return profiles;
  }

  /**
   * The module's config with a profile active.
   *
   * <p>Values are not masked: a secret's value is returned as written or resolved. Callers must
   * never copy a value into a fact if its key, or one of the keys it {@linkplain ConfigValue#used()
   * used}, names a secret ({@link ConfigExtractor#isSecret}).
   *
   * @param profile the active profile, or null for the default view
   * @return the values by key
   */
  public synchronized SortedMap<String, ConfigValue> values(String profile) {
    return views.computeIfAbsent(activeProfile(profile), this::view);
  }

  /**
   * Resolves the {@code ${...}} placeholders in a text, such as an annotation's value, against the
   * module's config with a profile active.
   *
   * <p>The result is not masked: a placeholder naming a secret resolves to the secret. Callers must
   * never copy a value into a fact if one of the keys it {@linkplain Resolution#used() used} names
   * a secret ({@link ConfigExtractor#isSecret}).
   *
   * @param profile the active profile, or null for the default view
   * @param text the text
   */
  public Resolution resolve(String profile, String text) {
    Objects.requireNonNull(text, "text");
    Map<String, Written> written = written(activeProfile(profile));
    return Placeholders.resolve(text, key -> value(written, key));
  }

  private SortedMap<String, ConfigValue> view(String active) {
    Map<String, Written> written = written(active);
    SortedMap<String, ConfigValue> values = new TreeMap<>();
    for (Map.Entry<String, Written> e : written.entrySet()) {
      Written w = e.getValue();
      Resolution r = Placeholders.resolve(w.value(), key -> value(written, key));
      values.put(
          e.getKey(),
          new ConfigValue(e.getKey(), r.value(), w.value(), r.unresolved(), r.used(), w.where()));
    }
    return Collections.unmodifiableSortedMap(values);
  }

  private static String activeProfile(String profile) {
    return profile == null ? ConfigReader.DEFAULT_PROFILE : profile;
  }

  private static String value(Map<String, Written> written, String key) {
    Written w = written.get(key);
    return w == null ? null : w.value();
  }

  /**
   * The values as written that hold with one profile active, the later overriding the earlier. As
   * in Spring, a list a later document sets ({@code routes[0].id}, ...) replaces the earlier
   * documents' list whole: their items are not merged with its items index by index.
   */
  private Map<String, Written> written(String active) {
    Set<String> activeProfiles = Set.of(active);
    Map<String, Written> values = new LinkedHashMap<>();
    for (Document d : documents) {
      if (applies(d, activeProfiles)) {
        Set<String> lists = new java.util.HashSet<>();
        for (String key : d.values().keySet()) {
          String list = listOf(key);
          if (list != null) {
            lists.add(list);
          }
        }
        if (!lists.isEmpty()) {
          values.keySet().removeIf(key -> lists.contains(key) || lists.contains(listOf(key)));
        }
        values.putAll(d.values());
      }
    }
    return values;
  }

  /** The list a key is an item of ({@code a.b} for {@code a.b[0].c}), or null. */
  private static String listOf(String key) {
    int bracket = key.indexOf('[');
    return bracket > 0 ? key.substring(0, bracket) : null;
  }

  private static boolean applies(Document d, Set<String> active) {
    if (d.activation() == null) {
      return false;
    }
    if (d.profileFile() != null && !active.contains(d.profileFile())) {
      return false;
    }
    if (d.activation().isEmpty()) {
      return true;
    }
    try {
      return Profiles.of(d.activation().toArray(String[]::new)).matches(active::contains);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
