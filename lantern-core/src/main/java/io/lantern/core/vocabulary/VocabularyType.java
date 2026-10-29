package io.lantern.core.vocabulary;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One entry in the vocabulary: an entity, relationship or attribute type. The JSON form is defined
 * by {@code schema/vocabulary-type.schema.json}.
 *
 * <p>Entity type names have the form of the type part of an entity ID ({@code batch-job});
 * relationship and attribute type names have the form of a fact predicate ({@code CALLS}, {@code
 * owner}). All names share one namespace.
 *
 * <p>The C4 level, shape, icon and object kind are inherited from the parent type when absent, see
 * {@link Vocabulary}.
 *
 * @param name the type name
 * @param kind entity, relationship or attribute
 * @param parent the parent type's name, of the same kind, or {@code null} for a root type
 * @param c4Level the C4 level, or {@code null} to inherit it (or for none)
 * @param shape the default shape to draw it with, or {@code null} to inherit it
 * @param icon the default icon, or {@code null} to inherit it
 * @param description what the type means, or {@code null}
 * @param attributeSchema the attributes facts with this type as predicate may have, on top of the
 *     parent type's
 * @param objectKind for an attribute type, the kind of value its facts' objects must have ({@code
 *     string}, {@code integer}, {@code decimal} or {@code boolean}), or {@code null} to inherit it
 *     (or allow any value); always {@code null} for entity and relationship types
 */
public record VocabularyType(
    String name,
    TypeKind kind,
    String parent,
    C4Level c4Level,
    String shape,
    String icon,
    String description,
    AttributeSchema attributeSchema,
    FactObject.Kind objectKind) {

  /** Shape and icon names. */
  private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9-]*");

  private static final Set<String> FIELDS =
      Set.of(
          "name",
          "kind",
          "parent",
          "c4Level",
          "shape",
          "icon",
          "description",
          "attributeSchema",
          "objectKind");

  public VocabularyType {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(attributeSchema, "attributeSchema");
    checkName(name, kind);
    if (parent != null) {
      checkName(parent, kind);
      if (parent.equals(name)) {
        throw new IllegalArgumentException("Type '" + name + "' cannot be its own parent");
      }
    }
    if (shape != null && !TOKEN.matcher(shape).matches()) {
      throw new IllegalArgumentException("Invalid shape: '" + shape + "'");
    }
    if (icon != null && !TOKEN.matcher(icon).matches()) {
      throw new IllegalArgumentException("Invalid icon: '" + icon + "'");
    }
    if (description != null && description.isBlank()) {
      throw new IllegalArgumentException("Description of '" + name + "' must not be blank");
    }
    if (objectKind != null && kind != TypeKind.ATTRIBUTE) {
      throw new IllegalArgumentException(
          "Only attribute types have an object kind, not " + kind.jsonName() + " '" + name + "'");
    }
    if (objectKind == FactObject.Kind.ENTITY) {
      throw new IllegalArgumentException(
          "Object kind of attribute type '" + name + "' must be a value kind, not entity");
    }
  }

  /** A type without an object kind of its own. */
  public VocabularyType(
      String name,
      TypeKind kind,
      String parent,
      C4Level c4Level,
      String shape,
      String icon,
      String description,
      AttributeSchema attributeSchema) {
    this(name, kind, parent, c4Level, shape, icon, description, attributeSchema, null);
  }

  /** The JSON form; absent optional fields are left out. */
  public ObjectNode toJson() {
    ObjectNode node = FactJson.newObject();
    node.put("name", name);
    node.put("kind", kind.jsonName());
    if (parent != null) {
      node.put("parent", parent);
    }
    if (c4Level != null) {
      node.put("c4Level", c4Level.jsonName());
    }
    if (shape != null) {
      node.put("shape", shape);
    }
    if (icon != null) {
      node.put("icon", icon);
    }
    if (description != null) {
      node.put("description", description);
    }
    node.set("attributeSchema", attributeSchema.toJson());
    if (objectKind != null) {
      node.put("objectKind", objectKind.jsonName());
    }
    return node;
  }

  /** Reads the JSON form. */
  public static VocabularyType fromJson(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Vocabulary type must be a JSON object");
    }
    for (String field : node.propertyNames()) {
      if (!FIELDS.contains(field)) {
        throw new IllegalArgumentException("Unknown field '" + field + "' in vocabulary type");
      }
    }
    String c4Level = optionalString(node, "c4Level");
    String objectKind = optionalString(node, "objectKind");
    JsonNode schema = node.get("attributeSchema");
    if (schema == null) {
      throw new IllegalArgumentException("Missing required field 'attributeSchema'");
    }
    return new VocabularyType(
        requiredString(node, "name"),
        TypeKind.fromJsonName(requiredString(node, "kind")),
        optionalString(node, "parent"),
        c4Level == null ? null : C4Level.fromJsonName(c4Level),
        optionalString(node, "shape"),
        optionalString(node, "icon"),
        optionalString(node, "description"),
        AttributeSchema.fromJson(schema),
        objectKind == null ? null : FactObject.Kind.fromJsonName(objectKind));
  }

  private static void checkName(String name, TypeKind kind) {
    boolean valid =
        kind == TypeKind.ENTITY ? EntityId.isValidType(name) : Fact.isValidPredicate(name);
    if (!valid) {
      throw new IllegalArgumentException(
          "Invalid " + kind.jsonName() + " type name: '" + name + "'");
    }
  }

  private static String requiredString(JsonNode node, String field) {
    String value = optionalString(node, field);
    if (value == null) {
      throw new IllegalArgumentException("Missing required field '" + field + "'");
    }
    return value;
  }

  private static String optionalString(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isString()) {
      throw new IllegalArgumentException("Field '" + field + "' must be a string");
    }
    return value.stringValue();
  }
}
