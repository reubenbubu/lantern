package io.lantern.harness;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ToolBox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code submit} tool of an agent conversation (knowledge-build epic, section 10, Agent loop):
 * the model submits its answer through it, as the answer envelope; the harness verifies each item
 * at once and returns per-item verdicts in the tool result, so the model can fix or withdraw what
 * failed in the same conversation. An item that fails a second time is dropped. It is an ordinary
 * tool the model chooses, never forced.
 */
final class SubmitTool implements ToolBox {

  /** The tool's name. */
  static final String NAME = "submit";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ToolBox tools;
  private final List<Item> packet;
  private final List<String> ids;
  private final PacketRole role;
  private final CheckInputs inputs;
  private final Map<String, ItemAnswer> accepted = new LinkedHashMap<>();
  private final Set<String> failedOnce = new LinkedHashSet<>();
  private final Set<String> dropped = new LinkedHashSet<>();
  private final Set<String> repaired = new LinkedHashSet<>();

  SubmitTool(
      ToolBox tools, List<Item> packet, List<String> ids, PacketRole role, CheckInputs inputs) {
    this.tools = tools;
    this.packet = List.copyOf(packet);
    this.ids = List.copyOf(ids);
    this.role = role;
    this.inputs = inputs;
  }

  /** The tool's definition: its input is the answer envelope. */
  static LlmTool definition() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema.putArray("required").add("items");
    schema.putObject("properties").putObject("items").put("type", "object");
    return new LlmTool(
        NAME,
        "Submit your answer: an object with an entry per item ID, either the item's answer or"
            + " {\"omitted\": true}. The result says which entries were accepted and why the others"
            + " were rejected; submit corrected entries again, or withdraw them.",
        schema);
  }

  @Override
  public List<LlmTool> tools() {
    List<LlmTool> all = new ArrayList<>(tools.tools());
    all.add(definition());
    return all;
  }

  @Override
  public synchronized LlmToolResult run(LlmToolCall call) {
    if (!call.name().equals(NAME)) {
      return tools.run(call);
    }
    var entries = Envelope.entries(call.input());
    if (entries.isEmpty()) {
      return LlmToolResult.error(call, "Submit an object {\"items\": {\"<item id>\": {...}}}.");
    }
    ObjectNode verdicts = JSON.createObjectNode();
    for (Map.Entry<String, JsonNode> entry : entries.get().entrySet()) {
      String id = entry.getKey();
      int index = ids.indexOf(id);
      if (index < 0) {
        verdicts.put(id, "not an item of this task");
      } else if (accepted.containsKey(id)) {
        verdicts.put(id, "already accepted");
      } else if (dropped.contains(id)) {
        verdicts.put(id, "dropped after a second rejection");
      } else {
        ItemAnswer answer = role.read(id, packet.get(index), entry.getValue());
        List<Rejection> found =
            answer.omitted() ? List.of() : Pipeline.STANDARD.check(answer, inputs);
        if (found.isEmpty()) {
          accepted.put(id, answer);
          if (failedOnce.contains(id)) {
            repaired.add(id);
          }
          verdicts.put(id, "accepted");
        } else if (failedOnce.add(id)) {
          verdicts.put(
              id,
              "rejected: "
                  + String.join(" ", found.stream().map(Rejection::reason).toList())
                  + " Submit it again corrected, or withdraw it.");
        } else {
          dropped.add(id);
          verdicts.put(
              id,
              "rejected again and dropped: "
                  + String.join(" ", found.stream().map(Rejection::reason).toList()));
        }
      }
    }
    return LlmToolResult.of(call, verdicts.toString());
  }

  synchronized Map<String, ItemAnswer> accepted() {
    return Map.copyOf(accepted);
  }

  synchronized Set<String> dropped() {
    return Set.copyOf(dropped);
  }

  synchronized boolean repaired(String id) {
    return repaired.contains(id);
  }
}
