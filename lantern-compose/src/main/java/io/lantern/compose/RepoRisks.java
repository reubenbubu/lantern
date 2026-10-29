package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Finds the risks of one repository with fixed checks of its knowledge (see ADR 0015), each a risk
 * callout with a severity, a consequence, a confidence and the facts it came from, most serious
 * first (ADR 0051). Besides those technology currency finds ({@link BriefSections}):
 *
 * <ul>
 *   <li>dependencies on unreleased (snapshot) versions of anything the repository does not build
 *       itself (medium);
 *   <li>the same dependency at different versions in different modules (medium);
 *   <li>packages of a service or library that depend on each other in a cycle (low).
 * </ul>
 *
 * <p>Test-scoped dependencies are left out. Without any risk the section gets a note saying what
 * was checked.
 */
final class RepoRisks {

  /** How many items a callout names before it says how many more there are. */
  static final int NAMED = 5;

  private static final String SNAPSHOT = "-SNAPSHOT";

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final String repo;
  private final SortedSet<EntityId> modules;
  private final SortedSet<EntityId> containers;
  private final Function<EntityId, Map<EntityId, Map<EntityId, List<Fact>>>> packageEdges;

  RepoRisks(
      Knowledge knowledge,
      Assembler assembler,
      String repo,
      SortedSet<EntityId> modules,
      SortedSet<EntityId> containers,
      Function<EntityId, Map<EntityId, Map<EntityId, List<Fact>>>> packageEdges) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.repo = repo;
    this.modules = modules;
    this.containers = containers;
    this.packageEdges = packageEdges;
  }

  /**
   * The risk callouts, or a note that none was found.
   *
   * @param currency the risks technology currency found, which come first among those of the same
   *     severity
   */
  List<Block> callouts(String section, List<Callout> currency) {
    List<Callout> risks = new ArrayList<>(currency);
    unreleased(section, risks);
    inconsistentVersions(section, risks);
    for (EntityId container : containers) {
      cycles(section, container, risks);
    }
    // Stable: risks of the same severity keep the order of the checks.
    risks.sort(
        Comparator.comparing(Callout::severity, Comparator.nullsLast(Comparator.naturalOrder())));
    if (risks.isEmpty()) {
      return List.of(
          new Callout(
              assembler.id(section, "none"),
              Callout.Variant.NOTE,
              null,
              "No risks found",
              List.of(
                  TextRun.of(
                      "Lantern checks for runtime and framework versions past or near the end of"
                          + " their free support, dependencies on unreleased versions,"
                          + " inconsistent dependency versions and packages that depend on each"
                          + " other in a cycle, and found none.")),
              List.of()));
    }
    return List.copyOf(risks);
  }

  /**
   * The repository's dependencies outside test scope on something it does not build itself, by
   * target.
   */
  private SortedMap<EntityId, List<Fact>> externalDependencies() {
    SortedMap<EntityId, List<Fact>> byTarget = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId module : modules) {
      for (Fact fact : knowledge.relations(module, RepoSections.BUILD_DEPENDS_ON)) {
        EntityId target = Knowledge.object(fact);
        boolean own =
            target.type().equals(BuiltInTypes.MODULE) && knowledge.repos(target).contains(repo);
        if (!own && !RepoSections.isTestScope(fact)) {
          byTarget.computeIfAbsent(target, t -> new ArrayList<>()).add(fact);
        }
      }
    }
    return byTarget;
  }

  private static String version(Fact fact) {
    return RepoSections.attribute(fact, "version", null);
  }

  // Dependencies on unreleased versions.

  private void unreleased(String section, List<Callout> risks) {
    List<Named> named = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    externalDependencies()
        .forEach(
            (target, dependencies) -> {
              SortedSet<String> versions = new TreeSet<>();
              for (Fact fact : dependencies) {
                String version = version(fact);
                if (version != null && version.endsWith(SNAPSHOT)) {
                  versions.add(version);
                  facts.add(fact);
                }
              }
              if (!versions.isEmpty()) {
                named.add(new Named(target, " (" + String.join(", ", versions) + ")"));
              }
            });
    if (named.isEmpty()) {
      return;
    }
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            repo
                + " is built against "
                + (named.size() == 1
                    ? "1 dependency on an unreleased (snapshot) version"
                    : named.size() + " dependencies on unreleased (snapshot) versions")
                + ": "));
    content.addAll(names(named));
    risks.add(
        risk(
            assembler.id(section, "unreleased"),
            Callout.Severity.MEDIUM,
            "Depends on unreleased versions",
            content,
            facts,
            "A release of it cannot be rebuilt exactly and may change without notice."));
  }

  // The same dependency at different versions.

  private void inconsistentVersions(String section, List<Callout> risks) {
    List<Named> named = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    externalDependencies()
        .forEach(
            (target, dependencies) -> {
              SortedSet<String> versions = new TreeSet<>();
              dependencies.stream()
                  .map(RepoRisks::version)
                  .filter(v -> v != null)
                  .forEach(versions::add);
              if (versions.size() > 1) {
                named.add(new Named(target, " (" + String.join(", ", versions) + ")"));
                dependencies.stream().filter(f -> version(f) != null).forEach(facts::add);
              }
            });
    if (named.isEmpty()) {
      return;
    }
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            repo
                + " uses different versions of "
                + count(named.size(), "dependency", "dependencies")
                + ": "));
    content.addAll(names(named));
    risks.add(
        risk(
            assembler.id(section, "inconsistent-versions"),
            Callout.Severity.MEDIUM,
            "Inconsistent dependency versions",
            content,
            facts,
            "The versions can conflict when they run together."));
  }

  // Packages that depend on each other in a cycle.

  private void cycles(String section, EntityId container, List<Callout> risks) {
    Map<EntityId, Map<EntityId, List<Fact>>> edges = packageEdges.apply(container);
    List<SortedSet<EntityId>> cycles = new ArrayList<>();
    for (SortedSet<EntityId> component : stronglyConnected(edges)) {
      if (component.size() > 1) {
        cycles.add(component);
      }
    }
    if (cycles.isEmpty()) {
      return;
    }
    // Largest cycle first, then by its first package.
    cycles.sort(
        Comparator.comparing((SortedSet<EntityId> c) -> -c.size())
            .thenComparing(c -> c.first().toString()));
    List<Fact> facts = new ArrayList<>();
    int packages = 0;
    for (SortedSet<EntityId> cycle : cycles) {
      packages += cycle.size();
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
    }
    String name = assembler.name(container);
    List<Inline> content = new ArrayList<>();
    content.add(
        TextRun.of(
            count(packages, "package", "packages")
                + " of "
                + name
                + (cycles.size() == 1
                    ? " depend on each other in a cycle"
                    : " depend on each other in " + cycles.size() + " cycles")
                + ". "
                + (cycles.size() == 1 ? "The cycle: " : "The largest: ")));
    List<Named> named = new ArrayList<>();
    cycles.getFirst().forEach(pkg -> named.add(new Named(pkg, "")));
    content.addAll(names(named));
    risks.add(
        risk(
            assembler.id(section, "cycles", container.toString()),
            Callout.Severity.LOW,
            "Tangled packages in " + name,
            content,
            facts,
            "None of them can be changed, tested or split out on its own."));
  }

  /** A risk whose confidence is the lowest of its facts'. */
  private Callout risk(
      String id,
      Callout.Severity severity,
      String title,
      List<Inline> content,
      List<Fact> facts,
      String consequence) {
    return new Callout(
        id,
        Callout.Variant.RISK,
        severity,
        title,
        content,
        assembler.facts(facts),
        consequence,
        BriefSections.confidence(facts, BigDecimal.ONE));
  }

  /**
   * The strongly connected components of the graph (Tarjan's algorithm, without recursion), each
   * sorted. Nodes are visited in ID order, so the result does not depend on the input's order.
   */
  static List<SortedSet<EntityId>> stronglyConnected(
      Map<EntityId, Map<EntityId, List<Fact>>> edges) {
    SortedSet<EntityId> nodes = new TreeSet<>(Knowledge.BY_ID);
    edges.forEach(
        (from, targets) -> {
          nodes.add(from);
          nodes.addAll(targets.keySet());
        });
    Map<EntityId, Integer> index = new HashMap<>();
    Map<EntityId, Integer> low = new HashMap<>();
    Deque<EntityId> stack = new ArrayDeque<>();
    Set<EntityId> onStack = new HashSet<>();
    List<SortedSet<EntityId>> components = new ArrayList<>();
    int[] next = {0};
    for (EntityId root : nodes) {
      if (index.containsKey(root)) {
        continue;
      }
      // Each frame is a node and the iterator over its successors.
      Deque<Map.Entry<EntityId, Iterator<EntityId>>> frames = new ArrayDeque<>();
      enter(root, edges, index, low, stack, onStack, frames, next);
      while (!frames.isEmpty()) {
        Map.Entry<EntityId, Iterator<EntityId>> frame = frames.peek();
        EntityId node = frame.getKey();
        if (frame.getValue().hasNext()) {
          EntityId successor = frame.getValue().next();
          if (!index.containsKey(successor)) {
            enter(successor, edges, index, low, stack, onStack, frames, next);
          } else if (onStack.contains(successor)) {
            low.put(node, Math.min(low.get(node), index.get(successor)));
          }
          continue;
        }
        frames.pop();
        if (!frames.isEmpty()) {
          EntityId parent = frames.peek().getKey();
          low.put(parent, Math.min(low.get(parent), low.get(node)));
        }
        if (low.get(node).equals(index.get(node))) {
          SortedSet<EntityId> component = new TreeSet<>(Knowledge.BY_ID);
          EntityId member;
          do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
          } while (!member.equals(node));
          components.add(component);
        }
      }
    }
    return components;
  }

  private static void enter(
      EntityId node,
      Map<EntityId, Map<EntityId, List<Fact>>> edges,
      Map<EntityId, Integer> index,
      Map<EntityId, Integer> low,
      Deque<EntityId> stack,
      Set<EntityId> onStack,
      Deque<Map.Entry<EntityId, Iterator<EntityId>>> frames,
      int[] next) {
    index.put(node, next[0]);
    low.put(node, next[0]);
    next[0]++;
    stack.push(node);
    onStack.add(node);
    SortedSet<EntityId> successors = new TreeSet<>(Knowledge.BY_ID);
    successors.addAll(edges.getOrDefault(node, Map.of()).keySet());
    frames.push(Map.entry(node, successors.iterator()));
  }

  // Text.

  /** An entity named in a callout, with text after its name. */
  private record Named(EntityId entity, String after) {}

  /**
   * The first {@value #NAMED} entities as cross-references separated by commas, then how many more
   * there are.
   */
  private List<Inline> names(List<Named> named) {
    List<Inline> inlines = new ArrayList<>();
    int shown = Math.min(named.size(), NAMED);
    for (int i = 0; i < shown; i++) {
      Named n = named.get(i);
      assembler.entity(n.entity());
      inlines.add(
          new CrossReference(assembler.name(n.entity()), CrossReference.Target.entity(n.entity())));
      String separator =
          i == shown - 1
              ? (named.size() > shown ? ", and " + (named.size() - shown) + " more." : ".")
              : (i == shown - 2 && named.size() == shown ? " and " : ", ");
      inlines.add(TextRun.of(n.after() + separator));
    }
    return inlines;
  }

  private static String count(int n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }
}
