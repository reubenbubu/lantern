package io.lantern.extract.frontend;

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
import io.lantern.extract.deployment.Image;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the frontends of every repository of a snapshot, those without a build too: each package
 * manifest ({@code package.json}) that depends on Angular, React or Vue is a single-page app, a
 * {@code frontend} entity. See {@code docs/adr/0083-frontend-containers.md}.
 *
 * <p>It records the framework and its version, the libraries Lantern knows, the image that serves
 * it (the last stage of the Dockerfile in its folder) as a {@code frontend-app} fact; each URL its
 * code calls from the browser (Angular's {@code HttpClient}, {@code fetch}, axios, a WebSocket,
 * SockJS or a STOMP client's {@code brokerURL}, or a navigation of the browser to a URL) as a
 * {@code frontend-call}, with constants of its file and the values of its environment files filled
 * in; and each path prefix the nginx that serves it or its development server passes on to another
 * server as a {@code frontend-proxy}. The linker matches the URLs with the units they reach. Files
 * are read from git at the commit; nothing is built or run, and the code is read as text, not
 * parsed.
 */
public final class FrontendExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "frontend";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "3";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "frontend-extractor";

  /** Entity: a frontend, a single-page app. */
  public static final String FRONTEND = "frontend";

  /** Attribute: the subject is a frontend; the value is its framework. */
  public static final String APP = "frontend-app";

  /** Attribute: a URL the frontend's code calls from the browser. */
  public static final String CALL = "frontend-call";

  /** Attribute: a path prefix the frontend's server passes on to another server. */
  public static final String PROXY = "frontend-proxy";

  /**
   * Attribute: a value a call's URL is built from, a constant or field of its file or an entry of
   * an environment file, at the line that declares it.
   */
  public static final String URL = "frontend-url";

  /** Attribute: how the frontend signs its requests or guards its pages. */
  public static final String AUTH = "frontend-auth";

  /** Attribute: an Angular page (component) or service of the frontend. */
  public static final String COMPONENT = "frontend-component";

  /** Attribute: an absolute URL the frontend's templates or styles load. */
  public static final String RESOURCE_URL = "frontend-resource";

  /** The protocols of calls, as their {@code protocol}. */
  public static final String HTTP = "HTTP";

  public static final String WEBSOCKET = "WebSocket";

  /** The sources of proxies, as their {@code source}. */
  public static final String NGINX = "nginx";

  public static final String DEV_SERVER = "dev-server";

  private static final String RESOURCE = "/vocabulary/frontend-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final long MAX_BYTES = 2L * 1024 * 1024;

  /** The most calls recorded per frontend; a frontend with more is still drawn. */
  static final int MAX_CALLS = 500;

  /** The directories nothing of a frontend is read from. */
  private static final List<String> SKIPPED =
      List.of(
          "node_modules/",
          "dist/",
          "target/",
          ".git/",
          "src/test/",
          "testdata/",
          "test/fixtures/",
          "e2e/",
          "coverage/");

  /** The frameworks, by the dependency that names each, in the order they are looked for. */
  private static final List<String[]> FRAMEWORKS =
      List.of(
          new String[] {"@angular/core", "angular", "Angular"},
          new String[] {"vue", "vue", "Vue"},
          new String[] {"nuxt", "vue", "Vue"},
          new String[] {"react", "react", "React"},
          new String[] {"next", "react", "React"});

  /** Libraries worth naming, by dependency, in the order they are named. */
  private static final List<String[]> LIBRARIES =
      List.of(
          new String[] {"vite", "Vite"},
          new String[] {"next", "Next.js"},
          new String[] {"nuxt", "Nuxt"},
          new String[] {"@angular/material", "Angular Material"},
          new String[] {"primeng", "PrimeNG"},
          new String[] {"@mui/material", "MUI"},
          new String[] {"vuetify", "Vuetify"},
          new String[] {"bootstrap", "Bootstrap"},
          new String[] {"tailwindcss", "Tailwind CSS"},
          new String[] {"axios", "axios"},
          new String[] {"@stomp/stompjs", "STOMP.js"},
          new String[] {"sockjs-client", "SockJS"},
          new String[] {"socket.io-client", "Socket.IO"},
          new String[] {"typescript", "TypeScript"});

  /** Build tools named in the technology line. */
  private static final Set<String> BUILD_TOOLS = Set.of("Vite", "Next.js", "Nuxt");

  private static final Pattern CODE_FILE = Pattern.compile("(?i).+\\.(ts|tsx|js|jsx|mjs|cjs|vue)");
  private static final Pattern MARKUP_FILE = Pattern.compile("(?i).+\\.(html|css|scss|sass|less)");
  private static final Pattern TEST_FILE =
      Pattern.compile("(?i).*(\\.(spec|test|stories)\\.[a-z]+|/__tests__/.*|/__mocks__/.*)");
  private static final Pattern CONFIG_FILE =
      Pattern.compile("(?i)(.*/)?[^/]*\\.config\\.[a-z]+|(.*/)?environments/[^/]*");

  /** An Angular environment file. */
  private static final Pattern ANGULAR_ENVIRONMENT =
      Pattern.compile("(.*/)?environments/(environment[^/]*)\\.ts");

  /** A dotenv file of Vite or Create React App. */
  private static final Pattern DOTENV = Pattern.compile("\\.env(\\.[A-Za-z0-9_.-]+)?");

  /** A key of an environment object with a string value: {@code apiUrl: 'http://...'}. */
  private static final Pattern ENVIRONMENT_VALUE =
      Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*:\\s*(['\"`])([^'\"`\\n]*)\\2");

  private static final Pattern DOTENV_LINE =
      Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");

  /** A declaration of a constant or field with a value: {@code private readonly BASE = '/api';}. */
  private static final Pattern DECLARATION =
      Pattern.compile(
          "^\\s*(?:export\\s+)?(?:(?:private|public|protected|readonly|static|const|let|var)\\s+)+"
              + "([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*([^;]+?)\\s*;?\\s*$");

  /** Names typed as Angular's HttpClient or injected as it. */
  private static final Pattern HTTP_CLIENT =
      Pattern.compile(
          "\\b([A-Za-z_$][\\w$]*)\\s*:\\s*HttpClient\\b"
              + "|\\b([A-Za-z_$][\\w$]*)\\s*=\\s*inject\\(\\s*HttpClient\\s*\\)");

  /** Axios instances: {@code const api = axios.create({...})}. */
  private static final Pattern AXIOS_INSTANCE =
      Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*=\\s*axios\\.create\\s*\\(");

  /** A request on a client: {@code this.http.get<User>(}, {@code api.post(}. */
  private static final Pattern CLIENT_CALL =
      Pattern.compile(
          "\\b(?:this\\.)?([A-Za-z_$][\\w$]*)\\s*\\.\\s*(get|post|put|patch|delete|head|options|request)"
              + "\\s*(?:<(?:[^<>()]|<[^<>()]*>)*>)?\\s*\\(");

  private static final Pattern FETCH = Pattern.compile("(?<![\\w$.])fetch\\s*\\(");
  private static final Pattern SOCKET = Pattern.compile("\\bnew\\s+(WebSocket|SockJS)\\s*\\(");
  private static final Pattern BROKER_URL = Pattern.compile("\\bbrokerURL\\s*:\\s*");

  /**
   * A navigation of the browser to a URL, which leaves the app for a page the server answers, such
   * as its sign-in: {@code window.location.href = url}, {@code location.assign(url)}, {@code
   * location.replace(url)}.
   */
  private static final Pattern NAVIGATION =
      Pattern.compile(
          "(?:\\b(?:window|document|\\$window)\\s*\\.\\s*|(?<![\\w$.]))location\\s*\\.\\s*"
              + "(?:href\\s*=(?!=)|assign\\s*\\(|replace\\s*\\()");

  private static final Pattern FETCH_METHOD =
      Pattern.compile("\\bmethod\\s*:\\s*['\"`]([A-Za-z]+)['\"`]");
  private static final Pattern BASE_URL = Pattern.compile("\\bbaseURL\\s*:\\s*([^,\\n}]+)");

  /** An nginx location block's start, by prefix (regular-expression locations are skipped). */
  private static final Pattern LOCATION =
      Pattern.compile("\\blocation\\s+(?:\\^~\\s+|=\\s*)?(/[^\\s{]*)\\s*\\{");

  private static final Pattern PROXY_PASS = Pattern.compile("\\bproxy_pass\\s+([^;\\s]+)\\s*;");
  private static final Pattern VITE_PROXY = Pattern.compile("\\bproxy\\s*:\\s*\\{");
  private static final Pattern VITE_ENTRY =
      Pattern.compile(
          "['\"](/[^'\"]*)['\"]\\s*:\\s*(?:['\"]([^'\"]+)['\"]|\\{[^}]*?\\btarget\\s*:\\s*['\"]([^'\"]+)['\"])");

  private static final Pattern FROM =
      Pattern.compile("(?i)^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+AS\\s+(\\S+))?");
  private static final Pattern EXPOSE = Pattern.compile("(?i)^\\s*EXPOSE\\s+(.+)$");

  /** The configuration an Angular build in a Dockerfile names: {@code ng build -c development}. */
  private static final Pattern BUILD_CONFIGURATION =
      Pattern.compile("\\bbuild\\b[^\\n]*?(?:--configuration[= ]|-c )\\s*([A-Za-z0-9_-]+)");

  private static final Pattern VERSION_NUMBER = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

  /** The vocabulary types this extractor's facts use. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = FrontendExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * Extracts the frontend facts of the given repositories.
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
  // Helpers, package-private for tests.

  /** The major and minor version a range asks for: 19.2 for {@code ^19.2.0}, 7 for {@code 7.x}. */
  static String version(String range) {
    if (range == null) {
      return null;
    }
    Matcher m = VERSION_NUMBER.matcher(range);
    if (!m.find()) {
      return null;
    }
    return m.group(2) == null ? m.group(1) : m.group(1) + "." + m.group(2);
  }

  /** A package's name without its npm scope: {@code web} for {@code @acme/web}. */
  static String unscoped(String name) {
    return name.startsWith("@") && name.contains("/")
        ? name.substring(name.indexOf('/') + 1)
        : name;
  }

  /**
   * The argument starting at {@code from} (just after an opening parenthesis or a colon): the text
   * up to the comma, semicolon or closing bracket at its own depth, outside strings.
   */
  static String argument(String text, int from) {
    return text.substring(from, end(text, from)).trim();
  }

  /** The index of the comma, semicolon or closing bracket that ends an argument at {@code from}. */
  static int end(String text, int from) {
    int depth = 0;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        i = afterString(text, i) - 1;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) {
          return i;
        }
        depth--;
      } else if ((c == ',' || c == ';') && depth == 0) {
        return i;
      }
    }
    return text.length();
  }

  /** The index of the bracket closing the one at {@code open}, outside strings, or -1. */
  static int closing(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        i = afterString(text, i) - 1;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        if (--depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * The index just after the string literal starting at {@code start}; a template literal's {@code
   * ${...}} parts are skipped whole. A quote left open ends at the line's end.
   */
  static int afterString(String text, int start) {
    char quote = text.charAt(start);
    for (int i = start + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (quote == '`' && c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
        int close = closing(text, i + 1);
        if (close < 0) {
          return text.length();
        }
        i = close;
      } else if (c == quote) {
        return i + 1;
      } else if (c == '\n' && quote != '`') {
        return i;
      }
    }
    return text.length();
  }

  /** The parts of an expression joined by {@code +} at its own depth, outside strings. */
  static List<String> concatenated(String expression) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < expression.length(); i++) {
      char c = expression.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        i = afterString(expression, i) - 1;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      } else if (c == '+' && depth == 0) {
        parts.add(expression.substring(start, i).trim());
        start = i + 1;
      }
    }
    parts.add(expression.substring(start).trim());
    return parts;
  }

  /**
   * The code with its comments blanked out, line breaks and offsets kept: block comments, and line
   * comments that start a line or follow code after a space (so {@code http://} stays).
   */
  static String uncommented(String code) {
    StringBuilder text = new StringBuilder(code);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        i = afterString(code, i) - 1;
      } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
        int close = code.indexOf("*/", i + 2);
        int stop = close < 0 ? text.length() : close + 2;
        blank(text, i, stop);
        i = stop - 1;
      } else if (c == '/'
          && i + 1 < text.length()
          && text.charAt(i + 1) == '/'
          && (i == 0 || Character.isWhitespace(text.charAt(i - 1)) || text.charAt(i - 1) == ';')) {
        int stop = code.indexOf('\n', i);
        stop = stop < 0 ? text.length() : stop;
        blank(text, i, stop);
        i = stop - 1;
      }
    }
    return text.toString();
  }

  private static void blank(StringBuilder text, int from, int to) {
    for (int i = from; i < to; i++) {
      if (text.charAt(i) != '\n') {
        text.setCharAt(i, ' ');
      }
    }
  }

  /** A URL as recorded: query and fragment left out, a relative path starting with a slash. */
  static String url(String resolved) {
    String url = resolved.trim();
    int query = url.indexOf('?');
    if (query >= 0) {
      url = url.substring(0, query);
    }
    int fragment = url.indexOf('#');
    if (fragment >= 0) {
      url = url.substring(0, fragment);
    }
    if (!url.contains("://") && !url.startsWith("/") && !url.startsWith("{")) {
      url = "/" + url;
    }
    return url.replaceAll("(?<!:)//+", "/");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int lineOf(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  // ---------------------------------------------------------------------------------------------
  // One repository

  /** An environment: its file and the values it sets. */
  private record Environment(String path, Map<String, String> values, Map<String, Where> where) {

    Environment(String path, Map<String, String> values) {
      this(path, values, Map.of());
    }
  }

  /** Where a value is declared: its file and line. */
  private record Where(Source source, int line) {}

  /** A file read at the commit: its text and hash. */
  private record Source(String path, String text, String hash) {}

  private static final class Run {
    private final RepoBuild repo;
    private final Validity validity;
    private final Map<String, Fact> facts;
    private final List<String> paths;

    Run(RepoBuild repo, Validity validity, Map<String, Fact> facts) {
      this.repo = repo;
      this.validity = validity;
      this.facts = facts;
      this.paths =
          GitFiles.list(repo, "").stream()
              .filter(p -> SKIPPED.stream().noneMatch(s -> p.startsWith(s) || p.contains("/" + s)))
              .toList();
    }

    void run() {
      List<String> found = new ArrayList<>();
      List<String> manifests =
          paths.stream()
              .filter(p -> p.equals("package.json") || p.endsWith("/package.json"))
              .sorted(Comparator.comparingInt((String p) -> p.split("/").length))
              .toList();
      for (String manifest : manifests) {
        String directory =
            manifest.contains("/") ? manifest.substring(0, manifest.lastIndexOf('/')) : "";
        // A manifest inside a frontend found already is a part of it (a workspace package).
        if (found.stream().anyMatch(d -> d.isEmpty() || directory.startsWith(d + "/"))) {
          continue;
        }
        Source source = read(manifest);
        if (source == null) {
          continue;
        }
        JsonNode json;
        try {
          json = FactJson.parse(source.text());
        } catch (RuntimeException e) {
          continue;
        }
        if (frontend(directory, source, json)) {
          found.add(directory);
        }
      }
    }

    private Source read(String path) {
      byte[] bytes = GitFiles.read(repo, path, MAX_BYTES);
      if (bytes == null) {
        return null;
      }
      return new Source(path, new String(bytes, StandardCharsets.UTF_8), sha256(bytes));
    }

    private Evidence evidence(Source source, int line) {
      return new Evidence(
          repo.repo().name(),
          repo.commit(),
          source.path(),
          line > 0 ? new Evidence.LineRange(line, line) : null,
          source.hash());
    }

    private void add(
        EntityId subject,
        String predicate,
        String value,
        ObjectNode attributes,
        Evidence evidence,
        double confidence) {
      Fact fact =
          Fact.create(
              subject,
              predicate,
              FactObject.string(value),
              attributes,
              ORIGIN,
              evidence,
              confidence,
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }

    /** Records the frontend of a manifest, if it is one; whether it is. */
    private boolean frontend(String directory, Source manifest, JsonNode json) {
      Map<String, String> dependencies = new LinkedHashMap<>();
      for (String section : List.of("dependencies", "devDependencies", "peerDependencies")) {
        json.path(section)
            .properties()
            .forEach(e -> dependencies.putIfAbsent(e.getKey(), e.getValue().asString("")));
      }
      String[] framework = null;
      for (String[] candidate : FRAMEWORKS) {
        if (dependencies.containsKey(candidate[0])) {
          framework = candidate;
          break;
        }
      }
      if (framework == null) {
        return false;
      }
      String prefix = directory.isEmpty() ? "" : directory + "/";
      String packageName = json.path("name").asString("");
      String name =
          !unscoped(packageName).isBlank()
              ? unscoped(packageName)
              : directory.isEmpty()
                  ? repo.repo().name()
                  : directory.substring(directory.lastIndexOf('/') + 1);
      EntityId subject =
          new EntityId(FRONTEND, repo.repo().name() + (directory.isEmpty() ? "" : "/" + directory));

      ObjectNode attributes = FactJson.newObject();
      attributes.put("name", name);
      attributes.put("directory", directory);
      if (!packageName.isBlank()) {
        attributes.put("package", packageName);
      }
      attributes.put("framework", framework[2]);
      String version = version(dependencies.get(framework[0]));
      if (version != null) {
        attributes.put("version", version);
      }
      List<String> libraries = new ArrayList<>();
      for (String[] library : LIBRARIES) {
        if (dependencies.containsKey(library[0]) && !library[1].equals(framework[2])) {
          libraries.add(library[1]);
        }
      }
      if (!libraries.isEmpty()) {
        ArrayNode array = attributes.putArray("libraries");
        libraries.forEach(array::add);
      }
      String[] served = dockerfile(prefix);
      String server = null;
      if (served != null) {
        attributes.put("image", served[0]);
        Image image = Image.of(served[0]);
        if (image != null) {
          server = image.product();
          attributes.put("server", server);
        }
        if (!served[1].isEmpty()) {
          ArrayNode ports = attributes.putArray("ports");
          for (String port : served[1].trim().split("\\s+")) {
            ports.add(port);
          }
        }
      }
      StringBuilder technology = new StringBuilder(framework[2]);
      if (version != null) {
        technology.append(' ').append(version);
      }
      libraries.stream()
          .filter(BUILD_TOOLS::contains)
          .forEach(tool -> technology.append(" · ").append(tool));
      if (server != null) {
        technology.append(" · ").append(server);
      }
      attributes.put("technology", technology.toString());
      add(
          subject,
          APP,
          framework[1],
          attributes,
          evidence(manifest, dependencyLine(manifest.text(), framework[0])),
          1.0);

      List<Environment> environments = environments(prefix);
      calls(subject, prefix, environments);
      proxies(subject, prefix, manifest, json);
      code(subject, prefix);
      return true;
    }

    private static int dependencyLine(String text, String dependency) {
      int at = text.indexOf("\"" + dependency + "\"");
      return at < 0 ? 0 : lineOf(text, at);
    }

    /**
     * The last stage's base image of the Dockerfile in the frontend's folder and the ports it
     * exposes, or null when there is none. A stage built on an earlier stage takes its image.
     */
    private String[] dockerfile(String prefix) {
      if (!paths.contains(prefix + "Dockerfile")) {
        return null;
      }
      Source source = read(prefix + "Dockerfile");
      if (source == null) {
        return null;
      }
      Map<String, String> stages = new LinkedHashMap<>();
      String image = null;
      StringBuilder exposed = new StringBuilder();
      for (String line : source.text().split("\n", -1)) {
        Matcher from = FROM.matcher(line);
        if (from.find()) {
          String base = from.group(1);
          image = stages.getOrDefault(base.toLowerCase(Locale.ROOT), base);
          if (from.group(2) != null) {
            stages.put(from.group(2).toLowerCase(Locale.ROOT), image);
          }
          exposed.setLength(0);
          continue;
        }
        Matcher expose = EXPOSE.matcher(line);
        if (expose.find()) {
          exposed.append(' ').append(expose.group(1).trim());
        }
      }
      return image == null ? null : new String[] {image, exposed.toString().trim()};
    }

    // Environments

    /**
     * The frontend's environments, the default build's first: Angular's environment files (the one
     * the build's default configuration puts in place of {@code environment.ts}), or the dotenv
     * files of Vite and Create React App ({@code .env.production} over {@code .env}).
     */
    private List<Environment> environments(String prefix) {
      List<Environment> result = new ArrayList<>();
      Map<String, Map<String, String>> angular = new LinkedHashMap<>();
      Map<String, Map<String, Where>> angularWhere = new LinkedHashMap<>();
      for (String path : paths) {
        if (path.startsWith(prefix) && ANGULAR_ENVIRONMENT.matcher(path).matches()) {
          Source source = read(path);
          if (source != null) {
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, Where> where = new LinkedHashMap<>();
            Matcher m = ENVIRONMENT_VALUE.matcher(source.text());
            while (m.find()) {
              if (values.putIfAbsent(m.group(1), m.group(3)) == null) {
                where.put(m.group(1), new Where(source, lineOf(source.text(), m.start())));
              }
            }
            angular.put(path, values);
            angularWhere.put(path, where);
          }
        }
      }
      if (!angular.isEmpty()) {
        String chosen = angularDefault(prefix, angular.keySet());
        if (chosen != null) {
          result.add(new Environment(chosen, angular.get(chosen), angularWhere.get(chosen)));
        }
        angular.forEach(
            (path, values) -> {
              if (!path.equals(chosen)) {
                result.add(new Environment(path, values, angularWhere.get(path)));
              }
            });
        return result;
      }
      Map<String, Map<String, String>> dotenv = new LinkedHashMap<>();
      Map<String, Map<String, Where>> dotenvWhere = new LinkedHashMap<>();
      for (String path : paths) {
        if (path.startsWith(prefix)
            && path.indexOf('/', prefix.length()) < 0
            && DOTENV.matcher(path.substring(prefix.length())).matches()) {
          Source source = read(path);
          if (source != null) {
            Map<String, String> values = new LinkedHashMap<>();
            Map<String, Where> where = new LinkedHashMap<>();
            String[] lines = source.text().split("\n", -1);
            for (int n = 0; n < lines.length; n++) {
              Matcher m = DOTENV_LINE.matcher(lines[n]);
              if (m.matches() && !lines[n].trim().startsWith("#")) {
                values.put(m.group(1), m.group(2).replaceAll("^(['\"])(.*)\\1$", "$2"));
                where.put(m.group(1), new Where(source, n + 1));
              }
            }
            dotenv.put(path.substring(prefix.length()), values);
            dotenvWhere.put(path.substring(prefix.length()), where);
          }
        }
      }
      if (dotenv.isEmpty()) {
        return result;
      }
      Map<String, String> standard = new LinkedHashMap<>(dotenv.getOrDefault(".env", Map.of()));
      standard.putAll(dotenv.getOrDefault(".env.production", Map.of()));
      Map<String, Where> standardWhere =
          new LinkedHashMap<>(dotenvWhere.getOrDefault(".env", Map.of()));
      standardWhere.putAll(dotenvWhere.getOrDefault(".env.production", Map.of()));
      result.add(new Environment(prefix + ".env", standard, standardWhere));
      dotenv.forEach(
          (file, values) -> {
            if (!file.equals(".env") && !file.equals(".env.production")) {
              Map<String, String> merged =
                  new LinkedHashMap<>(dotenv.getOrDefault(".env", Map.of()));
              merged.putAll(values);
              Map<String, Where> mergedWhere =
                  new LinkedHashMap<>(dotenvWhere.getOrDefault(".env", Map.of()));
              mergedWhere.putAll(dotenvWhere.get(file));
              result.add(new Environment(prefix + file, merged, mergedWhere));
            }
          });
      return result;
    }

    /**
     * The environment file the Angular build uses by default: the replacement of {@code
     * environment.ts} in the configuration the folder's Dockerfile builds ({@code --configuration
     * x}), else in the default configuration of {@code angular.json}, else {@code environment.ts}
     * itself, else the first.
     */
    private String angularDefault(String prefix, Set<String> files) {
      String base =
          files.stream().filter(p -> p.endsWith("/environment.ts")).findFirst().orElse(null);
      Source config =
          paths.contains(prefix + "angular.json") ? read(prefix + "angular.json") : null;
      String built = null;
      Source dockerfile =
          paths.contains(prefix + "Dockerfile") ? read(prefix + "Dockerfile") : null;
      if (dockerfile != null) {
        Matcher m = BUILD_CONFIGURATION.matcher(dockerfile.text());
        if (m.find()) {
          built = m.group(1);
        }
      }
      if (config != null) {
        try {
          for (JsonNode project : FactJson.parse(config.text()).path("projects")) {
            JsonNode build = project.path("architect").path("build");
            String configuration =
                built != null ? built : build.path("defaultConfiguration").asString("");
            for (JsonNode replacement :
                build.path("configurations").path(configuration).path("fileReplacements")) {
              String replace = replacement.path("replace").asString("");
              String with = prefix + replacement.path("with").asString("");
              if (replace.endsWith("environment.ts") && files.contains(with)) {
                return with;
              }
            }
            break;
          }
        } catch (RuntimeException e) {
          // An angular.json Lantern cannot read leaves the base file.
        }
      }
      return base != null ? base : files.iterator().next();
    }

    // Auth, Angular pages and services, and resources

    /**
     * Whether a path is a source file of the frontend: not a test, a build output or a vendor's.
     */
    private boolean source(String path, String prefix) {
      return path.startsWith(prefix)
          && !path.endsWith(".min.js")
          && !path.endsWith(".min.css")
          && !path.contains("/vendor/")
          && !TEST_FILE.matcher(path).matches();
    }

    private void code(EntityId subject, String prefix) {
      List<Source> scripts = new ArrayList<>();
      List<Source> markup = new ArrayList<>();
      for (String path : paths) {
        if (!source(path, prefix)) {
          continue;
        }
        boolean bundled = path.contains("/assets/") || path.contains("/public/");
        boolean script = CODE_FILE.matcher(path).matches() && !CONFIG_FILE.matcher(path).matches();
        boolean styled =
            MARKUP_FILE.matcher(path).matches() && (!bundled || path.endsWith("/index.html"));
        script = script && !bundled;
        if (!script && !styled) {
          continue;
        }
        Source source = read(path);
        if (source != null) {
          (script ? scripts : markup).add(source);
        }
      }
      Map<String, Set<String>> routes = new LinkedHashMap<>();
      for (Source source : scripts) {
        FrontendCode.routes(uncommented(source.text()))
            .forEach((k, v) -> routes.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(v));
      }
      for (Source source : scripts) {
        String text = uncommented(source.text());
        for (FrontendCode.Part part : FrontendCode.auth(text)) {
          ObjectNode attributes = attributes(part);
          Set<String> guarded = routes.get("guard:" + part.name());
          if (part.kind().equals("guard") && guarded != null) {
            ArrayNode array = attributes.putArray("routes");
            guarded.forEach(array::add);
          }
          add(subject, AUTH, part.kind(), attributes, evidence(source, text, part), 0.9);
        }
        for (FrontendCode.Part part : FrontendCode.components(text)) {
          ObjectNode attributes = attributes(part);
          Set<String> paths = routes.get(part.name());
          if (paths != null) {
            ArrayNode array = attributes.putArray("routes");
            paths.forEach(array::add);
          }
          add(subject, COMPONENT, part.kind(), attributes, evidence(source, text, part), 0.9);
        }
      }
      List<Source> all = new ArrayList<>(scripts);
      all.addAll(markup);
      int count = 0;
      for (Source source : all) {
        // HTML comments also occur in a component's inline template.
        String text = withoutHtmlComments(uncommented(source.text()));
        int[] lines = lineStarts(text);
        for (FrontendCode.Resource resource : FrontendCode.resources(text)) {
          if (count++ >= MAX_CALLS) {
            return;
          }
          ObjectNode attributes = FactJson.newObject();
          attributes.put("host", resource.host());
          attributes.put("tag", resource.tag());
          if (resource.perItem()) {
            attributes.put("perItem", true);
          }
          add(
              subject,
              RESOURCE_URL,
              resource.url(),
              attributes,
              evidence(source, line(lines, resource.offset())),
              0.9);
        }
      }
    }

    /** The text with its HTML comments blanked out, line breaks and offsets kept. */
    private static String withoutHtmlComments(String text) {
      StringBuilder blanked = new StringBuilder(text);
      int at = text.indexOf("<!--");
      while (at >= 0) {
        int end = text.indexOf("-->", at + 4);
        int stop = end < 0 ? text.length() : end + 3;
        blank(blanked, at, stop);
        at = end < 0 ? -1 : text.indexOf("<!--", stop);
      }
      return blanked.toString();
    }

    /** The offset each line of a text starts at. */
    private static int[] lineStarts(String text) {
      List<Integer> starts = new ArrayList<>(List.of(0));
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\n') {
          starts.add(i + 1);
        }
      }
      return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The 1-based line of an offset, from {@link #lineStarts}. */
    private static int line(int[] starts, int offset) {
      int at = java.util.Arrays.binarySearch(starts, offset);
      return at >= 0 ? at + 1 : -at - 1;
    }

    private static ObjectNode attributes(FrontendCode.Part part) {
      ObjectNode attributes = FactJson.newObject();
      part.attributes()
          .forEach(
              (key, value) -> {
                if (value instanceof List<?> list) {
                  ArrayNode array = attributes.putArray(key);
                  list.forEach(item -> array.add(item.toString()));
                } else if (value instanceof Boolean b) {
                  attributes.put(key, b);
                } else {
                  attributes.put(key, value.toString());
                }
              });
      return attributes;
    }

    /** The evidence of a part of a file: the lines from its start to its end. */
    private Evidence evidence(Source source, String text, FrontendCode.Part part) {
      int start = lineOf(text, part.start());
      int end = lineOf(text, Math.max(part.start(), part.end() - 1));
      return new Evidence(
          repo.repo().name(),
          repo.commit(),
          source.path(),
          new Evidence.LineRange(start, Math.max(start, end)),
          source.hash());
    }

    // Calls

    private void calls(EntityId subject, String prefix, List<Environment> environments) {
      int count = 0;
      for (String path : paths) {
        if (!path.startsWith(prefix)
            || !CODE_FILE.matcher(path).matches()
            || path.endsWith(".min.js")
            || path.contains("/assets/")
            || path.contains("/public/")
            || path.contains("/vendor/")
            || TEST_FILE.matcher(path).matches()
            || CONFIG_FILE.matcher(path).matches()) {
          continue;
        }
        Source source = read(path);
        if (source == null) {
          continue;
        }
        count += calls(subject, source, environments, MAX_CALLS - count);
        if (count >= MAX_CALLS) {
          return;
        }
      }
    }

    /**
     * A call site: where it is, its method (null for a WebSocket), its URL expression, and whether
     * it navigates the browser rather than requesting from the page.
     */
    private record Site(
        int offset, String method, String expression, String protocol, boolean navigation) {

      Site(int offset, String method, String expression, String protocol) {
        this(offset, method, expression, protocol, false);
      }
    }

    private int calls(
        EntityId subject, Source source, List<Environment> environments, int remaining) {
      String text = uncommented(source.text());
      Set<String> clients = new LinkedHashSet<>(List.of("axios"));
      Matcher m = HTTP_CLIENT.matcher(text);
      while (m.find()) {
        clients.add(m.group(1) != null ? m.group(1) : m.group(2));
      }
      m = AXIOS_INSTANCE.matcher(text);
      Map<String, String> bases = new LinkedHashMap<>();
      while (m.find()) {
        clients.add(m.group(1));
        Matcher base = BASE_URL.matcher(argument(text, m.end()));
        if (base.find()) {
          bases.put(m.group(1), base.group(1).trim());
        }
      }
      Map<String, String> constants = constants(text);
      List<Site> sites = new ArrayList<>();
      m = CLIENT_CALL.matcher(text);
      while (m.find()) {
        String client = m.group(1);
        if (!clients.contains(client)) {
          continue;
        }
        String method = m.group(2).toUpperCase(Locale.ROOT);
        int urlAt = m.end();
        if (method.equals("REQUEST")) {
          // Angular's http.request('GET', url); axios.request({...}) is not followed.
          String literal = argument(text, urlAt).replaceAll("^['\"`]|['\"`]$", "");
          int comma = end(text, urlAt);
          if (!literal.matches("[A-Za-z]+")
              || comma >= text.length()
              || text.charAt(comma) != ',') {
            continue;
          }
          method = literal.toUpperCase(Locale.ROOT);
          urlAt = comma + 1;
        }
        String expression = argument(text, urlAt);
        if (bases.containsKey(client)) {
          expression = bases.get(client) + " + " + expression;
        }
        sites.add(new Site(m.start(), method, expression, HTTP));
      }
      m = FETCH.matcher(text);
      while (m.find()) {
        String call = text.substring(m.end(), closingCall(text, m.end()));
        Matcher method = FETCH_METHOD.matcher(call);
        sites.add(
            new Site(
                m.start(),
                method.find() ? method.group(1).toUpperCase(Locale.ROOT) : "GET",
                argument(text, m.end()),
                HTTP));
      }
      m = SOCKET.matcher(text);
      while (m.find()) {
        // SockJS starts with HTTP, but it is the socket the page opens.
        sites.add(new Site(m.start(), null, argument(text, m.end()), WEBSOCKET));
      }
      m = BROKER_URL.matcher(text);
      while (m.find()) {
        sites.add(new Site(m.start(), null, argument(text, m.end()), WEBSOCKET));
      }
      m = NAVIGATION.matcher(text);
      while (m.find()) {
        String expression = argument(text, m.end());
        if (text.charAt(m.end() - 1) == '=') {
          // An assignment may end at its line, without a semicolon.
          int newline = expression.indexOf('\n');
          if (newline >= 0) {
            expression = expression.substring(0, newline).trim();
          }
        }
        sites.add(new Site(m.start(), "GET", expression, HTTP, true));
      }
      sites.sort(Comparator.comparingInt(Site::offset));
      int added = 0;
      for (Site site : sites) {
        if (added >= remaining || site.expression().isEmpty()) {
          continue;
        }
        if (call(subject, source, site, constants, environments)) {
          added++;
        }
      }
      return added;
    }

    /** The index of the parenthesis closing a call whose arguments start at {@code from}. */
    private static int closingCall(String text, int from) {
      int close = closing(text, from - 1);
      return close < 0 ? text.length() : close;
    }

    /** The line of each constant and field of a file that {@link #constants} reads. */
    private static Map<String, Integer> constantLines(String text) {
      Map<String, Integer> lines = new LinkedHashMap<>();
      String[] all = text.split("\n", -1);
      for (int n = 0; n < all.length; n++) {
        Matcher m = DECLARATION.matcher(all[n]);
        if (m.matches()) {
          lines.putIfAbsent(m.group(1), n + 1);
        }
      }
      return lines;
    }

    /** The constants and fields of a file whose value is an expression on one line. */
    private static Map<String, String> constants(String text) {
      Map<String, String> constants = new LinkedHashMap<>();
      for (String line : text.split("\n", -1)) {
        Matcher m = DECLARATION.matcher(line);
        if (m.matches()) {
          constants.putIfAbsent(m.group(1), m.group(2));
        }
      }
      return constants;
    }

    private boolean call(
        EntityId subject,
        Source source,
        Site site,
        Map<String, String> constants,
        List<Environment> environments) {
      List<Environment> views =
          environments.isEmpty() ? List.of(new Environment(null, Map.of())) : environments;
      Set<String> unresolved = new TreeSet<>();
      String url = null;
      Set<String> alternates = new LinkedHashSet<>();
      Set<String> constantsUsed = new LinkedHashSet<>();
      // Each environment entry used, by where it is, with its name; a NUL marks one of another
      // build than the default.
      Map<Where, String> environmentUsed = new LinkedHashMap<>();
      for (int i = 0; i < views.size(); i++) {
        Set<String> missing = new TreeSet<>();
        Set<String> usedHere = new LinkedHashSet<>();
        String resolved =
            url(evaluate(site.expression(), constants, views.get(i), 0, missing, usedHere));
        for (String name : usedHere) {
          if (name.startsWith("e:")) {
            Where where = views.get(i).where().get(name.substring(2));
            if (where != null) {
              environmentUsed.putIfAbsent(where, name.substring(2) + (i == 0 ? "" : "\u0000"));
            }
          } else {
            constantsUsed.add(name.substring(2));
          }
        }
        if (i == 0) {
          url = resolved;
          unresolved.addAll(missing);
        } else if (!resolved.equals(url)) {
          alternates.add(resolved);
        }
      }
      // A URL that is nothing but an unknown value says nothing about where the call goes.
      if (url == null || url.matches("\\{[^}]*}") || url.equals("/")) {
        return false;
      }
      ObjectNode attributes = FactJson.newObject();
      if (site.method() != null) {
        attributes.put("httpMethod", site.method());
      }
      attributes.put("url", url);
      if (!alternates.isEmpty()) {
        ArrayNode array = attributes.putArray("alternates");
        alternates.forEach(array::add);
      }
      attributes.put("protocol", site.protocol());
      if (site.navigation()) {
        attributes.put("navigation", true);
      }
      String written = site.expression().replaceAll("\\s+", " ");
      attributes.put("written", written.length() > 200 ? written.substring(0, 200) : written);
      if (!unresolved.isEmpty()) {
        ArrayNode array = attributes.putArray("unresolved");
        unresolved.forEach(array::add);
      }
      add(
          subject,
          CALL,
          (site.method() == null ? WEBSOCKET : site.method()) + " " + url,
          attributes,
          evidence(source, lineOf(source.text(), site.offset())),
          unresolved.isEmpty() ? 0.9 : 0.7);
      Map<String, Integer> lines = constantLines(uncommented(source.text()));
      for (String name : constantsUsed) {
        Integer line = lines.get(name);
        // A constant whose own value is unknown says nothing about where the call goes.
        String own =
            line == null || !constants.containsKey(name)
                ? ""
                : evaluate(
                    constants.get(name),
                    constants,
                    views.get(0),
                    0,
                    new TreeSet<>(),
                    new TreeSet<>());
        if (!own.replaceAll("\\{[^}]*}", "").isBlank()) {
          ObjectNode declared = FactJson.newObject();
          declared.put("name", name);
          declared.put("source", "constant");
          String value = constants.get(name).trim().replaceAll("^(['\"`])(.*)\\1$", "$2");
          add(subject, URL, value, declared, evidence(source, line), 0.9);
        }
      }
      environmentUsed.forEach(
          (where, name) -> {
            boolean other = name.endsWith("\u0000");
            String key = other ? name.substring(0, name.length() - 1) : name;
            ObjectNode declared = FactJson.newObject();
            declared.put("name", key);
            declared.put("source", "environment");
            if (!other) {
              declared.put("defaultBuild", true);
            }
            String value = environmentValue(where, key);
            add(subject, URL, value, declared, evidence(where.source(), where.line()), 0.9);
          });
      return true;
    }

    /** The value of an environment entry as its file declares it. */
    private static String environmentValue(Where where, String key) {
      String line = where.source().text().split("\n", -1)[where.line() - 1];
      Matcher m = ENVIRONMENT_VALUE.matcher(line);
      while (m.find()) {
        if (m.group(1).equals(key)) {
          return m.group(3);
        }
      }
      Matcher dotenv = DOTENV_LINE.matcher(line);
      return dotenv.matches() ? dotenv.group(2).replaceAll("^(['\"])(.*)\\1$", "$2") : line.trim();
    }

    /**
     * The text an expression stands for: string and template literals as written, constants of the
     * file and environment values filled in, anything else as {@code {name}}. The constants and
     * environment entries it uses go to {@code used}, as {@code c:<name>} and {@code e:<name>}.
     */
    private static String evaluate(
        String expression,
        Map<String, String> constants,
        Environment environment,
        int depth,
        Set<String> unresolved,
        Set<String> used) {
      StringBuilder text = new StringBuilder();
      for (String part : concatenated(expression)) {
        text.append(term(part, constants, environment, depth, unresolved, used));
      }
      return text.toString();
    }

    private static String term(
        String part,
        Map<String, String> constants,
        Environment environment,
        int depth,
        Set<String> unresolved,
        Set<String> used) {
      String term = part.trim();
      while (term.startsWith("(") && term.endsWith(")")) {
        term = term.substring(1, term.length() - 1).trim();
      }
      if (term.isEmpty()) {
        return "";
      }
      char first = term.charAt(0);
      if ((first == '\'' || first == '"') && term.length() >= 2 && term.endsWith("" + first)) {
        return term.substring(1, term.length() - 1);
      }
      if (first == '`' && term.length() >= 2 && term.endsWith("`")) {
        return template(
            term.substring(1, term.length() - 1), constants, environment, depth, unresolved, used);
      }
      // A ?? or || fallback: the first value that the code names.
      String name = term.replaceAll("\\s*(\\?\\?|\\|\\|).*$", "").replaceAll("[!?]$", "");
      if (name.matches("[A-Za-z_$][\\w$]*(\\s*\\??\\.\\s*[A-Za-z_$][\\w$]*)*")) {
        String[] segments = name.replaceAll("\\s|\\?", "").split("\\.");
        String last = segments[segments.length - 1];
        boolean fromEnvironment =
            (segments.length == 2 && segments[0].toLowerCase(Locale.ROOT).endsWith("environment"))
                || name.replaceAll("\\s", "").startsWith("import.meta.env.")
                || name.replaceAll("\\s", "").startsWith("process.env.");
        if (fromEnvironment) {
          String value = environment.values().get(last);
          if (value != null) {
            used.add("e:" + last);
            return value;
          }
          unresolved.add(last);
          return "{" + last + "}";
        }
        String constant =
            segments.length == 1 || (segments.length == 2 && segments[0].equals("this"))
                ? constants.get(last)
                : null;
        if (constant != null && depth < 4) {
          used.add("c:" + last);
          String value = evaluate(constant, constants, environment, depth + 1, unresolved, used);
          return value.equals("{…}") ? "{" + last + "}" : value;
        }
        return "{" + last + "}";
      }
      return "{…}";
    }

    private static String template(
        String body,
        Map<String, String> constants,
        Environment environment,
        int depth,
        Set<String> unresolved,
        Set<String> used) {
      StringBuilder text = new StringBuilder();
      int i = 0;
      while (i < body.length()) {
        int start = body.indexOf("${", i);
        if (start < 0) {
          text.append(body.substring(i));
          break;
        }
        text.append(body, i, start);
        int level = 0;
        int end = -1;
        for (int j = start + 2; j < body.length(); j++) {
          char c = body.charAt(j);
          if (c == '{') {
            level++;
          } else if (c == '}') {
            if (level == 0) {
              end = j;
              break;
            }
            level--;
          }
        }
        if (end < 0) {
          text.append(body.substring(start));
          break;
        }
        text.append(
            evaluate(
                body.substring(start + 2, end), constants, environment, depth, unresolved, used));
        i = end + 1;
      }
      return text.toString();
    }

    // Proxies

    private void proxies(EntityId subject, String prefix, Source manifest, JsonNode json) {
      for (String path : paths) {
        if (path.startsWith(prefix)
            && path.substring(prefix.length())
                .matches("(?i)([^/]+/)*[^/]*nginx[^/]*|.*\\.conf(\\.template)?")
            && !path.endsWith(".json")) {
          nginx(subject, path);
        }
      }
      angularProxy(subject, prefix);
      for (String config :
          List.of("vite.config.ts", "vite.config.js", "vite.config.mjs", "vite.config.mts")) {
        if (paths.contains(prefix + config)) {
          vite(subject, prefix + config);
        }
      }
      String proxy = json.path("proxy").asString("");
      if (proxy.matches("https?://.+")) {
        proxy(
            subject,
            "/",
            proxy,
            DEV_SERVER,
            evidence(manifest, dependencyLine(manifest.text(), "proxy")));
      }
    }

    private void proxy(
        EntityId subject, String prefix, String target, String source, Evidence evidence) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("target", target);
      attributes.put("source", source);
      add(subject, PROXY, prefix, attributes, evidence, 0.9);
    }

    /** The prefix locations of an nginx config that pass requests on with {@code proxy_pass}. */
    private void nginx(EntityId subject, String path) {
      Source source = read(path);
      if (source == null || !source.text().contains("proxy_pass")) {
        return;
      }
      String text = source.text().replaceAll("(?m)#.*$", "");
      Matcher location = LOCATION.matcher(text);
      while (location.find()) {
        int close = closing(text, location.end() - 1);
        String block = text.substring(location.end(), close < 0 ? text.length() : close);
        Matcher pass = PROXY_PASS.matcher(block);
        if (pass.find() && !pass.group(1).contains("$")) {
          proxy(
              subject,
              location.group(1),
              pass.group(1),
              NGINX,
              evidence(source, lineOf(text, location.end() + block.indexOf(pass.group()))));
        }
      }
    }

    /** The development server's proxy of an Angular frontend: its proxy config file. */
    private void angularProxy(EntityId subject, String prefix) {
      String file = prefix + "proxy.conf.json";
      Source config =
          paths.contains(prefix + "angular.json") ? read(prefix + "angular.json") : null;
      if (config != null) {
        try {
          for (JsonNode project : FactJson.parse(config.text()).path("projects")) {
            String named =
                project
                    .path("architect")
                    .path("serve")
                    .path("options")
                    .path("proxyConfig")
                    .asString("");
            if (!named.isEmpty()) {
              file = prefix + named.replaceFirst("^\\./", "");
            }
            break;
          }
        } catch (RuntimeException e) {
          // Then the conventional file.
        }
      }
      if (!file.endsWith(".json") || !paths.contains(file)) {
        return;
      }
      Source source = read(file);
      if (source == null) {
        return;
      }
      JsonNode json;
      try {
        json = FactJson.parse(source.text());
      } catch (RuntimeException e) {
        return;
      }
      json.properties()
          .forEach(
              e -> {
                String target =
                    e.getValue().isString()
                        ? e.getValue().asString("")
                        : e.getValue().path("target").asString("");
                String key = e.getKey().replaceAll("/\\*+$", "");
                if (target.matches("(https?|wss?)://.+") && key.startsWith("/")) {
                  proxy(
                      subject,
                      key,
                      target,
                      DEV_SERVER,
                      evidence(source, dependencyLine(source.text(), e.getKey())));
                }
              });
    }

    /** The {@code server.proxy} entries of a Vite config. */
    private void vite(EntityId subject, String path) {
      Source source = read(path);
      if (source == null) {
        return;
      }
      Matcher block = VITE_PROXY.matcher(source.text());
      if (!block.find()) {
        return;
      }
      int close = closing(source.text(), block.end() - 1);
      String entries =
          source.text().substring(block.end(), close < 0 ? source.text().length() : close);
      Matcher entry = VITE_ENTRY.matcher(entries);
      while (entry.find()) {
        String target = entry.group(2) != null ? entry.group(2) : entry.group(3);
        proxy(
            subject,
            entry.group(1),
            target,
            DEV_SERVER,
            evidence(source, lineOf(source.text(), block.end() + entry.start())));
      }
    }
  }
}
