package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
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

  private static final Comparator<Fact> PROMPT_ORDER =
      Comparator.comparing(Prompt::fact)
          .thenComparing(f -> f.evidence().repo())
          .thenComparing(f -> Objects.requireNonNullElse(f.evidence().path(), ""))
          .thenComparingInt(f -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
          .thenComparing(Fact::id);

  public SummaryNode {
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(level, "level");
    facts =
        Objects.requireNonNull(facts, "facts").stream().sorted(PROMPT_ORDER).distinct().toList();
    children =
        Objects.requireNonNull(children, "children").stream()
            .sorted(Comparator.comparing(child -> child.entity().toString()))
            .toList();
  }
}
