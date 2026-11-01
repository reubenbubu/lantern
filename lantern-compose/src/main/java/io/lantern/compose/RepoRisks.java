package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Inline;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.Findings;
import io.lantern.derive.Knowledge;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The risks of one repository, from the findings the fixed checks stored (see ADR 0015; {@link
 * Findings}, #619), each a risk callout with a severity, a consequence, a confidence and the facts
 * it came from, most serious first (ADR 0052). Besides those technology currency finds ({@link
 * BriefSections}):
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

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final String repo;
  private final SortedSet<EntityId> containers;

  RepoRisks(Knowledge knowledge, Assembler assembler, String repo, SortedSet<EntityId> containers) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.repo = repo;
    this.containers = containers;
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

  // Dependencies on unreleased versions, and the same dependency at different versions.

  /** The repository's stored findings of a kind about dependencies, by target. */
  private List<Fact> dependencyFindings(String kind) {
    return Findings.of(knowledge, kind).stream()
        .filter(f -> repo.equals(Findings.attribute(f, "repo")))
        .sorted(
            Comparator.comparing(
                (Fact f) -> EntityId.parse(Findings.attribute(f, "target")), Knowledge.BY_ID))
        .toList();
  }

  /** The findings' targets, each with its versions, and the facts they cite. */
  private List<Named> targets(List<Fact> findings, List<Fact> facts) {
    List<Named> named = new ArrayList<>();
    for (Fact finding : findings) {
      named.add(
          new Named(
              EntityId.parse(Findings.attribute(finding, "target")),
              " (" + String.join(", ", Findings.strings(finding, "versions")) + ")"));
      facts.addAll(Findings.cited(knowledge, finding));
    }
    return named;
  }

  private void unreleased(String section, List<Callout> risks) {
    List<Fact> facts = new ArrayList<>();
    List<Named> named = targets(dependencyFindings(Findings.SNAPSHOT_DEPENDENCY), facts);
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

  private void inconsistentVersions(String section, List<Callout> risks) {
    List<Fact> facts = new ArrayList<>();
    List<Named> named = targets(dependencyFindings(Findings.DEPENDENCY_DRIFT), facts);
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
    List<SortedSet<EntityId>> cycles = new ArrayList<>();
    List<Fact> facts = new ArrayList<>();
    for (Fact finding : Findings.of(knowledge, Findings.PACKAGE_CYCLE)) {
      if (container.toString().equals(Findings.attribute(finding, "about"))) {
        SortedSet<EntityId> cycle = new TreeSet<>(Knowledge.BY_ID);
        Findings.strings(finding, "packages").forEach(p -> cycle.add(EntityId.parse(p)));
        cycles.add(cycle);
        facts.addAll(Findings.cited(knowledge, finding));
      }
    }
    if (cycles.isEmpty()) {
      return;
    }
    // Largest cycle first, then by its first package.
    cycles.sort(
        Comparator.comparing((SortedSet<EntityId> c) -> -c.size())
            .thenComparing(c -> c.first().toString()));
    int packages = 0;
    for (SortedSet<EntityId> cycle : cycles) {
      packages += cycle.size();
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
