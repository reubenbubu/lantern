package io.lantern.harness;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A run's budget, split by role group (knowledge-build epic, section 10, Run modes and cost
 * controls). A role that would pass its group's share stops planning new items; what it skips is
 * not asked.
 *
 * @param totalUsd the most the run's calls may cost, or {@code null} for no run budget
 * @param shares each group's share in percent
 */
public record RunBudget(BigDecimal totalUsd, Map<String, Integer> shares) {

  /** The groups and their default shares, in percent. */
  public static final Map<String, Integer> DEFAULT_SHARES = defaults();

  /** No run budget: only the monthly budget and the roles' own caps apply. */
  public static final RunBudget NONE = new RunBudget(null, DEFAULT_SHARES);

  public RunBudget {
    if (totalUsd != null && totalUsd.signum() < 0) {
      throw new IllegalArgumentException("A run budget must not be negative");
    }
    shares = Map.copyOf(Objects.requireNonNull(shares, "shares"));
    shares.forEach(
        (group, share) -> {
          if (share < 0 || share > 100) {
            throw new IllegalArgumentException("A share is a percentage: " + group + "=" + share);
          }
        });
    int total = shares.values().stream().mapToInt(Integer::intValue).sum();
    if (total > 100) {
      throw new IllegalArgumentException("The shares add up to " + total + "%, more than 100%");
    }
  }

  private static Map<String, Integer> defaults() {
    Map<String, Integer> shares = new LinkedHashMap<>();
    shares.put("investigate", 40);
    shares.put("describe", 20);
    shares.put("summarize", 18);
    shares.put("name-groups", 2);
    shares.put("flows-and-purpose", 10);
    shares.put("resolve", 5);
    shares.put("model-checks", 5);
    return Map.copyOf(shares);
  }

  /** The group's share in dollars, or {@code null} without a run budget. */
  public BigDecimal share(String group) {
    if (totalUsd == null) {
      return null;
    }
    Integer percent = shares.get(group);
    if (percent == null) {
      throw new IllegalArgumentException("No share for the role group " + group);
    }
    return totalUsd
        .multiply(BigDecimal.valueOf(percent))
        .divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
  }
}
