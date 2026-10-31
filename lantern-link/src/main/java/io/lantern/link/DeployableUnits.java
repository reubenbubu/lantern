package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.AttributeValues;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.attributes.BuildDependsOnAttributes;
import io.lantern.extract.attributes.ConfigValueAttributes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.spring.SpringExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Finds the deployable units of a snapshot, the containers of its diagrams: each runnable
 * application together with the in-set libraries it bundles, its name and its technology. See
 * {@code docs/adr/0039-deployable-units.md}.
 *
 * <p>The step reads the facts of the whole snapshot, as the extractors made them, and derives new
 * facts from them: the Maven extractor's services, modules and dependencies, the Spring extractor's
 * application classes (placed in their modules through the Java extractor's {@code CONTAINS}
 * facts), and the Config extractor's {@code spring.application.name}. A unit is a {@code service:}
 * entity. Every fact about a unit has its evidence in the unit's own repository, so the unit's
 * facts are derived again whenever that repository is analyzed.
 */
public final class DeployableUnits implements LinkRule {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "deployable-units";

  /** The step's version; change it whenever the facts it produces change. */
  public static final String VERSION = "3";

  /** The source recorded on the vocabulary revision that adds this step's types. */
  public static final String VOCABULARY_SOURCE = "deployable-units";

  /** Attribute: a service is a deployable unit, and what makes it runnable. */
  public static final String DEPLOYABLE_UNIT = "deployable-unit";

  /** Attribute: the name a deployable unit runs under. */
  public static final String APPLICATION_NAME = "application-name";

  /** Attribute: what a deployable unit is built with. */
  public static final String TECHNOLOGY = "technology";

  /** Relationship: a deployable unit ships an in-set library. */
  public static final String BUNDLES = "BUNDLES";

  /**
   * Attribute: the units that bundle an in-set library ask for versions other than the one its
   * repository builds at the snapshot's commit.
   */
  public static final String VERSION_SKEW = "version-skew";

  /** Confidence of a unit found from an application class alone, relative to that class's fact. */
  static final double LIBRARY_UNIT_FACTOR = 0.8;

  /** Confidence of a name that no config sets, relative to the unit's. */
  static final double DEFAULTED_NAME_FACTOR = 0.5;

  static final String NAME_KEY = "spring.application.name";

  private static final String RESOURCE = "/vocabulary/deployable-units-types.json";
  private static final String SPRING_BOOT_GROUP = "org.springframework.boot:";
  private static final String SPRING_BOOT = "Spring Boot";
  private static final Set<String> BOOT_PARENTS =
      Set.of(
          "org.springframework.boot:spring-boot-starter-parent",
          "org.springframework.boot:spring-boot-parent");

  /**
   * The frameworks beyond Spring Boot a unit names in its technology, by the group and artifact
   * prefix of a dependency that brings it in; the first rule that matches an artifact names it.
   */
  private static final List<Framework> FRAMEWORKS =
      List.of(
          new Framework(
              "Spring Cloud Gateway", "org.springframework.cloud:spring-cloud-starter-gateway"),
          new Framework("Spring Cloud Gateway", "org.springframework.cloud:spring-cloud-gateway"),
          new Framework(
              "Eureka Server",
              "org.springframework.cloud:spring-cloud-starter-netflix-eureka-server"),
          new Framework(
              "Spring Cloud Config Server", "org.springframework.cloud:spring-cloud-config-server"),
          new Framework(
              "Spring Cloud Config Server",
              "org.springframework.cloud:spring-cloud-starter-config-server"),
          new Framework("WebFlux", "org.springframework.boot:spring-boot-starter-webflux"),
          new Framework("WebSocket", "org.springframework.boot:spring-boot-starter-websocket"));

  private record Framework(String name, String artifactPrefix) {}

  private static final Set<String> PACKAGED_SCOPES = Set.of("compile", "runtime");

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  @Override
  public RuleInfo info() {
    return RuleInfo.of(ORIGIN);
  }

  /** The extracted predicates the units are made of. */
  private static final List<String> READS =
      List.of(
          BuiltInTypes.CONTAINS,
          MavenExtractor.BUILD_DEPENDS_ON,
          MavenExtractor.PACKAGES,
          MavenExtractor.PACKAGING,
          MavenExtractor.PARENT,
          MavenExtractor.JAVA_RELEASE,
          MavenExtractor.VERSION_ATTRIBUTE,
          ConfigExtractor.CONFIG_VALUE,
          SpringExtractor.MAIN_CLASS);

  @Override
  public Set<String> reads() {
    return Set.copyOf(READS);
  }

  @Override
  public Set<String> writes() {
    return vocabularyPredicates();
  }

  /** The predicates of this step's vocabulary types, which it writes. */
  static Set<String> vocabularyPredicates() {
    return Rules.predicates(vocabulary());
  }

  /** The vocabulary types this step's facts use besides the built-in and extractors' ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = DeployableUnits.class.getResourceAsStream(RESOURCE)) {
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
   * Derives the deployable units of a snapshot from its facts.
   *
   * @param facts the facts the extractors found in all repositories of the snapshot
   * @param snapshot the snapshot number the facts hold from
   * @return the derived facts, without duplicates, ordered by subject, predicate, object and ID
   */
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    ClaimSink sink = new ClaimSink(info(), writes());
    derive(new ClaimQuery(info(), reads(), Set.of(), facts, snapshot), sink, View.DEFAULT);
    return sink.facts();
  }

  @Override
  public void derive(ClaimQuery in, ClaimSink out, View view) {
    Index index = new Index(in);
    Validity validity = Validity.from(in.snapshot());
    Map<String, Fact> derived = new LinkedHashMap<>();
    List<Unit> units = units(index);
    for (Unit unit : units) {
      unit.facts(index, validity).forEach(f -> derived.putIfAbsent(f.id(), f));
    }
    versionSkew(units, index, validity).forEach(f -> derived.putIfAbsent(f.id(), f));
    derived.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .forEach(out::add);
  }

  /**
   * The units: every service the Maven extractor found (a war, an ear or a repackaged jar), and
   * every library with a Spring Boot application class that no such service bundles.
   */
  private static List<Unit> units(Index index) {
    List<Unit> units = new ArrayList<>();
    Set<EntityId> bundledByServices = new HashSet<>();
    for (Fact contains : index.containerOf.values()) {
      if (contains.subject().type().equals(BuiltInTypes.SERVICE)) {
        Unit unit =
            new Unit(
                contains.subject(),
                contains.object().entityId(),
                contains.evidence(),
                contains.confidence(),
                null,
                index);
        units.add(unit);
        unit.bundled.forEach(b -> bundledByServices.add(b.library()));
      }
    }
    Set<EntityId> libraryUnits = new HashSet<>();
    for (Fact main : index.bootMains) {
      for (EntityId module : index.modulesOf(main.subject())) {
        Fact contains = index.containerOf.get(module);
        if (contains == null
            || !contains.subject().type().equals(BuiltInTypes.LIBRARY)
            || bundledByServices.contains(contains.subject())
            || !libraryUnits.add(contains.subject())) {
          continue;
        }
        units.add(
            new Unit(
                new EntityId(BuiltInTypes.SERVICE, contains.subject().key()),
                module,
                main.evidence(),
                round(main.confidence() * LIBRARY_UNIT_FACTOR),
                contains.subject(),
                index));
      }
    }
    return units;
  }

  // ---------------------------------------------------------------------------------------------
  // A unit

  /**
   * A library a unit bundles, found through the dependency that leads to it from the unit ({@code
   * first}); {@code reached} is the dependency on the library itself, whose version is the one the
   * unit gets.
   */
  private record Bundled(
      EntityId library, EntityId module, Fact first, Fact reached, List<EntityId> via) {}

  /** One deployable unit and what it is made of. */
  private static final class Unit {

    private final EntityId id;
    private final EntityId module;
    private final Evidence evidence;
    private final double confidence;

    /** The library that is itself the unit, for a unit found from an application class alone. */
    private final EntityId self;

    /** The libraries the unit bundles, nearest first, in the order of their declarations. */
    private final List<Bundled> bundled;

    Unit(
        EntityId id,
        EntityId module,
        Evidence evidence,
        double confidence,
        EntityId self,
        Index index) {
      this.id = id;
      this.module = module;
      this.evidence = evidence;
      this.confidence = confidence;
      this.self = self;
      this.bundled = bundle(module, index);
    }

    /** The unit's own module, then the modules of the libraries it bundles, nearest first. */
    List<EntityId> modules() {
      List<EntityId> modules = new ArrayList<>();
      modules.add(module);
      bundled.forEach(b -> modules.add(b.module()));
      return modules;
    }

    /** Evidence from another fact if it is in the unit's repository, else the unit's own. */
    Evidence local(Fact fact) {
      return fact != null && fact.evidence().repo().equals(evidence.repo())
          ? fact.evidence()
          : evidence;
    }

    List<Fact> facts(Index index, Validity validity) {
      List<Fact> facts = new ArrayList<>();
      Fact main = mainClass(index);
      Set<EntityId> mainLibraries = new HashSet<>(index.packages.getOrDefault(id, Set.of()));
      if (self != null) {
        mainLibraries.add(self);
      }
      if (main != null) {
        for (EntityId m : index.modulesOf(main.subject())) {
          Fact contains = index.containerOf.get(m);
          if (contains != null
              && contains.subject().type().equals(BuiltInTypes.LIBRARY)
              && modules().contains(m)) {
            mainLibraries.add(contains.subject());
          }
        }
      }

      String packaging = index.packaging.get(module);
      ObjectNode unit = FactJson.newObject();
      if (main != null) {
        unit.put("mainClass", main.subject().toString());
      }
      if (packaging != null) {
        unit.put("packaging", packaging);
      }
      String kind =
          main != null
              ? SpringExtractor.SPRING_BOOT
              : "war".equals(packaging) || "ear".equals(packaging) ? packaging : "executable-jar";
      facts.add(
          fact(
              id,
              DEPLOYABLE_UNIT,
              FactObject.string(kind),
              unit,
              local(main),
              confidence,
              validity));

      if (self != null) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("main", true);
        facts.add(
            fact(id, BUNDLES, FactObject.entity(self), attributes, evidence, confidence, validity));
      }
      for (Bundled b : bundled) {
        ObjectNode attributes = FactJson.newObject();
        if (mainLibraries.contains(b.library())) {
          attributes.put("main", true);
        }
        if (!b.via().isEmpty()) {
          ArrayNode via = attributes.putArray("via");
          b.via().forEach(v -> via.add(v.toString()));
        }
        facts.add(
            fact(
                id,
                BUNDLES,
                FactObject.entity(b.library()),
                attributes,
                local(b.first()),
                b.first().confidence(),
                validity));
      }

      names(index, validity, facts);
      technology(index, main, mainLibraries, validity, facts);
      return facts;
    }

    /**
     * The Spring Boot application class that starts the unit: one in its own module, else in the
     * nearest library it bundles; of several there, the first by ID.
     */
    private Fact mainClass(Index index) {
      for (EntityId m : modules()) {
        List<Fact> mains = index.bootMainsByModule.getOrDefault(m, List.of());
        if (!mains.isEmpty()) {
          return mains.getFirst();
        }
      }
      return null;
    }

    /**
     * The unit's {@code spring.application.name}, as Spring Boot finds it on the classpath. It
     * loads the {@code application} files and the {@code bootstrap} files each from the first
     * module on the classpath that has them: the unit's own module, else the nearest library it
     * bundles. A name in the {@code application} files wins over one in the {@code bootstrap}
     * files, for the default view and for each profile (ADR 0032). The name of the default view,
     * then one per profile whose name differs. Without a name in the default view, the unit's
     * artifact ID.
     */
    private void names(Index index, Validity validity, List<Fact> facts) {
      // Lowest precedence first, so a later family's name replaces an earlier one's.
      Map<String, Fact> byProfile = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
      for (String family : List.of("bootstrap", "application")) {
        EntityId provider =
            modules().stream()
                .filter(
                    m ->
                        index.config.getOrDefault(m, List.of()).stream()
                            .anyMatch(f -> family.equals(configFamily(f, index.config(f)))))
                .findFirst()
                .orElse(null);
        if (provider == null) {
          continue;
        }
        Map<String, Fact> names = new HashMap<>();
        for (Fact f : index.config.get(provider)) {
          if (family.equals(configFamily(f, index.config(f)))
              && NAME_KEY.equals(index.config(f).key())
              && !Boolean.TRUE.equals(index.config(f).masked())) {
            names.put(index.config(f).profile(), f);
          }
        }
        Fact familyDefault = names.get(null);
        if (familyDefault != null) {
          // The family's default view overrides every profile view of the families before it.
          byProfile.replaceAll((profile, earlier) -> familyDefault);
        }
        byProfile.putAll(names);
      }
      Fact defaultName = byProfile.get(null);
      for (Map.Entry<String, Fact> entry : byProfile.entrySet()) {
        String profile = entry.getKey();
        Fact name = entry.getValue();
        if (profile != null
            && defaultName != null
            && name.object().value().equals(defaultName.object().value())) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        attributes.put("module", name.subject().toString());
        if (profile != null) {
          attributes.put("profile", profile);
        }
        AttributeValues.put(attributes, "unresolved", index.config(name).unresolved());
        facts.add(
            fact(
                id,
                APPLICATION_NAME,
                FactObject.string(name.object().value()),
                attributes,
                local(name),
                name.confidence(),
                validity));
      }
      if (defaultName == null) {
        String key = id.key();
        ObjectNode attributes = FactJson.newObject();
        attributes.put("defaulted", true);
        facts.add(
            fact(
                id,
                APPLICATION_NAME,
                FactObject.string(key.substring(key.lastIndexOf(':') + 1)),
                attributes,
                evidence,
                round(confidence * DEFAULTED_NAME_FACTOR),
                validity));
      }
    }

    /**
     * The frameworks of {@link #FRAMEWORKS} that the unit's modules depend on, each with the
     * version its dependency states, if any, in the table's order; WebFlux is left out when the
     * gateway, which is built on it, is there.
     */
    private boolean hasArtifact(Index index, String artifactPrefix) {
      return modules().stream()
          .flatMap(m -> index.deps.getOrDefault(m, List.of()).stream())
          .anyMatch(
              d ->
                  d.object().value().equals("artifact:" + SPRING_BOOT_GROUP + artifactPrefix)
                      && PACKAGED_SCOPES.contains(index.dep(d).scope()));
    }

    private List<String> frameworks(Index index) {
      Map<String, String> found = new LinkedHashMap<>();
      for (Framework framework : FRAMEWORKS) {
        if (found.containsKey(framework.name())) {
          continue;
        }
        for (EntityId m : modules()) {
          for (Fact dep : index.deps.getOrDefault(m, List.of())) {
            if (dep.object().value().startsWith("artifact:" + framework.artifactPrefix())
                && PACKAGED_SCOPES.contains(index.dep(dep).scope())) {
              // Spring Boot's own starters are at Spring Boot's version, already named.
              String version =
                  framework.artifactPrefix().startsWith(SPRING_BOOT_GROUP)
                      ? null
                      : index.dep(dep).version();
              found.put(framework.name(), version == null ? "" : version);
              break;
            }
          }
          if (found.containsKey(framework.name())) {
            break;
          }
        }
      }
      if (found.containsKey("Spring Cloud Gateway")
          || hasArtifact(index, "spring-boot-starter-web")) {
        // The gateway is built on WebFlux; WebClient alone in a servlet application is not
        // reactive.
        found.remove("WebFlux");
      }
      List<String> result = new ArrayList<>();
      found.forEach((name, version) -> result.add(version.isEmpty() ? name : name + " " + version));
      return result;
    }

    /**
     * Spring Boot with the version of the first {@code org.springframework.boot} dependency of the
     * nearest module that has one, else of the Spring Boot parent POM the nearest module inherits
     * from, if the unit uses Spring Boot; and the Java release of the unit's own module, else of
     * its main library.
     */
    private void technology(
        Index index, Fact main, Set<EntityId> mainLibraries, Validity validity, List<Fact> facts) {
      Fact boot = null;
      boolean usesBoot = main != null;
      for (EntityId m : modules()) {
        List<Fact> deps =
            index.deps.getOrDefault(m, List.of()).stream()
                .filter(d -> d.object().value().startsWith("artifact:" + SPRING_BOOT_GROUP))
                .toList();
        usesBoot |= !deps.isEmpty();
        boot =
            deps.stream()
                .filter(d -> index.dep(d).version() != null)
                .min(Comparator.comparing(d -> d.object().value()))
                .orElse(null);
        if (boot != null) {
          break;
        }
      }
      String version = boot == null ? null : index.dep(boot).version();
      Fact bootParent = null;
      if (version == null) {
        // Without a build the dependencies' versions are those Spring Boot's parent POM manages.
        for (EntityId m : modules()) {
          bootParent = index.bootParent(m);
          if (bootParent != null) {
            usesBoot = true;
            version = parentVersion(bootParent);
            break;
          }
        }
      }
      Fact java = index.javaRelease.get(module);
      for (Bundled b : bundled) {
        if (java == null && mainLibraries.contains(b.library())) {
          java = index.javaRelease.get(b.module());
        }
      }
      ObjectNode attributes = FactJson.newObject();
      List<String> parts = new ArrayList<>();
      if (usesBoot) {
        attributes.put("framework", SPRING_BOOT);
        if (version != null) {
          attributes.put("frameworkVersion", version);
        }
        parts.add(version == null ? SPRING_BOOT : SPRING_BOOT + " " + version);
      }
      List<String> frameworks = frameworks(index);
      if (!frameworks.isEmpty()) {
        ArrayNode names = attributes.putArray("frameworks");
        frameworks.forEach(names::add);
        parts.addAll(frameworks);
      }
      if (java != null) {
        attributes.put("java", java.object().value());
        parts.add("Java " + java.object().value());
      }
      if (parts.isEmpty()) {
        return;
      }
      Evidence where =
          boot != null ? local(boot) : bootParent != null ? local(bootParent) : local(java);
      facts.add(
          fact(
              id,
              TECHNOLOGY,
              FactObject.string(String.join(" / ", parts)),
              attributes,
              where,
              confidence,
              validity));
    }
  }

  /**
   * The in-set libraries a module's deployable bundles: the libraries it depends on at compile or
   * runtime scope, and theirs in turn (optional dependencies of a library are not passed on), as
   * Maven puts them on the runtime classpath. A dependency on a service, a {@code pom} module or a
   * typed artifact ({@code test-jar}, {@code pom}) is not followed. Breadth first, each module's
   * dependencies in the order its POM declares them.
   */
  private static List<Bundled> bundle(EntityId root, Index index) {
    record Step(EntityId module, Fact first, List<EntityId> via) {}
    List<Bundled> bundled = new ArrayList<>();
    Set<EntityId> seen = new HashSet<>(Set.of(root));
    Deque<Step> queue = new ArrayDeque<>(List.of(new Step(root, null, List.of())));
    while (!queue.isEmpty()) {
      Step step = queue.poll();
      for (Fact dep : index.deps.getOrDefault(step.module(), List.of())) {
        EntityId target = dep.object().entityId();
        Fact contains = index.containerOf.get(target);
        if (!target.type().equals(BuiltInTypes.MODULE)
            || !PACKAGED_SCOPES.contains(index.dep(dep).scope())
            || index.dep(dep).artifactType() != null
            || (step.first() != null && Boolean.TRUE.equals(index.dep(dep).optional()))
            || contains == null
            || !contains.subject().type().equals(BuiltInTypes.LIBRARY)
            || !seen.add(target)) {
          continue;
        }
        Fact first = step.first() == null ? dep : step.first();
        bundled.add(new Bundled(contains.subject(), target, first, dep, step.via()));
        List<EntityId> via = new ArrayList<>(step.via());
        via.add(contains.subject());
        queue.add(new Step(target, first, List.copyOf(via)));
      }
    }
    return bundled;
  }

  /**
   * A {@code version-skew} fact for each in-set library that a unit bundles at a version other than
   * the one its module declares at the snapshot's commit: the library's version, with the version
   * each unit that bundles it asks for. Units whose dependency names no version are left out. The
   * evidence is the library's own version declaration.
   */
  private static List<Fact> versionSkew(List<Unit> units, Index index, Validity validity) {
    Map<EntityId, Map<EntityId, Fact>> pins = new TreeMap<>(Comparator.comparing(Object::toString));
    Map<EntityId, EntityId> moduleOf = new HashMap<>();
    for (Unit unit : units) {
      for (Bundled b : unit.bundled) {
        if (index.dep(b.reached()).version() != null) {
          pins.computeIfAbsent(
                  b.library(), l -> new TreeMap<>(Comparator.comparing(Object::toString)))
              .putIfAbsent(unit.id, b.reached());
          moduleOf.putIfAbsent(b.library(), b.module());
        }
      }
    }
    List<Fact> facts = new ArrayList<>();
    pins.forEach(
        (library, byUnit) -> {
          Fact built = index.moduleVersion.get(moduleOf.get(library));
          if (built == null) {
            return;
          }
          String version = built.object().value();
          if (byUnit.values().stream().allMatch(f -> version.equals(index.dep(f).version()))) {
            return;
          }
          ObjectNode attributes = FactJson.newObject();
          ObjectNode byUnitVersion = attributes.putObject("pins");
          Set<String> versions = new TreeSet<>();
          double confidence = built.confidence();
          for (Map.Entry<EntityId, Fact> pin : byUnit.entrySet()) {
            String pinned = index.dep(pin.getValue()).version();
            byUnitVersion.put(pin.getKey().toString(), pinned);
            versions.add(pinned);
            confidence = Math.min(confidence, pin.getValue().confidence());
          }
          ArrayNode list = attributes.putArray("versions");
          versions.forEach(list::add);
          facts.add(
              fact(
                  library,
                  VERSION_SKEW,
                  FactObject.string(version),
                  attributes,
                  built.evidence(),
                  confidence,
                  validity));
        });
    return facts;
  }

  // ---------------------------------------------------------------------------------------------
  // The facts the step reads

  /** The extractors' facts the step reads, indexed. */
  private static final class Index {

    /** The Maven {@code service|library CONTAINS module} fact by module. */
    final Map<EntityId, Fact> containerOf = new TreeMap<>(Comparator.comparing(Object::toString));

    /** A module's {@code BUILD_DEPENDS_ON} facts, in the order its POM declares them. */
    final Map<EntityId, List<Fact>> deps = new HashMap<>();

    /** The libraries each service packages as its main code ({@code PACKAGES}). */
    final Map<EntityId, Set<EntityId>> packages = new HashMap<>();

    final Map<EntityId, String> packaging = new HashMap<>();

    /** A module's {@code parent} fact. */
    final Map<EntityId, Fact> parent = new HashMap<>();

    final Map<EntityId, Fact> javaRelease = new HashMap<>();

    /** A module's {@code version} fact: the version its POM declares. */
    final Map<EntityId, Fact> moduleVersion = new HashMap<>();

    /** A module's {@code config-value} facts. */
    final Map<EntityId, List<Fact>> config = new HashMap<>();

    /** The Spring Boot application classes, by type ID. */
    final List<Fact> bootMains = new ArrayList<>();

    final Map<EntityId, List<Fact>> bootMainsByModule = new HashMap<>();

    /** What contains each package or type, from the Java extractor's {@code CONTAINS} facts. */
    private final Map<EntityId, Set<EntityId>> parents = new HashMap<>();

    /** The attributes of each {@code BUILD_DEPENDS_ON} fact, by fact ID. */
    private final Map<String, BuildDependsOnAttributes> depAttributes = new HashMap<>();

    /** The attributes of each {@code config-value} fact, by fact ID. */
    private final Map<String, ConfigValueAttributes> configAttributes = new HashMap<>();

    BuildDependsOnAttributes dep(Fact dependency) {
      return depAttributes.get(dependency.id());
    }

    ConfigValueAttributes config(Fact value) {
      return configAttributes.get(value.id());
    }

    Index(ClaimQuery in) {
      in.all(BuildDependsOnAttributes.class)
          .forEach(c -> depAttributes.put(c.fact().id(), c.attributes()));
      in.all(ConfigValueAttributes.class)
          .forEach(c -> configAttributes.put(c.fact().id(), c.attributes()));
      List<Fact> facts = new ArrayList<>();
      READS.forEach(predicate -> facts.addAll(in.all(predicate)));
      for (Fact f : facts) {
        String extractor = f.origin().name();
        boolean entity = f.object().kind() == FactObject.Kind.ENTITY;
        switch (f.predicate()) {
          case BuiltInTypes.CONTAINS -> {
            if (!entity) {
              break;
            }
            EntityId object = f.object().entityId();
            String subjectType = f.subject().type();
            if (extractor.equals(MavenExtractor.NAME)
                && object.type().equals(BuiltInTypes.MODULE)
                && (subjectType.equals(BuiltInTypes.SERVICE)
                    || subjectType.equals(BuiltInTypes.LIBRARY))) {
              containerOf.merge(object, f, DeployableUnits::first);
            } else if (!extractor.equals(MavenExtractor.NAME)
                && (object.type().equals(BuiltInTypes.PACKAGE)
                    || object.type().equals(BuiltInTypes.TYPE))) {
              parents.computeIfAbsent(object, o -> new LinkedHashSet<>()).add(f.subject());
            }
          }
          case MavenExtractor.BUILD_DEPENDS_ON -> {
            if (entity) {
              deps.computeIfAbsent(f.subject(), s -> new ArrayList<>()).add(f);
            }
          }
          case MavenExtractor.PACKAGES -> {
            if (entity) {
              packages
                  .computeIfAbsent(f.subject(), s -> new HashSet<>())
                  .add(f.object().entityId());
            }
          }
          case MavenExtractor.PACKAGING -> packaging.put(f.subject(), f.object().value());
          case MavenExtractor.PARENT -> parent.merge(f.subject(), f, DeployableUnits::first);
          case MavenExtractor.JAVA_RELEASE ->
              javaRelease.merge(f.subject(), f, DeployableUnits::first);
          case MavenExtractor.VERSION_ATTRIBUTE -> {
            if (extractor.equals(MavenExtractor.NAME)
                && f.subject().type().equals(BuiltInTypes.MODULE)) {
              moduleVersion.merge(f.subject(), f, DeployableUnits::first);
            }
          }
          case ConfigExtractor.CONFIG_VALUE ->
              config.computeIfAbsent(f.subject(), s -> new ArrayList<>()).add(f);
          case SpringExtractor.MAIN_CLASS -> {
            if (f.object().value().equals(SpringExtractor.SPRING_BOOT)) {
              bootMains.add(f);
            }
          }
          default -> {}
        }
      }
      deps.values().forEach(list -> list.sort(DECLARATION_ORDER));
      config.values().forEach(list -> list.sort(BY_ID));
      bootMains.sort(BY_ID);
      for (Fact main : bootMains) {
        for (EntityId module : modulesOf(main.subject())) {
          bootMainsByModule.computeIfAbsent(module, m -> new ArrayList<>()).add(main);
        }
      }
    }

    /**
     * The {@code parent} fact naming Spring Boot's parent POM ({@code spring-boot-starter-parent}
     * or {@code spring-boot-parent}) that a module inherits from, directly or through parents in
     * the snapshot, or null.
     */
    Fact bootParent(EntityId module) {
      Set<EntityId> seen = new HashSet<>();
      EntityId current = module;
      while (current != null && seen.add(current)) {
        Fact fact = parent.get(current);
        if (fact == null) {
          return null;
        }
        String coordinates = fact.object().value();
        String key = coordinates.substring(0, coordinates.lastIndexOf(':'));
        if (BOOT_PARENTS.contains(key)) {
          return fact;
        }
        try {
          current = new EntityId(BuiltInTypes.MODULE, key);
        } catch (IllegalArgumentException e) {
          return null;
        }
      }
      return null;
    }

    /** The modules that declare a type, found up its packages and enclosing types; by ID. */
    List<EntityId> modulesOf(EntityId type) {
      Set<EntityId> modules = new TreeSet<>(Comparator.comparing(Object::toString));
      Set<EntityId> seen = new HashSet<>();
      Deque<EntityId> queue = new ArrayDeque<>(List.of(type));
      while (!queue.isEmpty()) {
        EntityId current = queue.poll();
        if (!seen.add(current)) {
          continue;
        }
        if (current.type().equals(BuiltInTypes.MODULE)) {
          modules.add(current);
          continue;
        }
        queue.addAll(parents.getOrDefault(current, Set.of()));
      }
      return List.copyOf(modules);
    }
  }

  /** Facts in the order of their subject, object and ID, so choices among them are stable. */
  private static final Comparator<Fact> BY_ID =
      Comparator.comparing((Fact f) -> f.subject().toString())
          .thenComparing(f -> f.object().value())
          .thenComparing(Fact::id);

  /**
   * Dependencies in the order the POM declares them: by the line of their declaration, those
   * without one (inherited) last, then by object.
   */
  private static final Comparator<Fact> DECLARATION_ORDER =
      Comparator.comparing(
              (Fact f) ->
                  f.evidence().lines() == null ? Integer.MAX_VALUE : f.evidence().lines().start())
          .thenComparing(f -> f.object().value())
          .thenComparing(Fact::id);

  /**
   * Of two facts for the same thing, the one first by ID, so the choice does not depend on order.
   */
  private static Fact first(Fact a, Fact b) {
    return BY_ID.compare(a, b) <= 0 ? a : b;
  }

  private static Fact fact(
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Evidence evidence,
      double confidence,
      Validity validity) {
    return Fact.create(
        subject, predicate, object, attributes, ORIGIN, evidence, confidence, validity);
  }

  /** The config family of defaults a library adds in code; the lowest precedence. */
  static final String DEFAULTS = "defaults";

  /**
   * Which config files a {@code config-value} fact comes from: {@code application} or {@code
   * bootstrap}, by the name of the file its evidence names, or {@link #DEFAULTS} for a default a
   * library adds in code, or null.
   */
  static String configFamily(Fact fact) {
    // The cross-repo linker's index reads its config facts as facts until #575.
    return configFamily(fact, ConfigValueAttributes.from(fact.attributes()));
  }

  /** {@link #configFamily(Fact)} of a fact whose attributes are read. */
  static String configFamily(Fact fact, ConfigValueAttributes config) {
    if (ConfigExtractor.ENVIRONMENT_POST_PROCESSOR.equals(config.source())) {
      return DEFAULTS;
    }
    String path = fact.evidence().path();
    if (path == null) {
      return null;
    }
    String file = path.substring(path.lastIndexOf('/') + 1);
    for (String family : List.of("application", "bootstrap")) {
      if (file.startsWith(family + ".") || file.startsWith(family + "-")) {
        return family;
      }
    }
    return null;
  }

  /** The version in a {@code parent} fact's {@code groupId:artifactId:version}. */
  private static String parentVersion(Fact parent) {
    String coordinates = parent.object().value();
    return coordinates.substring(coordinates.lastIndexOf(':') + 1);
  }

  /** Rounded, so a stored confidence reads as it was meant: 0.56, not 0.5599... */
  private static double round(double confidence) {
    return Math.round(confidence * 100) / 100.0;
  }
}
