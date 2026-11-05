package io.lantern.harness;

import java.util.List;

/**
 * How a role whose items each make their own request joins several of them into one request and
 * splits the answer back (#874): the role knows its own request and answer formats, the harness
 * only when to join.
 */
public interface Packing {

  /**
   * The user turn of one request holding the items' first turns, in order; each item's references
   * are numbered after the ones before it, so a reference never means two things in one request.
   */
  String join(List<String> turns);

  /**
   * The answer to a joined request, split into each item's answer as the item's own request would
   * have had it, in order: its references numbered as in its own request. An item the answer leaves
   * out, or whose part cannot be read, is {@code null}: it is asked again alone.
   */
  List<String> split(String answer, List<String> turns);

  /** Whether the item's first turn may share a request; by default every one may. */
  default boolean packs(String turn) {
    return true;
  }

  /** What a joined request of that many items is about, such as {@code summarize 12 types}. */
  String purpose(int items);

  /**
   * How a packet is cut (#874).
   *
   * @param inputTokens the most estimated input tokens of a packed request, system prompt included,
   *     below the provider's price tier (100,000 prompt tokens for Haiku 5.5, {@code
   *     docs/research/haiku-request-pricing.md})
   * @param items the most items of a packet
   * @param thinkingPerItem the output tokens a packed request leaves per item for the model's
   *     thinking, beside each item's own answer limit
   */
  record Limits(long inputTokens, int items, int thinkingPerItem) {
    public Limits {
      if (inputTokens < 1 || items < 1 || thinkingPerItem < 0) {
        throw new IllegalArgumentException(
            "Packet limits must be positive: "
                + inputTokens
                + ", "
                + items
                + ", "
                + thinkingPerItem);
      }
    }
  }
}
