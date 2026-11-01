package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For tests that write the resolved message facts by hand: the relations value resolution gives
 * with them (#597), which the messaging matcher reads, each method's module taken from the types
 * that contain it.
 */
final class MessageRelationsFixture {

  private MessageRelationsFixture() {}

  /** The facts, with value resolution's relations for their message facts. */
  static List<Fact> withRelations(List<Fact> facts) {
    LinkIndex index = new LinkIndex(facts);
    Map<EntityId, String> repos = new HashMap<>();
    List<Fact> messages = new ArrayList<>();
    for (Fact f : facts) {
      if (MessageRelations.RELATION.containsKey(f.predicate())) {
        repos.putIfAbsent(f.subject(), f.evidence().repo());
        messages.add(
            Fact.create(
                f.subject(),
                f.predicate(),
                f.object(),
                f.attributes(),
                new ValueResolution().origin(),
                f.evidence(),
                f.confidence(),
                f.validity()));
      }
    }
    List<Fact> all = new ArrayList<>(facts);
    all.addAll(
        MessageRelations.of(
            messages,
            subject -> {
              List<EntityId> modules = index.modulesOf(subject);
              return modules.isEmpty()
                  ? null
                  : new MessageRelations.Config(modules.getFirst().toString(), repos.get(subject));
            }));
    return all;
  }
}
