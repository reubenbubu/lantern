package io.lantern.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Checks that the JSON Schema and the Java types describe the same document model: every object in
 * the schema has the fields the Java form writes, its required fields are the ones the Java form
 * always writes, and its enums and patterns match the Java ones.
 *
 * <p>The sample document has at least one instance of every object, and for each optional field at
 * least one instance without it.
 */
class DocumentSchemaTest {

  static JsonNode schema() {
    return FactJson.parse(SampleDocument.resource("/schema/document.schema.json"));
  }

  static JsonNode defs() {
    return schema().get("$defs");
  }

  /** The objects of the sample document's JSON form, grouped by the schema definition for them. */
  static Map<String, List<JsonNode>> instances() {
    Map<String, List<JsonNode>> byDef = new LinkedHashMap<>();
    new Collector(byDef).document(DocumentJson.toJson(SampleDocument.create()));
    return byDef;
  }

  @Test
  void everyObjectDefinitionHasInstancesInTheSample() {
    Set<String> objectDefs = new HashSet<>();
    defs()
        .properties()
        .forEach(
            e -> {
              if (e.getValue().has("properties")) {
                objectDefs.add(e.getKey());
              }
            });

    assertThat(instances().keySet()).isEqualTo(objectDefs);
  }

  @Test
  void propertiesMatchTheJavaForm() {
    JsonNode defs = defs();
    instances()
        .forEach(
            (def, nodes) -> {
              Set<String> written = new HashSet<>();
              nodes.forEach(n -> written.addAll(n.propertyNames()));
              assertThat(written).as(def).isEqualTo(names(defs.get(def).get("properties")));
              assertThat(defs.get(def).get("additionalProperties").booleanValue())
                  .as(def)
                  .isFalse();
            });
  }

  @Test
  void requiredFieldsMatchWhatTheJavaFormAlwaysWrites() {
    JsonNode defs = defs();
    instances()
        .forEach(
            (def, nodes) -> {
              Set<String> always = new HashSet<>(nodes.get(0).propertyNames());
              nodes.forEach(n -> always.retainAll(n.propertyNames()));
              JsonNode required = defs.get(def).get("required");
              assertThat(required == null ? Set.of() : strings(required)).as(def).isEqualTo(always);
            });
  }

  @Test
  void topLevelPropertiesMatchTheJavaForm() {
    JsonNode schema = schema();
    JsonNode full = DocumentJson.toJson(SampleDocument.create());
    Document sample = SampleDocument.create();
    JsonNode minimal =
        DocumentJson.toJson(
            new Document(
                sample.id(),
                sample.title(),
                null,
                sample.template(),
                sample.source(),
                List.of(),
                List.of(),
                List.of()));

    assertThat(names(schema.get("properties"))).isEqualTo(names(full));
    assertThat(strings(schema.get("required"))).isEqualTo(names(minimal));
    assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
    assertThat(schema.get("properties").get("schemaVersion").get("const").intValue())
        .isEqualTo(Document.SCHEMA_VERSION);
  }

  @Test
  void enumsMatchTheJavaEnums() {
    JsonNode defs = defs();

    assertThat(strings(defs.get("level").get("enum")))
        .isEqualTo(jsonNames(Level.values(), Level::jsonName));
    assertThat(strings(defs.get("callout").get("properties").get("variant").get("enum")))
        .isEqualTo(jsonNames(Callout.Variant.values(), Callout.Variant::jsonName));
    assertThat(strings(defs.get("callout").get("properties").get("severity").get("enum")))
        .isEqualTo(jsonNames(Callout.Severity.values(), Callout.Severity::jsonName));
    assertThat(strings(defs.get("diagram").get("properties").get("diagramKind").get("enum")))
        .isEqualTo(jsonNames(Diagram.Kind.values(), Diagram.Kind::jsonName));
    assertThat(
            defs.get("sequenceDiagram")
                .get("properties")
                .get("diagramKind")
                .get("const")
                .stringValue())
        .isEqualTo(SequenceDiagram.DIAGRAM_KIND)
        .isNotIn(jsonNames(Diagram.Kind.values(), Diagram.Kind::jsonName));
    assertThat(
            strings(defs.get("sequenceMessage").get("properties").get("messageKind").get("enum")))
        .isEqualTo(
            jsonNames(SequenceDiagram.MessageKind.values(), SequenceDiagram.MessageKind::jsonName));
    assertThat(strings(defs.get("sequenceFrame").get("properties").get("operator").get("enum")))
        .isEqualTo(
            jsonNames(SequenceDiagram.Operator.values(), SequenceDiagram.Operator::jsonName));
    assertThat(strings(defs.get("textRun").get("properties").get("style").get("enum")))
        .isEqualTo(jsonNames(TextRun.Style.values(), TextRun.Style::jsonName));
    Set<String> targetKinds =
        new HashSet<>(strings(defs.get("elementTarget").get("properties").get("kind").get("enum")));
    targetKinds.add(
        defs.get("entityTarget").get("properties").get("kind").get("const").stringValue());
    assertThat(targetKinds)
        .isEqualTo(
            jsonNames(CrossReference.Target.Kind.values(), CrossReference.Target.Kind::jsonName));
  }

  @Test
  void blockInlineAndStepKindsMatchTheJavaForm() {
    JsonNode defs = defs();
    Map<String, List<JsonNode>> instances = instances();

    for (String union : List.of("block", "inline", "sequenceStep")) {
      Set<String> schemaKinds = new HashSet<>();
      Set<String> writtenKinds = new HashSet<>();
      for (JsonNode ref : defs.get(union).get("oneOf")) {
        String def = ref.get("$ref").stringValue().substring("#/$defs/".length());
        schemaKinds.add(defs.get(def).get("properties").get("kind").get("const").stringValue());
        instances.get(def).forEach(n -> writtenKinds.add(n.get("kind").stringValue()));
      }
      assertThat(writtenKinds).as(union).isEqualTo(schemaKinds);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"overview", "a.b_c-d:e", "A1", "-a", "a b", "", "é", "a/b"})
  void elementIdPatternAgreesWithJava(String value) {
    Pattern pattern = Pattern.compile(defs().get("elementId").get("pattern").stringValue());

    assertThat(pattern.matcher(value).find()).isEqualTo(Checks.isElementId(value));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"a", " a ", " ", "\u00a0", "\ufeff", "\u2028", "\t\n", "\u001c", "\u0085", ""})
  void nonBlankPatternAgreesWithJava(String value) {
    Pattern pattern =
        Pattern.compile(ecmaScript(defs().get("nonBlankText").get("pattern").stringValue()));

    assertThat(pattern.matcher(value).find()).isEqualTo(Checks.isNonBlank(value));
  }

  /** Java's {@code \S} differs from ECMAScript's; this spells out ECMAScript's for the schema. */
  private static String ecmaScript(String pattern) {
    assertThat(pattern).isEqualTo("\\S");
    return "[^\\t\\n\\u000b\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";
  }

  @ParameterizedTest
  @ValueSource(strings = {"#1f77b4", "#1F77B4", "1f77b4", "#1f77b", "red"})
  void colorPatternAgreesWithJava(String value) {
    Pattern pattern =
        Pattern.compile(
            defs().get("nodeStyle").get("properties").get("color").get("pattern").stringValue());
    boolean valid;
    try {
      new Diagram.Style(null, null, value);
      valid = true;
    } catch (IllegalArgumentException e) {
      valid = false;
    }

    assertThat(pattern.matcher(value).find()).isEqualTo(valid);
  }

  @Test
  void relationshipPatternIsThePredicatePattern() {
    JsonNode fact = FactJson.parse(SampleDocument.resource("/schema/fact-envelope.schema.json"));

    assertThat(defs().get("diagramEdge").get("properties").get("relationship").get("pattern"))
        .isEqualTo(fact.get("properties").get("predicate").get("pattern"));
    assertThat(defs().get("factId").get("pattern"))
        .isEqualTo(fact.get("properties").get("id").get("pattern"));
  }

  private static <E> Set<String> jsonNames(E[] values, Function<E, String> name) {
    return new HashSet<>(Stream.of(values).map(name).toList());
  }

  private static Set<String> names(JsonNode node) {
    return new HashSet<>(node.propertyNames());
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> result = new HashSet<>();
    for (JsonNode item : array) {
      result.add(item.stringValue());
    }
    return result;
  }

  /** Walks a document's JSON form, filing each object under its schema definition. */
  private record Collector(Map<String, List<JsonNode>> byDef) {

    void add(String def, JsonNode node) {
      byDef.computeIfAbsent(def, d -> new ArrayList<>()).add(node);
    }

    void document(JsonNode document) {
      add("templateRef", document.get("template"));
      add("documentSource", document.get("source"));
      for (JsonNode entity : document.get("entities")) {
        add("entity", entity);
        if (entity.has("description")) {
          content(entity.get("description"));
        }
      }
      document.get("sections").forEach(this::section);
    }

    void section(JsonNode section) {
      add("section", section);
      section.get("blocks").forEach(this::block);
      section.get("sections").forEach(this::section);
    }

    void block(JsonNode block) {
      String kind = block.get("kind").stringValue();
      switch (kind) {
        case "paragraph" -> {
          add("paragraph", block);
          content(block.get("content"));
        }
        case "list" -> {
          add("list", block);
          for (JsonNode item : block.get("items")) {
            add("listItem", item);
            content(item.get("content"));
          }
        }
        case "callout" -> {
          add("callout", block);
          content(block.get("content"));
        }
        case "table" -> {
          add("table", block);
          block.get("columns").forEach(c -> add("tableColumn", c));
          for (JsonNode row : block.get("rows")) {
            add("tableRow", row);
            row.get("cells").forEach(this::content);
          }
        }
        case "diagram" -> {
          if (block.get("diagramKind").stringValue().equals(SequenceDiagram.DIAGRAM_KIND)) {
            add("sequenceDiagram", block);
            for (JsonNode participant : block.get("participants")) {
              add("sequenceParticipant", participant);
              if (participant.has("style")) {
                add("nodeStyle", participant.get("style"));
              }
            }
            block.get("steps").forEach(this::step);
            return;
          }
          add("diagram", block);
          for (JsonNode node : block.get("nodes")) {
            add("diagramNode", node);
            if (node.has("style")) {
              add("nodeStyle", node.get("style"));
            }
            if (node.has("position")) {
              add("nodePosition", node.get("position"));
            }
          }
          block.get("edges").forEach(e -> add("diagramEdge", e));
        }
        case "tree" -> {
          add("tree", block);
          block.get("roots").forEach(this::treeNode);
        }
        case "metrics" -> {
          add("metrics", block);
          block.get("items").forEach(i -> add("metric", i));
        }
        case "quality-report" -> {
          add("qualityReport", block);
          for (JsonNode failure : block.get("failures")) {
            add("qualityFailure", failure);
            if (failure.has("target")) {
              target(failure.get("target"));
            }
            if (failure.has("detail")) {
              content(failure.get("detail"));
            }
          }
        }
        default -> throw new AssertionError("Unknown block kind " + kind);
      }
    }

    void step(JsonNode step) {
      String kind = step.get("kind").stringValue();
      switch (kind) {
        case "message" -> {
          add("sequenceMessage", step);
          if (step.has("info")) {
            content(step.get("info"));
          }
        }
        case "frame" -> {
          add("sequenceFrame", step);
          for (JsonNode operand : step.get("operands")) {
            add("sequenceOperand", operand);
            operand.get("steps").forEach(this::step);
          }
        }
        case "note" -> {
          add("sequenceNote", step);
          content(step.get("content"));
        }
        default -> throw new AssertionError("Unknown sequence step kind " + kind);
      }
    }

    void target(JsonNode target) {
      add(
          target.get("kind").stringValue().equals("entity") ? "entityTarget" : "elementTarget",
          target);
    }

    void treeNode(JsonNode node) {
      add("treeNode", node);
      node.get("children").forEach(this::treeNode);
    }

    void content(JsonNode content) {
      for (JsonNode inline : content) {
        String kind = inline.get("kind").stringValue();
        switch (kind) {
          case "text" -> add("textRun", inline);
          case "ref" -> {
            add("ref", inline);
            target(inline.get("target"));
          }
          case "citation" -> add("citation", inline);
          default -> throw new AssertionError("Unknown inline kind " + kind);
        }
      }
    }
  }
}
