package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The findings of the architecture model (#619; knowledge-build epic, section 9): each candidate
 * risk the fixed checks find, stored once per snapshot as {@code finding:<kind>/<key>} with a
 * {@code finding-raised} fact whose value is the kind, the rule that raised it, the attributes its
 * kind keys on and lineage to the facts behind it, so documents and roles select findings instead
 * of computing them. Whether a finding is new or gone follows from its validity.
 *
 * <ul>
 *   <li>{@code snapshot-dependency}: a repository's modules depend on unreleased (snapshot)
 *       versions of something it does not build itself, outside test scope; per repository and
 *       target.
 *   <li>{@code dependency-drift}: a repository's modules depend on different versions of one thing;
 *       per repository and target.
 *   <li>{@code package-cycle}: packages of a service or library depend on each other in a cycle;
 *       per cycle.
 *   <li>{@code end-of-life}: a runtime or framework version in use whose free support has an end
 *       the bundled table knows; per product, version and user. Whether it has ended, or ends soon,
 *       is judged as of a document's date, so it is not part of the finding.
 *   <li>{@code version-skew}, {@code shared-jwt-secret}, {@code unrouted-endpoint} and {@code
 *       readme-contradiction}: one per fact the linker or the README claim checks record.
 * </ul>
 */
public final class Findings implements LinkRule.OverFacts {

  public static final String NAME = "findings";

  /** Raised whenever the findings it gives for the same facts change. */
  public static final String VERSION = "1";

  public static final String SNAPSHOT_DEPENDENCY = "snapshot-dependency";
  public static final String DEPENDENCY_DRIFT = "dependency-drift";
  public static final String PACKAGE_CYCLE = "package-cycle";
  public static final String END_OF_LIFE = "end-of-life";
  public static final String VERSION_SKEW = "version-skew";
  public static final String SHARED_JWT_SECRET = "shared-jwt-secret";
  public static final String UNROUTED_ENDPOINT = "unrouted-endpoint";
  public static final String README_CONTRADICTION = "readme-contradiction";

  /**
   * A chunk of a file the injection screen found addressed to an AI (ADR 0101). Unlike the other
   * kinds it is raised by a model, after derivation, with origin {@code llm}.
   */
  public static final String INJECTION_SUSPECTED = "injection-suspected";

  /** A merge of two groups the Name groups role suggested and Lantern never applies (ADR 0107). */
  public static final String GROUP_MERGE = "group-merge";

  /** The origin of the injection screen's findings (ADR 0101). */
  public static final Origin SCREEN_ORIGIN = new Origin(Origin.Kind.LLM, "injection-screen", "1");

  /** How an end-of-life finding's version is in use: see {@link #endOfLife}. */
  public static final String VIA_UNIT = "unit";

  public static final String VIA_DEPENDENCY = "dependency";
  public static final String VIA_JAVA_RELEASE = "java-release";

  /**
   * The linker's (and the README claim checks') facts that are findings as they stand: each
   * predicate, with the kind of finding it becomes (a kind is Findings' own name, even where it
   * equals the predicate's).
   */
  private static final Map<String, String> RECORDED =
      new TreeMap<>(
          Map.of(
              Types.VERSION_SKEW,
              VERSION_SKEW,
              Types.SHARED_SECRET,
              SHARED_JWT_SECRET,
              Types.UNROUTED_ENDPOINT,
              UNROUTED_ENDPOINT,
              Types.CLAIM_CONTRADICTION,
              README_CONTRADICTION));

  private static final String SPRING_GROUP = "org.springframework:spring-";
  private static final String SNAPSHOT = "-SNAPSHOT";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  @Override
  public RuleInfo info() {
    return new RuleInfo(NAME, VERSION, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    // The README claim checks' facts too: they run with the derive rules, before this one.
    return Set.of(EXTRACTED, LINKED, Types.CLAIM_CONTRADICTION);
  }

  @Override
  public Set<String> writes() {
    return Set.of(Types.FINDING_RAISED);
  }

  /** The vocabulary types of the findings' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/findings-types.json");
  }

  /** The finding of a kind with a key, percent-encoded where it holds whitespace. */
  public static EntityId id(String kind, String key) {
    return new EntityId(Types.FINDING, kind + "/" + DeploymentInstances.encoded(key, ""));
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Writer out = new Writer(Validity.from(snapshot));
    for (String repo : knowledge.repos()) {
      dependencies(knowledge, repo, out);
    }
    for (EntityId container : containers(knowledge)) {
      cycles(knowledge, container, out);
    }
    endOfLife(knowledge, out);
    for (Map.Entry<String, String> recorded : RECORDED.entrySet()) {
      for (Fact fact : knowledge.withPredicate(recorded.getKey())) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("about", fact.subject().toString());
        String claim = fact.claimId() == null ? fact.id() : fact.claimId();
        out.add(recorded.getValue(), fact.subject() + "/" + claim, attributes, List.of(fact));
      }
    }
    return Derivation.merged(out.facts);
  }

  // Dependencies on unreleased versions, and the same dependency at different versions.

  private static void dependencies(Knowledge knowledge, String repo, Writer out) {
    SortedMap<EntityId, List<Fact>> byTarget = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId module : knowledge.modules(repo)) {
      for (Fact fact : knowledge.relations(module, Types.BUILD_DEPENDS_ON)) {
        EntityId target = Knowledge.object(fact);
        boolean own = target.type().equals(Types.MODULE) && knowledge.repos(target).contains(repo);
        if (!own && !"test".equals(Screens.attribute(fact, "scope", "compile"))) {
          byTarget.computeIfAbsent(target, t -> new ArrayList<>()).add(fact);
        }
      }
    }
    byTarget.forEach(
        (target, dependencies) -> {
          SortedSet<String> snapshots = new TreeSet<>();
          List<Fact> unreleased = new ArrayList<>();
          SortedSet<String> versions = new TreeSet<>();
          List<Fact> versioned = new ArrayList<>();
          for (Fact fact : dependencies) {
            String version = Screens.attribute(fact, "version", null);
            if (version == null) {
              continue;
            }
            versions.add(version);
            versioned.add(fact);
            if (version.endsWith(SNAPSHOT)) {
              snapshots.add(version);
              unreleased.add(fact);
            }
          }
          if (!snapshots.isEmpty()) {
            out.add(SNAPSHOT_DEPENDENCY, repo, target, snapshots, unreleased);
          }
          if (versions.size() > 1) {
            out.add(DEPENDENCY_DRIFT, repo, target, versions, versioned);
          }
        });
  }

  // Packages that depend on each other in a cycle.

  /** Every service and library whose modules hold code. */
  private static SortedSet<EntityId> containers(Knowledge knowledge) {
    SortedSet<EntityId> containers = new TreeSet<>(Knowledge.BY_ID);
    for (String repo : knowledge.repos()) {
      for (EntityId module : knowledge.modules(repo)) {
        containers.addAll(knowledge.containers(module));
      }
    }
    return containers;
  }

  private static void cycles(Knowledge knowledge, EntityId container, Writer out) {
    Map<EntityId, Map<EntityId, List<Fact>>> edges = PackageGraph.of(knowledge, container);
    for (SortedSet<EntityId> cycle : PackageGraph.stronglyConnected(edges)) {
      if (cycle.size() < 2) {
        continue;
      }
      List<Fact> facts = new ArrayList<>();
      for (EntityId from : cycle) {
        edges
            .getOrDefault(from, Map.of())
            .forEach(
                (to, edgeFacts) -> {
                  if (cycle.contains(to)) {
                    facts.addAll(edgeFacts);
                  }
                });
      }
      ObjectNode attributes = FactJson.newObject();
      attributes.put("about", container.toString());
      strings(attributes, "packages", cycle);
      out.add(PACKAGE_CYCLE, container + "/" + cycle.first(), attributes, facts);
    }
  }

  // Runtime and framework versions with an end of free support.

  /**
   * The runtime and framework versions in use whose line the bundled end-of-life table knows: the
   * Spring Boot and Java versions of the units' technology ({@link #VIA_UNIT}), the Spring
   * Framework versions the modules depend on outside test scope ({@link #VIA_DEPENDENCY}), and the
   * Java release each module that builds code is compiled for ({@link #VIA_JAVA_RELEASE}). Each
   * names who uses it: the unit, or the service or library building the module, else the module.
   */
  private static void endOfLife(Knowledge knowledge, Writer out) {
    for (Fact fact : knowledge.withPredicate(Types.DEPLOYABLE_UNIT)) {
      EntityId unit = fact.subject();
      for (Fact technology : knowledge.attributes(unit)) {
        if (!technology.predicate().equals(Types.TECHNOLOGY)) {
          continue;
        }
        if ("Spring Boot".equals(Screens.attribute(technology, "framework", null))) {
          endOfLife(
              out,
              EndOfLife.Product.SPRING_BOOT,
              Screens.attribute(technology, "frameworkVersion", null),
              unit,
              VIA_UNIT,
              null,
              technology);
        }
        String java = Screens.attribute(technology, "java", null);
        if (java != null) {
          endOfLife(out, EndOfLife.Product.JAVA, java, unit, VIA_UNIT, null, technology);
        }
      }
    }
    for (String repo : knowledge.repos()) {
      for (EntityId module : knowledge.modules(repo)) {
        for (Fact fact : knowledge.relations(module, Types.BUILD_DEPENDS_ON)) {
          EntityId target = Knowledge.object(fact);
          String version = Screens.attribute(fact, "version", null);
          if (target.type().equals(Types.ARTIFACT)
              && target.key().startsWith(SPRING_GROUP)
              && version != null
              && !"test".equals(Screens.attribute(fact, "scope", "compile"))) {
            endOfLife(
                out,
                EndOfLife.Product.SPRING_FRAMEWORK,
                version,
                user(knowledge, module),
                VIA_DEPENDENCY,
                module,
                fact);
          }
        }
        if (knowledge.attribute(module, Types.PACKAGING).orElse("jar").equals("pom")) {
          continue;
        }
        for (Fact fact : knowledge.attributes(module)) {
          if (fact.predicate().equals(Types.JAVA_RELEASE)) {
            endOfLife(
                out,
                EndOfLife.Product.JAVA,
                fact.object().value(),
                user(knowledge, module),
                VIA_JAVA_RELEASE,
                module,
                fact);
          }
        }
      }
    }
  }

  private static void endOfLife(
      Writer out,
      EndOfLife.Product product,
      String version,
      EntityId user,
      String via,
      EntityId module,
      Fact fact) {
    if (version == null) {
      return;
    }
    String shown =
        product == EndOfLife.Product.JAVA
            ? EndOfLife.line(product, version).orElse(version)
            : version;
    EndOfLife.Support support = EndOfLife.BUNDLED.support(product, shown);
    ObjectNode attributes = FactJson.newObject();
    switch (support) {
      case EndOfLife.Support.Until u -> {
        attributes.put("support", "until");
        attributes.put("line", u.line());
        attributes.put("ends", u.end().toString());
      }
      case EndOfLife.Support.Older o -> {
        attributes.put("support", "older");
        attributes.put("line", o.line());
      }
      case EndOfLife.Support.EndedBy b -> {
        attributes.put("support", "ended-by");
        attributes.put("line", b.line());
        attributes.put("ends", b.by().toString());
      }
      case EndOfLife.Support.Unknown u -> {
        return;
      }
    }
    attributes.put("product", product.name());
    attributes.put("version", version);
    attributes.put("about", user.toString());
    attributes.put("via", via);
    if (module != null) {
      attributes.put("module", module.toString());
    }
    String key =
        product.name()
            + "/"
            + version
            + "/"
            + user
            + "/"
            + via
            + (module == null ? "" : "/" + module);
    out.add(END_OF_LIFE, key, attributes, List.of(fact));
  }

  /** Who uses a module's version: the service or library building it, else the module. */
  private static EntityId user(Knowledge knowledge, EntityId module) {
    SortedSet<EntityId> containers = knowledge.containers(module);
    return containers.isEmpty() ? module : containers.first();
  }

  // Writing.

  private static final class Writer {
    final Validity validity;
    final List<Fact> facts = new ArrayList<>();

    Writer(Validity validity) {
      this.validity = validity;
    }

    /** A finding about a repository's dependencies on a target at the versions. */
    void add(
        String kind, String repo, EntityId target, Collection<String> versions, List<Fact> cited) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("about", "repo:" + repo);
      attributes.put("repo", repo);
      attributes.put("target", target.toString());
      strings(attributes, "versions", versions);
      add(kind, repo + "/" + target, attributes, cited);
    }

    void add(String kind, String key, ObjectNode attributes, List<Fact> cited) {
      attributes.put("rule", NAME + "/" + kind);
      ArrayNode ids = attributes.putArray("facts");
      new LinkedHashSet<>(cited).forEach(f -> ids.add(f.id()));
      facts.add(
          Fact.create(
              id(kind, key),
              Types.FINDING_RAISED,
              FactObject.string(kind),
              attributes,
              ORIGIN,
              cited.getFirst().evidence(),
              1.0,
              validity));
    }
  }

  /**
   * The finding that the screen flagged lines of a file as addressed to an AI (ADR 0101), keyed by
   * the file: {@code finding:injection-suspected/<repo>:<path>}.
   */
  public static Fact injectionSuspected(
      String repo, String path, String blob, int from, int to, String quote, long snapshot) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("about", "file:" + repo + ":" + path);
    attributes.put("repo", repo);
    attributes.put("rule", NAME + "/" + INJECTION_SUSPECTED);
    attributes.put("lines", from + "-" + to);
    attributes.put("quote", quote);
    return Fact.create(
        id(INJECTION_SUSPECTED, repo + ":" + path),
        Types.FINDING_RAISED,
        FactObject.string(INJECTION_SUSPECTED),
        attributes,
        SCREEN_ORIGIN,
        new io.lantern.core.fact.Evidence(
            java.util.List.of(
                new io.lantern.core.fact.Evidence.Location(
                    repo,
                    path,
                    new io.lantern.core.fact.Evidence.LineRange(from, to),
                    blob,
                    null,
                    io.lantern.core.fact.Evidence.Role.DECLARED))),
        0.8,
        Validity.from(snapshot));
  }

  private static void strings(ObjectNode attributes, String name, Collection<?> values) {
    ArrayNode array = attributes.putArray(name);
    values.forEach(v -> array.add(v.toString()));
  }

  /** The findings of a kind the model holds, by ID. */
  public static List<Fact> of(ArchitectureModel model, String kind) {
    return of(model.index(), kind);
  }

  /** The findings of a kind stored in the knowledge, by ID. */
  public static List<Fact> of(Knowledge knowledge, String kind) {
    List<Fact> found = new ArrayList<>();
    for (Fact fact : knowledge.withPredicate(Types.FINDING_RAISED)) {
      if (fact.object().value().equals(kind) && cited(knowledge, fact) != null) {
        found.add(fact);
      }
    }
    return found;
  }

  /**
   * A stored finding about a dependency of the repository: the target and the versions it is used
   * at.
   *
   * @param fact the finding
   * @param target the dependency
   * @param versions the versions the repository uses it at, as stored
   */
  public record Dependency(Fact fact, EntityId target, List<String> versions) {}

  /**
   * The findings of a kind about the repository's dependencies, sorted by target, then by fact ID.
   *
   * @param kind {@link #SNAPSHOT_DEPENDENCY} or {@link #DEPENDENCY_DRIFT}
   */
  public static List<Dependency> dependencies(ArchitectureModel model, String kind, String repo) {
    return of(model, kind).stream()
        .filter(f -> repo.equals(attribute(f, "repo")))
        .map(f -> new Dependency(f, EntityId.parse(attribute(f, "target")), strings(f, "versions")))
        .sorted(Comparator.comparing(Dependency::target, ArchitectureModel.BY_ID))
        .toList();
  }

  /**
   * A text addressed to an AI model that the injection screen found in a file of the repository
   * (ADR 0101).
   *
   * @param fact the finding
   * @param lines the lines the text is on
   * @param quote the text
   */
  public record Injection(Fact fact, String lines, String quote) {}

  /** The injection findings of the repository, by the subject they are about. */
  public static List<Injection> injections(ArchitectureModel model, String repo) {
    return of(model, INJECTION_SUSPECTED).stream()
        .filter(f -> repo.equals(attribute(f, "repo")))
        .sorted(Comparator.comparing(f -> f.subject().toString()))
        .map(f -> new Injection(f, attribute(f, "lines"), attribute(f, "quote")))
        .toList();
  }

  /** Every injection finding the model holds, by the subject it is about. */
  public static List<Injection> injections(ArchitectureModel model) {
    return of(model, INJECTION_SUSPECTED).stream()
        .sorted(Comparator.comparing(f -> f.subject().toString()))
        .map(f -> new Injection(f, attribute(f, "lines"), attribute(f, "quote")))
        .toList();
  }

  /**
   * Two groups the Name groups role suggested are one area (ADR 0107), which Lantern does not
   * merge.
   *
   * @param fact the finding
   * @param first the group of the pair whose key sorts first
   * @param second the other group
   * @param reason why the two look like one area, in the model's words
   */
  public record GroupMerge(Fact fact, EntityId first, EntityId second, String reason) {}

  /** The merge suggestions the model holds, by the first group, then the second. */
  public static List<GroupMerge> groupMerges(ArchitectureModel model) {
    return of(model, GROUP_MERGE).stream()
        .map(
            f ->
                new GroupMerge(
                    f,
                    EntityId.parse(attribute(f, "about")),
                    EntityId.parse(attribute(f, "target")),
                    attribute(f, "reason")))
        .sorted(
            Comparator.comparing(GroupMerge::first, ArchitectureModel.BY_ID)
                .thenComparing(GroupMerge::second, ArchitectureModel.BY_ID))
        .toList();
  }

  /**
   * A cycle between packages that a container's packages form (ADR 0039).
   *
   * @param fact the finding
   * @param packages the packages in the cycle, as stored
   */
  public record Cycle(Fact fact, List<String> packages) {}

  /** The package cycles found in the container's code, in the order the model holds them. */
  public static List<Cycle> cycles(ArchitectureModel model, EntityId container) {
    List<Cycle> cycles = new ArrayList<>();
    for (Fact finding : of(model, PACKAGE_CYCLE)) {
      if (container.toString().equals(attribute(finding, "about"))) {
        cycles.add(new Cycle(finding, strings(finding, "packages")));
      }
    }
    return cycles;
  }

  /** The facts a finding cites, as the model holds them. */
  public static List<Fact> cited(ArchitectureModel model, Fact finding) {
    return cited(model.index(), finding);
  }

  /**
   * The facts a finding cites, in order, or null when the knowledge does not hold one of them: a
   * document composed from part of the snapshot's facts shows a finding only whole.
   */
  public static List<Fact> cited(Knowledge knowledge, Fact finding) {
    List<Fact> facts = new ArrayList<>();
    JsonNode ids = finding.attributes().get("facts");
    if (ids == null || !ids.isArray()) {
      return facts;
    }
    for (JsonNode id : ids) {
      Fact fact = knowledge.fact(id.asString());
      if (fact == null) {
        return null;
      }
      facts.add(fact);
    }
    return facts;
  }

  /** The strings of an array attribute of a finding. */
  public static List<String> strings(Fact finding, String name) {
    return Screens.strings(finding, name);
  }

  /** A text attribute of a finding, or null. */
  public static String attribute(Fact finding, String name) {
    return Screens.attribute(finding, name, null);
  }
}
