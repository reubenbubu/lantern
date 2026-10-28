package io.lantern.core.document;

import java.util.List;

/**
 * A bulleted or numbered list.
 *
 * @param id the block ID
 * @param ordered whether the items are numbered
 * @param items the items, at least one
 * @param facts the IDs of the facts the list as a whole came from
 */
public record ListBlock(String id, boolean ordered, List<Item> items, List<String> facts)
    implements Block {

  public ListBlock {
    Checks.elementId(id, "block ID");
    items = Checks.list(items, "list items");
    if (items.isEmpty()) {
      throw new IllegalArgumentException("A list must have at least one item");
    }
    facts = Checks.factIds(facts, "list facts");
  }

  /**
   * A list item.
   *
   * @param content the text, not empty
   * @param facts the IDs of the facts the item came from
   */
  public record Item(List<Inline> content, List<String> facts) {

    public Item {
      content = Checks.content(content, "list item content");
      facts = Checks.factIds(facts, "list item facts");
    }
  }
}
