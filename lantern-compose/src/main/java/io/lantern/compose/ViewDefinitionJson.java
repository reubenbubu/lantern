package io.lantern.compose;

import io.lantern.core.document.TemplateRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and reads view definitions as JSON, the form they are stored in:
 *
 * <pre>{@code
 * {"id": "shop", "title": "Shop", "scope": {"kind": "repo", "repo": "shop"},
 *  "audience": "architect", "template": {"name": "architecture-explorer", "version": "1"},
 *  "sections": ["overview", "context", "containers"]}
 * }</pre>
 *
 * <p>{@code audience} is left out when there is none. Unknown fields are refused.
 */
public final class ViewDefinitionJson {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ViewDefinitionJson() {}

  public static String write(ViewDefinition view) {
    return MAPPER.writeValueAsString(toJson(view));
  }

  public static ViewDefinition read(String json) {
    return fromJson(MAPPER.readTree(json));
  }

  public static ObjectNode toJson(ViewDefinition view) {
    Objects.requireNonNull(view, "view");
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", view.id());
    node.put("title", view.title());
    ObjectNode scope = node.putObject("scope");
    scope.put("kind", view.scope().kind().jsonName());
    scope.put("repo", view.scope().repo());
    if (view.audience() != null) {
      node.put("audience", view.audience());
    }
    ObjectNode template = node.putObject("template");
    template.put("name", view.template().name());
    template.put("version", view.template().version());
    ArrayNode sections = node.putArray("sections");
    view.sections().forEach(s -> sections.add(s.jsonName()));
    return node;
  }

  public static ViewDefinition fromJson(JsonNode node) {
    object(
        node,
        "view definition",
        Set.of("id", "title", "scope", "audience", "template", "sections"));
    JsonNode scope = node.get("scope");
    object(scope, "scope", Set.of("kind", "repo"));
    JsonNode template = node.get("template");
    object(template, "template", Set.of("name", "version"));
    JsonNode sections = node.get("sections");
    if (sections == null || !sections.isArray()) {
      throw new IllegalArgumentException("View definition sections must be an array");
    }
    List<SectionKind> kinds = new ArrayList<>();
    for (JsonNode section : sections) {
      kinds.add(SectionKind.fromJsonName(text(section, "section")));
    }
    JsonNode audience = node.get("audience");
    return new ViewDefinition(
        text(node.get("id"), "id"),
        text(node.get("title"), "title"),
        new Scope(
            Scope.Kind.fromJsonName(text(scope.get("kind"), "scope kind")),
            text(scope.get("repo"), "scope repo")),
        audience == null ? null : text(audience, "audience"),
        new TemplateRef(
            text(template.get("name"), "template name"),
            text(template.get("version"), "template version")),
        kinds);
  }

  private static void object(JsonNode node, String what, Set<String> fields) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(what + " must be a JSON object");
    }
    for (String name : node.propertyNames()) {
      if (!fields.contains(name)) {
        throw new IllegalArgumentException("Unknown field '" + name + "' in " + what);
      }
    }
  }

  private static String text(JsonNode node, String what) {
    if (node == null || !node.isString()) {
      throw new IllegalArgumentException(what + " must be a string");
    }
    return node.asString();
  }
}
