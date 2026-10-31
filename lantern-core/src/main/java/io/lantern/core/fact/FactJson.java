package io.lantern.core.fact;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and reads facts in the JSON form defined by {@code schema/fact-envelope.schema.json}, and
 * derives fact IDs.
 *
 * <p>Optional fields that are absent are left out of the JSON rather than written as {@code null},
 * and attributes are written in canonical form (see {@link #normalize}), so each fact has exactly
 * one JSON form.
 */
public final class FactJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Set<String> FACT_FIELDS =
      Set.of(
          "id",
          "claim",
          "subject",
          "predicate",
          "object",
          "attributes",
          "lineage",
          "origin",
          "evidence",
          "confidence",
          "validity");
  private static final Set<String> OBJECT_FIELDS = Set.of("kind", "value");
  private static final Set<String> ORIGIN_FIELDS = Set.of("kind", "name", "version");
  private static final Set<String> LOCATION_FIELDS =
      Set.of("repo", "path", "lines", "blob", "contentHash", "role");
  private static final Set<String> LINES_FIELDS = Set.of("start", "end");
  private static final Set<String> VALIDITY_FIELDS = Set.of("from", "to");

  private FactJson() {}

  /** A new, empty JSON object, for building attributes. */
  public static ObjectNode newObject() {
    return MAPPER.createObjectNode();
  }

  /** Writes a fact as a JSON string. */
  public static String write(Fact fact) {
    return MAPPER.writeValueAsString(toJson(fact));
  }

  /** Reads a fact from a JSON string. */
  public static Fact read(String json) {
    return fromJson(parse(json));
  }

  /** Parses a JSON string into a tree. */
  public static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }

  public static ObjectNode toJson(Fact fact) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", fact.id());
    if (fact.claimId() != null) {
      node.put("claim", fact.claimId());
    }
    node.put("subject", fact.subject().toString());
    node.put("predicate", fact.predicate());
    node.set("object", toJson(fact.object()));
    node.set("attributes", fact.attributes());
    if (!fact.lineage().isEmpty()) {
      node.set("lineage", lineage(fact.lineage()));
    }
    node.set("origin", toJson(fact.origin()));
    node.set("evidence", toJson(fact.evidence()));
    node.put("confidence", fact.confidence());
    node.set("validity", toJson(fact.validity()));
    return node;
  }

  public static Fact fromJson(JsonNode node) {
    checkObject(node, "fact", FACT_FIELDS);
    JsonNode attributes = required(node, "attributes");
    if (!attributes.isObject()) {
      throw new IllegalArgumentException("Fact attributes must be a JSON object");
    }
    JsonNode confidence = required(node, "confidence");
    if (!confidence.isNumber()) {
      throw new IllegalArgumentException("Fact confidence must be a number");
    }
    List<String> lineage = new ArrayList<>();
    JsonNode lineageNode = node.get("lineage");
    if (lineageNode != null && !lineageNode.isNull()) {
      if (!lineageNode.isArray()) {
        throw new IllegalArgumentException("Fact lineage must be a JSON array");
      }
      for (JsonNode claim : lineageNode) {
        if (!claim.isString()) {
          throw new IllegalArgumentException("Fact lineage must hold claim IDs");
        }
        lineage.add(claim.stringValue());
      }
    }
    return new Fact(
        requiredString(node, "id"),
        optionalString(node, "claim"),
        EntityId.parse(requiredString(node, "subject")),
        requiredString(node, "predicate"),
        objectFromJson(required(node, "object")),
        (ObjectNode) attributes,
        lineage,
        originFromJson(required(node, "origin")),
        evidenceFromJson(required(node, "evidence")),
        confidence.doubleValue(),
        validityFromJson(required(node, "validity")));
  }

  public static ObjectNode toJson(FactObject object) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("kind", object.kind().jsonName());
    switch (object.kind()) {
      case ENTITY, STRING, DECIMAL -> node.put("value", object.value());
      case INTEGER -> node.put("value", Long.parseLong(object.value()));
      case BOOLEAN -> node.put("value", Boolean.parseBoolean(object.value()));
    }
    return node;
  }

  public static FactObject objectFromJson(JsonNode node) {
    checkObject(node, "object", OBJECT_FIELDS);
    FactObject.Kind kind = FactObject.Kind.fromJsonName(requiredString(node, "kind"));
    JsonNode value = required(node, "value");
    boolean typeMatches =
        switch (kind) {
          case ENTITY, STRING, DECIMAL -> value.isString();
          case INTEGER -> value.isIntegralNumber() && value.canConvertToLong();
          case BOOLEAN -> value.isBoolean();
        };
    if (!typeMatches) {
      throw new IllegalArgumentException(
          "Object value has the wrong JSON type for kind " + kind.jsonName());
    }
    return switch (kind) {
      case ENTITY, STRING, DECIMAL -> new FactObject(kind, value.stringValue());
      case INTEGER -> FactObject.integer(value.longValue());
      case BOOLEAN -> FactObject.bool(value.booleanValue());
    };
  }

  public static ObjectNode toJson(Origin origin) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("kind", origin.kind().jsonName());
    node.put("name", origin.name());
    if (origin.version() != null) {
      node.put("version", origin.version());
    }
    return node;
  }

  public static Origin originFromJson(JsonNode node) {
    checkObject(node, "origin", ORIGIN_FIELDS);
    return new Origin(
        Origin.Kind.fromJsonName(requiredString(node, "kind")),
        requiredString(node, "name"),
        optionalString(node, "version"));
  }

  /** The evidence as a JSON array of its locations. */
  public static ArrayNode toJson(Evidence evidence) {
    ArrayNode array = MAPPER.createArrayNode();
    evidence.locations().forEach(location -> array.add(toJson(location)));
    return array;
  }

  public static ObjectNode toJson(Evidence.Location location) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("repo", location.repo());
    if (location.path() != null) {
      node.put("path", location.path());
    }
    if (location.lines() != null) {
      ObjectNode lines = node.putObject("lines");
      lines.put("start", location.lines().start());
      lines.put("end", location.lines().end());
    }
    if (location.blob() != null) {
      node.put("blob", location.blob());
    }
    if (location.contentHash() != null) {
      node.put("contentHash", location.contentHash());
    }
    node.put("role", location.role().jsonName());
    return node;
  }

  public static Evidence evidenceFromJson(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("Fact evidence must be a JSON array");
    }
    List<Evidence.Location> locations = new ArrayList<>();
    for (JsonNode location : node) {
      locations.add(locationFromJson(location));
    }
    return new Evidence(locations);
  }

  public static Evidence.Location locationFromJson(JsonNode node) {
    checkObject(node, "evidence location", LOCATION_FIELDS);
    Evidence.LineRange lines = null;
    JsonNode linesNode = node.get("lines");
    if (linesNode != null && !linesNode.isNull()) {
      checkObject(linesNode, "lines", LINES_FIELDS);
      lines =
          new Evidence.LineRange(requiredInt(linesNode, "start"), requiredInt(linesNode, "end"));
    }
    return new Evidence.Location(
        requiredString(node, "repo"),
        optionalString(node, "path"),
        lines,
        optionalString(node, "blob"),
        optionalString(node, "contentHash"),
        Evidence.Role.fromJsonName(requiredString(node, "role")));
  }

  public static ObjectNode toJson(Validity validity) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("from", validity.from());
    if (validity.to() != null) {
      node.put("to", validity.to());
    }
    return node;
  }

  public static Validity validityFromJson(JsonNode node) {
    checkObject(node, "validity", VALIDITY_FIELDS);
    JsonNode to = node.get("to");
    return new Validity(
        requiredLong(node, "from"), to == null || to.isNull() ? null : requiredLong(node, "to"));
  }

  /**
   * The fact ID of a fact without lineage, see {@link #factId(EntityId, String, FactObject,
   * ObjectNode, List, Origin, Evidence)}.
   */
  public static String factId(
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Origin origin,
      Evidence evidence) {
    return factId(subject, predicate, object, attributes, List.of(), origin, evidence);
  }

  /**
   * Derives a fact's observation ID from what it claims and how it was seen: subject, predicate,
   * object, attributes, lineage, origin and evidence (ADR 0091). Confidence and validity are left
   * out, so the same sighting keeps its ID while its validity range grows or its confidence is
   * re-assessed; evidence names no commit, so it keeps it across commits that leave its file alone.
   *
   * <p>The ID is the SHA-256, as 64 lowercase hex characters, of the canonical JSON of those
   * fields: object keys sorted, no whitespace, optional fields (and an empty lineage) left out.
   */
  public static String factId(
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      List<String> lineage,
      Origin origin,
      Evidence evidence) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(object, "object");
    Objects.requireNonNull(attributes, "attributes");
    Objects.requireNonNull(origin, "A fact must have an origin");
    Objects.requireNonNull(evidence, "A fact must have evidence");
    ObjectNode content = MAPPER.createObjectNode();
    content.put("subject", subject.toString());
    content.put("predicate", predicate);
    content.set("object", toJson(object));
    content.set("attributes", attributes);
    if (!lineage.isEmpty()) {
      content.set("lineage", lineage(lineage));
    }
    content.set("origin", toJson(origin));
    content.set("evidence", toJson(evidence));
    return sha256(canonical(content));
  }

  private static ArrayNode lineage(List<String> claims) {
    ArrayNode array = MAPPER.createArrayNode();
    claims.forEach(array::add);
    return array;
  }

  /**
   * The canonical form of attributes: a copy whose keys are sorted and whose numbers are parsed
   * from their canonical text. Attributes in this form read back from JSON or the database
   * unchanged.
   *
   * @throws IllegalArgumentException if the attributes hold values JSON cannot carry, such as NaN
   */
  public static ObjectNode normalize(ObjectNode attributes) {
    return (ObjectNode) parse(canonical(attributes));
  }

  /**
   * The canonical JSON text of a tree: object keys sorted, no whitespace, numbers in plain form
   * without trailing zeros.
   */
  static String canonical(JsonNode node) {
    StringBuilder out = new StringBuilder();
    writeCanonical(node, out);
    return out.toString();
  }

  private static void writeCanonical(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      entries.sort(Map.Entry.comparingByKey());
      out.append('{');
      for (int i = 0; i < entries.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(MAPPER.writeValueAsString(entries.get(i).getKey())).append(':');
        writeCanonical(entries.get(i).getValue(), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        writeCanonical(node.get(i), out);
      }
      out.append(']');
    } else if (node.isNumber()) {
      if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
        throw new IllegalArgumentException("Non-finite number in fact: " + node.doubleValue());
      }
      out.append(canonicalNumber(node.decimalValue()));
    } else if (node.isString() || node.isBoolean() || node.isNull()) {
      out.append(MAPPER.writeValueAsString(node));
    } else {
      throw new IllegalArgumentException("Unsupported JSON value in fact: " + node.getNodeType());
    }
  }

  /** Writes numbers that are numerically equal the same way, however they were parsed. */
  private static String canonicalNumber(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    if (Math.abs(stripped.scale()) > 1000) {
      throw new IllegalArgumentException("Number exponent out of range: " + value);
    }
    return stripped.signum() == 0 ? "0" : stripped.toPlainString();
  }

  static String sha256(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static void checkObject(JsonNode node, String what, Set<String> allowed) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Fact " + what + " must be a JSON object");
    }
    for (String name : node.propertyNames()) {
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException("Unknown field '" + name + "' in fact " + what);
      }
    }
  }

  private static JsonNode required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Missing required field '" + field + "'");
    }
    return value;
  }

  private static String requiredString(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isString()) {
      throw new IllegalArgumentException("Field '" + field + "' must be a string");
    }
    return value.stringValue();
  }

  private static String optionalString(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : requiredString(node, field);
  }

  private static long requiredLong(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("Field '" + field + "' must be an integer");
    }
    return value.longValue();
  }

  private static int requiredInt(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("Field '" + field + "' must be an integer");
    }
    return value.intValue();
  }
}
