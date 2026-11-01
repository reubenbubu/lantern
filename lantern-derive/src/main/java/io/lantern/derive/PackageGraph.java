package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The dependencies between the packages of a service or library, read from the code: package to
 * package to the facts, where a type in the first calls, extends or implements a type in the
 * second, and the cycles among them. The package-cycle finding reads them (ADR 0015, #619);
 * packages are not drawn as components (docs/design.md, Diagrams).
 */
public final class PackageGraph {

  private static final String METHOD = "method";

  private PackageGraph() {}

  public static Map<EntityId, Map<EntityId, List<Fact>>> of(
      Knowledge knowledge, EntityId container) {
    Map<EntityId, SortedSet<EntityId>> packagesOfType = new TreeMap<>(Knowledge.BY_ID);
    for (EntityId module : knowledge.parts(container, BuiltInTypes.MODULE)) {
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        for (EntityId type : knowledge.typesWithin(pkg)) {
          packagesOfType.computeIfAbsent(type, t -> new TreeSet<>(Knowledge.BY_ID)).add(pkg);
        }
      }
    }
    Map<EntityId, Map<EntityId, List<Fact>>> edges = new TreeMap<>(Knowledge.BY_ID);
    packagesOfType.forEach(
        (type, from) -> {
          for (Fact fact : dependencies(knowledge, type)) {
            EntityId target = Components.ownerType(knowledge, Knowledge.object(fact));
            for (EntityId to : packagesOfType.getOrDefault(target, new TreeSet<>())) {
              for (EntityId pkg : from) {
                if (!pkg.equals(to)) {
                  edges
                      .computeIfAbsent(pkg, p -> new TreeMap<>(Knowledge.BY_ID))
                      .computeIfAbsent(to, t -> new ArrayList<>())
                      .add(fact);
                }
              }
            }
          }
        });
    return edges;
  }

  /** What a type depends on: its supertypes and the calls it and its members make. */
  private static List<Fact> dependencies(Knowledge knowledge, EntityId type) {
    List<Fact> facts = new ArrayList<>();
    facts.addAll(knowledge.relations(type, BuiltInTypes.EXTENDS));
    facts.addAll(knowledge.relations(type, BuiltInTypes.IMPLEMENTS));
    facts.addAll(knowledge.relations(type, BuiltInTypes.CALLS));
    for (EntityId method : knowledge.parts(type, METHOD)) {
      facts.addAll(knowledge.relations(method, BuiltInTypes.CALLS));
    }
    return facts;
  }

  /**
   * The strongly connected components of the graph (Tarjan's algorithm, without recursion), each
   * sorted. Nodes are visited in ID order, so the result does not depend on the input's order.
   */
  public static List<SortedSet<EntityId>> stronglyConnected(
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
}
