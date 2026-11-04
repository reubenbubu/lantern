package io.lantern.harness;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A role whose items share requests in packets (knowledge-build epic, section 10, Items, plans and
 * packets): it lays out a packet's request, says what the checks compare each answer with, reads
 * each item's entry into the checks' form, and turns an accepted entry into what is stored.
 */
public interface PacketRole {

  /** The request of a packet, whose items have the given local IDs ({@code I1}, ...). */
  PromptLayout layout(List<Item> packet, List<String> ids);

  /** The most tokens the answer to a packet of that many items may take. */
  int maxOutputTokens(int items);

  /** The most tokens the answer to the packet may take; by default, by its number of items. */
  default int maxOutputTokens(List<Item> packet) {
    return maxOutputTokens(packet.size());
  }

  /** What the checks compare the answers of the packet's items with. */
  CheckInputs inputs(List<Item> packet, List<String> ids);

  /**
   * What the checks compare the answer of the packet's item at the index with; by default what they
   * compare every item's with. A role whose items number their references one after another gives
   * each item only its own, so an item cannot cite what another item shows.
   */
  default CheckInputs inputs(List<Item> packet, List<String> ids, int index) {
    return inputs(packet, ids);
  }

  /** An item's entry, read into the checks' form. */
  ItemAnswer read(String id, Item item, JsonNode entry);

  /**
   * The checks of each item's answer: by default the standard pipeline; a role with checks of its
   * own, such as Resolve's, adds them to it.
   */
  default Pipeline pipeline() {
    return Pipeline.STANDARD;
  }

  /**
   * The input schema of the {@code submit} tool in an agent conversation: the role's submit schema
   * as the epic writes it, or null for the answer envelope with any entries.
   */
  default JsonNode submitSchema() {
    return null;
  }

  /**
   * What the {@code submit} tool's description tells the model; null for the harness's, which
   * describes the answer envelope.
   */
  default String submitDescription() {
    return null;
  }

  /**
   * A {@code submit} call's input as the answer envelope, {@code {"items": {"<item id>": {...}}}}:
   * by default the input as it is; a role whose submit schema is written another way, such as a
   * list of proposals, turns it into the envelope here.
   */
  default JsonNode envelope(JsonNode submitted) {
    return submitted;
  }

  /**
   * Whether an item's entry is kept in part (knowledge-build epic, section 11.2, Modes B and C):
   * when every rejection of a submitted entry is of a field, the rest is accepted at once, and a
   * later submission of the item adds to what was accepted, so the model may submit more than once.
   * By default an entry is accepted whole or not at all, once.
   */
  default boolean partial() {
    return false;
  }

  /**
   * Whether a text of an accepted answer keeps its earlier wording when everything it cited is
   * still shown unchanged (#722, ADR 0126); by default none does. A role says yes for texts that
   * stand on their own, and no for a text that only gives the reason for a verdict the model may
   * change.
   */
  default boolean carries(io.lantern.harness.verify.CheckedText text) {
    return false;
  }

  /**
   * The stable name of what a text of the item's answer is about, so a text is kept only for the
   * same thing whatever key the item gives it this time (#722); by default its subject name.
   */
  default String about(Item item, io.lantern.harness.verify.CheckedText text) {
    return text.subjectName();
  }

  /** An accepted entry as it is stored (JSON). */
  String store(Item item, ItemAnswer accepted);

  /**
   * The fields an item asks for, such as {@code interactions.R1}, which the run record counts when
   * the model leaves them out (#741); by default none are counted.
   */
  default List<String> askedFields(Item item) {
    return List.of();
  }
}
