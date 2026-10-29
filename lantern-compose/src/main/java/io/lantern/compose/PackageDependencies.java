package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The dependencies between the packages of a service or library, read from the code: package to
 * package to the facts, where a type in the first calls, extends or implements a type in the
 * second. The Executive Brief's package-cycle risk reads them (ADR 0015); packages are not drawn as
 * components (docs/design.md, Diagrams).
 */
final class PackageDependencies {

  private static final String METHOD = "method";
  private static final String FIELD = "field";

  private PackageDependencies() {}

  static Map<EntityId, Map<EntityId, List<Fact>>> of(Knowledge knowledge, EntityId container) {
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
            EntityId target = ownerType(knowledge, Knowledge.object(fact));
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

  /** The type itself, or the type that declares a method or field. */
  static EntityId ownerType(Knowledge knowledge, EntityId entity) {
    if (entity.type().equals(METHOD) || entity.type().equals(FIELD)) {
      SortedSet<EntityId> owners = knowledge.wholes(entity, BuiltInTypes.TYPE);
      return owners.isEmpty() ? entity : owners.first();
    }
    return entity;
  }
}
