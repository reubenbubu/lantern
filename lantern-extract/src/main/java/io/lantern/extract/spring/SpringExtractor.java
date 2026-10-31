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
import io.lantern.extract.CacheUnit;
import io.lantern.extract.ClaimSink;
import io.lantern.extract.ExtractionContext;
import io.lantern.extract.ExtractionUnit;
import io.lantern.extract.Extractor;
import io.lantern.extract.ExtractorInfo;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.CodeConfig;
import io.lantern.extract.config.ValueLookup;
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
public final class SpringExtractor implements Extractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "spring";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "6";

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

  /** Entity: an HTTP endpoint a module's code serves, its path as written (#519). */
  public static final String HTTP_ENDPOINT = "http-endpoint";

  /** Relationship: the subject endpoint is handled by the object method. */
  public static final String HANDLED_BY = "HANDLED_BY";

  /** Attribute: the method runs on a schedule written so; linking resolves it (#519). */
  public static final String SCHEDULED_WRITTEN = "scheduled-written";

  /**
   * Attribute: the method runs as a background job that is not on a schedule of {@code trigger}'s
   * kinds: a JobRunr job or a Quartz job without a cron found.
   */
  public static final String BACKGROUND_JOB = "background-job";

  /** Attribute: the bean class or method is registered only under a profile expression. */
  public static final String PROFILE_GATE = "profile-gate";

  /** Attribute: a bean that a property switches on, {@code @ConditionalOnProperty}. */
  public static final String PROPERTY_GATE = "property-gate";

  /**
   * Attribute: a main class that registers with service discovery, {@code @EnableDiscoveryClient}.
   */
  public static final String DISCOVERY_CLIENT = "discovery-client";

  /**
   * Attribute: a main class that makes its unit an infrastructure server (Eureka, config, admin).
   */
  public static final String INFRASTRUCTURE_SERVER = "infrastructure-server";

  /**
   * Attribute: a method or class whose callers must pass an access check, as its annotation states.
   */
  public static final String ACCESS_CHECK = "access-check";

  /** Attribute: a class that turns method security on, {@code @EnableMethodSecurity}. */
  public static final String METHOD_SECURITY = "method-security";

  /** Attribute: a method an aspect's advice applies to, at the annotation its pointcut selects. */
  public static final String ADVISED_BY = "advised-by";

  /** Relation: a method schedules a job method, at the call that schedules it. */
  public static final String SCHEDULES = "SCHEDULES";

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

  /** Discovery clients, by annotation. */
  private static final List<String> DISCOVERY_ANNOTATIONS =
      List.of(
          "org.springframework.cloud.client.discovery.EnableDiscoveryClient",
          "org.springframework.cloud.netflix.eureka.EnableEurekaClient");

  /** Infrastructure servers, by annotation, and what the fact calls each. */
  private static final Map<String, String> SERVER_ANNOTATIONS =
      Map.of(
          "org.springframework.cloud.netflix.eureka.server.EnableEurekaServer", "eureka",
          "org.springframework.cloud.config.server.EnableConfigServer", "config",
          "de.codecentric.boot.admin.server.config.EnableAdminServer", "admin");

  /** Method security's annotations, and what the fact calls each. */
  private static final Map<String, String> ACCESS_ANNOTATIONS =
      Map.of(
          "org.springframework.security.access.prepost.PreAuthorize", "pre-authorize",
          "org.springframework.security.access.prepost.PostAuthorize", "post-authorize",
          "org.springframework.security.access.annotation.Secured", "secured",
          "jakarta.annotation.security.RolesAllowed", "roles-allowed",
          "javax.annotation.security.RolesAllowed", "roles-allowed",
          "jakarta.annotation.security.PermitAll", "permit-all",
          "javax.annotation.security.PermitAll", "permit-all",
          "jakarta.annotation.security.DenyAll", "deny-all",
          "javax.annotation.security.DenyAll", "deny-all");

  /** The annotations that turn method security on, newest first. */
  private static final List<String> METHOD_SECURITY_ANNOTATIONS =
      List.of(
          "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity",
          "org.springframework.security.config.annotation.method.configuration"
              + ".EnableReactiveMethodSecurity",
          "org.springframework.security.config.annotation.method.configuration"
              + ".EnableGlobalMethodSecurity");

  private static final String CONDITIONAL_ON_PROPERTY =
      "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

  /** The Quartz {@code Scheduler} methods that schedule a job. */
  private static final Set<String> QUARTZ_SCHEDULING_METHODS =
      Set.of("scheduleJob", "scheduleJobs");

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
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(Fact::id))
        .toList();
  }

  /** One extraction: the facts so far, and the config read for each repository. */
  private static final class Run {

    private final JavaAnalysis code;
    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    private JavaAnalysis.Module module;

    /** Job classes found in the modules read so far, and where jobs are scheduled. */
    private final List<JobClass> jobClasses = new ArrayList<>();

    private final List<Scheduling> schedulings = new ArrayList<>();

    Run(JavaAnalysis code, Validity validity) {
      this.code = code;
      this.validity = validity;
    }

    void module(JavaAnalysis.Module m) {
      this.module = m;
      for (TypeDecl type : m.structure().types()) {
        if (type.kind().equals(ModuleStructure.ANNOTATION)) {
          continue;
        }
        boolean bean = type.kind().equals(ModuleStructure.CLASS);
        if (bean) {
          mainClass(type);
          registrations(type);
          stereotype(type);
          endpoints(type);
          accessChecks(type.annotations(), typeId(type));
        }
        profileGate(type.annotations(), typeId(type), type.where());
        propertyGate(type.annotations(), typeId(type), type.where());
        if (bean) {
          jobClass(type);
        }
        for (Member member : type.members()) {
          if (!member.id().type().equals(ModuleStructure.METHOD)) {
            continue;
          }
          scheduled(member);
          jobAnnotations(type, member);
          if (bean) {
            accessChecks(member.annotations(), member.id());
          }
          if (beanMethod(member)) {
            profileGate(member.annotations(), member.id(), member.where());
            propertyGate(member.annotations(), member.id(), member.where());
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
      // The endpoint as written, an entity its method handles; linking resolves its path in each
      // view against the module's config and records the endpoint facts (ADR 0094, #519).
      String text = joinWritten(basePath, path);
      EntityId endpoint =
          io.lantern.extract.EntityKeys.of(
              HTTP_ENDPOINT,
              module.id().key() + ":" + (httpMethod == null ? "*" : httpMethod) + ":" + text);
      ObjectNode attributes = FactJson.newObject();
      if (httpMethod != null) {
        attributes.put("httpMethod", httpMethod);
      }
      attributes.put("basePath", basePath);
      attributes.put("path", path);
      attributes.put("controller", controller.binaryName());
      attributes.put("annotation", written.type());
      attributes.put("framework", framework);
      attributes.put("configModule", module.id().toString());
      attributes.put("configRepo", module.repo().repo().name());
      fact(
          endpoint,
          HANDLED_BY,
          FactObject.entity(method.id()),
          attributes,
          written.where(),
          source);
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
      // As written; linking resolves it in each view and records the scheduled facts (#519).
      ObjectNode attributes = FactJson.newObject();
      attributes.put("trigger", trigger);
      if (!annotation.values("timeUnit").isEmpty() && !trigger.equals("cron")) {
        attributes.put("timeUnit", annotation.values("timeUnit").getFirst());
      }
      extra.forEach(attributes::put);
      attributes.put("configModule", module.id().toString());
      attributes.put("configRepo", module.repo().repo().name());
      fact(method.id(), SCHEDULED_WRITTEN, written, attributes, where);
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
        JavaAnalysis.Module module,
        EntityId caller,
        String framework,
        String job,
        String cron,
        Location where) {}

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
          schedulings.add(new Scheduling(module, i.caller(), "jobrunr", request, cron, i.where()));
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
        // Only the scheduler call schedules; building the job or trigger does not.
        Location at =
            root.equals("org.quartz.Scheduler")
                    && QUARTZ_SCHEDULING_METHODS.contains(
                        i.expression().chain().getFirst().method())
                ? i.where()
                : null;
        for (String job : classes) {
          schedulings.add(
              new Scheduling(
                  module,
                  i.caller(),
                  "quartz",
                  job,
                  classes.size() == 1 && crons.size() == 1 ? crons.getFirst() : null,
                  at));
        }
        if (classes.isEmpty() && crons.size() == 1) {
          schedulings.add(new Scheduling(module, i.caller(), "quartz", null, crons.getFirst(), at));
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
              if (s.where() != null && s.where().start() > 0) {
                // The call that schedules the job, at its own line.
                ObjectNode call = FactJson.newObject();
                call.put("framework", s.framework());
                JavaAnalysis.Module was = module;
                module = s.module();
                relation(s.caller(), SCHEDULES, job.method().id(), call, s.where());
                module = was;
              }
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

    /** Records a {@code @ConditionalOnProperty} on a bean class or method. */
    private void propertyGate(List<Annotation> annotations, EntityId subject, Location fallback) {
      List<Annotation> path = first(annotations, CONDITIONAL_ON_PROPERTY);
      if (path == null || subject == null) {
        return;
      }
      Annotation gate = path.getLast();
      String prefix = firstOf(gate, "prefix");
      List<String> names = new ArrayList<>(gate.values("name"));
      if (names.isEmpty()) {
        names.addAll(gate.values("value"));
      }
      if (names.isEmpty()) {
        return;
      }
      ObjectNode attributes = FactJson.newObject();
      ArrayNode properties = attributes.putArray("properties");
      for (String name : names) {
        properties.add(
            prefix == null || prefix.isBlank() ? name : prefix.replaceAll("\\.$", "") + "." + name);
      }
      String having = firstOf(gate, "havingValue");
      if (having != null && !having.isEmpty()) {
        attributes.put("havingValue", having);
      }
      String missing = firstOf(gate, "matchIfMissing");
      if ("true".equals(missing)) {
        attributes.put("matchIfMissing", true);
      }
      if (path.size() > 1) {
        attributes.put("annotation", path.getFirst().type());
      }
      List<String> written = new ArrayList<>();
      properties.forEach(n -> written.add(n.asString()));
      String value =
          String.join(" & ", written) + (having != null && !having.isEmpty() ? "=" + having : "");
      Location where = path.getFirst().where() != null ? path.getFirst().where() : fallback;
      fact(subject, PROPERTY_GATE, value, attributes, where);
    }

    /**
     * Records a main class's registrations: with service discovery
     * ({@code @EnableDiscoveryClient}), and as an infrastructure server (Eureka, config, admin).
     */
    private void registrations(TypeDecl type) {
      for (String annotation : DISCOVERY_ANNOTATIONS) {
        List<Annotation> path = first(type.annotations(), annotation);
        if (path != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("annotation", path.getFirst().type());
          String register = firstOf(path.getLast(), "autoRegister");
          if ("false".equals(register)) {
            attributes.put("autoRegister", false);
          }
          fact(typeId(type), DISCOVERY_CLIENT, "discovery", attributes, path.getFirst().where());
          break;
        }
      }
      for (Map.Entry<String, String> server : SERVER_ANNOTATIONS.entrySet()) {
        List<Annotation> path = first(type.annotations(), server.getKey());
        if (path != null) {
          ObjectNode attributes = FactJson.newObject();
          attributes.put("annotation", path.getFirst().type());
          fact(
              typeId(type),
              INFRASTRUCTURE_SERVER,
              server.getValue(),
              attributes,
              path.getFirst().where());
        }
      }
      for (String annotation : METHOD_SECURITY_ANNOTATIONS) {
        List<Annotation> path = first(type.annotations(), annotation);
        if (path != null) {
          Annotation on = path.getLast();
          ObjectNode attributes = FactJson.newObject();
          attributes.put("annotation", path.getFirst().type());
          boolean legacy = annotation.endsWith("EnableGlobalMethodSecurity");
          // @EnableMethodSecurity turns @PreAuthorize on unless told not to; the older one off.
          String prePost = firstOf(on, "prePostEnabled");
          attributes.put(
              "prePostEnabled", legacy ? "true".equals(prePost) : !"false".equals(prePost));
          attributes.put("securedEnabled", "true".equals(firstOf(on, "securedEnabled")));
          attributes.put("jsr250Enabled", "true".equals(firstOf(on, "jsr250Enabled")));
          fact(typeId(type), METHOD_SECURITY, "on", attributes, path.getFirst().where());
          break;
        }
      }
    }

    /**
     * Records the access checks method security's annotations state on a class or method: the
     * expression or roles, at the annotation, through meta-annotations too.
     */
    private void accessChecks(List<Annotation> annotations, EntityId subject) {
      if (subject == null) {
        return;
      }
      for (Map.Entry<String, String> check : ACCESS_ANNOTATIONS.entrySet()) {
        List<Annotation> path = first(annotations, check.getKey());
        if (path == null) {
          continue;
        }
        Annotation written = path.getLast();
        List<String> values = written.values("value");
        String value = values.isEmpty() ? check.getValue() : String.join(", ", values);
        ObjectNode attributes = FactJson.newObject();
        attributes.put("kind", check.getValue());
        if (path.size() > 1) {
          attributes.put("annotation", path.getFirst().type());
        }
        Location where = path.getFirst().where();
        if (where != null) {
          fact(subject, ACCESS_CHECK, value, attributes, where);
        }
      }
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
            AspectPointcuts.Candidate candidate =
                new AspectPointcuts.Candidate(type, lineage, method.annotations(), name);
            if (AspectPointcuts.selects(advice.pointcut(), candidate, names)) {
              ObjectNode attributes = FactJson.newObject();
              attributes.put("advice", advice.method().id().toString());
              attributes.put("kind", advice.kind());
              attributes.put("pointcut", advice.pointcut());
              relation(subject, ADVISES, method.id(), attributes, advice.written().where());
              Annotation selecting =
                  selecting(
                      AspectPointcuts.selectingAnnotation(advice.pointcut(), candidate, names),
                      method,
                      type);
              if (selecting != null && selecting.where() != null) {
                ObjectNode at = FactJson.newObject();
                at.put("advice", advice.method().id().toString());
                at.put("kind", advice.kind());
                at.put("annotation", selecting.type());
                JavaAnalysis.Module was = module;
                module = m;
                fact(method.id(), ADVISED_BY, aspect.type().binaryName(), at, selecting.where());
                module = was;
              }
            }
          }
        }
      }
    }

    /** The annotation of the given type on the method, else on its class; null for none. */
    private static Annotation selecting(String annotation, Member method, TypeDecl type) {
      if (annotation == null) {
        return null;
      }
      for (List<Annotation> on : List.of(method.annotations(), type.annotations())) {
        for (Annotation a : on) {
          if (a.type().equals(annotation)) {
            return a;
          }
        }
      }
      return null;
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
          Evidence.of(
              repo.repo().name(),
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
      fact(subject, predicate, FactObject.string(object), attributes, where, source);
    }

    private void fact(
        EntityId subject,
        String predicate,
        FactObject object,
        ObjectNode attributes,
        Location where,
        JavaAnalysis.Module source) {
      if (subject == null) {
        return;
      }
      RepoBuild repo = source.repo();
      Evidence evidence =
          Evidence.of(
              repo.repo().name(),
              where.file().path(),
              where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
              where.file().contentHash());
      Fact fact =
          Fact.create(
              subject,
              predicate,
              object,
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

    /** A text from code resolved in a view of its module's config. */
    static Resolved of(ValueLookup config, String view, String written) {
      CodeConfig.CodeValue v = config.value(view, written);
      return new Resolved(v.value(), v.written(), v.unresolved(), v.masked());
    }

    /**
     * A controller's and a method's paths, each resolved, then joined; masked, as written, if a
     * secret was substituted into either.
     */
    static Resolved path(ValueLookup config, String view, String basePath, String path) {
      CodeConfig.CodeValue base = config.value(view, basePath);
      CodeConfig.CodeValue own = config.value(view, path);
      String written = joinWritten(basePath, path);
      if (base.masked() || own.masked()) {
        return new Resolved(written, written, new TreeSet<>(), true);
      }
      SortedSet<String> unresolved = new TreeSet<>(base.unresolved());
      unresolved.addAll(own.unresolved());
      return new Resolved(join(base.value(), own.value()), written, unresolved, false);
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

  /**
   * The facts linking gives from the values this extractor keeps as written (ADR 0094, #519): each
   * endpoint its code serves ({@value #HANDLED_BY}) and each schedule ({@value #SCHEDULED_WRITTEN})
   * resolved in each view of its module's config, as this extractor recorded them before: {@value
   * #ENDPOINT} and {@value #SCHEDULED} facts, the default view's and each differing profile's.
   *
   * @param facts the snapshot's facts; only the written ones of this extractor are read
   * @param config the config of a module, by its entity ID and its repository's name
   * @param origin the origin of the facts
   * @param snapshot the snapshot number the facts hold from
   */
  public static List<Fact> resolve(
      List<Fact> facts,
      java.util.function.BiFunction<EntityId, String, ValueLookup> config,
      Origin origin,
      long snapshot) {
    Validity validity = Validity.from(snapshot);
    Map<String, ValueLookup> lookups = new HashMap<>();
    Map<String, Fact> out = new LinkedHashMap<>();
    for (Fact f : facts) {
      boolean endpoint =
          f.predicate().equals(HANDLED_BY) && f.subject().type().equals(HTTP_ENDPOINT);
      boolean scheduled = f.predicate().equals(SCHEDULED_WRITTEN);
      if (!endpoint && !scheduled) {
        continue;
      }
      JsonNode a = f.attributes();
      String module = a.get("configModule").stringValue();
      String repo = a.get("configRepo").stringValue();
      ValueLookup lookup =
          lookups.computeIfAbsent(
              module + "\0" + repo, k -> config.apply(EntityId.parse(module), repo));
      Resolved byDefault = null;
      for (String view : lookup.views()) {
        Resolved value =
            endpoint
                ? Resolved.path(
                    lookup, view, a.get("basePath").stringValue(), a.get("path").stringValue())
                : Resolved.of(lookup, view, f.object().value());
        if (view == null) {
          byDefault = value;
        } else if (value.equals(byDefault)) {
          continue;
        }
        ObjectNode attributes = value.attributes(view);
        EntityId subject;
        String predicate;
        String object;
        if (endpoint) {
          String httpMethod = a.has("httpMethod") ? a.get("httpMethod").stringValue() : null;
          if (httpMethod != null) {
            attributes.put("httpMethod", httpMethod);
          }
          attributes.put("path", value.value());
          attributes.set("controller", a.get("controller"));
          attributes.set("annotation", a.get("annotation"));
          attributes.set("framework", a.get("framework"));
          subject = f.object().entityId();
          predicate = ENDPOINT;
          object = httpMethod == null ? value.value() : httpMethod + " " + value.value();
        } else {
          a.properties()
              .forEach(
                  e -> {
                    if (!e.getKey().equals("configModule") && !e.getKey().equals("configRepo")) {
                      attributes.set(e.getKey(), e.getValue());
                    }
                  });
          subject = f.subject();
          predicate = SCHEDULED;
          object = value.value();
        }
        Fact fact =
            Fact.create(
                subject,
                predicate,
                FactObject.string(object),
                attributes,
                origin,
                f.evidence(),
                f.confidence(),
                validity);
        out.putIfAbsent(fact.id(), fact);
      }
    }
    return out.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(f -> f.evidence().path())
                .thenComparing(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(Fact::id))
        .toList();
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

  @Override
  public ExtractorInfo info() {
    return new ExtractorInfo(NAME, VERSION);
  }

  @Override
  public CacheUnit unit() {
    return CacheUnit.SNAPSHOT;
  }

  @Override
  public void extract(ExtractionContext context, ExtractionUnit unit, ClaimSink sink) {
    sink.emitAll(extract(context.code(), context.snapshot()));
  }
}
