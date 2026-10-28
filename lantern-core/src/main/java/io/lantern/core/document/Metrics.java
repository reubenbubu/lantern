package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Headline numbers.
 *
 * @param id the block ID
 * @param items the numbers, at least one
 * @param facts the IDs of the facts the block as a whole came from
 */
public record Metrics(String id, List<Item> items, List<String> facts) implements Block {

  public Metrics {
    Checks.elementId(id, "block ID");
    items = Checks.list(items, "metrics items");
    if (items.isEmpty()) {
      throw new IllegalArgumentException("Metrics must have at least one item");
    }
    facts = Checks.factIds(facts, "metrics facts");
  }

  /**
   * A headline number.
   *
   * @param label what is counted or measured
   * @param value the number, held without trailing zeros
   * @param unit the unit, or {@code null}
   * @param entity the entity the number is about, or {@code null}
   * @param facts the IDs of the facts the number came from
   */
  public record Item(
      String label, BigDecimal value, String unit, EntityId entity, List<String> facts) {

    public Item {
      Checks.text(label, "metric label");
      Objects.requireNonNull(value, "value");
      // One form per number, so 1, 1.0 and 1E+0 compare equal.
      value = new BigDecimal(value.stripTrailingZeros().toPlainString());
      Checks.optionalText(unit, "metric unit");
      facts = Checks.factIds(facts, "metric facts");
    }
  }
}
