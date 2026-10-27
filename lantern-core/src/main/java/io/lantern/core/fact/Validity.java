package io.lantern.core.fact;

/**
 * The range of snapshots in which a fact holds, by snapshot number.
 *
 * @param from the first snapshot in which the fact holds (inclusive)
 * @param to the first snapshot in which it no longer holds (exclusive), or {@code null} while it
 *     still holds
 */
public record Validity(long from, Long to) {

  public Validity {
    if (from < 0) {
      throw new IllegalArgumentException("Validity must start at a snapshot >= 0: " + from);
    }
    if (to != null && to <= from) {
      throw new IllegalArgumentException(
          "Validity end must be after its start: " + from + ".." + to);
    }
  }

  /** Holds from the given snapshot onwards. */
  public static Validity from(long from) {
    return new Validity(from, null);
  }

  /** Whether the fact holds in the given snapshot. */
  public boolean contains(long snapshot) {
    return snapshot >= from && (to == null || snapshot < to);
  }
}
