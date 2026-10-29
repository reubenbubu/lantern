package io.lantern.core.vocabulary;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The vocabulary as of one revision: every type, with parent-type lookup and the values a type
 * inherits. Immutable; changes go through {@link VocabularyRegistry}, which creates a new revision.
 *
 * <p>A type inherits its C4 level, shape, icon and object kind from the nearest ancestor that sets
 * them, and its attribute schema is its own merged with all its ancestors' (see {@link
 * AttributeSchema#merge}). A type may not set an object kind that differs from one it inherits.
 */
public final class Vocabulary {

  private final long revision;
  private final Map<String, VocabularyType> types;
  private final Map<String, AttributeSchema> schemas = new HashMap<>();

  /**
   * @param revision the revision these types are the vocabulary of; 0 for the empty vocabulary
   * @param types the types
   * @throws IllegalArgumentException if the types are not consistent: duplicate names, a missing
   *     parent, a parent of another kind, a cycle of parents, or attribute schemas or object kinds
   *     that conflict with their parents'
   */
  public Vocabulary(long revision, Collection<VocabularyType> types) {
    if (revision < 0) {
      throw new IllegalArgumentException("Revision must not be negative: " + revision);
    }
    this.revision = revision;
    Map<String, VocabularyType> byName = new TreeMap<>();
    for (VocabularyType type : types) {
      if (byName.put(type.name(), type) != null) {
        throw new IllegalArgumentException("Type '" + type.name() + "' is defined twice");
      }
    }
    this.types = Collections.unmodifiableMap(byName);
    for (VocabularyType type : byName.values()) {
      if (type.parent() != null) {
        VocabularyType parent = byName.get(type.parent());
        if (parent == null) {
          throw new IllegalArgumentException(
              "Parent type '" + type.parent() + "' of '" + type.name() + "' does not exist");
        }
        if (parent.kind() != type.kind()) {
          throw new IllegalArgumentException(
              "Type '" + type.name() + "' and its parent '" + parent.name() + "' differ in kind");
        }
      }
    }
    for (VocabularyType type : byName.values()) {
      List<AttributeSchema> rootFirst = new ArrayList<>();
      for (VocabularyType t : lineage(type.name())) {
        rootFirst.addFirst(t.attributeSchema());
      }
      try {
        schemas.put(type.name(), AttributeSchema.merge(rootFirst));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Attribute schema of '" + type.name() + "': " + e.getMessage(), e);
      }
      if (type.objectKind() != null && type.parent() != null) {
        FactObject.Kind inherited = objectKind(type.parent()).orElse(null);
        if (inherited != null && inherited != type.objectKind()) {
          throw new IllegalArgumentException(
              "Object kind of '"
                  + type.name()
                  + "' is "
                  + type.objectKind().jsonName()
                  + ", but it inherits "
                  + inherited.jsonName());
        }
      }
    }
  }

  /** The empty vocabulary, revision 0. */
  public static Vocabulary empty() {
    return new Vocabulary(0, List.of());
  }

  public long revision() {
    return revision;
  }

  /** All types, sorted by name. */
  public List<VocabularyType> types() {
    return List.copyOf(types.values());
  }

  public Optional<VocabularyType> find(String name) {
    return Optional.ofNullable(types.get(name));
  }

  /**
   * The type and its ancestors, nearest first: the type, its parent, its parent's parent, up to the
   * root.
   *
   * @throws IllegalArgumentException if there is no such type
   */
  public List<VocabularyType> lineage(String name) {
    List<VocabularyType> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String current = name; current != null; ) {
      VocabularyType type = types.get(current);
      if (type == null) {
        throw new IllegalArgumentException("Unknown type: '" + current + "'");
      }
      if (!seen.add(current)) {
        throw new IllegalArgumentException("Type '" + name + "' has a cycle of parent types");
      }
      result.add(type);
      current = type.parent();
    }
    return List.copyOf(result);
  }

  /** Whether the type is the given ancestor or descends from it. False for unknown types. */
  public boolean isA(String name, String ancestor) {
    if (!types.containsKey(name)) {
      return false;
    }
    return lineage(name).stream().anyMatch(t -> t.name().equals(ancestor));
  }

  /** The type's C4 level, inherited if it sets none. */
  public Optional<C4Level> c4Level(String name) {
    return inherited(name, VocabularyType::c4Level);
  }

  /** The type's default shape, inherited if it sets none. */
  public Optional<String> shape(String name) {
    return inherited(name, VocabularyType::shape);
  }

  /** The type's default icon, inherited if it sets none. */
  public Optional<String> icon(String name) {
    return inherited(name, VocabularyType::icon);
  }

  /**
   * The kind of value the objects of facts with this attribute type must have, inherited if it sets
   * none; empty if any value is allowed, and for entity and relationship types.
   */
  public Optional<FactObject.Kind> objectKind(String name) {
    return inherited(name, VocabularyType::objectKind);
  }

  /**
   * The attribute schema facts with this predicate are checked against: the type's own merged with
   * its ancestors'.
   *
   * @throws IllegalArgumentException if there is no such type
   */
  public AttributeSchema attributeSchema(String name) {
    AttributeSchema schema = schemas.get(name);
    if (schema == null) {
      throw new IllegalArgumentException("Unknown type: '" + name + "'");
    }
    return schema;
  }

  /**
   * Checks a fact against the vocabulary:
   *
   * <ul>
   *   <li>its subject's type, and an entity object's type, must be entity types;
   *   <li>its predicate must be a relationship or attribute type;
   *   <li>a relationship fact's object must be an entity, and an attribute fact's object a value,
   *       of the attribute type's object kind if it has one;
   *   <li>its attributes must match the predicate's attribute schema.
   * </ul>
   *
   * @throws IllegalArgumentException if the fact does not match
   */
  public void validate(Fact fact) {
    Objects.requireNonNull(fact, "fact");
    checkEntityType(fact.subject(), "Subject");
    VocabularyType type = types.get(fact.predicate());
    if (type == null) {
      throw new IllegalArgumentException(
          "Predicate '"
              + fact.predicate()
              + "' is not in the vocabulary (revision "
              + revision
              + ")");
    }
    if (!type.kind().isPredicate()) {
      throw new IllegalArgumentException(
          "Predicate '"
              + fact.predicate()
              + "' is an entity type, not a relationship or attribute");
    }
    FactObject object = fact.object();
    if (type.kind() == TypeKind.RELATIONSHIP) {
      if (object.kind() != FactObject.Kind.ENTITY) {
        throw new IllegalArgumentException(
            "Object of a '"
                + fact.predicate()
                + "' fact must be an entity, as it is a relationship, not "
                + withArticle(object.kind().jsonName()));
      }
      checkEntityType(object.entityId(), "Object");
    } else {
      if (object.kind() == FactObject.Kind.ENTITY) {
        throw new IllegalArgumentException(
            "Object of a '"
                + fact.predicate()
                + "' fact must be a value, as it is an attribute, not an entity");
      }
      FactObject.Kind expected = objectKind(type.name()).orElse(null);
      if (expected != null && object.kind() != expected) {
        throw new IllegalArgumentException(
            "Object of a '"
                + fact.predicate()
                + "' fact must be "
                + withArticle(expected.jsonName())
                + ", not "
                + withArticle(object.kind().jsonName()));
      }
    }
    try {
      schemas.get(type.name()).validate(fact.attributes());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Attributes of a '" + fact.predicate() + "' fact are invalid: " + e.getMessage(), e);
    }
  }

  private void checkEntityType(EntityId entity, String role) {
    VocabularyType type = types.get(entity.type());
    if (type == null) {
      throw new IllegalArgumentException(
          role
              + " '"
              + entity
              + "' has type '"
              + entity.type()
              + "', which is not in the vocabulary (revision "
              + revision
              + ")");
    }
    if (type.kind() != TypeKind.ENTITY) {
      throw new IllegalArgumentException(
          role
              + " '"
              + entity
              + "' has type '"
              + entity.type()
              + "', which is "
              + withArticle(type.kind().jsonName())
              + " type, not an entity type");
    }
  }

  private static String withArticle(String word) {
    return ("aeiou".indexOf(word.charAt(0)) >= 0 ? "an " : "a ") + word;
  }

  /**
   * The definitions among {@code definitions} that would change this vocabulary: new types, and
   * existing types whose definition differs. Unchanged definitions are left out.
   *
   * <p>An existing type may get a different parent, or none. That is an ordinary change, not a
   * rename or removal, so it needs no alias. It changes what the type inherits (C4 level, shape,
   * icon and attributes), so the facts already stored must stay valid: no type may accept less than
   * before once the parents are applied.
   *
   * @throws IllegalArgumentException if a name is listed twice; a change to an existing type
   *     changes its kind or an object kind it sets, or its attribute schema accepts less than
   *     before (see {@link AttributeSchema#nonAdditiveChanges}), including through a new parent;
   *     or, when a parent changes, the result is not consistent (a missing parent, a parent of
   *     another kind, a cycle of parents). {@link VocabularyRegistry#apply} checks the consistency
   *     of every change.
   */
  public List<VocabularyType> changes(Collection<VocabularyType> definitions) {
    List<VocabularyType> result = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (VocabularyType definition : definitions) {
      if (!names.add(definition.name())) {
        throw new IllegalArgumentException("Type '" + definition.name() + "' is listed twice");
      }
      VocabularyType existing = types.get(definition.name());
      if (definition.equals(existing)) {
        continue;
      }
      if (existing != null) {
        List<String> problems = new ArrayList<>();
        if (existing.kind() != definition.kind()) {
          problems.add("changes the kind");
        }
        if (existing.objectKind() != null && existing.objectKind() != definition.objectKind()) {
          problems.add("changes the object kind");
        }
        problems.addAll(
            existing.attributeSchema().nonAdditiveChanges(definition.attributeSchema()));
        if (!problems.isEmpty()) {
          throw new IllegalArgumentException(
              "Change to type '"
                  + definition.name()
                  + "' is not additive: it "
                  + String.join(", ", problems));
        }
      }
      result.add(definition);
    }
    if (result.stream().anyMatch(t -> isParentChange(types.get(t.name()), t))) {
      checkParentChanges(result);
    }
    return result;
  }

  private static boolean isParentChange(VocabularyType existing, VocabularyType definition) {
    return existing != null && !Objects.equals(existing.parent(), definition.parent());
  }

  /**
   * Applies the changes to a copy and checks that the result is consistent and that no existing
   * type, the ones under a new parent included, accepts less than before.
   */
  private void checkParentChanges(List<VocabularyType> changes) {
    Map<String, VocabularyType> merged = new TreeMap<>(types);
    changes.forEach(t -> merged.put(t.name(), t));
    Vocabulary after = new Vocabulary(revision + 1, merged.values());
    for (String name : types.keySet()) {
      List<String> problems =
          new ArrayList<>(schemas.get(name).nonAdditiveChanges(after.schemas.get(name)));
      FactObject.Kind objectKind = objectKind(name).orElse(null);
      if (objectKind != null && objectKind != after.objectKind(name).orElse(null)) {
        problems.add("changes the object kind");
      }
      if (!problems.isEmpty()) {
        throw new IllegalArgumentException(
            "Type '"
                + name
                + "' would accept less than before with the new parent types: it "
                + String.join(", ", problems));
      }
    }
  }

  private <T> Optional<T> inherited(String name, Function<VocabularyType, T> field) {
    for (VocabularyType type : lineage(name)) {
      T value = field.apply(type);
      if (value != null) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Vocabulary other && revision == other.revision && types.equals(other.types);
  }

  @Override
  public int hashCode() {
    return Objects.hash(revision, types);
  }

  @Override
  public String toString() {
    return "Vocabulary[revision=" + revision + ", types=" + types.keySet() + "]";
  }
}
