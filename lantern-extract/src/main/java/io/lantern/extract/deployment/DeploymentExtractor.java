package io.lantern.extract.deployment;

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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the deployment descriptors of every repository of a snapshot, those without code or a build
 * too (a deployment repository holds only these): Dockerfiles, compose files, kustomize overlays,
 * Helm charts and plain Kubernetes manifests. See {@code docs/adr/0082-deployment-descriptors.md}.
 *
 * <p>Each container a descriptor runs in an environment is a {@code deployment-container} fact on
 * the repository ({@code repo:<name>}), with its image and the product and version the image names,
 * the names others reach it by, its environment variables (secret ones masked), published ports,
 * what it depends on and the databases it creates. Each Dockerfile is a {@code dockerfile} fact
 * with its base image and exposed ports. The linker ties containers to the deployable units they
 * run and to the data stores and brokers the units' config leaves open. Files are read from git at
 * the commit; nothing is run or rendered by a tool.
 */
public final class DeploymentExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "deployment";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "deployment-extractor";

  /** Attribute: a container a deployment descriptor of the subject repository runs. */
  public static final String DEPLOYMENT_CONTAINER = "deployment-container";

  /**
   * Attribute: a route an Ingress or Gateway API HTTPRoute of the subject repository sends into a
   * Service; the value is its host (or {@code *}) and path.
   */
  public static final String DEPLOYMENT_ROUTE = "deployment-route";

  /** Attribute: a Dockerfile of the subject repository; the value is its path. */
  public static final String DOCKERFILE = "dockerfile";

  /** The platforms, as the {@code platform} attribute of a container. */
  public static final String COMPOSE = "compose";

  public static final String KUBERNETES = "kubernetes";
  public static final String HELM = "helm";

  /** The variables the JVM reads extra options from, such as {@code -Dspring.profiles.active=x}. */
  private static final List<String> JAVA_OPTIONS =
      List.of("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS");

  /** A system property ({@code -Dkey=value}) or a Spring argument ({@code --key=value}). */
  private static final Pattern COMMAND_PROPERTY =
      Pattern.compile("^(?:-D|--)([A-Za-z][\\w.\\-\\[\\]]*)=(.*)$");

  private static final Pattern PATH_LINE = Pattern.compile("^[\\s-]*(?:path|value):(.*)$");

  private static final Pattern DEPENDENT_VARIABLE =
      Pattern.compile("\\$\\(([A-Za-z_][A-Za-z0-9_]*)\\)");

  /** The environment variables that name the database a database image creates on start. */
  public static final Set<String> DATABASE_VARIABLES =
      Set.of("POSTGRES_DB", "MYSQL_DATABASE", "MARIADB_DATABASE", "MONGO_INITDB_DATABASE");

  private static final String RESOURCE = "/vocabulary/deployment-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final long MAX_BYTES = 2L * 1024 * 1024;

  private static final Pattern COMPOSE_FILE =
      Pattern.compile("(?i)(.*/)?(docker-)?compose([._-][^/]*)?\\.ya?ml");
  private static final Pattern DOCKERFILE_NAME =
      Pattern.compile("(?i)(.*/)?(Dockerfile([._-][^/]*)?|[^/]+\\.dockerfile)");
  private static final Pattern KUSTOMIZATION = Pattern.compile("(.*/)?kustomization\\.ya?ml");
  private static final Pattern CHART = Pattern.compile("(.*/)?Chart\\.ya?ml");
  private static final Pattern YAML = Pattern.compile("(?i).*\\.ya?ml");
  private static final Pattern SEPARATOR = Pattern.compile("^---(\\s.*)?$");

  /** Environment variable names whose values are secrets, so never recorded. */
  private static final Pattern SECRET_NAME =
      Pattern.compile(
          "(?i).*(pass(word|wd)?|pwd|secret|token|credential|private|api[_-]?key|access[_-]?key"
              + "|signing|_key$|^key$).*");

  private static final Pattern URL_USER = Pattern.compile("(?i)(://)[^/@\\s]+@");

  /**
   * A value that writes a secret out: {@code password=x}, {@code -Dapp.secret=x}, {@code key: x}.
   */
  private static final Pattern SECRET_VALUE =
      Pattern.compile(
          "(?i)(pass(word|wd)?|pwd|secret|token|credential|api[_-]?key|access[_-]?key"
              + "|private[_-]?key)"
              + "[\\w.-]*\\s*[=:]");

  private static final Pattern CREATE_DATABASE =
      Pattern.compile(
          "(?i)\\bCREATE\\s+DATABASE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\"`']?([A-Za-z_][A-Za-z0-9_$-]*)");
  private static final Pattern COMPOSE_VARIABLE =
      Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?:(:?[-?+])([^}]*))?}|\\$([A-Za-z_]\\w*)");

  /** The directories nothing deployed is read from. */
  private static final List<String> SKIPPED =
      List.of("src/test/", "node_modules/", "target/", ".git/", "testdata/", "test/fixtures/");

  /** Where an official database image runs its init scripts. */
  private static final String INIT_DIRECTORY = "/docker-entrypoint-initdb.d";

  /**
   * An environment as documents name it, such as {@code Docker Compose (local)}, from a container's
   * {@code platform} and {@code environment}.
   */
  public static String environmentLabel(String platform, String environment) {
    String name =
        switch (platform == null ? "" : platform) {
          case COMPOSE -> "Docker Compose";
          case KUBERNETES -> "Kubernetes";
          case HELM -> "Helm";
          default -> platform;
        };
    return environment == null ? name : name + " (" + environment + ")";
  }

  /** The vocabulary types this extractor's facts use besides the built-in and earlier ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = DeploymentExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * Extracts the deployment facts of the given repositories.
   *
   * @param repos the repositories of the snapshot, with or without a build
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object, evidence,
   *     attributes and ID
   * @throws IllegalStateException if git fails or times out while reading files
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    Objects.requireNonNull(repos, "repos");
    Map<String, Fact> facts = new LinkedHashMap<>();
    Validity validity = Validity.from(snapshot);
    for (RepoBuild repo : new LinkedHashSet<>(repos)) {
      new Run(repo, validity, facts).run();
    }
    return facts.values().stream()
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

  // ---------------------------------------------------------------------------------------------

  /** A file read from git: its text, lines and hash. */
  record File(String path, String text, List<String> lines, String hash) {}

  /** One YAML document of a file, with the line it starts on (1-based). */
  record Doc(File file, int start, int end, Map<String, Object> map) {}

  /** A container found in a descriptor, before it becomes a fact. */
  private static final class Found {
    String name;
    String kind;
    String image;
    String build;
    final Set<String> hosts = new TreeSet<>();
    final Set<String> aliases = new TreeSet<>();
    final Map<String, String> env = new TreeMap<>();
    final Set<String> masked = new TreeSet<>();
    final List<String> ports = new ArrayList<>();
    final Set<String> dependsOn = new TreeSet<>();
    final Set<String> profiles = new TreeSet<>();
    final Set<String> databases = new TreeSet<>();
    final Map<String, String> properties = new TreeMap<>();
    final List<String> command = new ArrayList<>();
    Integer replicas;
    File file;
    int line;
  }

  private static final class Run {

    private final RepoBuild repo;
    private final Validity validity;
    private final Map<String, Fact> facts;
    private final EntityId subject;
    private final Map<String, File> cache = new TreeMap<>();
    private List<String> paths;
    private Set<String> pathSet;

    Run(RepoBuild repo, Validity validity, Map<String, Fact> facts) {
      this.repo = repo;
      this.validity = validity;
      this.facts = facts;
      this.subject = new EntityId("repo", repo.repo().name());
    }

    void run() {
      paths =
          GitFiles.list(repo, "").stream()
              .filter(p -> SKIPPED.stream().noneMatch(s -> p.startsWith(s) || p.contains("/" + s)))
              .toList();
      pathSet = Set.copyOf(paths);
      Set<String> used = new TreeSet<>();
      for (String path : paths) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (DOCKERFILE_NAME.matcher(name).matches()) {
          dockerfile(path);
        } else if (COMPOSE_FILE.matcher(path).matches()) {
          compose(path);
          used.add(path);
        }
      }
      Set<String> charts = new TreeSet<>();
      for (String path : paths) {
        if (CHART.matcher(path).matches()) {
          String dir = parent(path);
          charts.add(dir);
          helm(dir, path);
        }
      }
      for (String path : paths) {
        if (charts.stream().anyMatch(c -> c.isEmpty() || path.startsWith(c + "/"))) {
          used.add(path);
        }
      }
      kustomize(used);
      manifests(used);
    }

    // -------------------------------------------------------------------------------------------
    // Dockerfiles

    private void dockerfile(String path) {
      File file = read(path);
      if (file == null) {
        return;
      }
      Map<String, String> args = new TreeMap<>();
      Map<String, String> stages = new TreeMap<>();
      String from = null;
      int fromLine = 0;
      Set<String> exposes = new LinkedHashSet<>();
      Found settings = new Found();
      List<String> entrypoint = List.of();
      List<String> cmd = List.of();
      List<String> lines = joined(file.lines());
      for (int n = 0; n < lines.size(); n++) {
        String line = lines.get(n).strip();
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ARG ") && from == null) {
          String[] parts = line.substring(4).strip().split("=", 2);
          if (parts.length == 2) {
            args.put(parts[0].strip(), unquote(parts[1].strip()));
          }
        } else if (upper.startsWith("FROM ")) {
          String[] words = line.substring(5).strip().split("\\s+");
          int i = 0;
          while (i < words.length && words[i].startsWith("--")) {
            i++;
          }
          if (i < words.length) {
            from = substitute(words[i], args);
            // FROM <stage> builds on an earlier stage: its base image is that stage's.
            from = stages.getOrDefault(from.toLowerCase(Locale.ROOT), from);
            if (i + 2 < words.length && words[i + 1].equalsIgnoreCase("AS")) {
              stages.put(words[i + 2].toLowerCase(Locale.ROOT), from);
            }
            fromLine = n + 1;
            exposes.clear();
            settings = new Found();
            entrypoint = List.of();
            cmd = List.of();
          }
        } else if (upper.startsWith("EXPOSE ")) {
          for (String port : line.substring(7).strip().split("\\s+")) {
            if (!port.isBlank()) {
              exposes.add(substitute(port, args));
            }
          }
        } else if (upper.startsWith("ENV ")) {
          for (Map.Entry<String, String> e : dockerEnv(line.substring(4).strip()).entrySet()) {
            putEnv(settings, e.getKey(), substitute(e.getValue(), args));
          }
        } else if (upper.startsWith("ENTRYPOINT ")) {
          entrypoint = commandWords(line.substring(11).strip());
          cmd = List.of(); // an ENTRYPOINT resets the CMD inherited from the base image
        } else if (upper.startsWith("CMD ")) {
          cmd = commandWords(line.substring(4).strip());
        }
      }
      if (from == null) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("from", from);
      Image image = Image.of(from);
      if (image != null) {
        attributes.put("technology", image.technology());
      }
      if (!exposes.isEmpty()) {
        ArrayNode array = attributes.putArray("exposes");
        exposes.forEach(array::add);
      }
      settings.command.addAll(entrypoint);
      settings.command.addAll(cmd);
      settings(settings, attributes);
      add(DOCKERFILE, path, attributes, file, fromLine);
    }

    /**
     * A Dockerfile's instructions with continuation lines joined, each at the index of the line it
     * starts on; the lines it continues onto are empty.
     */
    private static List<String> joined(List<String> lines) {
      List<String> out = new ArrayList<>();
      int start = -1;
      StringBuilder current = new StringBuilder();
      for (String line : lines) {
        String text = line.stripTrailing();
        boolean continues = text.endsWith("\\");
        if (start < 0) {
          start = out.size();
        }
        current.append(continues ? text.substring(0, text.length() - 1) : text).append(' ');
        out.add("");
        if (!continues) {
          out.set(start, current.toString().strip());
          current.setLength(0);
          start = -1;
        }
      }
      if (start >= 0) {
        out.set(start, current.toString().strip());
      }
      return out;
    }

    private static String substitute(String text, Map<String, String> args) {
      Matcher m = COMPOSE_VARIABLE.matcher(text);
      StringBuilder b = new StringBuilder();
      while (m.find()) {
        String name = m.group(1) != null ? m.group(1) : m.group(4);
        String value = args.get(name);
        if (value == null && m.group(2) != null && m.group(2).endsWith("-")) {
          value = m.group(3);
        }
        m.appendReplacement(b, Matcher.quoteReplacement(value == null ? m.group() : value));
      }
      m.appendTail(b);
      return b.toString();
    }

    /**
     * The variables an {@code ENV} instruction sets: {@code KEY=value ...}, or the legacy {@code
     * KEY value} with the rest of the line as the value.
     */
    private static Map<String, String> dockerEnv(String text) {
      Map<String, String> env = new LinkedHashMap<>();
      List<String> words = shellWords(text);
      if (!words.isEmpty() && !words.getFirst().contains("=")) {
        String key = words.getFirst();
        String value = text.substring(text.indexOf(key) + key.length()).strip();
        env.put(key, unquote(value));
        return env;
      }
      for (String word : words) {
        int eq = word.indexOf('=');
        if (eq > 0) {
          env.put(word.substring(0, eq), word.substring(eq + 1));
        }
      }
      return env;
    }

    /** The words of an {@code ENTRYPOINT} or {@code CMD}: its JSON array, else its shell form. */
    private static List<String> commandWords(String text) {
      if (text.startsWith("[")) {
        Object parsed = Yaml.load(text);
        if (parsed instanceof List<?> list) {
          return list.stream().map(String::valueOf).toList();
        }
      }
      return shellWords(text);
    }

    /** A command line split into words the way a shell splits plain and quoted words. */
    static List<String> shellWords(String text) {
      List<String> words = new ArrayList<>();
      StringBuilder word = new StringBuilder();
      char quote = 0;
      boolean inWord = false;
      for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (quote != 0) {
          if (ch == quote) {
            quote = 0;
          } else {
            word.append(ch);
          }
        } else if (ch == '"' || ch == '\'') {
          quote = ch;
          inWord = true;
        } else if (Character.isWhitespace(ch)) {
          if (inWord) {
            words.add(word.toString());
            word.setLength(0);
            inWord = false;
          }
        } else {
          word.append(ch);
          inWord = true;
        }
      }
      if (inWord) {
        words.add(word.toString());
      }
      return words;
    }

    /**
     * Records what configures a container's Spring application: the properties its Java command
     * line and the JVM option variables set, and the Spring profiles its variables or properties
     * make active.
     */
    private void settings(Found c, ObjectNode attributes) {
      List<String> words = new ArrayList<>();
      // A shell's -c script, or a variable holding options, is a command line of its own.
      c.command.forEach(w -> words.addAll(w.contains(" ") ? shellWords(w) : List.of(w)));
      boolean runsJava =
          words.stream().anyMatch(w -> w.equals("java") || w.endsWith("/java") || w.equals("-jar"));
      // Arguments alone go to the image's own entrypoint, which for an application image (not a
      // known product's) is its java command.
      boolean appArguments =
          !words.isEmpty()
              && words.getFirst().startsWith("-")
              && (c.image == null || Image.of(c.image) == null);
      boolean readsCommand = runsJava || appArguments;
      // Lowest first, as Spring orders its sources: system properties from the JVM's option
      // variables and the command line, then SPRING_APPLICATION_JSON, then command-line arguments.
      // JAVA_OPTS is no JVM variable: it counts only where the command expands it.
      boolean expandsJavaOpts = c.command.stream().anyMatch(w -> w.contains("JAVA_OPTS"));
      for (String variable : JAVA_OPTIONS) {
        String options = c.env.get(variable);
        if (options != null && (!variable.equals("JAVA_OPTS") || expandsJavaOpts)) {
          shellWords(options).stream()
              .filter(w -> w.startsWith("-D"))
              .forEach(w -> commandProperty(c, w));
        }
      }
      if (readsCommand) {
        words.stream().filter(w -> w.startsWith("-D")).forEach(w -> commandProperty(c, w));
      }
      String json = c.env.get("SPRING_APPLICATION_JSON");
      if (json != null && Yaml.load(json) instanceof Map<?, ?> tree) {
        applicationJson(c, "", tree);
      }
      if (readsCommand) {
        words.stream().filter(w -> w.startsWith("--")).forEach(w -> commandProperty(c, w));
      }
      if (!c.env.isEmpty()) {
        ObjectNode env = attributes.putObject("env");
        c.env.forEach(env::put);
      }
      array(attributes, "masked", c.masked);
      if (!c.properties.isEmpty()) {
        ObjectNode properties = attributes.putObject("properties");
        c.properties.forEach(properties::put);
      }
      // A command-line property wins over the environment, as in Spring's property sources.
      String value = c.properties.get("spring.profiles.active");
      if (value == null) {
        value = c.env.get("SPRING_PROFILES_ACTIVE");
      }
      Set<String> profiles = new LinkedHashSet<>();
      if (value != null && !value.contains("${") && !value.contains("$(")) {
        for (String profile : value.split(",")) {
          if (!profile.isBlank()) {
            profiles.add(profile.strip());
          }
        }
      }
      array(attributes, "activeProfiles", profiles);
    }

    /**
     * Records the properties of a {@code SPRING_APPLICATION_JSON} tree, flattened to dotted keys.
     */
    private static void applicationJson(Found c, String prefix, Map<?, ?> tree) {
      tree.forEach(
          (k, v) -> {
            String key = prefix + k;
            if (v instanceof Map<?, ?> m) {
              applicationJson(c, key + ".", m);
            } else if (v != null && !(v instanceof List<?>)) {
              commandProperty(c, "--" + key + "=" + v);
            }
          });
    }

    /** Records a {@code -Dkey=value} or {@code --key=value} word as a property, masking secrets. */
    private static void commandProperty(Found c, String word) {
      Matcher m = COMMAND_PROPERTY.matcher(word);
      if (!m.matches()) {
        return;
      }
      String key = m.group(1);
      String value = m.group(2);
      if (SECRET_NAME.matcher(key).matches() || SECRET_VALUE.matcher(word).find()) {
        c.masked.add(key);
        c.properties.remove(key);
        return;
      }
      c.properties.put(key, URL_USER.matcher(value).replaceAll("$1"));
    }

    // -------------------------------------------------------------------------------------------
    // Compose

    private void compose(String path) {
      File file = read(path);
      if (file == null) {
        return;
      }
      Map<String, Object> doc = Yaml.map(Yaml.load(file.text()));
      Map<String, Object> services = doc == null ? null : Yaml.map(doc.get("services"));
      if (services == null) {
        return;
      }
      String dir = parent(path);
      Map<String, String> dotEnv = dotEnv(dir);
      String environment = composeEnvironment(path);
      for (Map.Entry<String, Object> e : services.entrySet()) {
        Map<String, Object> service = Yaml.map(e.getValue());
        if (service == null) {
          continue;
        }
        Found c = new Found();
        c.file = file;
        c.kind = "service";
        c.line = keyLine(file, e.getKey());
        c.hosts.add(e.getKey());
        String containerName = Yaml.string(service.get("container_name"));
        if (containerName != null) {
          containerName = resolve(containerName, dotEnv);
          c.hosts.add(containerName);
        }
        String hostname = Yaml.string(service.get("hostname"));
        if (hostname != null) {
          c.hosts.add(resolve(hostname, dotEnv));
        }
        c.name = containerName != null ? containerName : e.getKey();
        String image = Yaml.string(service.get("image"));
        if (image != null) {
          c.image = resolve(image, dotEnv);
        }
        Object build = service.get("build");
        if (build instanceof Map<?, ?> b) {
          String context = Yaml.string(b.get("context"));
          String dockerfile = Yaml.string(b.get("dockerfile"));
          c.build =
              resolve(
                  (context == null ? "." : context) + (dockerfile == null ? "" : " " + dockerfile),
                  dotEnv);
        } else if (Yaml.string(build) != null) {
          c.build = resolve(Yaml.string(build), dotEnv);
        }
        Object environmentNode = service.get("environment");
        Map<String, String> env = new TreeMap<>();
        // env_file comes first: environment overrides what it sets.
        Object envFiles = service.get("env_file");
        for (Object item :
            envFiles instanceof List<?> l ? l : envFiles == null ? List.of() : List.of(envFiles)) {
          String envFile =
              item instanceof Map<?, ?> m ? Yaml.string(m.get("path")) : Yaml.string(item);
          File envText = envFile == null ? null : read(normalize(dir, resolve(envFile, dotEnv)));
          if (envText != null) {
            env.putAll(properties(envText));
          }
        }
        if (environmentNode instanceof Map<?, ?> m) {
          m.forEach(
              (k, v) -> {
                if (k != null && v != null) {
                  env.put(k.toString(), Yaml.string(v));
                }
              });
        } else if (environmentNode instanceof List<?> list) {
          for (Object item : list) {
            String text = Yaml.string(item);
            if (text != null && text.contains("=")) {
              env.put(
                  text.substring(0, text.indexOf('=')).strip(),
                  text.substring(text.indexOf('=') + 1));
            }
          }
        }
        env.forEach((k, v) -> putEnv(c, k, resolve(v, dotEnv)));
        for (String key : List.of("entrypoint", "command")) {
          Object node = service.get(key);
          if (node instanceof List<?> words) {
            words.forEach(w -> c.command.add(resolve(String.valueOf(w), dotEnv)));
          } else if (Yaml.string(node) != null) {
            shellWords(resolve(Yaml.string(node), dotEnv)).forEach(c.command::add);
          }
        }
        for (Object port : Yaml.list(service.get("ports"))) {
          if (port instanceof Map<?, ?> p) {
            String target = Yaml.string(p.get("target"));
            String published = Yaml.string(p.get("published"));
            if (target != null) {
              c.ports.add(published == null ? target : resolve(published, dotEnv) + ":" + target);
            }
          } else if (Yaml.string(port) != null) {
            c.ports.add(resolve(Yaml.string(port), dotEnv));
          }
        }
        Object dependsOn = service.get("depends_on");
        if (dependsOn instanceof Map<?, ?> m) {
          m.keySet().forEach(k -> c.dependsOn.add(k.toString()));
        } else {
          Yaml.list(dependsOn).forEach(d -> c.dependsOn.add(String.valueOf(d)));
        }
        Yaml.list(service.get("profiles")).forEach(p -> c.profiles.add(String.valueOf(p)));
        Object networks = service.get("networks");
        if (networks instanceof Map<?, ?> m) {
          for (Object network : m.values()) {
            Map<String, Object> settings = Yaml.map(network);
            if (settings != null) {
              Yaml.list(settings.get("aliases")).forEach(a -> c.hosts.add(String.valueOf(a)));
            }
          }
        }
        for (Object volume : Yaml.list(service.get("volumes"))) {
          String text = Yaml.string(volume);
          if (text == null && volume instanceof Map<?, ?> v) {
            String source = Yaml.string(v.get("source"));
            String target = Yaml.string(v.get("target"));
            text = source == null || target == null ? null : source + ":" + target;
          }
          if (text == null) {
            continue;
          }
          String[] parts = text.split(":");
          if (parts.length >= 2 && parts[1].startsWith(INIT_DIRECTORY)) {
            String source = parts[0];
            // A bind mount: a named volume has no slash and no leading dot.
            if (source.startsWith(".") || (!source.startsWith("/") && source.contains("/"))) {
              initScripts(normalize(dir, source), c.databases);
            }
          }
        }
        databasesFromEnv(c);
        record(c, COMPOSE, environment);
      }
    }

    /** The variables of the {@code .env} file beside a compose file, secret ones left out. */
    private Map<String, String> dotEnv(String dir) {
      File file = read(dir.isEmpty() ? ".env" : dir + "/.env");
      Map<String, String> values = new TreeMap<>();
      if (file != null) {
        values.putAll(properties(file));
        values.keySet().removeIf(k -> SECRET_NAME.matcher(k).matches());
      }
      return values;
    }

    /** Resolves compose variables from the {@code .env} file, else to their defaults. */
    private static String resolve(String text, Map<String, String> dotEnv) {
      if (text == null) {
        return null;
      }
      Matcher m = COMPOSE_VARIABLE.matcher(text.replace("$$", "\u0000"));
      StringBuilder b = new StringBuilder();
      while (m.find()) {
        String name = m.group(1) != null ? m.group(1) : m.group(4);
        String value = dotEnv.get(name);
        String operator = m.group(2);
        if (operator != null && operator.endsWith("-")) {
          if (value == null || (operator.startsWith(":") && value.isEmpty())) {
            value = m.group(3);
          }
        } else if (operator != null && operator.endsWith("+")) {
          // ${X:+alt} is alt when X is set, else empty; unset here means unknown.
          value =
              value == null || (operator.startsWith(":") && value.isEmpty()) ? null : m.group(3);
        }
        // An unset variable is kept as a reference, so a service and the container it reaches can
        // be tied by the variable they share.
        m.appendReplacement(b, Matcher.quoteReplacement(value == null ? "${" + name + "}" : value));
      }
      m.appendTail(b);
      return b.toString().replace("\u0000", "$");
    }

    private static String composeEnvironment(String path) {
      String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
      String stem = name.replaceFirst("\\.ya?ml$", "").replaceFirst("^(docker-)?compose", "");
      stem = stem.replaceFirst("^[._-]", "");
      return stem.isEmpty() ? "default" : stem;
    }

    /** The line of a compose service's key, 1-based; 0 when it cannot be found. */
    private static int keyLine(File file, String key) {
      Pattern line =
          Pattern.compile("^(\\s+)[\"']?" + Pattern.quote(key) + "[\"']?\\s*:\\s*(#.*)?$");
      boolean inServices = false;
      int indent = -1;
      for (int n = 0; n < file.lines().size(); n++) {
        String text = file.lines().get(n);
        if (text.startsWith("services:")) {
          inServices = true;
          indent = -1;
          continue;
        } else if (!text.isBlank()
            && !Character.isWhitespace(text.charAt(0))
            && !text.startsWith("#")) {
          inServices = false;
        }
        if (!inServices || text.isBlank() || text.strip().startsWith("#")) {
          continue;
        }
        int at = text.length() - text.stripLeading().length();
        if (indent < 0) {
          // The first key under services: is a service; every service is at its indent.
          indent = at;
        }
        Matcher m = line.matcher(text);
        if (at == indent && m.matches()) {
          return n + 1;
        }
      }
      return 0;
    }

    // -------------------------------------------------------------------------------------------
    // Kubernetes: kustomize, Helm and plain manifests

    /** The overlays of each kustomization no other one builds on, each as an environment. */
    private void kustomize(Set<String> used) {
      Map<String, Map<String, Object>> kustomizations = new TreeMap<>();
      Map<String, File> files = new TreeMap<>();
      for (String path : paths) {
        if (KUSTOMIZATION.matcher(path).matches() && !used.contains(path)) {
          File file = read(path);
          Map<String, Object> doc = file == null ? null : Yaml.map(Yaml.load(file.text()));
          if (doc != null) {
            kustomizations.put(parent(path), doc);
            files.put(parent(path), file);
          }
        }
      }
      Set<String> built = new TreeSet<>();
      for (Map.Entry<String, Map<String, Object>> k : kustomizations.entrySet()) {
        for (String entry : resources(k.getValue())) {
          String target = normalize(k.getKey(), entry);
          if (kustomizations.containsKey(target)) {
            built.add(target);
          }
        }
      }
      for (String dir : kustomizations.keySet()) {
        used.add(files.get(dir).path());
        if (built.contains(dir)) {
          continue;
        }
        List<Doc> docs = new ArrayList<>();
        Map<String, Map<String, String>> generated = new TreeMap<>();
        Map<String, Map<String, String>> images = new TreeMap<>();
        collect(dir, kustomizations, docs, generated, images, new TreeSet<>(), used);
        String environment = dir.isEmpty() ? "default" : dir.substring(dir.lastIndexOf('/') + 1);
        kubernetes(docs, generated, images, KUBERNETES, environment);
      }
    }

    private static List<String> resources(Map<String, Object> kustomization) {
      List<String> entries = new ArrayList<>();
      for (String key : List.of("resources", "bases", "components")) {
        Yaml.list(kustomization.get(key)).forEach(e -> entries.add(String.valueOf(e)));
      }
      return entries;
    }

    private void collect(
        String dir,
        Map<String, Map<String, Object>> kustomizations,
        List<Doc> docs,
        Map<String, Map<String, String>> generated,
        Map<String, Map<String, String>> images,
        Set<String> visited,
        Set<String> used) {
      if (!visited.add(dir)) {
        return;
      }
      Map<String, Object> k = kustomizations.get(dir);
      for (String entry : resources(k)) {
        String target = normalize(dir, entry);
        if (kustomizations.containsKey(target)) {
          collect(target, kustomizations, docs, generated, images, visited, used);
        } else {
          File file = read(target);
          if (file != null) {
            used.add(target);
            docs.addAll(docs(file));
          }
        }
      }
      for (String key : List.of("patches", "patchesStrategicMerge", "patchesJson6902")) {
        for (Object item : Yaml.list(k.get(key))) {
          Map<String, Object> patch = Yaml.map(item);
          String path = patch == null ? Yaml.string(item) : Yaml.string(patch.get("path"));
          if (path != null) {
            // A patch changes resources; it is not one, so it is no manifest of its own.
            used.add(normalize(dir, path));
          }
        }
      }
      for (Object item : Yaml.list(k.get("configMapGenerator"))) {
        Map<String, Object> generator = Yaml.map(item);
        String name = generator == null ? null : Yaml.string(generator.get("name"));
        if (name == null) {
          continue;
        }
        Map<String, String> data = generated.computeIfAbsent(name, n -> new TreeMap<>());
        for (Object env : Yaml.list(generator.get("envs"))) {
          File file = read(normalize(dir, String.valueOf(env)));
          if (file != null) {
            data.putAll(properties(file));
          }
        }
        String env = Yaml.string(generator.get("env"));
        if (env != null) {
          File file = read(normalize(dir, env));
          if (file != null) {
            data.putAll(properties(file));
          }
        }
        for (Object literal : Yaml.list(generator.get("literals"))) {
          String text = String.valueOf(literal);
          if (text.contains("=")) {
            data.put(text.substring(0, text.indexOf('=')), text.substring(text.indexOf('=') + 1));
          }
        }
        for (Object entry : Yaml.list(generator.get("files"))) {
          String text = String.valueOf(entry);
          String key = text.contains("=") ? text.substring(0, text.indexOf('=')) : null;
          String source = text.contains("=") ? text.substring(text.indexOf('=') + 1) : text;
          File file = read(normalize(dir, source));
          if (file != null) {
            used.add(file.path());
            data.put(
                key == null ? source.substring(source.lastIndexOf('/') + 1) : key, file.text());
          }
        }
      }
      for (Object item : Yaml.list(k.get("images"))) {
        Map<String, Object> image = Yaml.map(item);
        String name = image == null ? null : Yaml.string(image.get("name"));
        if (name != null) {
          Map<String, String> change = new TreeMap<>();
          for (String key : List.of("newName", "newTag", "digest")) {
            String value = Yaml.string(image.get(key));
            if (value != null) {
              change.put(key, value);
            }
          }
          images.put(name, change);
        }
      }
    }

    /** Each Helm chart, once with its default values and once with each other values file. */
    private void helm(String dir, String chartPath) {
      File chartFile = read(chartPath);
      Map<String, Object> chart = chartFile == null ? null : Yaml.map(Yaml.load(chartFile.text()));
      if (chart == null) {
        return;
      }
      String chartName = Objects.requireNonNullElse(Yaml.string(chart.get("name")), "chart");
      String prefix = dir.isEmpty() ? "" : dir + "/";
      File valuesFile = read(prefix + "values.yaml");
      Map<String, Object> defaults =
          valuesFile == null ? new LinkedHashMap<>() : Yaml.map(Yaml.load(valuesFile.text()));
      if (defaults == null) {
        defaults = new LinkedHashMap<>();
      }
      Map<String, Map<String, Object>> environments = new TreeMap<>();
      environments.put(chartName, defaults);
      for (String path : paths) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (parent(path).equals(dir) && name.matches("values[-.][^/]+\\.ya?ml")) {
          File overlay = read(path);
          Map<String, Object> values = overlay == null ? null : Yaml.map(Yaml.load(overlay.text()));
          if (values != null) {
            String env = name.replaceFirst("^values[-.]", "").replaceFirst("\\.ya?ml$", "");
            environments.put(chartName + " " + env, Yaml.merge(defaults, values));
          }
        }
      }
      for (Map.Entry<String, Map<String, Object>> env : environments.entrySet()) {
        List<Doc> docs = new ArrayList<>();
        for (String path : paths) {
          String name = path.substring(path.lastIndexOf('/') + 1);
          if (path.startsWith(prefix + "templates/")
              && YAML.matcher(path).matches()
              && !name.startsWith("_")) {
            File template = read(path);
            if (template != null) {
              String rendered = HelmTemplate.render(template.text(), env.getValue(), chart);
              File file =
                  new File(path, rendered, List.of(rendered.split("\n", -1)), template.hash());
              docs.addAll(docs(file));
            }
          }
        }
        kubernetes(docs, Map.of(), Map.of(), HELM, env.getKey());
      }
    }

    /** Kubernetes manifests that no kustomization, chart or compose file uses: one environment. */
    private void manifests(Set<String> used) {
      List<Doc> docs = new ArrayList<>();
      for (String path : paths) {
        if (!YAML.matcher(path).matches() || used.contains(path) || path.startsWith(".github/")) {
          continue;
        }
        File file = read(path);
        if (file == null || !file.text().contains("apiVersion") || !file.text().contains("kind")) {
          continue;
        }
        for (Doc doc : docs(file)) {
          if (doc.map().containsKey("apiVersion") && doc.map().containsKey("kind")) {
            docs.add(doc);
          }
        }
      }
      if (!docs.isEmpty()) {
        kubernetes(docs, Map.of(), Map.of(), KUBERNETES, "default");
      }
    }

    /** The containers the workloads of one Kubernetes environment run. */
    private void kubernetes(
        List<Doc> docs,
        Map<String, Map<String, String>> generated,
        Map<String, Map<String, String>> images,
        String platform,
        String environment) {
      Map<String, Map<String, String>> configMaps = new TreeMap<>();
      List<Doc> services = new ArrayList<>();
      for (Doc doc : docs) {
        String kind = Yaml.string(doc.map().get("kind"));
        Map<String, Object> metadata = Yaml.map(doc.map().get("metadata"));
        String name = metadata == null ? null : Yaml.string(metadata.get("name"));
        if ("ConfigMap".equals(kind) && name != null) {
          Map<String, String> data = new TreeMap<>();
          Map<String, Object> values = Yaml.map(doc.map().get("data"));
          if (values != null) {
            values.forEach((k, v) -> data.put(k, Yaml.string(v)));
          }
          configMaps.put(name, data);
        } else if ("Service".equals(kind) && name != null) {
          services.add(doc);
        }
      }
      generated.forEach(
          (name, data) ->
              configMaps.merge(
                  name,
                  data,
                  (a, b) -> {
                    Map<String, String> merged = new TreeMap<>(a);
                    merged.putAll(b);
                    return merged;
                  }));
      for (Doc doc : docs) {
        String kind = Yaml.string(doc.map().get("kind"));
        if (kind == null) {
          continue;
        }
        if (kind.equals("Ingress")) {
          ingress(doc, platform, environment);
          continue;
        }
        if (kind.equals("HTTPRoute")) {
          httpRoute(doc, platform, environment);
          continue;
        }
        Map<String, Object> metadata = Yaml.map(doc.map().get("metadata"));
        String workload = metadata == null ? null : Yaml.string(metadata.get("name"));
        Map<String, Object> spec = Yaml.map(doc.map().get("spec"));
        Map<String, Object> template;
        switch (kind) {
          case "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job" ->
              template = spec == null ? null : Yaml.map(spec.get("template"));
          case "CronJob" -> {
            Map<String, Object> job = spec == null ? null : Yaml.map(spec.get("jobTemplate"));
            Map<String, Object> jobSpec = job == null ? null : Yaml.map(job.get("spec"));
            template = jobSpec == null ? null : Yaml.map(jobSpec.get("template"));
          }
          case "Pod" -> template = doc.map();
          default -> template = null;
        }
        if (template == null) {
          continue;
        }
        Map<String, Object> podMetadata = Yaml.map(template.get("metadata"));
        Map<String, String> labels = new TreeMap<>();
        Map<String, Object> labelMap =
            podMetadata == null ? null : Yaml.map(podMetadata.get("labels"));
        if (labelMap != null) {
          labelMap.forEach((k, v) -> labels.put(k, Yaml.string(v)));
        }
        Map<String, Object> podSpec = Yaml.map(template.get("spec"));
        if (podSpec == null) {
          continue;
        }
        Map<String, String> volumes = new TreeMap<>();
        for (Object item : Yaml.list(podSpec.get("volumes"))) {
          Map<String, Object> volume = Yaml.map(item);
          Map<String, Object> configMap = volume == null ? null : Yaml.map(volume.get("configMap"));
          if (configMap != null && Yaml.string(volume.get("name")) != null) {
            volumes.put(Yaml.string(volume.get("name")), Yaml.string(configMap.get("name")));
          }
        }
        List<Object> containers = Yaml.list(podSpec.get("containers"));
        for (Object item : containers) {
          Map<String, Object> container = Yaml.map(item);
          if (container == null) {
            continue;
          }
          Found c = new Found();
          c.file = doc.file();
          c.kind = kind;
          String containerName = Yaml.string(container.get("name"));
          if (workload == null) {
            // A name this reading cannot fill (a Helm helper): the container's.
            workload = containerName;
          }
          if (workload == null) {
            continue;
          }
          c.name =
              containers.size() == 1 || containerName == null
                  ? workload
                  : workload + "/" + containerName;
          c.hosts.add(workload);
          if (spec != null && Yaml.string(spec.get("serviceName")) != null) {
            c.hosts.add(Yaml.string(spec.get("serviceName")));
          }
          for (Doc service : services) {
            Map<String, Object> serviceSpec = Yaml.map(service.map().get("spec"));
            Map<String, Object> selector =
                serviceSpec == null ? null : Yaml.map(serviceSpec.get("selector"));
            Map<String, Object> serviceMetadata = Yaml.map(service.map().get("metadata"));
            if (selector != null
                && !selector.isEmpty()
                && selector.entrySet().stream()
                    .allMatch(
                        s -> Objects.equals(labels.get(s.getKey()), Yaml.string(s.getValue())))) {
              c.hosts.add(Yaml.string(serviceMetadata.get("name")));
            }
          }
          for (String label : List.of("app.kubernetes.io/name", "app", "k8s-app")) {
            if (labels.get(label) != null) {
              c.aliases.add(labels.get(label));
            }
          }
          if (containerName != null) {
            c.aliases.add(containerName);
          }
          String image = Yaml.string(container.get("image"));
          if (image != null) {
            c.image = retag(image, images);
          }
          c.line = imageLine(doc, image);
          if (spec != null && spec.get("replicas") instanceof Integer replicas) {
            c.replicas = replicas;
          }
          for (Object from : Yaml.list(container.get("envFrom"))) {
            Map<String, Object> source = Yaml.map(from);
            Map<String, Object> ref = source == null ? null : Yaml.map(source.get("configMapRef"));
            String mapName = ref == null ? null : Yaml.string(ref.get("name"));
            if (mapName != null && configMaps.containsKey(mapName)) {
              String prefixText = Objects.requireNonNullElse(Yaml.string(source.get("prefix")), "");
              configMaps.get(mapName).forEach((k, v) -> putEnv(c, prefixText + k, v));
            }
          }
          for (Object e : Yaml.list(container.get("env"))) {
            Map<String, Object> env = Yaml.map(e);
            String name = env == null ? null : Yaml.string(env.get("name"));
            if (name == null) {
              continue;
            }
            if (env.containsKey("value")) {
              putEnv(c, name, Yaml.string(env.get("value")));
            } else {
              Map<String, Object> valueFrom = Yaml.map(env.get("valueFrom"));
              Map<String, Object> ref =
                  valueFrom == null ? null : Yaml.map(valueFrom.get("configMapKeyRef"));
              if (ref != null && configMaps.containsKey(Yaml.string(ref.get("name")))) {
                putEnv(
                    c,
                    name,
                    configMaps.get(Yaml.string(ref.get("name"))).get(Yaml.string(ref.get("key"))));
              } else {
                // From a secret (never read) or the pod's own fields.
                c.env.remove(name);
                c.masked.add(name);
              }
            }
          }
          for (String key : List.of("command", "args")) {
            for (Object word : Yaml.list(container.get(key))) {
              c.command.add(dependentVariables(String.valueOf(word), c.env));
            }
          }
          for (Object p : Yaml.list(container.get("ports"))) {
            Map<String, Object> port = Yaml.map(p);
            String number = port == null ? null : Yaml.string(port.get("containerPort"));
            if (number != null) {
              c.ports.add(number);
            }
          }
          for (Object m : Yaml.list(container.get("volumeMounts"))) {
            Map<String, Object> mount = Yaml.map(m);
            String mountPath = mount == null ? null : Yaml.string(mount.get("mountPath"));
            String configMap = mount == null ? null : volumes.get(Yaml.string(mount.get("name")));
            if (mountPath != null
                && mountPath.startsWith(INIT_DIRECTORY)
                && configMaps.containsKey(configMap)) {
              configMaps
                  .get(configMap)
                  .values()
                  .forEach(text -> createdDatabases(text, c.databases));
            }
          }
          databasesFromEnv(c);
          record(c, platform, environment);
        }
      }
    }

    /**
     * The routes of an Ingress: each rule's host (or {@code *}) and path, the Service and port it
     * sends to, and the path rewrite the nginx or Traefik controller's annotation sets.
     */
    private void ingress(Doc doc, String platform, String environment) {
      Map<String, Object> metadata = Yaml.map(doc.map().get("metadata"));
      Map<String, Object> spec = Yaml.map(doc.map().get("spec"));
      if (spec == null) {
        return;
      }
      String name = metadata == null ? null : Yaml.string(metadata.get("name"));
      Map<String, Object> annotations =
          metadata == null ? null : Yaml.map(metadata.get("annotations"));
      String rewrite = null;
      if (annotations != null) {
        rewrite = Yaml.string(annotations.get("nginx.ingress.kubernetes.io/rewrite-target"));
      }
      String className = Yaml.string(spec.get("ingressClassName"));
      Map<String, Object> defaultBackend = Yaml.map(spec.get("defaultBackend"));
      if (defaultBackend != null) {
        route(
            doc,
            platform,
            environment,
            "Ingress",
            name,
            className,
            null,
            "/",
            null,
            serviceBackend(defaultBackend),
            rewrite);
      }
      for (Object item : Yaml.list(spec.get("rules"))) {
        Map<String, Object> rule = Yaml.map(item);
        if (rule == null) {
          continue;
        }
        String host = Yaml.string(rule.get("host"));
        Map<String, Object> http = Yaml.map(rule.get("http"));
        for (Object p : http == null ? List.of() : Yaml.list(http.get("paths"))) {
          Map<String, Object> path = Yaml.map(p);
          if (path == null) {
            continue;
          }
          route(
              doc,
              platform,
              environment,
              "Ingress",
              name,
              className,
              host,
              Objects.requireNonNullElse(Yaml.string(path.get("path")), "/"),
              Yaml.string(path.get("pathType")),
              serviceBackend(Yaml.map(path.get("backend"))),
              rewrite);
        }
      }
    }

    /** The first value if it is set, else the second, which may be null too. */
    private static String either(String first, String second) {
      return first != null ? first : second;
    }

    /** An Ingress backend's Service name and port (number or name), or nulls. */
    private static String[] serviceBackend(Map<String, Object> backend) {
      Map<String, Object> service = backend == null ? null : Yaml.map(backend.get("service"));
      if (service != null) {
        Map<String, Object> port = Yaml.map(service.get("port"));
        String number =
            port == null
                ? null
                : either(Yaml.string(port.get("number")), Yaml.string(port.get("name")));
        return new String[] {Yaml.string(service.get("name")), number};
      }
      // networking.k8s.io/v1beta1 and extensions/v1beta1
      return backend == null
          ? new String[2]
          : new String[] {
            Yaml.string(backend.get("serviceName")), Yaml.string(backend.get("servicePort"))
          };
    }

    /**
     * The routes of a Gateway API HTTPRoute: each rule's path matches for each of its hostnames,
     * the Service backends it sends to, and the URLRewrite filter's path.
     */
    private void httpRoute(Doc doc, String platform, String environment) {
      Map<String, Object> metadata = Yaml.map(doc.map().get("metadata"));
      Map<String, Object> spec = Yaml.map(doc.map().get("spec"));
      if (spec == null) {
        return;
      }
      String name = metadata == null ? null : Yaml.string(metadata.get("name"));
      List<String> hosts = new ArrayList<>();
      Yaml.list(spec.get("hostnames")).forEach(h -> hosts.add(String.valueOf(h)));
      if (hosts.isEmpty()) {
        hosts.add(null);
      }
      String gateway = null;
      for (Object item : Yaml.list(spec.get("parentRefs"))) {
        Map<String, Object> parent = Yaml.map(item);
        if (parent != null && gateway == null) {
          gateway = Yaml.string(parent.get("name"));
        }
      }
      for (Object item : Yaml.list(spec.get("rules"))) {
        Map<String, Object> rule = Yaml.map(item);
        if (rule == null) {
          continue;
        }
        String rewrite = null;
        for (Object f : Yaml.list(rule.get("filters"))) {
          Map<String, Object> filter = Yaml.map(f);
          Map<String, Object> urlRewrite =
              filter == null ? null : Yaml.map(filter.get("urlRewrite"));
          Map<String, Object> path = urlRewrite == null ? null : Yaml.map(urlRewrite.get("path"));
          if (path != null) {
            rewrite =
                either(
                    Yaml.string(path.get("replacePrefixMatch")),
                    Yaml.string(path.get("replaceFullPath")));
          }
        }
        List<String[]> paths = new ArrayList<>();
        for (Object m : Yaml.list(rule.get("matches"))) {
          Map<String, Object> match = Yaml.map(m);
          Map<String, Object> path = match == null ? null : Yaml.map(match.get("path"));
          if (path != null) {
            paths.add(
                new String[] {
                  Objects.requireNonNullElse(Yaml.string(path.get("value")), "/"),
                  Objects.requireNonNullElse(Yaml.string(path.get("type")), "PathPrefix")
                });
          }
        }
        if (paths.isEmpty()) {
          paths.add(new String[] {"/", "PathPrefix"});
        }
        List<String[]> backends = new ArrayList<>();
        for (Object b : Yaml.list(rule.get("backendRefs"))) {
          Map<String, Object> ref = Yaml.map(b);
          String refKind = ref == null ? null : Yaml.string(ref.get("kind"));
          if (ref != null && (refKind == null || refKind.equals("Service"))) {
            backends.add(new String[] {Yaml.string(ref.get("name")), Yaml.string(ref.get("port"))});
          }
        }
        if (backends.isEmpty()) {
          backends.add(new String[2]);
        }
        for (String host : hosts) {
          for (String[] path : paths) {
            for (String[] backend : backends) {
              route(
                  doc,
                  platform,
                  environment,
                  "HTTPRoute",
                  name,
                  gateway,
                  host,
                  path[0],
                  path[1],
                  backend,
                  rewrite);
            }
          }
        }
      }
    }

    private void route(
        Doc doc,
        String platform,
        String environment,
        String kind,
        String name,
        String className,
        String host,
        String path,
        String pathType,
        String[] backend,
        String rewrite) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("platform", platform);
      attributes.put("environment", environment);
      attributes.put("kind", kind);
      if (name != null) {
        attributes.put("name", name);
      }
      if (className != null) {
        attributes.put(kind.equals("Ingress") ? "class" : "gateway", className);
      }
      if (host != null) {
        attributes.put("host", host);
      }
      attributes.put("path", path);
      if (pathType != null) {
        attributes.put("pathType", pathType);
      }
      if (backend[0] != null) {
        attributes.put("service", backend[0]);
      }
      if (backend[1] != null) {
        attributes.put("port", backend[1]);
      }
      if (rewrite != null) {
        attributes.put("rewrite", rewrite);
      }
      add(
          DEPLOYMENT_ROUTE,
          (host == null ? "*" : host) + path,
          attributes,
          doc.file(),
          valueLine(doc, path));
    }

    /** The first line of the document whose {@code path:} or {@code value:} is the value. */
    private static int valueLine(Doc doc, String value) {
      for (int n = doc.start() - 1; n < doc.end() && n < doc.file().lines().size(); n++) {
        Matcher m = PATH_LINE.matcher(doc.file().lines().get(n));
        if (m.matches() && unquote(m.group(1).strip()).equals(value)) {
          return n + 1;
        }
      }
      return doc.start();
    }

    /**
     * A command word with the {@code $(VAR)} references Kubernetes fills from the container's env.
     */
    private static String dependentVariables(String word, Map<String, String> env) {
      Matcher m = DEPENDENT_VARIABLE.matcher(word);
      StringBuilder b = new StringBuilder();
      while (m.find()) {
        String value = env.get(m.group(1));
        m.appendReplacement(b, Matcher.quoteReplacement(value == null ? m.group() : value));
      }
      m.appendTail(b);
      return b.toString();
    }

    /** An image as kustomize's {@code images} transformer changes it. */
    private static String retag(String image, Map<String, Map<String, String>> images) {
      if (image.contains("@")) {
        // Pinned by digest: kustomize matches the name before it; keep it as written.
        String name = image.substring(0, image.indexOf('@'));
        Map<String, String> change = images.get(name);
        return change == null || !change.containsKey("newName")
            ? image
            : change.get("newName") + image.substring(image.indexOf('@'));
      }
      int colon = image.lastIndexOf(':');
      String name = colon > image.lastIndexOf('/') ? image.substring(0, colon) : image;
      Map<String, String> change = images.get(name);
      if (change == null) {
        return image;
      }
      String newName = change.getOrDefault("newName", name);
      if (change.containsKey("digest")) {
        return newName + "@" + change.get("digest");
      }
      String tag = colon > image.lastIndexOf('/') ? image.substring(colon + 1) : null;
      tag = change.getOrDefault("newTag", tag);
      return tag == null ? newName : newName + ":" + tag;
    }

    private static int imageLine(Doc doc, String image) {
      for (int n = doc.start() - 1; n < doc.end() && n < doc.file().lines().size(); n++) {
        String text = doc.file().lines().get(n);
        if (text.contains("image:") && (image == null || text.contains(image))) {
          return n + 1;
        }
      }
      return doc.start();
    }

    // -------------------------------------------------------------------------------------------
    // Shared

    private void putEnv(Found c, String name, String value) {
      if (name == null || value == null) {
        return;
      }
      if (SECRET_NAME.matcher(name).matches()) {
        c.masked.add(name);
        c.env.remove(name);
        return;
      }
      if (value.contains("\n") || SECRET_VALUE.matcher(value).find()) {
        // A whole file from a ConfigMap, or a value that writes a secret out: not recorded.
        c.masked.add(name);
        c.env.remove(name);
        return;
      }
      c.env.put(name, URL_USER.matcher(value).replaceAll("$1"));
    }

    private static void databasesFromEnv(Found c) {
      for (String variable : DATABASE_VARIABLES) {
        String value = c.env.get(variable);
        if (value != null && !value.isBlank() && !value.contains("$")) {
          c.databases.add(value);
        }
      }
    }

    private void initScripts(String path, Set<String> databases) {
      if (pathSet.contains(path)) {
        File file = read(path);
        if (file != null) {
          createdDatabases(file.text(), databases);
        }
        return;
      }
      for (String p : paths) {
        if (p.startsWith(path + "/") && p.matches("(?i).*\\.(sql|sh)")) {
          File file = read(p);
          if (file != null) {
            createdDatabases(file.text(), databases);
          }
        }
      }
    }

    private static void createdDatabases(String text, Set<String> databases) {
      if (text == null) {
        return;
      }
      Matcher m = CREATE_DATABASE.matcher(text);
      while (m.find()) {
        databases.add(m.group(1));
      }
    }

    private void record(Found c, String platform, String environment) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("platform", platform);
      attributes.put("environment", environment);
      attributes.put("kind", c.kind);
      if (c.image != null) {
        attributes.put("image", c.image);
        Image image = Image.of(c.image);
        if (image != null) {
          attributes.put("product", image.product());
          if (image.version() != null) {
            attributes.put("version", image.version());
          }
          attributes.put("role", image.role());
        }
      }
      if (c.build != null) {
        attributes.put("build", c.build);
      }
      array(attributes, "hosts", c.hosts);
      Set<String> aliases = new TreeSet<>(c.aliases);
      aliases.removeAll(c.hosts);
      array(attributes, "aliases", aliases);
      settings(c, attributes);
      array(attributes, "ports", c.ports);
      array(attributes, "dependsOn", c.dependsOn);
      array(attributes, "composeProfiles", c.profiles);
      array(attributes, "databases", c.databases);
      if (c.replicas != null) {
        attributes.put("replicas", c.replicas);
      }
      add(DEPLOYMENT_CONTAINER, c.name, attributes, c.file, c.line);
    }

    private static void array(
        ObjectNode attributes, String name, java.util.Collection<String> values) {
      if (!values.isEmpty()) {
        ArrayNode array = attributes.putArray(name);
        values.forEach(array::add);
      }
    }

    private void add(String predicate, String value, ObjectNode attributes, File file, int line) {
      Fact fact =
          Fact.create(
              subject,
              predicate,
              FactObject.string(value),
              attributes,
              ORIGIN,
              new Evidence(
                  repo.repo().name(),
                  repo.commit(),
                  file.path(),
                  line > 0 ? new Evidence.LineRange(line, line) : null,
                  file.hash()),
              1.0,
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }

    /** The YAML documents of a file, each with the lines it spans. */
    private static List<Doc> docs(File file) {
      List<Doc> docs = new ArrayList<>();
      List<String> lines = file.lines();
      int start = 0;
      for (int n = 0; n <= lines.size(); n++) {
        if (n == lines.size() || SEPARATOR.matcher(lines.get(n)).matches()) {
          if (n > start) {
            String chunk = String.join("\n", lines.subList(start, n));
            Map<String, Object> map = Yaml.map(Yaml.load(chunk));
            if (map != null) {
              docs.add(new Doc(file, start + 1, n, map));
            }
          }
          start = n + 1;
        }
      }
      return docs;
    }

    /** {@code KEY=VALUE} lines, as env files and kustomize's generators read them. */
    private static Map<String, String> properties(File file) {
      Map<String, String> values = new TreeMap<>();
      for (String line : file.lines()) {
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#") || !text.contains("=")) {
          continue;
        }
        if (text.startsWith("export ")) {
          text = text.substring(7).strip();
        }
        String key = text.substring(0, text.indexOf('=')).strip();
        String value = unquote(text.substring(text.indexOf('=') + 1).strip());
        values.put(key, value);
      }
      return values;
    }

    private static String unquote(String text) {
      if (text.length() >= 2
          && (text.startsWith("\"") && text.endsWith("\"")
              || text.startsWith("'") && text.endsWith("'"))) {
        return text.substring(1, text.length() - 1);
      }
      return text;
    }

    private File read(String path) {
      if (path == null || !pathSet.contains(path)) {
        return null;
      }
      return cache.computeIfAbsent(
          path,
          p -> {
            byte[] bytes = GitFiles.read(repo, p, MAX_BYTES);
            if (bytes == null) {
              return null;
            }
            String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            return new File(p, text, List.of(text.split("\n", -1)), sha256(bytes));
          });
    }

    private static String parent(String path) {
      int slash = path.lastIndexOf('/');
      return slash < 0 ? "" : path.substring(0, slash);
    }

    /** A path relative to a directory, with {@code .} and {@code ..} resolved. */
    private static String normalize(String dir, String relative) {
      List<String> parts = new ArrayList<>();
      String joined =
          relative.startsWith("/")
              ? relative.substring(1)
              : (dir.isEmpty() ? "" : dir + "/") + relative;
      for (String part : joined.split("/")) {
        if (part.isEmpty() || part.equals(".")) {
          continue;
        }
        if (part.equals("..")) {
          if (!parts.isEmpty()) {
            parts.removeLast();
          }
        } else {
          parts.add(part);
        }
      }
      return String.join("/", parts);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
