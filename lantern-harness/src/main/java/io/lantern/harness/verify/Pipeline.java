package io.lantern.harness.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs checks over an item's answer and collects every rejection, so all of them go back in one
 * repair turn (knowledge-build epic, section 10, Verification pipeline).
 */
public final class Pipeline {

  /** The standard pipeline of every role on the harness's prompt layout. */
  public static final Pipeline STANDARD = new Pipeline(Checks.STANDARD);

  private final List<Check> checks;

  public Pipeline(List<Check> checks) {
    this.checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
  }

  /** Every rejection of the answer, check by check; empty when every check accepts it. */
  public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
    List<Rejection> found = new ArrayList<>();
    for (Check check : checks) {
      found.addAll(check.check(answer, inputs));
    }
    return found;
  }

  /**
   * The repair turn (knowledge-build epic, section 11.0): every rejection listed, asking for each
   * rejected part to be corrected or withdrawn.
   */
  public static String repairTurn(List<Rejection> rejections) {
    StringBuilder text =
        new StringBuilder(
            "Some parts of your answer were rejected. For each one below, answer again with a"
                + " corrected version, or withdraw it: answer {\"omitted\": true} for a whole item,"
                + " or leave a rejected field out of the item. Parts not listed were accepted; do"
                + " not repeat them.\n<rejections>\n");
    for (Rejection rejection : rejections) {
      text.append("<rejection item=\"").append(rejection.item()).append('"');
      if (!rejection.field().isEmpty()) {
        text.append(" field=\"").append(rejection.field()).append('"');
      }
      text.append('>').append(rejection.reason()).append("</rejection>\n");
    }
    text.append(
        "</rejections>\nAnswer with one JSON object in the same schema, holding only the items you"
            + " are correcting or withdrawing.");
    return text.toString();
  }
}
