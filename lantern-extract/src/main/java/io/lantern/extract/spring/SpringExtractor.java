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
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.Resolution;
import io.lantern.extract.java.Expression;
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
 * docs/adr/0035-spring-extractor.md}.
 *
 * <p>It reads the code as the Java structure extractor read it ({@link JavaAnalysis}): through
 * javac with the build's classpath, or parsed in degraded mode, with the same confidence.
 * Placeholders in paths and schedules are resolved through the config extractor, per profile.
 */
public final class SpringExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "spring";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "3";

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

  /**
   * Attribute: the method runs as a background job that is not on a schedule of {@code trigger}'s
   * kinds: a JobRunr job or a Quartz job without a cron found.
   */
  public static final String BACKGROUND_JOB = "background-job";

  /** Attribute: the bean class or method is registered only under a profile expression. */
  public static final String PROFILE_GATE = "profile-gate";

  /** Attribute: the method defines a bean. */
  public static final String BEAN = "bean";

  /** Attribute: the type is an AspectJ aspect (an {@code @Aspect} class). */
  public static final String ASPECT = "aspect";

  /** Relationship: an aspect's advice selects the method (the aspect advises it). */
  public static final String ADVISES = "ADVISES";

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
  private static final String ASPECTJ = "org.aspectj.lang.annotation.";
  private static final String ASPECT_ANNOTATION = ASPECTJ + "Aspect";
  private static final String POINTCUT_ANNOTATION = ASPECTJ + "Pointcut";
  private static final Map<String, String> ADVICE =
      Map.of(
          ASPECTJ + "Before", "before",
          ASPECTJ + "After", "after",
          ASPECTJ + "AfterReturning", "after-returning",
          ASPECTJ + "AfterThrowing", "after-throwing",
          ASPECTJ + "Around", "around");
  private static final String JOBRUNR = "org.jobrunr.";
  private static final String JOBRUNR_HANDLER = JOBRUNR + "jobs.lambdas.JobRequestHandler";
  private static final String JOBRUNR_JOB = JOBRUNR + "jobs.annotations.Job";
  private static final String JOBRUNR_RECURRING = JOBRUNR + "jobs.annotations.Recurring";
  private static final Set<String> JOBRUNR_SCHEDULERS =
      Set.of(
          JOBRUNR + "scheduling.BackgroundJobRequest", JOBRUNR + "scheduling.JobRequestScheduler");
  private static final Set<String> JOBRUNR_SCHEDULING_METHODS =
      Set.of("enqueue", "schedule", "scheduleRecurrently", "create");
  private static final String QUARTZ_JOB = "org.quartz.Job";
  private static final String QUARTZ_BEAN = SPRING + "scheduling.quartz.QuartzJobBean";
  private static final String QUARTZ_CONTEXT = "org.quartz.JobExecutionContext";
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
    run.aspects();
    run.jobs();
    return run.facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }

  /** One extraction: the facts so far, and the config read for each repository. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final CodeConfig configs = new CodeConfig();

    private JavaAnalysis.Module module;
    private CodeConfig.View config;

    /** Job classes found in the modules read so far, and where jobs are scheduled. */
    private final List<JobClass> jobClasses = new ArrayList<>();

    private final List<Scheduling> schedulings = new ArrayList<>();

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
    }

    void module(JavaAnalysis.Module m) {
      this.module = m;
      this.config = configs.forModule(m.repo(), m.directory());
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
        if (bean) {
          jobClass(type);
        }
        for (Member member : type.members()) {
          if (!member.id().type().equals(ModuleStructure.METHOD)) {
            continue;
          }
          scheduled(member);
          jobAnnotations(type, member);
          if (beanMethod(member)) {
            profileGate(member.annotations(), member.id(), member.where());
          }
        }
      }
      for (ModuleStructure.Invocation i : m.structure().invocations()) {
        scheduling(i);
      }
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
      for (TypeDecl t : lineage(type)) {
        List<Annotation> path = restPathAnnotation(t.annotations());
        if (path != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("annotation", path.getFirst().type());
          if (path.size() > 1) {
            ArrayNode via = attributes.putArray("via");
            path.subList(1, path.size()).forEach(a -> via.add(a.type()));
          }
          attributes.put("rest", true);
          fact(
              typeId(type),
              STEREOTYPE,
              "controller",
              attributes,
              path.getFirst().where(),
              declaring(type, t));
          return;
        }
      }
    }

    // -------------------------------------------------------------------------------------------
    // Endpoints

    /** A request mapping: its paths and HTTP methods, and the annotation written. */
    private record Mapping(List<String> paths, List<String> methods, Annotation written) {}

    /** A mapping on a method, and the module whose code it is written in. */
    private record Mapped(Mapping mapping, JavaAnalysis.Module source) {}

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
      Map<String, Mapped> mappings = new HashMap<>();
      for (TypeDecl t : lineage) {
        JavaAnalysis.Module source = declaring(type, t);
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
              mappings.put(signature, new Mapped(mapping, source));
            }
          }
        }
      }
      List<String> basePaths = base == null || base.paths().isEmpty() ? List.of("") : base.paths();
      for (Map.Entry<String, Member> e : declared.entrySet()) {
        Mapped mapped = mappings.get(e.getKey());
        if (mapped == null) {
          continue;
        }
        Mapping mapping = mapped.mapping();
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
              endpoint(
                  type,
                  method,
                  mapping.written(),
                  mapped.source(),
                  framework,
                  basePath,
                  path,
                  httpMethod);
            }
          }
        }
      }
    }

    private void endpoint(
        TypeDecl controller,
        Member method,
        Annotation written,
        JavaAnalysis.Module source,
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
        fact(method.id(), ENDPOINT, object, attributes, written.where(), source);
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
      // As Spring finds them, the nearest first: one written directly before one carried by a
      // custom annotation.
      Annotation a = null;
      List<Annotation> path = null;
      for (Annotation candidate : annotations) {
        List<Annotation> found = code.annotationPath(candidate, REQUEST_MAPPING);
        if (found != null && (path == null || found.size() < path.size())) {
          a = candidate;
          path = found;
        }
      }
      if (path != null) {
        // A composed mapping (@GetMapping) aliases its path to the request mapping's; one that
        // sets none takes the nearest along its meta-annotations that does.
        Set<String> paths = new LinkedHashSet<>();
        for (Annotation p : path) {
          paths.addAll(p.values("path"));
          paths.addAll(p.values("value"));
          if (!paths.isEmpty()) {
            break;
          }
        }
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
          List<Annotation> path = code.annotationPath(a, httpMethod);
          if (path != null && path.size() > 1) {
            Mapping own = restPath(annotations);
            return new Mapping(
                own == null ? List.of() : own.paths(), path.getLast().values("value"), a);
          }
        }
      }
      return null;
    }

    /** The first Jakarta REST {@code @Path} written on an element: the meta-annotation path. */
    private List<Annotation> restPathAnnotation(List<Annotation> annotations) {
      for (String type : REST_PATHS) {
        List<Annotation> path = first(annotations, type);
        if (path != null) {
          return path;
        }
      }
      return null;
    }

    /**
     * The module whose code declares a type of a class's lineage: the module being read for the
     * class itself, else the one that declares the supertype.
     */
    private JavaAnalysis.Module declaring(TypeDecl type, TypeDecl t) {
      if (t == type) {
        return module;
      }
      JavaAnalysis.Module declaring = code.module(t.binaryName());
      return declaring != null ? declaring : module;
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
        List<Annotation> path = code.annotationPath(a, SCHEDULED_ANNOTATION);
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
        scheduledFact(method, trigger, written, a.where(), scheduled, Map.of());
      }
    }

    /**
     * Records a schedule written in an annotation, one fact for the default view and one per
     * profile whose value differs.
     */
    private void scheduledFact(
        Member method,
        String trigger,
        String written,
        Location where,
        Annotation annotation,
        Map<String, String> extra) {
      Resolved byDefault = Resolved.of(resolve(null, written), written);
      for (String profile : views()) {
        Resolved value =
            profile == null ? byDefault : Resolved.of(resolve(profile, written), written);
        if (profile != null && value.equals(byDefault)) {
          continue;
        }
        ObjectNode attributes = value.attributes(profile);
        attributes.put("trigger", trigger);
        if (!annotation.values("timeUnit").isEmpty() && !trigger.equals("cron")) {
          attributes.put("timeUnit", annotation.values("timeUnit").getFirst());
        }
        extra.forEach(attributes::put);
        fact(method.id(), SCHEDULED, value.value(), attributes, where);
      }
    }

    // -------------------------------------------------------------------------------------------
    // Jobs of JobRunr and Quartz

    /** A class whose method a job library runs: a JobRunr request handler or a Quartz job. */
    private record JobClass(
        JavaAnalysis.Module module,
        String framework,
        TypeDecl type,
        Member method,
        String request,
        String name,
        Location where) {}

    /** Where a job is scheduled: the calling method, the job's class and, if written, the cron. */
    private record Scheduling(
        JavaAnalysis.Module module, EntityId caller, String framework, String job, String cron) {}

    /** The nearest supertype named in the lineage of a type declared in the analyzed sources. */
    private Supertype supertype(TypeDecl type, String... names) {
      List<String> wanted = List.of(names);
      for (TypeDecl t : lineage(type)) {
        for (Supertype s : t.supertypes()) {
          if (wanted.contains(s.binaryName())) {
            return s;
          }
        }
      }
      return null;
    }

    private Member method(TypeDecl type, String name, String parameter) {
      for (Member m : type.members()) {
        String signature = m.id().type().equals(ModuleStructure.METHOD) ? signature(m.id()) : "";
        if (signature.startsWith(name + "(")
            && (parameter == null || signature.equals(name + "(" + parameter + ")"))) {
          return m;
        }
      }
      return null;
    }

    private void jobClass(TypeDecl type) {
      Supertype handler = supertype(type, JOBRUNR_HANDLER);
      if (handler != null && !handler.typeArguments().isEmpty()) {
        String request = handler.typeArguments().getFirst();
        Member run = method(type, "run", request);
        if (run != null) {
          List<Annotation> job = first(run.annotations(), JOBRUNR_JOB);
          String name = job == null ? null : firstOf(job.getLast(), "name");
          Location where = job == null ? run.where() : job.getFirst().where();
          jobClasses.add(
              new JobClass(
                  module,
                  "jobrunr",
                  type,
                  run,
                  request,
                  name,
                  where == null ? type.where() : where));
        }
      }
      Supertype quartz = supertype(type, QUARTZ_JOB, QUARTZ_BEAN);
      if (quartz != null) {
        boolean bean = quartz.binaryName().equals(QUARTZ_BEAN);
        Member execute = method(type, bean ? "executeInternal" : "execute", QUARTZ_CONTEXT);
        if (execute != null) {
          jobClasses.add(
              new JobClass(module, "quartz", type, execute, null, null, execute.where()));
        }
      }
    }

    /** {@code @Job} methods that are not request handlers, and {@code @Recurring} methods. */
    private void jobAnnotations(TypeDecl type, Member method) {
      boolean handler = jobClasses.stream().anyMatch(j -> j.type() == type && j.method() == method);
      List<Annotation> job = first(method.annotations(), JOBRUNR_JOB);
      List<Annotation> recurring = first(method.annotations(), JOBRUNR_RECURRING);
      if (job != null && !handler && recurring == null) {
        String name = firstOf(job.getLast(), "name");
        ObjectNode attributes = FactJson.newObject();
        attributes.put("framework", "jobrunr");
        attributes.put("kind", "job");
        fact(
            method.id(),
            BACKGROUND_JOB,
            name != null ? name : signature(method.id()).replaceAll("\\(.*", ""),
            attributes,
            job.getFirst().where());
      }
      if (recurring != null) {
        Annotation r = recurring.getLast();
        String cron = firstOf(r, "cron");
        String interval = firstOf(r, "interval");
        String written = cron != null ? cron : interval;
        if (cron != null) {
          scheduledFact(
              method,
              "cron",
              cron,
              recurring.getFirst().where(),
              r,
              Map.of("framework", "jobrunr"));
        } else if (written != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("framework", "jobrunr");
          attributes.put("kind", "interval");
          fact(method.id(), BACKGROUND_JOB, written, attributes, recurring.getFirst().where());
        }
      }
    }

    /** A call that schedules a JobRunr request or builds a Quartz job and its trigger. */
    private void scheduling(ModuleStructure.Invocation i) {
      String root = i.rootType();
      if (root == null) {
        return;
      }
      List<String> constructed = new ArrayList<>();
      List<String> classes = new ArrayList<>();
      walk(i.expression(), constructed, classes);
      if (JOBRUNR_SCHEDULERS.contains(root)) {
        if (!JOBRUNR_SCHEDULING_METHODS.contains(i.expression().chain().getFirst().method())) {
          return;
        }
        List<Expression> arguments = i.expression().chain().getFirst().arguments();
        String cron =
            i.expression().chain().getFirst().method().equals("scheduleRecurrently")
                    && arguments.size() >= 3
                    && arguments.get(1) instanceof Expression.Literal l
                ? l.value()
                : null;
        for (String request : constructed) {
          schedulings.add(new Scheduling(module, i.caller(), "jobrunr", request, cron));
        }
      } else if (root.startsWith("org.quartz.")) {
        // Quartz: a class literal given to JobBuilder, a cron expression given to
        // CronScheduleBuilder or CronTrigger, and the scheduler call that joins them, in one
        // expression.
        List<String> crons = new ArrayList<>();
        for (Expression.Invoke call : allCalls(i.expression())) {
          if ((call.method().equals("cronSchedule") || call.method().equals("<init>"))
              && !call.arguments().isEmpty()
              && call.arguments().getFirst() instanceof Expression.Literal l
              && l.value().trim().split("\\s+").length >= 6) {
            crons.add(l.value());
          }
        }
        for (String job : classes) {
          schedulings.add(
              new Scheduling(
                  module,
                  i.caller(),
                  "quartz",
                  job,
                  classes.size() == 1 && crons.size() == 1 ? crons.getFirst() : null));
        }
        if (classes.isEmpty() && crons.size() == 1) {
          schedulings.add(new Scheduling(module, i.caller(), "quartz", null, crons.getFirst()));
        }
      }
    }

    private static List<Expression.Invoke> allCalls(Expression e) {
      List<Expression.Invoke> calls = new ArrayList<>();
      collect(e, calls);
      return calls;
    }

    private static void collect(Expression e, List<Expression.Invoke> calls) {
      switch (e) {
        case Expression.Invoke call -> {
          calls.add(call);
          if (call.receiver() != null) {
            collect(call.receiver(), calls);
          }
          call.arguments().forEach(a -> collect(a, calls));
        }
        case Expression.Concat c -> c.parts().forEach(p -> collect(p, calls));
        default -> {}
      }
    }

    /** The types constructed and the class literals given as arguments in an expression. */
    private static void walk(Expression e, List<String> constructed, List<String> classes) {
      for (Expression.Invoke call : allCalls(e)) {
        if (call.method().equals(ModuleStructure.CONSTRUCTOR)
            && call.receiver() instanceof Expression.TypeRef t) {
          constructed.add(t.binaryName());
        }
        for (Expression a : call.arguments()) {
          if (a instanceof Expression.TypeRef t) {
            classes.add(t.binaryName());
          }
        }
      }
    }

    /** Records the jobs found, once every module is read: their schedulers may be elsewhere. */
    void jobs() {
      for (JobClass job : jobClasses) {
        String key = job.framework().equals("jobrunr") ? job.request() : job.type().binaryName();
        SortedSet<String> by = new TreeSet<>();
        String cron = null;
        for (Scheduling s : schedulings) {
          if (s.framework().equals(job.framework()) && Objects.equals(s.job(), key)) {
            if (s.caller() != null) {
              by.add(s.caller().toString());
            }
            if (cron == null) {
              cron = s.cron();
            }
          }
        }
        if (cron == null && !jobrunr(job)) {
          cron = unpairedCron(job);
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("framework", job.framework());
        boolean jobrunr = jobrunr(job);
        if (jobrunr) {
          attributes.put("request", job.request());
        } else {
          attributes.put("job", job.type().binaryName());
        }
        // With a cron found, a job is on a schedule like @Scheduled; else it is a background job.
        String predicate = cron != null ? SCHEDULED : BACKGROUND_JOB;
        String value;
        if (cron != null) {
          attributes.put("trigger", "cron");
          value = cron;
        } else {
          attributes.put("kind", "job");
          value =
              jobrunr ? (job.name() != null ? job.name() : job.request()) : job.type().binaryName();
        }
        if (!by.isEmpty()) {
          ArrayNode scheduledBy = attributes.putArray("scheduledBy");
          by.forEach(scheduledBy::add);
        }
        fact(job.method().id(), predicate, value, attributes, job.where(), job.module());
      }
    }

    /**
     * The cron of a Quartz trigger built apart from its job (Spring beans for each), when the
     * module has exactly one such trigger and exactly one job without a cron.
     */
    private static boolean jobrunr(JobClass job) {
      return job.framework().equals("jobrunr");
    }

    private String unpairedCron(JobClass job) {
      List<Scheduling> crons =
          schedulings.stream()
              .filter(s -> s.module() == job.module() && s.job() == null && s.cron() != null)
              .toList();
      long jobs =
          jobClasses.stream()
              .filter(j -> j.module() == job.module() && j.framework().equals("quartz"))
              .count();
      boolean paired =
          schedulings.stream()
              .anyMatch(
                  s ->
                      s.module() == job.module()
                          && Objects.equals(s.job(), job.type().binaryName())
                          && s.cron() != null);
      return crons.size() == 1 && jobs == 1 && !paired ? crons.getFirst().cron() : null;
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
    // Aspects

    /** One advice method of an aspect. */
    private record Advice(String kind, String pointcut, Member method, Annotation written) {}

    /**
     * Records each {@code @Aspect} class of the snapshot with its pointcuts, and which methods of
     * the analyzed code its advice selects. It runs after every module is read, because an aspect
     * advises code in other modules and repositories (a shared library's permission check).
     */
    void aspects() {
      List<AspectOf> aspects = new ArrayList<>();
      for (JavaAnalysis.Module m : code.modules()) {
        for (TypeDecl type : m.structure().types()) {
          if (type.kind().equals(ModuleStructure.CLASS)
              && first(type.annotations(), ASPECT_ANNOTATION) != null) {
            aspects.add(new AspectOf(m, type));
          }
        }
      }
      for (AspectOf aspect : aspects) {
        List<Advice> advice = advice(aspect.type());
        if (advice.isEmpty()) {
          continue;
        }
        module = aspect.module();
        aspect(aspect, advice);
      }
    }

    private record AspectOf(JavaAnalysis.Module module, TypeDecl type) {}

    private List<Advice> advice(TypeDecl type) {
      List<Advice> advice = new ArrayList<>();
      for (Member member : type.members()) {
        if (!member.id().type().equals(ModuleStructure.METHOD)) {
          continue;
        }
        for (Annotation a : member.annotations()) {
          String kind = ADVICE.get(a.type());
          if (kind == null) {
            continue;
          }
          String pointcut = firstOf(a, "pointcut", "value");
          if (pointcut != null && !pointcut.isBlank()) {
            advice.add(new Advice(kind, pointcut.strip(), member, a));
          }
        }
      }
      return advice;
    }

    private void aspect(AspectOf aspect, List<Advice> advice) {
      TypeDecl type = aspect.type();
      Set<String> kinds = new LinkedHashSet<>();
      Set<String> pointcuts = new LinkedHashSet<>();
      Set<String> annotations = new LinkedHashSet<>();
      Map<Advice, AspectPointcuts.Names> names = new LinkedHashMap<>();
      for (Advice a : advice) {
        AspectPointcuts.Names n = names(type, a);
        names.put(a, n);
        kinds.add(a.kind());
        pointcuts.add(a.pointcut());
        annotations.addAll(AspectPointcuts.annotationTypes(a.pointcut(), n));
      }
      ObjectNode attributes = FactJson.newObject();
      ArrayNode kindArray = attributes.putArray("advice");
      kinds.forEach(kindArray::add);
      ArrayNode pointcutArray = attributes.putArray("pointcuts");
      pointcuts.forEach(pointcutArray::add);
      if (!annotations.isEmpty()) {
        ArrayNode annotationArray = attributes.putArray("annotations");
        annotations.forEach(annotationArray::add);
      }
      Annotation written = first(type.annotations(), ASPECT_ANNOTATION).getFirst();
      fact(typeId(type), ASPECT, "aspect", attributes, written.where());
      for (Advice a : advice) {
        advised(aspect, a, names.get(a));
      }
    }

    private void advised(AspectOf aspect, Advice advice, AspectPointcuts.Names names) {
      EntityId subject = typeId(aspect.type());
      for (JavaAnalysis.Module m : code.modules()) {
        for (TypeDecl type : m.structure().types()) {
          if (!type.kind().equals(ModuleStructure.CLASS)
              || type.binaryName().equals(aspect.type().binaryName())
              || first(type.annotations(), ASPECT_ANNOTATION) != null) {
            continue;
          }
          List<TypeDecl> lineage = lineage(type);
          for (Member method : type.members()) {
            if (!method.id().type().equals(ModuleStructure.METHOD)) {
              continue;
            }
            String signature = signature(method.id());
            String name = signature.substring(0, signature.indexOf('('));
            if (name.equals(ModuleStructure.CONSTRUCTOR) || name.contains("$")) {
              continue;
            }
            if (AspectPointcuts.selects(
                advice.pointcut(),
                new AspectPointcuts.Candidate(type, lineage, method.annotations(), name),
                names)) {
              ObjectNode attributes = FactJson.newObject();
              attributes.put("advice", advice.method().id().toString());
              attributes.put("kind", advice.kind());
              attributes.put("pointcut", advice.pointcut());
              relation(subject, ADVISES, method.id(), attributes, advice.written().where());
            }
          }
        }
      }
    }

    /** What an advice's pointcut can mention: its parameters, named pointcuts, annotation types. */
    private AspectPointcuts.Names names(TypeDecl aspect, Advice advice) {
      return new AspectPointcuts.Names() {
        @Override
        public String annotationType(String argument) {
          String a = argument.strip();
          if (a.isEmpty()) {
            return null;
          }
          List<String> types = parameterTypes(advice.method().id());
          List<ModuleStructure.Parameter> parameters = advice.method().parameters();
          for (int i = 0; i < parameters.size() && i < types.size(); i++) {
            if (parameters.get(i).name().equals(a)) {
              return types.get(i);
            }
          }
          if (a.contains(".")) {
            return a;
          }
          return annotationBySimpleName(a);
        }

        @Override
        public String pointcut(String name) {
          TypeDecl in = aspect;
          String method = name;
          int dot = name.lastIndexOf('.');
          if (dot >= 0) {
            in = typeBySimpleOrBinaryName(name.substring(0, dot));
            method = name.substring(dot + 1);
          }
          if (in == null) {
            return null;
          }
          for (Member m : in.members()) {
            if (m.id().type().equals(ModuleStructure.METHOD)
                && signature(m.id()).startsWith(method + "(")) {
              for (Annotation a : m.annotations()) {
                if (a.type().equals(POINTCUT_ANNOTATION)) {
                  return firstOf(a, "value", "pointcut");
                }
              }
            }
          }
          return null;
        }
      };
    }

    private Map<String, List<TypeDecl>> typesBySimpleName;

    private Map<String, List<TypeDecl>> typesBySimpleName() {
      if (typesBySimpleName == null) {
        typesBySimpleName = new HashMap<>();
        for (JavaAnalysis.Module m : code.modules()) {
          for (TypeDecl t : m.structure().types()) {
            String binary = t.binaryName();
            String simple =
                binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
            typesBySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(t);
          }
        }
      }
      return typesBySimpleName;
    }

    private String annotationBySimpleName(String simple) {
      List<TypeDecl> found =
          typesBySimpleName().getOrDefault(simple, List.of()).stream()
              .filter(t -> t.kind().equals(ModuleStructure.ANNOTATION))
              .toList();
      return found.size() == 1 ? found.getFirst().binaryName() : null;
    }

    private TypeDecl typeBySimpleOrBinaryName(String name) {
      TypeDecl exact = code.type(name);
      if (exact != null) {
        return exact;
      }
      List<TypeDecl> found = typesBySimpleName().getOrDefault(name, List.of());
      return found.size() == 1 ? found.getFirst() : null;
    }

    /** The erased parameter types in a method's key. */
    private static List<String> parameterTypes(EntityId method) {
      String signature = signature(method);
      int open = signature.indexOf('(');
      String inside = signature.substring(open + 1, signature.lastIndexOf(')'));
      return inside.isEmpty() ? List.of() : List.of(inside.split(","));
    }

    // -------------------------------------------------------------------------------------------
    // Annotations

    /**
     * The first annotation written that is, or carries through meta-annotations, the given type:
     * the path from the one written to the one found, or null.
     */
    private List<Annotation> first(List<Annotation> annotations, String type) {
      return code.annotationPath(annotations, type);
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

    private List<String> views() {
      return config.views();
    }

    private Resolution resolve(String profile, String text) {
      return config.resolve(profile, text);
    }

    private void fact(
        EntityId subject, String predicate, String object, ObjectNode attributes, Location where) {
      fact(subject, predicate, object, attributes, where, module);
    }

    private void relation(
        EntityId subject,
        String predicate,
        EntityId object,
        ObjectNode attributes,
        Location where) {
      if (subject == null || object == null) {
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
              FactObject.entity(object),
              attributes,
              ORIGIN,
              evidence,
              module.confidence(),
              validity);
      facts.putIfAbsent(fact.id(), fact);
    }

    /**
     * Records a fact whose evidence is in the code of the given module, such as a mapping on an
     * interface in another repository.
     */
    private void fact(
        EntityId subject,
        String predicate,
        String object,
        ObjectNode attributes,
        Location where,
        JavaAnalysis.Module source) {
      if (subject == null) {
        return;
      }
      RepoBuild repo = source.repo();
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
              source.confidence(),
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
      CodeConfig.CodeValue v = CodeConfig.CodeValue.of(value, written, unresolved, used);
      return new Resolved(v.value(), v.written(), v.unresolved(), v.masked());
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
  public static String join(String base, String path) {
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
