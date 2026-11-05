package io.lantern.harness;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
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

  /** The dry run's name (#892). */
  static final String CHECK = "check";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ToolBox tools;
  private final List<Item> packet;
  private final List<String> ids;
  private final PacketRole role;
  private final java.util.function.IntFunction<CheckInputs> inputs;
  private final RejectedAnswer.Sink sink;
  private final Map<String, ItemAnswer> accepted = new LinkedHashMap<>();
  private final Set<String> failedOnce = new LinkedHashSet<>();
  private final Set<String> dropped = new LinkedHashSet<>();
  private final Set<String> repaired = new LinkedHashSet<>();
  private final Set<String> rejectedBefore = new LinkedHashSet<>();

  /**
   * @param inputs what the checks compare the answer of the packet's item at an index with, asked
   *     at each submit, so the references the tools have shown by then count
   */
  SubmitTool(
      ToolBox tools,
      List<Item> packet,
      List<String> ids,
      PacketRole role,
      java.util.function.IntFunction<CheckInputs> inputs,
      RejectedAnswer.Sink sink) {
    this.tools = tools;
    this.sink = sink;
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
    return definition(schema);
  }

  /** The tool's definition, its input the role's submit schema. */
  static LlmTool definition(JsonNode schema) {
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
    JsonNode schema = role.submitSchema();
    LlmTool submit = schema == null ? definition() : definition(schema);
    String description = role.submitDescription();
    all.add(description == null ? submit : new LlmTool(NAME, description, submit.inputSchema()));
    if (role.offersCheck()) {
      all.add(
          new LlmTool(
              CHECK,
              "A dry run of submit: the same input and the same checks, but nothing is recorded and"
                  + " a rejection here does not count. Use it before submit.",
              submit.inputSchema()));
    }
    return all;
  }

  @Override
  public synchronized LlmToolResult run(LlmToolCall call) {
    if (call.name().equals(CHECK) && role.offersCheck()) {
      return dryRun(call);
    }
    if (!call.name().equals(NAME)) {
      return tools.run(call);
    }
    var entries = Envelope.entries(role.envelope(call.input()));
    if (entries.isEmpty()) {
      return LlmToolResult.error(call, "Submit an object {\"items\": {\"<item id>\": {...}}}.");
    }
    ObjectNode verdicts = JSON.createObjectNode();
    for (Map.Entry<String, JsonNode> entry : entries.get().entrySet()) {
      String id = entry.getKey();
      int index = ids.indexOf(id);
      if (index < 0) {
        verdicts.put(id, "not an item of this task");
      } else if (accepted.containsKey(id) && !role.partial()) {
        verdicts.put(id, "already accepted");
      } else if (dropped.contains(id)) {
        verdicts.put(id, "dropped after a second rejection");
      } else {
        ItemAnswer answer = role.read(id, packet.get(index), entry.getValue());
        List<Rejection> found =
            answer.omitted() ? List.of() : role.pipeline().check(answer, inputs.apply(index));
        if (!found.isEmpty()) {
          sink.rejected(
              packet.get(index).subject(),
              rejectedBefore.add(id) ? 1 : 2,
              entry.getValue().toString(),
              RoleSession.reasons(found));
        }
        if (role.partial() && partly(id, index, answer, found, verdicts)) {
          continue;
        }
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

  /**
   * A partial role's entry: the parts no check rejected are added to what was accepted of the item
   * before, and the rejected ones listed by field. Returns false when a rejection is of the whole
   * item, which the usual rules then settle.
   */
  private boolean partly(
      String id, int index, ItemAnswer answer, List<Rejection> found, ObjectNode verdicts) {
    if (Entries.whole(found)) {
      if (accepted.containsKey(id)) {
        // What was accepted before stays; this submission adds nothing.
        verdicts.put(
            id,
            "rejected, keeping what was accepted before: "
                + String.join(" ", found.stream().map(Rejection::reason).toList()));
        return true;
      }
      return false;
    }
    ItemAnswer earlier = accepted.get(id);
    JsonNode kept = Entries.without(answer.raw(), found);
    if (earlier != null
        && !earlier.omitted()
        && earlier.raw() != null
        && earlier.raw().isObject()) {
      kept = answer.omitted() ? earlier.raw() : Entries.overlay((ObjectNode) earlier.raw(), kept);
    } else if (answer.omitted()) {
      kept = answer.raw();
    }
    if (!Entries.empty(kept) || answer.omitted()) {
      accepted.put(id, role.read(id, packet.get(index), kept));
    }
    if (found.isEmpty()) {
      verdicts.put(id, "accepted");
      return true;
    }
    StringBuilder text = new StringBuilder("accepted in part; rejected:");
    for (Rejection rejection : found) {
      text.append(' ').append(rejection.field()).append(": ").append(rejection.reason());
    }
    verdicts.put(id, text.append(" Submit corrected ones again, or leave them out.").toString());
    return true;
  }

  /** What submit would say of the call's entries, recording nothing. */
  private LlmToolResult dryRun(LlmToolCall call) {
    var entries = Envelope.entries(role.envelope(call.input()));
    if (entries.isEmpty()) {
      return LlmToolResult.error(call, "Check an object {\"items\": {\"<item id>\": {...}}}.");
    }
    ObjectNode verdicts = JSON.createObjectNode();
    for (Map.Entry<String, JsonNode> entry : entries.get().entrySet()) {
      int index = ids.indexOf(entry.getKey());
      if (index < 0) {
        verdicts.put(entry.getKey(), "not an item of this task");
        continue;
      }
      ItemAnswer answer = role.read(entry.getKey(), packet.get(index), entry.getValue());
      List<Rejection> found =
          answer.omitted() ? List.of() : role.pipeline().check(answer, inputs.apply(index));
      verdicts.put(
          entry.getKey(),
          found.isEmpty()
              ? "accepted"
              : "rejected: " + String.join(" ", found.stream().map(Rejection::reason).toList()));
    }
    return LlmToolResult.of(call, "Dry run, nothing recorded: " + verdicts);
  }

  /** Whether the item was submitted wrongly twice. */
  synchronized boolean rejectedTwice(String id) {
    return dropped.contains(id);
  }

  synchronized Map<String, ItemAnswer> accepted() {
    return Map.copyOf(accepted);
  }

  /**
   * The items dropped: rejected twice, or rejected once and never submitted again, which is not an
   * answer and must not be cached as one.
   */
  synchronized Set<String> dropped() {
    Set<String> all = new LinkedHashSet<>(dropped);
    failedOnce.stream().filter(id -> !accepted.containsKey(id)).forEach(all::add);
    return Set.copyOf(all);
  }

  synchronized boolean repaired(String id) {
    return repaired.contains(id);
  }
}
