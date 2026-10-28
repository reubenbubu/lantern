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
 * @param facts the facts about it, sorted by ID
 * @param children the units it contains, sorted by entity ID
 */
public record SummaryNode(
    EntityId entity, SummaryLevel level, List<Fact> facts, List<SummaryNode> children) {

  public SummaryNode {
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(level, "level");
    facts =
        Objects.requireNonNull(facts, "facts").stream()
            .sorted(Comparator.comparing(Fact::id))
            .distinct()
            .toList();
    children =
        Objects.requireNonNull(children, "children").stream()
            .sorted(Comparator.comparing(child -> child.entity().toString()))
            .toList();
  }
}
