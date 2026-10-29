package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A unit to summarize: an entity, the facts its summary is made from, and the units it contains,
 * whose summaries it builds on. Build a tree from stored facts with {@link SummaryTree}.
 *
 * @param entity the entity summarized
 * @param level its level in the hierarchy
 * @param facts the facts about it, in the order its prompt lists them: by the fact as the prompt
 *     writes it, then by where it was observed (repository, file and first line) and by ID; not by
 *     ID first, since fact IDs change with the commit while the prompt should not
 * @param children the units it contains, sorted by entity ID
 */
public record SummaryNode(
    EntityId entity, SummaryLevel level, List<Fact> facts, List<SummaryNode> children) {

  /** A fact with its line as the prompt writes it, worked out once for sorting. */
  private record Line(String text, Fact fact) {}

  private static final Comparator<Line> PROMPT_ORDER =
      Comparator.comparing(Line::text)
          .thenComparing(l -> l.fact().evidence().repo())
          .thenComparing(l -> Objects.requireNonNullElse(l.fact().evidence().path(), ""))
          .thenComparingInt(
              l -> l.fact().evidence().lines() == null ? 0 : l.fact().evidence().lines().start())
          .thenComparing(l -> l.fact().id());

  /**
   * The facts without repeats, in the order a prompt lists them: by the fact as the prompt writes
   * it, then by where it was observed and by ID.
   */
  static List<Fact> inPromptOrder(Collection<Fact> facts) {
    return facts.stream()
        .distinct()
        .map(f -> new Line(Prompt.fact(f), f))
        .sorted(PROMPT_ORDER)
        .map(Line::fact)
        .toList();
  }

  public SummaryNode {
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(level, "level");
    facts = inPromptOrder(Objects.requireNonNull(facts, "facts"));
    children =
        Objects.requireNonNull(children, "children").stream()
            .sorted(Comparator.comparing(child -> child.entity().toString()))
            .toList();
  }
}
