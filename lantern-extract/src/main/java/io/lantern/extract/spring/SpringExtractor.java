package io.lantern.extract.spring;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ModuleConfig;
import io.lantern.extract.config.Resolution;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure;
import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.Location;
import io.lantern.extract.java.ModuleStructure.Member;
import io.lantern.extract.java.ModuleStructure.Supertype;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns Spring and Jakarta annotations into facts: application main classes, bean classes by
 * stereotype, request handlers with their paths, scheduled jobs, {@code @Bean} methods and
 * {@code @Profile} gates. Annotations count directly or through meta-annotations, so a custom
 * annotation carrying {@code @RestController} makes a controller. See {@code
 * docs/adr/0033-spring-extractor.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}): through
 * javac with the build's classpath, or parsed in degraded mode, with the same confidence.
 * Placeholders in paths and schedules are resolved through the config extractor, per profile.
 */
public final class SpringExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "spring";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "spring-extractor";

  /** Attribute: the Spring stereotype of a bean class. */
  public static final String STEREOTYPE = "stereotype";

  /** Attribute: the type is an application's main class ({@code spring-boot} or {@code java}). */
  public static final String MAIN_CLASS = "main-class";

  /** Attribute: the parent of the entry point kinds. */
  public static final String ENTRY_POINT = "entry-point";

  /** Attribute: the method handles HTTP requests. */
  public static final String ENDPOINT = "endpoint";

  /** Attribute: the method runs on a schedule. */
  public static final String SCHEDULED = "scheduled";

  /** Attribute: the bean class or method is registered only under a profile expression. */
  public static final String PROFILE_GATE = "profile-gate";

  /** Attribute: the method defines a bean. */
  public static final String BEAN = "bean";

  /** Main class kinds. */
  public static final String SPRING_BOOT = "spring-boot";

  public static final String JAVA = "java";

  private static final String RESOURCE = "/vocabulary/spring-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String SPRING = "org.springframework.";
  private static final String WEB = SPRING + "web.bind.annotation.";
  private static final String REQUEST_MAPPING = WEB + "RequestMapping";
  private static final String RESPONSE_BODY = WEB + "ResponseBody";
  private static final String PROFILE = SPRING + "context.annotation.Profile";
  private static final String BEAN_ANNOTATION = SPRING + "context.annotation.Bean";
  private static final String SCHEDULED_ANNOTATION = SPRING + "scheduling.annotation.Scheduled";
  private static final String AUTO_CONFIGURATION =
      SPRING + "boot.autoconfigure.EnableAutoConfiguration";

  /** Stereotype annotations, the most specific first, with the stereotype each gives. */
  private static final Map<String, String> STEREOTYPES = stereotypes();

  /** Jakarta REST annotations, in the current and the older {@code javax} namespace. */
  private static final List<String> REST_PATHS = List.of("jakarta.ws.rs.Path", "javax.ws.rs.Path");

  private static final List<String> REST_HTTP_METHODS =
      List.of("jakarta.ws.rs.HttpMethod", "javax.ws.rs.HttpMethod");

  private static final String SPRING_MVC = "spring-mvc";
  private static final String JAKARTA_REST = "jakarta-rest";

  private static final String MAIN_SIGNATURE = "main(java.lang.String[])";

  private static Map<String, String> stereotypes() {
    Map<String, String> s = new LinkedHashMap<>();
    s.put(SPRING + "stereotype.Controller", "controller");
    s.put(SPRING + "stereotype.Service", "service");
    s.put(SPRING + "stereotype.Repository", "repository");
    s.put(SPRING + "context.annotation.Configuration", "configuration");
    s.put(SPRING + "stereotype.Component", "component");
    return s;
  }

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = SpringExtractor.class.getResourceAsStream(RESOURCE)) {
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
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws UncheckedIOException if the sources cannot be read or copied
   * @throws IllegalStateException if git fails or times out, or Lantern runs without a compiler
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(JavaExtractor.analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws IllegalStateException if git fails or times out while reading config
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Run run = new Run(code, Validity.from(snapshot));
    for (JavaAnalysis.Module module : code.modules()) {
      run.module(module);
    }
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  /** One extraction: the facts so far, and the config read for each repository. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Map<RepoBuild, List<ModuleConfig>> configs = new HashMap<>();

    private JavaAnalysis.Module module;
    private ModuleConfig config;

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
    }

    void module(JavaAnalysis.Module m) {
      this.module = m;
      this.config = config(m);
      for (TypeDecl type : m.structure().types()) {
        if (type.kind().equals(ModuleStructure.ANNOTATION)) {
          continue;
        }
        boolean bean = type.kind().equals(ModuleStructure.CLASS);
        if (bean) {
          mainClass(type);
          stereotype(type);
          endpoints(type);
        }
        profileGate(type.annotations(), typeId(type), type.where());
        for (Member member : type.members()) {
          if (!member.id().type().equals(ModuleStructure.METHOD)) {
            continue;
          }
          scheduled(member);
          if (beanMethod(member)) {
            profileGate(member.annotations(), member.id(), member.where());
          }
        }
      }
    }

    /**
     * The config that applies to a module's code: its own, or if it has none (a library), that of
     * the one module of the repository that has config, if there is exactly one.
     */
    private ModuleConfig config(JavaAnalysis.Module m) {
      List<ModuleConfig> all = configs.computeIfAbsent(m.repo(), ConfigExtractor::read);
      for (ModuleConfig c : all) {
        if (c.directory().equals(m.directory())) {
          return c;
        }
      }
      return all.size() == 1 ? all.getFirst() : null;
    }

    // -------------------------------------------------------------------------------------------
    // Main classes and stereotypes

    private void mainClass(TypeDecl type) {
      Member main = null;
      for (Member m : type.members()) {
        if (signature(m.id()).equals(MAIN_SIGNATURE)) {
          main = m;
        }
      }
      List<Annotation> boot = first(type.annotations(), AUTO_CONFIGURATION);
      if (boot == null && main == null) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      if (main != null) {
        attributes.put("main", main.id().toString());
      }
      Location where = boot != null ? boot.getFirst().where() : main.where();
      fact(typeId(type), MAIN_CLASS, boot != null ? SPRING_BOOT : JAVA, attributes, where);
    }

    private void stereotype(TypeDecl type) {
      for (Map.Entry<String, String> s : STEREOTYPES.entrySet()) {
        List<Annotation> path = first(type.annotations(), s.getKey());
        if (path != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("annotation", path.getFirst().type());
          if (path.size() > 1) {
            ArrayNode via = attributes.putArray("via");
            path.subList(1, path.size()).forEach(a -> via.add(a.type()));
          }
          if (s.getValue().equals("controller")
              && first(type.annotations(), RESPONSE_BODY) != null) {
            attributes.put("rest", true);
          }
          fact(typeId(type), STEREOTYPE, s.getValue(), attributes, path.getFirst().where());
          return;
        }
      }
      for (String restPath : REST_PATHS) {
        List<Annotation> path = first(type.annotations(), restPath);
        if (path != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("annotation", path.getFirst().type());
          if (path.size() > 1) {
            ArrayNode via = attributes.putArray("via");
            path.subList(1, path.size()).forEach(a -> via.add(a.type()));
          }
          attributes.put("rest", true);
          fact(typeId(type), STEREOTYPE, "controller", attributes, path.getFirst().where());
          return;
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Endpoints

    /** A request mapping: its paths and HTTP methods, and the annotation written. */
    private record Mapping(List<String> paths, List<String> methods, Annotation written) {}

    private void endpoints(TypeDecl type) {
      List<TypeDecl> lineage = lineage(type);
      boolean controller = false;
      for (TypeDecl t : lineage) {
        if (first(t.annotations(), SPRING + "stereotype.Controller") != null) {
          controller = true;
          break;
        }
      }
      if (controller) {
        Mapping base = null;
        for (TypeDecl t : lineage) {
          base = springMapping(t.annotations());
          if (base != null) {
            break;
          }
        }
        endpoints(type, lineage, base, SPRING_MVC);
      }
      Mapping restBase = null;
      for (TypeDecl t : lineage) {
        restBase = restPath(t.annotations());
        if (restBase != null) {
          break;
        }
      }
      if (restBase != null) {
        endpoints(type, lineage, restBase, JAKARTA_REST);
      }
    }

    /**
     * The endpoints of a controller's methods, its own and inherited from the analyzed sources: for
     * each method signature, the subject is the nearest declaration and the mapping the nearest one
     * written, as Spring and Jakarta REST inherit mappings from overridden methods.
     */
    private void endpoints(TypeDecl type, List<TypeDecl> lineage, Mapping base, String framework) {
      Map<String, Member> declared = new LinkedHashMap<>();
      Map<String, Mapping> mappings = new HashMap<>();
      for (TypeDecl t : lineage) {
        for (Member m : t.members()) {
          if (!m.id().type().equals(ModuleStructure.METHOD)) {
            continue;
          }
          String signature = signature(m.id());
          if (signature.startsWith(ModuleStructure.CONSTRUCTOR + "(")) {
            continue;
          }
          declared.putIfAbsent(signature, m);
          if (!mappings.containsKey(signature)) {
            Mapping mapping =
                framework.equals(SPRING_MVC)
                    ? springMapping(m.annotations())
                    : restMethod(m.annotations());
            if (mapping != null) {
              mappings.put(signature, mapping);
            }
          }
        }
      }
      List<String> basePaths = base == null || base.paths().isEmpty() ? List.of("") : base.paths();
      for (Map.Entry<String, Member> e : declared.entrySet()) {
        Mapping mapping = mappings.get(e.getKey());
        if (mapping == null) {
          continue;
        }
        Member method = e.getValue();
        List<String> paths = mapping.paths().isEmpty() ? List.of("") : mapping.paths();
        Set<String> methods = new LinkedHashSet<>();
        if (base != null && framework.equals(SPRING_MVC)) {
          methods.addAll(base.methods());
        }
        methods.addAll(mapping.methods());
        // No HTTP method: the mapping takes any.
        List<String> httpMethods =
            methods.isEmpty() ? Arrays.asList((String) null) : List.copyOf(methods);
        for (String basePath : basePaths) {
          for (String path : paths) {
            for (String httpMethod : httpMethods) {
              endpoint(type, method, mapping.written(), framework, basePath, path, httpMethod);
            }
          }
        }
      }
    }

    private void endpoint(
        TypeDecl controller,
        Member method,
        Annotation written,
        String framework,
        String basePath,
        String path,
        String httpMethod) {
      Resolved byDefault = path(null, basePath, path);
      for (String profile : views()) {
        Resolved value = profile == null ? byDefault : path(profile, basePath, path);
        if (profile != null && value.equals(byDefault)) {
          continue;
        }
        ObjectNode attributes = value.attributes(profile);
        if (httpMethod != null) {
          attributes.put("httpMethod", httpMethod);
        }
        attributes.put("path", value.value());
        attributes.put("controller", controller.binaryName());
        attributes.put("annotation", written.type());
        attributes.put("framework", framework);
        String object = httpMethod == null ? value.value() : httpMethod + " " + value.value();
        fact(method.id(), ENDPOINT, object, attributes, written.where());
      }
    }

    /** A controller's and a method's paths, each resolved, then joined. */
    private Resolved path(String profile, String basePath, String path) {
      Resolution base = resolve(profile, basePath);
      Resolution own = resolve(profile, path);
      SortedSet<String> unresolved = new TreeSet<>(base.unresolved());
      unresolved.addAll(own.unresolved());
      SortedSet<String> used = new TreeSet<>(base.used());
      used.addAll(own.used());
      return Resolved.of(
          join(base.value(), own.value()), joinWritten(basePath, path), unresolved, used);
    }

    /** A Spring request mapping written on an element, directly or through a composed one. */
    private Mapping springMapping(List<Annotation> annotations) {
      for (Annotation a : annotations) {
        List<Annotation> path = find(a, REQUEST_MAPPING, new HashSet<>());
        if (path == null) {
          continue;
        }
        // A composed mapping (@GetMapping) aliases its path to the request mapping's.
        Set<String> paths = new LinkedHashSet<>(a.values("path"));
        paths.addAll(a.values("value"));
        Set<String> methods = new LinkedHashSet<>();
        for (Annotation p : path) {
          methods.addAll(p.values("method"));
        }
        return new Mapping(List.copyOf(paths), List.copyOf(methods), a);
      }
      return null;
    }

    /** A Jakarta REST {@code @Path} written on a class. */
    private Mapping restPath(List<Annotation> annotations) {
      for (String type : REST_PATHS) {
        List<Annotation> path = first(annotations, type);
        if (path != null) {
          return new Mapping(path.getLast().values("value"), List.of(), path.getFirst());
        }
      }
      return null;
    }

    /** A Jakarta REST resource method: its HTTP method annotation, and its {@code @Path} if any. */
    private Mapping restMethod(List<Annotation> annotations) {
      for (Annotation a : annotations) {
        for (String httpMethod : REST_HTTP_METHODS) {
          List<Annotation> path = find(a, httpMethod, new HashSet<>());
          if (path != null && path.size() > 1) {
            Mapping own = restPath(annotations);
            return new Mapping(
                own == null ? List.of() : own.paths(), path.getLast().values("value"), a);
          }
        }
      }
      return null;
    }

    /** The type and its supertypes declared in the analyzed sources, nearest first. */
    private List<TypeDecl> lineage(TypeDecl type) {
      List<TypeDecl> lineage = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      Deque<TypeDecl> queue = new ArrayDeque<>(List.of(type));
      while (!queue.isEmpty()) {
        TypeDecl t = queue.removeFirst();
        if (!seen.add(t.binaryName())) {
          continue;
        }
        lineage.add(t);
        for (Supertype s : t.supertypes()) {
          TypeDecl supertype = code.type(s.binaryName());
          if (supertype != null) {
            queue.addLast(supertype);
          }
        }
      }
      return lineage;
    }

    // -------------------------------------------------------------------------------------------
    // Scheduled jobs, beans and profile gates

    private void scheduled(Member method) {
      for (Annotation a : method.annotations()) {
        List<Annotation> path = find(a, SCHEDULED_ANNOTATION, new HashSet<>());
        if (path == null) {
          continue;
        }
        Annotation scheduled = path.getLast();
        String trigger;
        String written;
        if (!scheduled.values("cron").isEmpty()) {
          trigger = "cron";
          written = scheduled.values("cron").getFirst();
        } else if (!scheduled.values("fixedDelayString").isEmpty()
            || !scheduled.values("fixedDelay").isEmpty()) {
          trigger = "fixed-delay";
          written = firstOf(scheduled, "fixedDelayString", "fixedDelay");
        } else if (!scheduled.values("fixedRateString").isEmpty()
            || !scheduled.values("fixedRate").isEmpty()) {
          trigger = "fixed-rate";
          written = firstOf(scheduled, "fixedRateString", "fixedRate");
        } else {
          continue;
        }
        Resolved byDefault = Resolved.of(resolve(null, written), written);
        for (String profile : views()) {
          Resolved value =
              profile == null ? byDefault : Resolved.of(resolve(profile, written), written);
          if (profile != null && value.equals(byDefault)) {
            continue;
          }
          ObjectNode attributes = value.attributes(profile);
          attributes.put("trigger", trigger);
          if (!scheduled.values("timeUnit").isEmpty() && !trigger.equals("cron")) {
            attributes.put("timeUnit", scheduled.values("timeUnit").getFirst());
          }
          fact(method.id(), SCHEDULED, value.value(), attributes, a.where());
        }
      }
    }

    /** Records a {@code @Bean} method; returns whether it is one. */
    private boolean beanMethod(Member method) {
      List<Annotation> path = first(method.annotations(), BEAN_ANNOTATION);
      if (path == null) {
        return false;
      }
      Annotation bean = path.getLast();
      String name = firstOf(bean, "name", "value");
      if (name == null) {
        String signature = signature(method.id());
        name = signature.substring(0, signature.indexOf('('));
      }
      fact(method.id(), BEAN, name, FactJson.newObject(), path.getFirst().where());
      return true;
    }

    private void profileGate(List<Annotation> annotations, EntityId subject, Location fallback) {
      List<Annotation> path = first(annotations, PROFILE);
      if (path == null || subject == null) {
        return;
      }
      List<String> profiles = path.getLast().values("value");
      if (profiles.isEmpty()) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      ArrayNode written = attributes.putArray("profiles");
      profiles.forEach(written::add);
      if (path.size() > 1) {
        attributes.put("annotation", path.getFirst().type());
      }
      String expression =
          profiles.size() == 1
              ? profiles.getFirst()
              : String.join(" | ", profiles.stream().map(p -> "(" + p + ")").toList());
      Location where = path.getFirst().where() != null ? path.getFirst().where() : fallback;
      fact(subject, PROFILE_GATE, expression, attributes, where);
    }

    // -------------------------------------------------------------------------------------------
    // Annotations

    /**
     * The first annotation written that is, or carries through meta-annotations, the given type:
     * the path from the one written to the one found, or null.
     */
    private List<Annotation> first(List<Annotation> annotations, String type) {
      for (Annotation a : annotations) {
        List<Annotation> path = find(a, type, new HashSet<>());
        if (path != null) {
          return path;
        }
      }
      return null;
    }

    /** The path from an annotation to one of the given type among its meta-annotations, or null. */
    private List<Annotation> find(Annotation a, String type, Set<String> seen) {
      if (a.type().equals(type)) {
        return List.of(a);
      }
      if (!seen.add(a.type())) {
        return null;
      }
      for (Annotation meta : code.metaAnnotations(a.type())) {
        List<Annotation> path = find(meta, type, seen);
        if (path != null) {
          List<Annotation> full = new ArrayList<>();
          full.add(a);
          full.addAll(path);
          return full;
        }
      }
      return null;
    }

    private static String firstOf(Annotation a, String... elements) {
      for (String e : elements) {
        if (!a.values(e).isEmpty()) {
          return a.values(e).getFirst();
        }
      }
      return null;
    }

    // -------------------------------------------------------------------------------------------
    // Values and facts

    /** The views to resolve placeholders in: the default view (null), then each profile. */
    private List<String> views() {
      List<String> views = new ArrayList<>();
      views.add(null);
      if (config != null) {
        views.addAll(config.profiles());
      }
      return views;
    }

    private Resolution resolve(String profile, String text) {
      return config == null
          ? ConfigExtractor.resolveWithoutConfig(text)
          : config.resolve(profile, text);
    }

    private void fact(
        EntityId subject, String predicate, String object, ObjectNode attributes, Location where) {
      if (subject == null) {
        return;
      }
      RepoBuild repo = module.repo();
      Evidence evidence =
          new Evidence(
              repo.repo().name(),
              repo.commit(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      Fact fact =
          Fact.create(
              subject,
              predicate,
              FactObject.string(object),
              attributes,
              ORIGIN,
              evidence,
              module.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }
  }

  /**
   * A value from code with its placeholders resolved, as it may be recorded: a value that a secret
   * was substituted into is kept as written instead, and marked masked.
   */
  private record Resolved(
      String value, String written, SortedSet<String> unresolved, boolean masked) {

    static Resolved of(Resolution r, String written) {
      return of(r.value(), written, r.unresolved(), r.used());
    }

    static Resolved of(
        String value, String written, SortedSet<String> unresolved, SortedSet<String> used) {
      if (used.stream().anyMatch(ConfigExtractor::isSecret)) {
        return new Resolved(written, written, new TreeSet<>(), true);
      }
      return new Resolved(value, written, new TreeSet<>(unresolved), false);
    }

    ObjectNode attributes(String profile) {
      ObjectNode attributes = FactJson.newObject();
      if (profile != null) {
        attributes.put("profile", profile);
      }
      if (masked) {
        attributes.put("masked", true);
        return attributes;
      }
      if (!written.equals(value)) {
        attributes.put("written", written);
      }
      if (!unresolved.isEmpty()) {
        ArrayNode keys = attributes.putArray("unresolved");
        unresolved.forEach(keys::add);
      }
      return attributes;
    }
  }

  /** A path joined as Spring and Jakarta REST join a class's and a method's: one slash between. */
  static String join(String base, String path) {
    String a = slashed(base);
    String b = slashed(path);
    if (a.endsWith("/") && !b.isEmpty()) {
      a = a.substring(0, a.length() - 1);
    }
    String joined = a + b;
    return joined.isEmpty() ? "/" : joined;
  }

  /**
   * Paths as written, joined the same way, except that one starting with a placeholder gets no
   * slash in front, since its value may start with one.
   */
  private static String joinWritten(String base, String path) {
    String a = base.strip();
    String b = path.strip();
    if (a.startsWith("${") || (a.isEmpty() && b.startsWith("${"))) {
      b = slashed(b);
      return a.endsWith("/") && !b.isEmpty() ? a.substring(0, a.length() - 1) + b : a + b;
    }
    return join(base, path);
  }

  private static String slashed(String path) {
    String p = path.strip();
    return p.isEmpty() || p.startsWith("/") ? p : "/" + p;
  }

  /** The part of a member's key after the type: its name and parameter types. */
  private static String signature(EntityId member) {
    String key = member.key();
    return key.substring(key.indexOf('#') + 1);
  }

  private static EntityId typeId(TypeDecl type) {
    try {
      return new EntityId(BuiltInTypes.TYPE, type.binaryName());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
