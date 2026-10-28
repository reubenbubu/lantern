package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The facts of one snapshot, indexed for composing. Every list and set it returns is sorted (facts
 * by ID, entities by ID), so what is built from it does not depend on the order facts came in.
 */
final class Knowledge {

  static final Comparator<EntityId> BY_ID = Comparator.comparing(EntityId::toString);

  private final Map<String, Fact> byId = new HashMap<>();
  private final Map<EntityId, List<Fact>> bySubject = new HashMap<>();
  private final Map<String, List<Fact>> byPredicate = new HashMap<>();
  private final Map<EntityId, List<Fact>> containsByPart = new HashMap<>();
  private final Map<EntityId, List<Fact>> containsByWhole = new HashMap<>();
  private final Map<String, SortedSet<EntityId>> modulesByRepo = new HashMap<>();
  private final Map<EntityId, SortedSet<String>> reposByModule = new HashMap<>();

  Knowledge(Collection<Fact> facts) {
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(Comparator.comparing(Fact::id));
    for (Fact fact : sorted) {
      if (byId.putIfAbsent(fact.id(), fact) != null) {
        continue;
      }
      bySubject.computeIfAbsent(fact.subject(), s -> new ArrayList<>()).add(fact);
      byPredicate.computeIfAbsent(fact.predicate(), p -> new ArrayList<>()).add(fact);
      if (fact.predicate().equals(BuiltInTypes.CONTAINS) && isEntity(fact)) {
        containsByWhole.computeIfAbsent(fact.subject(), s -> new ArrayList<>()).add(fact);
        containsByPart.computeIfAbsent(object(fact), p -> new ArrayList<>()).add(fact);
      }
      if (fact.subject().type().equals(BuiltInTypes.MODULE)) {
        String repo = fact.evidence().repo();
        modulesByRepo.computeIfAbsent(repo, r -> new TreeSet<>(BY_ID)).add(fact.subject());
        reposByModule.computeIfAbsent(fact.subject(), m -> new TreeSet<>()).add(repo);
      }
    }
  }

  static boolean isEntity(Fact fact) {
    return fact.object().kind() == FactObject.Kind.ENTITY;
  }

  static EntityId object(Fact fact) {
    return fact.object().entityId();
  }

  /** The fact with the ID, or {@code null}. */
  Fact fact(String id) {
    return byId.get(id);
  }

  /** The facts with the entity as subject. */
  List<Fact> about(EntityId subject) {
    return bySubject.getOrDefault(subject, List.of());
  }

  /** The facts with the predicate. */
  List<Fact> withPredicate(String predicate) {
    return byPredicate.getOrDefault(predicate, List.of());
  }

  /** The facts relating the subject to an entity by the predicate. */
  List<Fact> relations(EntityId subject, String predicate) {
    return about(subject).stream()
        .filter(f -> f.predicate().equals(predicate) && isEntity(f))
        .toList();
  }

  /** The facts with the entity as subject and a value, not an entity, as object. */
  List<Fact> attributes(EntityId subject) {
    return about(subject).stream().filter(f -> !isEntity(f)).toList();
  }

  /** The value of the first attribute fact with the predicate, by fact ID. */
  Optional<String> attribute(EntityId subject, String predicate) {
    return attributes(subject).stream()
        .filter(f -> f.predicate().equals(predicate))
        .map(f -> f.object().value())
        .findFirst();
  }

  /** What the entity contains, of the given entity type. */
  SortedSet<EntityId> parts(EntityId whole, String type) {
    SortedSet<EntityId> parts = new TreeSet<>(BY_ID);
    for (Fact fact : containsByWhole.getOrDefault(whole, List.of())) {
      if (object(fact).type().equals(type)) {
        parts.add(object(fact));
      }
    }
    return parts;
  }

  /** What contains the entity, of the given entity type. */
  SortedSet<EntityId> wholes(EntityId part, String type) {
    SortedSet<EntityId> wholes = new TreeSet<>(BY_ID);
    for (Fact fact : containsByPart.getOrDefault(part, List.of())) {
      if (fact.subject().type().equals(type)) {
        wholes.add(fact.subject());
      }
    }
    return wholes;
  }

  /** Everything that contains the entity. */
  SortedSet<EntityId> wholes(EntityId part) {
    SortedSet<EntityId> wholes = new TreeSet<>(BY_ID);
    containsByPart.getOrDefault(part, List.of()).forEach(f -> wholes.add(f.subject()));
    return wholes;
  }

  /** The facts saying that the whole contains the part. */
  List<Fact> containsFacts(EntityId whole, EntityId part) {
    return containsByPart.getOrDefault(part, List.of()).stream()
        .filter(f -> f.subject().equals(whole))
        .toList();
  }

  /** The modules whose facts were observed in the repository. */
  SortedSet<EntityId> modules(String repo) {
    return modulesByRepo.getOrDefault(repo, new TreeSet<>(BY_ID));
  }

  /** The repositories the module's facts were observed in. */
  SortedSet<String> repos(EntityId module) {
    return reposByModule.getOrDefault(module, new TreeSet<>());
  }

  /** The services and libraries that build the module. */
  SortedSet<EntityId> containers(EntityId module) {
    SortedSet<EntityId> containers = wholes(module, BuiltInTypes.SERVICE);
    containers.addAll(wholes(module, BuiltInTypes.LIBRARY));
    return containers;
  }

  /** The types directly in a package, module or type, and their member types, recursively. */
  SortedSet<EntityId> typesWithin(EntityId whole) {
    SortedSet<EntityId> types = new TreeSet<>(BY_ID);
    List<EntityId> todo = new ArrayList<>(parts(whole, BuiltInTypes.TYPE));
    while (!todo.isEmpty()) {
      EntityId type = todo.removeLast();
      if (types.add(type)) {
        todo.addAll(parts(type, BuiltInTypes.TYPE));
      }
    }
    return types;
  }
}
