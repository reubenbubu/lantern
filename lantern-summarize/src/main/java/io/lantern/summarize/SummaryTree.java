package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.docs.DocsExtractor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the summary hierarchy from facts: repository, its top modules, their submodules, packages
 * and types, and member types. The hierarchy follows {@code CONTAINS} facts between modules,
 * packages and types; a module belongs to the repositories its facts were observed in.
 *
 * <p>Each unit's facts are those about it, those about the parts it contains that are not
 * summarized on their own (a type's methods and fields), and those about what contains it outside
 * the hierarchy (the service or library that builds a module). Facts about the unit as the object
 * of a relationship, such as who calls it, are left out, so a unit's summary changes only when the
 * unit itself does. A module's or repository's prompt also shows some facts of its parts, its
 * {@link FactSheet}, which is worked out from the tree when it is summarized.
 *
 * <p>A repository's facts are the first {@value #README_CLAIMS} claims, as written, of its README
 * with the fewest folders in its path (listed in the prompt in its usual fact order), so its
 * summary can take its purpose from what people wrote about it.
 */
public final class SummaryTree {

  /** How many claims of its README a repository's prompt shows at most. */
  static final int README_CLAIMS = 8;

  private final Map<EntityId, List<Fact>> bySubject = new HashMap<>();
  private final Map<EntityId, Set<EntityId>> contains = new HashMap<>();
  private final Map<EntityId, Set<EntityId>> containedBy = new HashMap<>();
  private final Map<EntityId, SummaryNode> built = new HashMap<>();

  private SummaryTree(Collection<Fact> facts) {
    for (Fact fact : facts) {
      bySubject.computeIfAbsent(fact.subject(), s -> new ArrayList<>()).add(fact);
      if (fact.predicate().equals(BuiltInTypes.CONTAINS)
          && fact.object().kind() == FactObject.Kind.ENTITY) {
        EntityId part = EntityId.parse(fact.object().value());
        contains.computeIfAbsent(fact.subject(), s -> new LinkedHashSet<>()).add(part);
        containedBy.computeIfAbsent(part, p -> new LinkedHashSet<>()).add(fact.subject());
      }
    }
  }

  /**
   * One tree per repository the facts were observed in, sorted by repository name. Pass the facts
   * that hold in one snapshot.
   *
   * @throws IllegalArgumentException if the {@code CONTAINS} facts form a cycle
   */
  public static List<SummaryNode> fromFacts(Collection<Fact> facts) {
    SummaryTree tree = new SummaryTree(facts);
    Map<String, Set<EntityId>> modulesByRepo = new TreeMap<>();
    for (Fact fact : facts) {
      if (fact.subject().type().equals(BuiltInTypes.MODULE)) {
        modulesByRepo
            .computeIfAbsent(fact.evidence().repo(), r -> new HashSet<>())
            .add(fact.subject());
      }
    }
    List<SummaryNode> repos = new ArrayList<>();
    modulesByRepo.forEach(
        (repo, modules) -> {
          List<SummaryNode> top = new ArrayList<>();
          for (EntityId module : modules) {
            Set<EntityId> parents = tree.containedBy.getOrDefault(module, Set.of());
            if (parents.stream().noneMatch(modules::contains)) {
              top.add(tree.node(module, new HashSet<>()));
            }
          }
          repos.add(
              new SummaryNode(
                  new EntityId(SummaryLevel.REPO_ENTITY_TYPE, repo),
                  SummaryLevel.REPO,
                  readmeClaims(facts, repo),
                  top));
        });
    return List.copyOf(repos);
  }

  /**
   * The first {@value #README_CLAIMS} claims, in the order written, of the repository's README with
   * the fewest folders in its path (the first such path in name order if several have as few).
   */
  static List<Fact> readmeClaims(Collection<Fact> facts, String repo) {
    List<Fact> claims =
        facts.stream()
            .filter(
                f ->
                    f.predicate().equals(DocsExtractor.CLAIM)
                        && f.subject().type().equals(DocsExtractor.README)
                        && f.evidence().repo().equals(repo)
                        && f.evidence().path() != null)
            .toList();
    Optional<String> readme =
        claims.stream()
            .map(f -> f.evidence().path())
            .min(
                Comparator.comparingLong(
                        (String path) -> path.chars().filter(c -> c == '/').count())
                    .thenComparing(Comparator.naturalOrder()));
    return claims.stream()
        .filter(f -> f.evidence().path().equals(readme.orElse(null)))
        .sorted(
            Comparator.comparingInt(
                    (Fact f) -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(Fact::id))
        .limit(README_CLAIMS)
        .toList();
  }

  private SummaryNode node(EntityId entity, Set<EntityId> visiting) {
    SummaryNode done = built.get(entity);
    if (done != null) {
      return done;
    }
    if (!visiting.add(entity)) {
      throw new IllegalArgumentException("CONTAINS facts form a cycle through " + entity);
    }
    List<Fact> facts = new ArrayList<>(bySubject.getOrDefault(entity, List.of()));
    List<SummaryNode> children = new ArrayList<>();
    for (EntityId part : contains.getOrDefault(entity, Set.of())) {
      SummaryLevel level = SummaryLevel.of(part);
      if (level == null) {
        facts.addAll(bySubject.getOrDefault(part, List.of()));
      } else if (level != SummaryLevel.REPO) {
        children.add(node(part, visiting));
      }
    }
    for (EntityId whole : containedBy.getOrDefault(entity, Set.of())) {
      if (SummaryLevel.of(whole) == null) {
        facts.addAll(bySubject.getOrDefault(whole, List.of()));
      }
    }
    visiting.remove(entity);
    SummaryNode node = new SummaryNode(entity, SummaryLevel.of(entity), facts, children);
    built.put(entity, node);
    return node;
  }
}
