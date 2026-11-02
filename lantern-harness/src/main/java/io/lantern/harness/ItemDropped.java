package io.lantern.harness;

/** An item whose answer failed the checks again after the repair turn. */
public final class ItemDropped extends Exception {

  private final boolean kept;

  public ItemDropped(String message) {
    this(message, true);
  }

  /**
   * @param kept whether the drop is stored, so a rerun on the same inputs repeats it without a call
   *     (#682); not for an answer cut off at its length limit, which a larger limit may let through
   */
  public ItemDropped(String message, boolean kept) {
    super(message, null, false, false);
    this.kept = kept;
  }

  /** Whether the drop is stored for a rerun to repeat. */
  public boolean kept() {
    return kept;
  }
}
