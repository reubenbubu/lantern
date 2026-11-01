package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
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
import tools.jackson.databind.JsonNode;

/**
 * The facts of one snapshot, indexed for deriving the architecture model and composing. Every list
 * and set it returns is sorted (facts by ID, entities by ID), so what is built from it does not
 * depend on the order facts came in.
 */
public final class Knowledge {

  public static final Comparator<EntityId> BY_ID = Comparator.comparing(EntityId::toString);

  private final Map<String, Fact> byId = new HashMap<>();
  private final Map<EntityId, List<Fact>> bySubject = new HashMap<>();
  private final Map<String, List<Fact>> byPredicate = new HashMap<>();
  private final Map<EntityId, List<Fact>> containsByPart = new HashMap<>();
  private final Map<EntityId, List<Fact>> containsByWhole = new HashMap<>();
  private final Map<String, SortedSet<EntityId>> modulesByRepo = new HashMap<>();
  private final Map<EntityId, SortedSet<String>> reposByModule = new HashMap<>();
  private final Map<String, Fact> resolvedLinks = new HashMap<>();

  public Knowledge(Collection<Fact> facts) {
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(Comparator.comparing(Fact::id));
    for (Fact fact : sorted) {
      if (byId.putIfAbsent(fact.id(), fact) != null) {
        continue;
      }
      bySubject.computeIfAbsent(fact.subject(), s -> new ArrayList<>()).add(fact);
      JsonNode resolves = fact.attributes().get("resolves");
      if (resolves != null && resolves.isString()) {
        resolvedLinks.putIfAbsent(resolves.asString(), fact);
      }
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

  /**
   * The view under which alone a fact holds, or null when it holds in the default view: a derived
   * fact's {@code view} (ADR 0095, amendment for #590), else an extracted one's {@code profile}.
   */
  public static String view(Fact fact) {
    for (String name : new String[] {"view", "profile"}) {
      tools.jackson.databind.JsonNode value = fact.attributes().get(name);
      if (value != null && value.isString()) {
        return value.asString();
      }
    }
    return null;
  }

  public static boolean isEntity(Fact fact) {
    return fact.object().kind() == FactObject.Kind.ENTITY;
  }

  public static EntityId object(Fact fact) {
    return fact.object().entityId();
  }

  /**
   * Whether a link the linker left open ({@code unresolved-link}) was resolved: a link of the LLM
   * resolver names it in {@code resolves}, by its claim ID, its stable link ID, or, before it is
   * stored, its fact ID (ADR 0045, ADR 0095 amendment for #576). The link is drawn, so it is not
   * also listed as not linked.
   */
  public boolean resolved(Fact unresolved) {
    return resolution(unresolved) != null;
  }

  /** The link of the LLM resolver that resolved the link left open, or {@code null}. */
  public Fact resolution(Fact unresolved) {
    Fact byId = resolvedLinks.get(unresolved.id());
    if (byId != null) {
      return byId;
    }
    return unresolved.claimId() == null ? null : resolvedLinks.get(unresolved.claimId());
  }

  /** The fact with the ID, or {@code null}. */
  public Fact fact(String id) {
    return byId.get(id);
  }

  /** The facts with the entity as subject. */
  public List<Fact> about(EntityId subject) {
    return bySubject.getOrDefault(subject, List.of());
  }

  /** The facts of the origin kind, by ID. */
  public List<Fact> withOrigin(Origin.Kind kind) {
    return byId.values().stream()
        .filter(f -> f.origin().kind() == kind)
        .sorted(Comparator.comparing(Fact::id))
        .toList();
  }

  /** The facts with the predicate. */
  public List<Fact> withPredicate(String predicate) {
    return byPredicate.getOrDefault(predicate, List.of());
  }

  /** The facts relating the subject to an entity by the predicate. */
  public List<Fact> relations(EntityId subject, String predicate) {
    return about(subject).stream()
        .filter(f -> f.predicate().equals(predicate) && isEntity(f))
        .toList();
  }

  /** The facts with the entity as subject and a value, not an entity, as object. */
  public List<Fact> attributes(EntityId subject) {
    return about(subject).stream().filter(f -> !isEntity(f)).toList();
  }

  /** The value of the first attribute fact with the predicate, by fact ID. */
  public Optional<String> attribute(EntityId subject, String predicate) {
    return attributes(subject).stream()
        .filter(f -> f.predicate().equals(predicate))
        .map(f -> f.object().value())
        .findFirst();
  }

  /** What the entity contains, of the given entity type. */
  public SortedSet<EntityId> parts(EntityId whole, String type) {
    SortedSet<EntityId> parts = new TreeSet<>(BY_ID);
    for (Fact fact : containsByWhole.getOrDefault(whole, List.of())) {
      if (object(fact).type().equals(type)) {
        parts.add(object(fact));
      }
    }
    return parts;
  }

  /** What contains the entity, of the given entity type. */
  public SortedSet<EntityId> wholes(EntityId part, String type) {
    SortedSet<EntityId> wholes = new TreeSet<>(BY_ID);
    for (Fact fact : containsByPart.getOrDefault(part, List.of())) {
      if (fact.subject().type().equals(type)) {
        wholes.add(fact.subject());
      }
    }
    return wholes;
  }

  /** Everything that contains the entity. */
  public SortedSet<EntityId> wholes(EntityId part) {
    SortedSet<EntityId> wholes = new TreeSet<>(BY_ID);
    containsByPart.getOrDefault(part, List.of()).forEach(f -> wholes.add(f.subject()));
    return wholes;
  }

  /** The facts saying that the whole contains the part. */
  public List<Fact> containsFacts(EntityId whole, EntityId part) {
    return containsByPart.getOrDefault(part, List.of()).stream()
        .filter(f -> f.subject().equals(whole))
        .toList();
  }

  /** The modules whose facts were observed in the repository. */
  public SortedSet<EntityId> modules(String repo) {
    return modulesByRepo.getOrDefault(repo, new TreeSet<>(BY_ID));
  }

  /** The repositories the module's facts were observed in. */
  public SortedSet<String> repos(EntityId module) {
    return reposByModule.getOrDefault(module, new TreeSet<>());
  }

  /** The services and libraries that build the module. */
  public SortedSet<EntityId> containers(EntityId module) {
    SortedSet<EntityId> containers = wholes(module, BuiltInTypes.SERVICE);
    containers.addAll(wholes(module, BuiltInTypes.LIBRARY));
    return containers;
  }

  /** The types directly in a package, module or type, and their member types, recursively. */
  public SortedSet<EntityId> typesWithin(EntityId whole) {
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
