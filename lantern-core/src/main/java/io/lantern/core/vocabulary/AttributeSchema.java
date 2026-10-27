package io.lantern.core.vocabulary;

import io.lantern.core.fact.FactJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The attributes a vocabulary type allows on its facts. The JSON form is a small subset of JSON
 * Schema (see {@code schema/vocabulary-type.schema.json}), checked by this class rather than by a
 * general JSON Schema validator.
 *
 * <p>A type's own schema adds to its parent's: see {@link #merge}.
 *
 * @param properties the declared attributes by name, sorted
 * @param required the attributes every fact must have; each must be declared by this type or an
 *     ancestor; sorted
 * @param additionalProperties whether attributes nobody declared are allowed. As in JSON Schema,
 *     this is {@code true} when the JSON form leaves it out.
 */
public record AttributeSchema(
    Map<String, Property> properties, Set<String> required, boolean additionalProperties) {

  /** Attribute names: a letter, then letters, digits, {@code _}, {@code .} or {@code -}. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

  private static final Set<String> KEYWORDS =
      Set.of("type", "properties", "required", "additionalProperties");
  private static final Set<String> PROPERTY_KEYWORDS =
      Set.of("type", "description", "enum", "items");

  /** A schema that declares no attributes and allows none. */
  public static final AttributeSchema NONE = new AttributeSchema(Map.of(), Set.of(), false);

  public AttributeSchema {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(required, "required");
    properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
    required = Collections.unmodifiableSortedSet(new TreeSet<>(required));
    for (Map.Entry<String, Property> entry : properties.entrySet()) {
      checkName(entry.getKey());
      Objects.requireNonNull(entry.getValue(), "property " + entry.getKey());
    }
    for (String name : required) {
      checkName(name);
    }
  }

  /** A value type an attribute can have. */
  public enum ValueType {
    STRING,
    /** A whole number. */
    INTEGER,
    /** Any number, whole or not. */
    NUMBER,
    BOOLEAN,
    ARRAY,
    /** Any JSON object. */
    OBJECT;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static ValueType fromJsonName(String name) {
      for (ValueType type : values()) {
        if (type.jsonName().equals(name)) {
          return type;
        }
      }
      throw new IllegalArgumentException("Unknown attribute value type: '" + name + "'");
    }
  }

  /**
   * One declared attribute.
   *
   * @param type the value type
   * @param description what the attribute means, or {@code null}
   * @param allowed the allowed values ({@code enum}) for a string attribute, or empty for any
   * @param items the schema every element must match for an array attribute, or {@code null} for
   *     any elements
   */
  public record Property(ValueType type, String description, List<String> allowed, Property items) {

    public Property {
      Objects.requireNonNull(type, "type");
      allowed = List.copyOf(allowed == null ? List.of() : allowed);
      if (description != null && description.isBlank()) {
        throw new IllegalArgumentException("Attribute description must not be blank");
      }
      if (!allowed.isEmpty() && type != ValueType.STRING) {
        throw new IllegalArgumentException("Only string attributes can list allowed values");
      }
      if (new HashSet<>(allowed).size() != allowed.size()) {
        throw new IllegalArgumentException("Allowed values must be distinct: " + allowed);
      }
      if (items != null && type != ValueType.ARRAY) {
        throw new IllegalArgumentException("Only array attributes can have an items schema");
      }
    }

    /** A property of the given type with no further constraints. */
    public static Property of(ValueType type) {
      return new Property(type, null, List.of(), null);
    }

    /**
     * Whether both accept exactly the same values; descriptions and the order of allowed values are
     * ignored.
     */
    public boolean sameConstraints(Property other) {
      return type == other.type
          && Set.copyOf(allowed).equals(Set.copyOf(other.allowed))
          && (items == null
              ? other.items == null
              : other.items != null && items.sameConstraints(other.items));
    }

    void check(JsonNode value, String path, List<String> problems) {
      boolean matches =
          switch (type) {
            case STRING -> value.isString();
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case ARRAY -> value.isArray();
            case OBJECT -> value.isObject();
          };
      if (!matches) {
        problems.add("'" + path + "' must be " + article(type) + " " + type.jsonName());
        return;
      }
      if (!allowed.isEmpty() && !allowed.contains(value.stringValue())) {
        problems.add("'" + path + "' must be one of " + allowed);
      }
      if (items != null) {
        for (int i = 0; i < value.size(); i++) {
          items.check(value.get(i), path + "[" + i + "]", problems);
        }
      }
    }

    ObjectNode toJson() {
      ObjectNode node = FactJson.newObject();
      node.put("type", type.jsonName());
      if (description != null) {
        node.put("description", description);
      }
      if (!allowed.isEmpty()) {
        ArrayNode values = node.putArray("enum");
        allowed.forEach(values::add);
      }
      if (items != null) {
        node.set("items", items.toJson());
      }
      return node;
    }

    static Property fromJson(JsonNode node, String path) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("Attribute schema for '" + path + "' must be an object");
      }
      for (String keyword : node.propertyNames()) {
        if (!PROPERTY_KEYWORDS.contains(keyword)) {
          throw new IllegalArgumentException(
              "Unsupported keyword '" + keyword + "' in attribute schema for '" + path + "'");
        }
      }
      JsonNode type = node.get("type");
      if (type == null || !type.isString()) {
        throw new IllegalArgumentException("Attribute '" + path + "' needs a type");
      }
      JsonNode description = node.get("description");
      if (description != null && !description.isString()) {
        throw new IllegalArgumentException(
            "Description of attribute '" + path + "' must be a string");
      }
      List<String> allowed = new ArrayList<>();
      JsonNode values = node.get("enum");
      if (values != null) {
        if (!values.isArray() || values.isEmpty()) {
          throw new IllegalArgumentException(
              "Allowed values of attribute '" + path + "' must be a non-empty array");
        }
        for (JsonNode value : values) {
          if (!value.isString()) {
            throw new IllegalArgumentException(
                "Allowed values of attribute '" + path + "' must be strings");
          }
          allowed.add(value.stringValue());
        }
      }
      JsonNode items = node.get("items");
      return new Property(
          ValueType.fromJsonName(type.stringValue()),
          description == null ? null : description.stringValue(),
          allowed,
          items == null ? null : fromJson(items, path + "[]"));
    }

    private static String article(ValueType type) {
      return type == ValueType.ARRAY || type == ValueType.INTEGER || type == ValueType.OBJECT
          ? "an"
          : "a";
    }
  }

  /**
   * The schema of a type whose ancestors have the given schemas: the declared attributes and the
   * required ones of all of them, closed to undeclared attributes if any of them is.
   *
   * @param rootFirst the schemas from the root type down to the type itself
   * @throws IllegalArgumentException if two of them declare the same attribute differently, or an
   *     attribute is required but nobody declares it
   */
  public static AttributeSchema merge(List<AttributeSchema> rootFirst) {
    Map<String, Property> properties = new LinkedHashMap<>();
    Set<String> required = new TreeSet<>();
    boolean additional = true;
    for (AttributeSchema schema : rootFirst) {
      for (Map.Entry<String, Property> entry : schema.properties.entrySet()) {
        Property inherited = properties.get(entry.getKey());
        if (inherited != null && !inherited.sameConstraints(entry.getValue())) {
          throw new IllegalArgumentException(
              "Attribute '" + entry.getKey() + "' is declared differently by a parent type");
        }
        properties.putIfAbsent(entry.getKey(), entry.getValue());
      }
      required.addAll(schema.required);
      additional &= schema.additionalProperties;
    }
    for (String name : required) {
      if (!properties.containsKey(name)) {
        throw new IllegalArgumentException("Required attribute '" + name + "' is not declared");
      }
    }
    return new AttributeSchema(properties, required, additional);
  }

  /**
   * Checks attributes against this schema.
   *
   * @throws IllegalArgumentException listing every problem found
   */
  public void validate(ObjectNode attributes) {
    List<String> problems = new ArrayList<>();
    for (String name : required) {
      JsonNode value = attributes.get(name);
      if (value == null || value.isNull()) {
        problems.add("'" + name + "' is required");
      }
    }
    for (Map.Entry<String, JsonNode> entry : attributes.properties()) {
      Property property = properties.get(entry.getKey());
      if (property != null) {
        property.check(entry.getValue(), entry.getKey(), problems);
      } else if (!additionalProperties) {
        problems.add("'" + entry.getKey() + "' is not a declared attribute");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", problems));
    }
  }

  /**
   * What would make replacing this schema with a newer one more than an additive change. Allowed:
   * declaring new optional attributes, dropping required ones, starting to allow undeclared
   * attributes, and changing descriptions. Refused: removing or changing a declared attribute,
   * making an attribute required, and starting to refuse undeclared attributes.
   *
   * <p>Declaring a new attribute on a schema that allows undeclared ones does constrain values it
   * used to accept, for example a new {@code integer} attribute refuses a string that was accepted
   * as undeclared. That is still treated as additive: declaring attributes is how the vocabulary
   * grows.
   *
   * @return the problems, empty when the change is additive
   */
  public List<String> nonAdditiveChanges(AttributeSchema newer) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Property> entry : properties.entrySet()) {
      Property replacement = newer.properties.get(entry.getKey());
      if (replacement == null) {
        problems.add("removes attribute '" + entry.getKey() + "'");
      } else if (!replacement.sameConstraints(entry.getValue())) {
        problems.add("changes attribute '" + entry.getKey() + "'");
      }
    }
    for (String name : newer.required) {
      if (!required.contains(name)) {
        problems.add("makes attribute '" + name + "' required");
      }
    }
    if (additionalProperties && !newer.additionalProperties) {
      problems.add("stops allowing undeclared attributes");
    }
    return problems;
  }

  /** The JSON form, with every keyword written out. */
  public ObjectNode toJson() {
    ObjectNode node = FactJson.newObject();
    node.put("type", "object");
    ObjectNode props = node.putObject("properties");
    properties.forEach((name, property) -> props.set(name, property.toJson()));
    ArrayNode names = node.putArray("required");
    required.forEach(names::add);
    node.put("additionalProperties", additionalProperties);
    return node;
  }

  /** Reads the JSON form. */
  public static AttributeSchema fromJson(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Attribute schema must be a JSON object");
    }
    for (String keyword : node.propertyNames()) {
      if (!KEYWORDS.contains(keyword)) {
        throw new IllegalArgumentException(
            "Unsupported keyword '" + keyword + "' in attribute schema");
      }
    }
    JsonNode type = node.get("type");
    if (type != null && !(type.isString() && type.stringValue().equals("object"))) {
      throw new IllegalArgumentException("Attribute schema type must be \"object\"");
    }
    Map<String, Property> properties = new TreeMap<>();
    JsonNode props = node.get("properties");
    if (props != null) {
      if (!props.isObject()) {
        throw new IllegalArgumentException("Attribute schema properties must be an object");
      }
      for (Map.Entry<String, JsonNode> entry : props.properties()) {
        properties.put(entry.getKey(), Property.fromJson(entry.getValue(), entry.getKey()));
      }
    }
    Set<String> required = new TreeSet<>();
    JsonNode names = node.get("required");
    if (names != null) {
      if (!names.isArray()) {
        throw new IllegalArgumentException("Attribute schema required must be an array");
      }
      for (JsonNode name : names) {
        if (!name.isString() || !required.add(name.stringValue())) {
          throw new IllegalArgumentException(
              "Attribute schema required must list distinct names: " + names);
        }
      }
    }
    JsonNode additional = node.get("additionalProperties");
    if (additional != null && !additional.isBoolean()) {
      throw new IllegalArgumentException(
          "Attribute schema additionalProperties must be true or false");
    }
    return new AttributeSchema(
        properties, required, additional == null || additional.booleanValue());
  }

  private static void checkName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid attribute name: '" + name + "'");
    }
  }
}
