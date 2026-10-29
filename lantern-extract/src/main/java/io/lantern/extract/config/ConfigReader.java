package io.lantern.extract.config;

import io.lantern.core.fact.EntityId;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ModuleConfig.Document;
import io.lantern.extract.config.ModuleConfig.Written;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.sandbox.BuildModule;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * Finds and reads the {@code application*} and {@code bootstrap*} files of a repository's Maven
 * modules at the repository's commit, with Spring Boot's own loaders.
 */
final class ConfigReader {

  /** The profile Spring Boot activates when none is set. */
  static final String DEFAULT_PROFILE = "default";

  /** The largest config file read; larger ones are left out. */
  static final long MAX_FILE_BYTES = 1024L * 1024;

  private static final String RESOURCES = "src/main/resources/";

  /** Locations under the resources, lowest precedence first, as Spring Boot searches them. */
  private static final List<String> LOCATIONS = List.of("", "config/");

  /** Config file bases, lowest precedence first: Spring Cloud's bootstrap, then application. */
  private static final List<String> BASES = List.of("bootstrap", "application");

  /** Extensions, lowest precedence first: Spring Boot prefers properties to YAML. */
  private static final List<String> EXTENSIONS = List.of("yaml", "yml", "properties");

  private static final Pattern FILE =
      Pattern.compile("(bootstrap|application)(?:-([^/]+))?\\.(yaml|yml|properties)");

  private static final String ON_PROFILE = "spring.config.activate.on-profile";
  private static final String ON_CLOUD_PLATFORM = "spring.config.activate.on-cloud-platform";

  /** The key Spring Boot before 2.4 limited a YAML document to profiles with. */
  private static final String LEGACY_PROFILES = "spring.profiles";

  private ConfigReader() {}

  /** The config of each module of the repository that has any, ordered by directory. */
  static List<ModuleConfig> read(RepoBuild repo) {
    Map<String, EntityId> modules = moduleIds(repo);
    List<String> paths = GitFiles.list(repo, "");
    List<ModuleConfig> result = new ArrayList<>();
    for (Map.Entry<String, EntityId> module : modules.entrySet()) {
      String resources = (module.getKey().isEmpty() ? "" : module.getKey() + "/") + RESOURCES;
      Map<String, String> files = new HashMap<>();
      for (String path : paths) {
        if (path.startsWith(resources)) {
          files.put(path.substring(resources.length()), path);
        }
      }
      List<Document> documents = new ArrayList<>();
      for (String base : BASES) {
        documents.addAll(documents(repo, files, base, false));
        documents.addAll(documents(repo, files, base, true));
      }
      if (!documents.isEmpty()) {
        result.add(new ModuleConfig(repo, module.getKey(), module.getValue(), documents));
      }
    }
    return result;
  }

  /**
   * The module IDs by directory, as the Java extractor finds them: from the build when it has the
   * module, else from the POM as written, so they match the Maven extractor's.
   */
  private static Map<String, EntityId> moduleIds(RepoBuild repo) {
    Map<String, EntityId> modules = new LinkedHashMap<>(MavenExtractor.moduleDirectories(repo));
    if (repo.build() != null) {
      for (BuildModule m : repo.build().modules()) {
        if (modules.containsKey(m.path())) {
          try {
            modules.put(
                m.path(), new EntityId(BuiltInTypes.MODULE, m.groupId() + ":" + m.artifactId()));
          } catch (IllegalArgumentException e) {
            // Keep the ID from the POM as written.
          }
        }
      }
    }
    return modules;
  }

  /** The documents of one base's files without or with a profile, in precedence order. */
  private static List<Document> documents(
      RepoBuild repo, Map<String, String> files, String base, boolean withProfile) {
    List<Document> documents = new ArrayList<>();
    for (String location : LOCATIONS) {
      // Profile files are ordered by profile, so that the order does not depend on the listing.
      Map<String, Map<String, String>> byExtension = new HashMap<>();
      for (Map.Entry<String, String> file : files.entrySet()) {
        String name = file.getKey();
        if (!name.startsWith(location) || name.indexOf('/', location.length()) >= 0) {
          continue;
        }
        Matcher m = FILE.matcher(name.substring(location.length()));
        if (!m.matches() || !m.group(1).equals(base) || (m.group(2) != null) != withProfile) {
          continue;
        }
        String profile = m.group(2) == null ? "" : m.group(2);
        byExtension
            .computeIfAbsent(m.group(3), k -> new java.util.TreeMap<>())
            .put(profile, file.getValue());
      }
      for (String extension : EXTENSIONS) {
        for (Map.Entry<String, String> file :
            byExtension.getOrDefault(extension, Map.of()).entrySet()) {
          String profile = withProfile ? file.getKey() : null;
          documents.addAll(read(repo, file.getValue(), extension, profile));
        }
      }
    }
    return documents;
  }

  /** The documents of one file, or none if it cannot be read. */
  private static List<Document> read(
      RepoBuild repo, String path, String extension, String profileFile) {
    byte[] content = GitFiles.read(repo, path, MAX_FILE_BYTES);
    if (content == null) {
      return List.of();
    }
    String hash = sha256(content);
    PropertySourceLoader loader =
        extension.equals("properties")
            ? new PropertiesPropertySourceLoader()
            : new YamlPropertySourceLoader();
    List<PropertySource<?>> sources;
    try {
      sources = loader.load(path, new ByteArrayResource(content, path));
    } catch (IOException | RuntimeException e) {
      // Not valid YAML or properties: Spring Boot would not start with it either.
      return List.of();
    }
    List<Document> documents = new ArrayList<>();
    for (PropertySource<?> source : sources) {
      if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
        continue;
      }
      Map<String, Written> values = new LinkedHashMap<>();
      List<String> activation = new ArrayList<>();
      boolean cloudPlatform = false;
      for (String key : enumerable.getPropertyNames()) {
        Object value = enumerable.getProperty(key);
        int line = 0;
        if (value instanceof OriginTrackedValue tracked) {
          value = tracked.getValue();
          line = line(tracked.getOrigin());
        } else if (source instanceof OriginLookup<?> lookup) {
          line = line(origin(lookup, key));
        }
        String text = value == null ? "" : value.toString();
        if (isKey(key, ON_PROFILE) || isKey(key, LEGACY_PROFILES)) {
          Arrays.stream(text.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .forEach(activation::add);
        } else if (isKey(key, ON_CLOUD_PLATFORM)) {
          cloudPlatform = true;
        } else {
          values.put(key, new Written(text, new ConfigValue.Where(path, hash, line)));
        }
      }
      documents.add(
          new Document(
              profileFile,
              cloudPlatform || !valid(activation) ? null : List.copyOf(activation),
              values));
    }
    return documents;
  }

  /** Whether the key is the given one, or an element of it when written as a YAML list. */
  private static boolean isKey(String key, String name) {
    return key.equals(name) || (key.startsWith(name + "[") && key.endsWith("]"));
  }

  private static boolean valid(List<String> expressions) {
    if (expressions.isEmpty()) {
      return true;
    }
    try {
      Profiles.of(expressions.toArray(String[]::new));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  private static Origin origin(OriginLookup<?> lookup, String key) {
    return ((OriginLookup<String>) lookup).getOrigin(key);
  }

  /** The 1-based line an origin points at, or 0. */
  private static int line(Origin origin) {
    Set<Origin> seen = new HashSet<>();
    for (Origin o = origin; o != null && seen.add(o); o = o.getParent()) {
      if (o instanceof TextResourceOrigin text && text.getLocation() != null) {
        return text.getLocation().getLine() + 1;
      }
    }
    return 0;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
