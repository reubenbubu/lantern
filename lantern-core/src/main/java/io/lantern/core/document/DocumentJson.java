package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactJson;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and reads documents in the JSON form defined by {@code schema/document.schema.json}.
 *
 * <p>Optional fields that are absent are left out rather than written as {@code null}, and lists
 * are always written, even when empty. Reading is strict: unknown fields, missing required fields
 * and values of the wrong JSON type are refused.
 */
public final class DocumentJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private DocumentJson() {}

  /** Writes a document as a JSON string. */
  public static String write(Document document) {
    return MAPPER.writeValueAsString(toJson(document));
  }

  /** Reads a document from a JSON string. */
  public static Document read(String json) {
    return fromJson(MAPPER.readTree(json));
  }

  public static ObjectNode toJson(Document document) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("schemaVersion", Document.SCHEMA_VERSION);
    node.put("id", document.id());
    node.put("title", document.title());
    putOptional(node, "audience", document.audience());
    ObjectNode template = node.putObject("template");
    template.put("name", document.template().name());
    template.put("version", document.template().version());
    ObjectNode source = node.putObject("source");
    source.put("snapshot", document.source().snapshot());
    source.put("vocabularyRevision", document.source().vocabularyRevision());
    source.put("toolVersion", document.source().toolVersion());
    ArrayNode entities = node.putArray("entities");
    document.entities().forEach(e -> entities.add(toJson(e)));
    ArrayNode facts = node.putArray("facts");
    document.facts().forEach(f -> facts.add(FactJson.toJson(f)));
    ArrayNode sections = node.putArray("sections");
    document.sections().forEach(s -> sections.add(toJson(s)));
    return node;
  }

  public static Document fromJson(JsonNode node) {
    Reader in = Reader.of(node, "document");
    in.allow(
        "schemaVersion",
        "id",
        "title",
        "audience",
        "template",
        "source",
        "entities",
        "facts",
        "sections");
    if (in.integer("schemaVersion") != Document.SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported document schemaVersion: " + in.required("schemaVersion"));
    }
    Reader template = in.object("template", "name", "version");
    Reader source = in.object("source", "snapshot", "vocabularyRevision", "toolVersion");
    return new Document(
        in.string("id"),
        in.string("title"),
        in.optionalString("audience"),
        new TemplateRef(template.string("name"), template.string("version")),
        new DocumentSource(
            source.integer("snapshot"),
            source.integer("vocabularyRevision"),
            source.string("toolVersion")),
        in.array("entities", DocumentJson::entityFromJson),
        in.array("facts", FactJson::fromJson),
        in.array("sections", DocumentJson::sectionFromJson));
  }

  private static ObjectNode toJson(DocumentEntity entity) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", entity.id().toString());
    node.put("name", entity.name());
    putOptional(node, "parent", entity.parent());
    if (!entity.description().isEmpty()) {
      node.set("description", contentToJson(entity.description()));
    }
    putFacts(node, entity.facts());
    return node;
  }

  private static DocumentEntity entityFromJson(JsonNode node) {
    Reader in = Reader.of(node, "entity", "id", "name", "parent", "description", "facts");
    return new DocumentEntity(
        in.entityId("id"),
        in.string("name"),
        in.optionalEntityId("parent"),
        in.has("description")
            ? Checks.content(in.content("description"), "entity description")
            : List.of(),
        in.facts());
  }

  private static ObjectNode toJson(Section section) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", section.id());
    node.put("title", section.title());
    if (!section.levels().isEmpty()) {
      ArrayNode levels = node.putArray("levels");
      section.levels().forEach(l -> levels.add(l.jsonName()));
    }
    putOptional(node, "entity", section.entity());
    putFacts(node, section.facts());
    ArrayNode blocks = node.putArray("blocks");
    section.blocks().forEach(b -> blocks.add(toJson(b)));
    ArrayNode sections = node.putArray("sections");
    section.sections().forEach(s -> sections.add(toJson(s)));
    return node;
  }

  private static Section sectionFromJson(JsonNode node) {
    Reader in =
        Reader.of(
            node, "section", "id", "title", "levels", "entity", "facts", "blocks", "sections");
    Set<Level> levels = EnumSet.noneOf(Level.class);
    if (in.has("levels")) {
      List<Level> list = in.array("levels", l -> Level.fromJsonName(Reader.string(l, "level")));
      if (list.isEmpty()) {
        throw new IllegalArgumentException("Section levels must not be empty when present");
      }
      if (list.size() != EnumSet.copyOf(list).size()) {
        throw new IllegalArgumentException("Duplicate section level");
      }
      levels.addAll(list);
    }
    return new Section(
        in.string("id"),
        in.string("title"),
        levels,
        in.optionalEntityId("entity"),
        in.facts(),
        in.array("blocks", DocumentJson::blockFromJson),
        in.array("sections", DocumentJson::sectionFromJson));
  }

  private static ObjectNode toJson(Block block) {
    ObjectNode node = MAPPER.createObjectNode();
    switch (block) {
      case Paragraph p -> {
        node.put("kind", "paragraph");
        node.put("id", p.id());
        node.set("content", contentToJson(p.content()));
      }
      case ListBlock l -> {
        node.put("kind", "list");
        node.put("id", l.id());
        node.put("ordered", l.ordered());
        ArrayNode items = node.putArray("items");
        for (ListBlock.Item item : l.items()) {
          ObjectNode itemNode = items.addObject();
          itemNode.set("content", contentToJson(item.content()));
          putFacts(itemNode, item.facts());
        }
      }
      case Callout c -> {
        node.put("kind", "callout");
        node.put("id", c.id());
        node.put("variant", c.variant().jsonName());
        if (c.severity() != null) {
          node.put("severity", c.severity().jsonName());
        }
        putOptional(node, "title", c.title());
        node.set("content", contentToJson(c.content()));
      }
      case Table t -> {
        node.put("kind", "table");
        node.put("id", t.id());
        putOptional(node, "title", t.title());
        ArrayNode columns = node.putArray("columns");
        t.columns().forEach(c -> columns.addObject().put("label", c.label()));
        ArrayNode rows = node.putArray("rows");
        for (Table.Row row : t.rows()) {
          ObjectNode rowNode = rows.addObject();
          putOptional(rowNode, "entity", row.entity());
          ArrayNode cells = rowNode.putArray("cells");
          row.cells().forEach(cell -> cells.add(contentToJson(cell)));
          putFacts(rowNode, row.facts());
        }
      }
      case Diagram d -> {
        node.put("kind", "diagram");
        node.put("id", d.id());
        node.put("diagramKind", d.diagramKind().jsonName());
        putOptional(node, "title", d.title());
        ArrayNode nodes = node.putArray("nodes");
        d.nodes().forEach(n -> nodes.add(toJson(n)));
        ArrayNode edges = node.putArray("edges");
        d.edges().forEach(e -> edges.add(toJson(e)));
      }
      case Tree t -> {
        node.put("kind", "tree");
        node.put("id", t.id());
        putOptional(node, "title", t.title());
        ArrayNode roots = node.putArray("roots");
        t.roots().forEach(r -> roots.add(toJson(r)));
      }
      case Metrics m -> {
        node.put("kind", "metrics");
        node.put("id", m.id());
        ArrayNode items = node.putArray("items");
        for (Metrics.Item item : m.items()) {
          ObjectNode itemNode = items.addObject();
          itemNode.put("label", item.label());
          itemNode.put("value", item.value());
          putOptional(itemNode, "unit", item.unit());
          putOptional(itemNode, "entity", item.entity());
          putFacts(itemNode, item.facts());
        }
      }
    }
    putFacts(node, block.facts());
    return node;
  }

  private static Block blockFromJson(JsonNode node) {
    String kind = Reader.of(node, "block").string("kind");
    return switch (kind) {
      case "paragraph" -> {
        Reader in = Reader.of(node, "paragraph", "kind", "id", "content", "facts");
        yield new Paragraph(in.string("id"), in.content("content"), in.facts());
      }
      case "list" -> {
        Reader in = Reader.of(node, "list", "kind", "id", "ordered", "items", "facts");
        yield new ListBlock(
            in.string("id"),
            in.bool("ordered"),
            in.array(
                "items",
                item -> {
                  Reader i = Reader.of(item, "list item", "content", "facts");
                  return new ListBlock.Item(i.content("content"), i.facts());
                }),
            in.facts());
      }
      case "callout" -> {
        Reader in =
            Reader.of(
                node, "callout", "kind", "id", "variant", "severity", "title", "content", "facts");
        String severity = in.optionalString("severity");
        yield new Callout(
            in.string("id"),
            Callout.Variant.fromJsonName(in.string("variant")),
            severity == null ? null : Callout.Severity.fromJsonName(severity),
            in.optionalString("title"),
            in.content("content"),
            in.facts());
      }
      case "table" -> {
        Reader in = Reader.of(node, "table", "kind", "id", "title", "columns", "rows", "facts");
        yield new Table(
            in.string("id"),
            in.optionalString("title"),
            in.array(
                "columns", c -> new Table.Column(Reader.of(c, "column", "label").string("label"))),
            in.array("rows", DocumentJson::rowFromJson),
            in.facts());
      }
      case "diagram" -> {
        Reader in =
            Reader.of(
                node, "diagram", "kind", "id", "diagramKind", "title", "nodes", "edges", "facts");
        yield new Diagram(
            in.string("id"),
            Diagram.Kind.fromJsonName(in.string("diagramKind")),
            in.optionalString("title"),
            in.array("nodes", DocumentJson::nodeFromJson),
            in.array("edges", DocumentJson::edgeFromJson),
            in.facts());
      }
      case "tree" -> {
        Reader in = Reader.of(node, "tree", "kind", "id", "title", "roots", "facts");
        yield new Tree(
            in.string("id"),
            in.optionalString("title"),
            in.array("roots", DocumentJson::treeNodeFromJson),
            in.facts());
      }
      case "metrics" -> {
        Reader in = Reader.of(node, "metrics", "kind", "id", "items", "facts");
        yield new Metrics(
            in.string("id"), in.array("items", DocumentJson::metricFromJson), in.facts());
      }
      default -> throw new IllegalArgumentException("Unknown block kind: '" + kind + "'");
    };
  }

  private static Table.Row rowFromJson(JsonNode node) {
    Reader in = Reader.of(node, "row", "entity", "cells", "facts");
    return new Table.Row(
        in.optionalEntityId("entity"),
        in.array("cells", cell -> Reader.list(cell, "cell", DocumentJson::inlineFromJson)),
        in.facts());
  }

  private static ObjectNode toJson(Diagram.Node diagramNode) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", diagramNode.id().toString());
    node.put("label", diagramNode.label());
    putOptional(node, "technology", diagramNode.technology());
    putOptional(node, "parent", diagramNode.parent());
    Diagram.Style style = diagramNode.style();
    if (style != null) {
      ObjectNode styleNode = node.putObject("style");
      putOptional(styleNode, "shape", style.shape());
      putOptional(styleNode, "icon", style.icon());
      putOptional(styleNode, "color", style.color());
    }
    Diagram.Position position = diagramNode.position();
    if (position != null) {
      ObjectNode positionNode = node.putObject("position");
      positionNode.put("x", position.x());
      positionNode.put("y", position.y());
      positionNode.put("pinned", position.pinned());
    }
    putFacts(node, diagramNode.facts());
    return node;
  }

  private static Diagram.Node nodeFromJson(JsonNode node) {
    Reader in =
        Reader.of(
            node, "node", "id", "label", "technology", "parent", "style", "position", "facts");
    Diagram.Style style = null;
    if (in.has("style")) {
      Reader s = in.object("style", "shape", "icon", "color");
      style =
          new Diagram.Style(
              s.optionalString("shape"), s.optionalString("icon"), s.optionalString("color"));
    }
    Diagram.Position position = null;
    if (in.has("position")) {
      Reader p = in.object("position", "x", "y", "pinned");
      position = new Diagram.Position(p.integer("x"), p.integer("y"), p.bool("pinned"));
    }
    return new Diagram.Node(
        in.entityId("id"),
        in.string("label"),
        in.optionalString("technology"),
        in.optionalEntityId("parent"),
        style,
        position,
        in.facts());
  }

  private static ObjectNode toJson(Diagram.Edge edge) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", edge.id());
    node.put("from", edge.from().toString());
    node.put("to", edge.to().toString());
    putOptional(node, "label", edge.label());
    putOptional(node, "relationship", edge.relationship());
    putFacts(node, edge.facts());
    return node;
  }

  private static Diagram.Edge edgeFromJson(JsonNode node) {
    Reader in = Reader.of(node, "edge", "id", "from", "to", "label", "relationship", "facts");
    return new Diagram.Edge(
        in.string("id"),
        in.entityId("from"),
        in.entityId("to"),
        in.optionalString("label"),
        in.optionalString("relationship"),
        in.facts());
  }

  private static ObjectNode toJson(Tree.Node treeNode) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("label", treeNode.label());
    putOptional(node, "entity", treeNode.entity());
    putFacts(node, treeNode.facts());
    ArrayNode children = node.putArray("children");
    treeNode.children().forEach(c -> children.add(toJson(c)));
    return node;
  }

  private static Tree.Node treeNodeFromJson(JsonNode node) {
    Reader in = Reader.of(node, "tree node", "label", "entity", "facts", "children");
    return new Tree.Node(
        in.string("label"),
        in.optionalEntityId("entity"),
        in.facts(),
        in.array("children", DocumentJson::treeNodeFromJson));
  }

  private static Metrics.Item metricFromJson(JsonNode node) {
    Reader in = Reader.of(node, "metric", "label", "value", "unit", "entity", "facts");
    JsonNode value = in.required("value");
    if (!value.isNumber()) {
      throw new IllegalArgumentException("Metric value must be a number");
    }
    return new Metrics.Item(
        in.string("label"),
        value.decimalValue(),
        in.optionalString("unit"),
        in.optionalEntityId("entity"),
        in.facts());
  }

  private static ArrayNode contentToJson(List<Inline> content) {
    ArrayNode array = MAPPER.createArrayNode();
    for (Inline inline : content) {
      ObjectNode node = array.addObject();
      switch (inline) {
        case TextRun t -> {
          node.put("kind", "text");
          node.put("text", t.text());
          if (t.style() != null) {
            node.put("style", t.style().jsonName());
          }
        }
        case CrossReference r -> {
          node.put("kind", "ref");
          node.put("text", r.text());
          ObjectNode target = node.putObject("target");
          target.put("kind", r.target().kind().jsonName());
          target.put("id", r.target().id());
        }
        case Citation c -> {
          node.put("kind", "citation");
          ArrayNode facts = node.putArray("facts");
          c.facts().forEach(facts::add);
        }
      }
    }
    return array;
  }

  private static Inline inlineFromJson(JsonNode node) {
    String kind = Reader.of(node, "inline").string("kind");
    return switch (kind) {
      case "text" -> {
        Reader in = Reader.of(node, "text run", "kind", "text", "style");
        String style = in.optionalString("style");
        yield new TextRun(
            in.string("text"), style == null ? null : TextRun.Style.fromJsonName(style));
      }
      case "ref" -> {
        Reader in = Reader.of(node, "cross-reference", "kind", "text", "target");
        Reader target = in.object("target", "kind", "id");
        yield new CrossReference(
            in.string("text"),
            new CrossReference.Target(
                CrossReference.Target.Kind.fromJsonName(target.string("kind")),
                target.string("id")));
      }
      case "citation" -> new Citation(Reader.of(node, "citation", "kind", "facts").facts());
      default -> throw new IllegalArgumentException("Unknown inline kind: '" + kind + "'");
    };
  }

  private static void putOptional(ObjectNode node, String field, Object value) {
    if (value != null) {
      node.put(field, value.toString());
    }
  }

  private static void putFacts(ObjectNode node, List<String> facts) {
    ArrayNode array = node.putArray("facts");
    facts.forEach(array::add);
  }

  /** Reads the fields of one JSON object, refusing unknown fields and wrong types. */
  private record Reader(JsonNode node, String what) {

    static Reader of(JsonNode node, String what, String... allowed) {
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("Document " + what + " must be a JSON object");
      }
      Reader reader = new Reader(node, what);
      if (allowed.length > 0) {
        reader.allow(allowed);
      }
      return reader;
    }

    void allow(String... allowed) {
      Set<String> names = Set.of(allowed);
      for (String name : node.propertyNames()) {
        if (!names.contains(name)) {
          throw new IllegalArgumentException("Unknown field '" + name + "' in document " + what);
        }
      }
    }

    boolean has(String field) {
      return node.has(field);
    }

    JsonNode required(String field) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) {
        throw new IllegalArgumentException(
            "Missing required field '" + field + "' in document " + what);
      }
      return value;
    }

    String string(String field) {
      return string(required(field), field);
    }

    static String string(JsonNode value, String field) {
      if (!value.isString()) {
        throw new IllegalArgumentException("Field '" + field + "' must be a string");
      }
      return value.stringValue();
    }

    String optionalString(String field) {
      return has(field) ? string(field) : null;
    }

    EntityId entityId(String field) {
      return EntityId.parse(string(field));
    }

    EntityId optionalEntityId(String field) {
      return has(field) ? entityId(field) : null;
    }

    /** An integer; as in JSON Schema, a number with a zero fraction such as 3.0 counts. */
    long integer(String field) {
      JsonNode value = required(field);
      if (value.isNumber()
          && !(value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue()))) {
        try {
          return value.decimalValue().longValueExact();
        } catch (ArithmeticException e) {
          // not integral or out of range
        }
      }
      throw new IllegalArgumentException("Field '" + field + "' must be an integer");
    }

    boolean bool(String field) {
      JsonNode value = required(field);
      if (!value.isBoolean()) {
        throw new IllegalArgumentException("Field '" + field + "' must be a boolean");
      }
      return value.booleanValue();
    }

    Reader object(String field, String... allowed) {
      return Reader.of(required(field), field, allowed);
    }

    <T> List<T> array(String field, Function<JsonNode, T> item) {
      return list(required(field), field, item);
    }

    static <T> List<T> list(JsonNode value, String field, Function<JsonNode, T> item) {
      if (!value.isArray()) {
        throw new IllegalArgumentException("Field '" + field + "' must be an array");
      }
      List<T> result = new ArrayList<>();
      for (JsonNode element : value) {
        result.add(item.apply(element));
      }
      return result;
    }

    List<Inline> content(String field) {
      return array(field, DocumentJson::inlineFromJson);
    }

    List<String> facts() {
      return array("facts", f -> string(f, "facts"));
    }
  }
}
