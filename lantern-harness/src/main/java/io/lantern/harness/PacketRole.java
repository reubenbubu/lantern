package io.lantern.harness;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
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

  /** What the checks compare the answers of the packet's items with. */
  CheckInputs inputs(List<Item> packet, List<String> ids);

  /** An item's entry, read into the checks' form. */
  ItemAnswer read(String id, Item item, JsonNode entry);

  /** An accepted entry as it is stored (JSON). */
  String store(Item item, ItemAnswer accepted);
}
