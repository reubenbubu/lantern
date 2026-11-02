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

  /** An accepted entry as it is stored (JSON). */
  String store(Item item, ItemAnswer accepted);
}
