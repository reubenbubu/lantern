package io.lantern.harness;

import java.util.List;

/**
 * An answer the checks rejected, with every reason, which go back in one repair turn
 * (knowledge-build epic, section 10, Verification pipeline).
 */
public final class Rejected extends Exception {

  private final transient List<String> reasons;

  public Rejected(List<String> reasons) {
    super(String.join(" ", reasons), null, false, false);
    if (reasons.isEmpty()) {
      throw new IllegalArgumentException("A rejection needs a reason");
    }
    this.reasons = List.copyOf(reasons);
  }

  public Rejected(String reason) {
    this(List.of(reason));
  }

  /** Every reason, in order. */
  public List<String> reasons() {
    return reasons;
  }
}
