package io.lantern.link;

import io.lantern.core.fact.EntityId;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A resolution as the harness's answer cache stores it (ADR 0100), and back. */
final class ResolutionJson {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ResolutionJson() {}

  static String write(Resolution resolution) {
    ObjectNode node = JSON.createObjectNode();
    node.put("key", resolution.key());
    node.put("link", resolution.link());
    if (resolution.target() == null) {
      node.putNull("target");
      node.putNull("confidence");
    } else {
      node.put("target", resolution.target().toString());
      node.put("confidence", resolution.confidence());
    }
    resolution.cited().forEach(node.putArray("cited")::add);
    node.put("rationale", resolution.rationale());
    node.put("promptVersion", resolution.promptVersion());
    node.put("provider", resolution.provider());
    node.put("model", resolution.model());
    return JSON.writeValueAsString(node);
  }

  static Resolution read(String json) {
    JsonNode node = JSON.readTree(json);
    List<String> cited = new ArrayList<>();
    node.get("cited").forEach(c -> cited.add(c.asString()));
    boolean unknown = node.get("target").isNull();
    return new Resolution(
        node.get("key").asString(),
        node.get("link").asString(),
        unknown ? null : EntityId.parse(node.get("target").asString()),
        unknown ? null : node.get("confidence").asString(),
        cited,
        node.get("rationale").asString(),
        node.get("promptVersion").asString(),
        node.get("provider").asString(),
        node.get("model").asString());
  }
}
