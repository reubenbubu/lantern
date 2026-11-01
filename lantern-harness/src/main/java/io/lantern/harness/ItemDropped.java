package io.lantern.harness;

/** An item whose answer failed the checks again after the repair turn. */
public final class ItemDropped extends Exception {

  public ItemDropped(String message) {
    super(message, null, false, false);
  }
}
