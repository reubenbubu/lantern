package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Which flows each scope draws, chosen by the flow significance role and by the rank fallback on
 * the same candidates, side by side, so the scorecard can hold the model's choice against the
 * rank's on one run (ADR 0108, amendment for #743). The scopes are those whose documents draw
 * flows: the system, from every fact, and each repository with a unit, from the facts its documents
 * are composed from, as Describe's flow roles ask about them.
 */
public final class FlowSelection {

  /**
   * One scope's choice.
   *
   * @param scope {@link ArchitectureModel#SYSTEM_SCOPE} or a repository's name
   * @param candidates the candidate flows, one for each shape, by ID
   * @param judged the flows drawn from the model's judgement, in order, by ID; null when the scope
   *     has no judgement that holds (none stored, or one made on other steps)
   * @param rank the flows the rank fallback would draw, in order, by ID
   * @param overlaps the pairs of drawn flows ({@code judged} when there is one, else {@code rank})
   *     of which one holds the other or both have one shape ({@link ScopeFlows#overlaps})
   */
  public record Scope(
      String scope,
      List<String> candidates,
      List<String> judged,
      List<String> rank,
      List<String> overlaps) {}

  private FlowSelection() {}

  /**
   * Every scope's choice.
   *
   * @param systemFacts every fact the system's documents are composed from, the roles' texts
   *     included
   * @param repoFacts the facts the repositories' documents are composed from
   * @param systemName the name a document gives the system
   */
  public static List<Scope> of(
      Collection<Fact> systemFacts, Collection<Fact> repoFacts, String systemName) {
    List<Scope> result = new ArrayList<>();
    ArchitectureModel model = new ArchitectureModel(systemFacts);
    Knowledge knowledge = new Knowledge(systemFacts);
    Elements elements =
        new Elements(
            knowledge, model.vocabulary(), new ElementNames(knowledge, systemName), Set.of());
    result.add(
        scope(
            ArchitectureModel.SYSTEM_SCOPE,
            model,
            new ScopeFlows(knowledge, elements, Elements.units(knowledge))));
    ArchitectureModel repoModel = new ArchitectureModel(repoFacts);
    Knowledge repoKnowledge = new Knowledge(repoFacts);
    ElementNames repoNames = new ElementNames(repoKnowledge, systemName);
    SortedSet<String> repos = new TreeSet<>();
    repoFacts.forEach(f -> repos.add(f.evidence().repo()));
    for (String repo : repos) {
      SortedSet<EntityId> units = Elements.units(repoKnowledge, repoKnowledge.modules(repo));
      if (units.isEmpty()) {
        continue;
      }
      Elements repoElements =
          new Elements(repoKnowledge, repoModel.vocabulary(), repoNames, Set.of(repo));
      result.add(scope(repo, repoModel, new ScopeFlows(repoKnowledge, repoElements, units)));
    }
    return result;
  }

  private static Scope scope(String key, ArchitectureModel model, ScopeFlows flows) {
    List<String> candidates = flows.candidates().stream().map(c -> c.flow().toString()).toList();
    List<ScopeFlows.Trace> rank = flows.chosen(null);
    List<ScopeFlows.Trace> judged = model.significantFlows(key).map(flows::chosen).orElse(null);
    return new Scope(
        key,
        candidates,
        judged == null ? null : ids(judged),
        ids(rank),
        ScopeFlows.overlaps(judged != null ? judged : rank));
  }

  private static List<String> ids(List<ScopeFlows.Trace> traces) {
    return traces.stream().map(t -> t.flow().id().toString()).toList();
  }
}
