package io.lantern.summarize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the references a model's answer cites and rewrites them to stable IDs. References are
 * {@code F<n>} for the n-th fact and {@code S<n>} for the n-th contained summary in the prompt,
 * cited in square brackets, alone or several separated by commas ({@code [F1, S2]}).
 */
final class Citations {

  private static final Pattern GROUP =
      Pattern.compile("\\[\\s*([FS]\\d+(?:\\s*,\\s*[FS]\\d+)*)\\s*\\]");
  private static final Pattern SPLIT = Pattern.compile("\\s*,\\s*");

  private Citations() {}

  /**
   * The checked answer.
   *
   * @param text the answer with references rewritten to {@code [fact:<ID>]} and {@code
   *     [summary:<entity ID>]}
   * @param facts the cited fact IDs, in order of first citation
   * @param summaries the cited summaries' keys, in order of first citation
   */
  record Checked(String text, List<String> facts, List<String> summaries) {}

  /**
   * Checks an answer against the references the prompt gave.
   *
   * @param facts the facts' IDs, {@code F1} first
   * @param children the contained summaries, {@code S1} first
   * @throws Rejected if the answer is empty, cites nothing or cites a reference that was not given
   */
  static Checked check(String answer, List<String> facts, List<Summary> children) {
    String text = answer == null ? "" : answer.strip();
    if (text.isEmpty()) {
      throw new Rejected("it is empty.");
    }
    Set<String> citedFacts = new LinkedHashSet<>();
    Set<String> citedSummaries = new LinkedHashSet<>();
    Set<String> unknown = new TreeSet<>();
    StringBuilder out = new StringBuilder();
    Matcher matcher = GROUP.matcher(text);
    while (matcher.find()) {
      StringBuilder markers = new StringBuilder();
      for (String ref : SPLIT.split(matcher.group(1))) {
        int index = parseIndex(ref.substring(1));
        if (ref.charAt(0) == 'F' && index >= 1 && index <= facts.size()) {
          String id = facts.get(index - 1);
          citedFacts.add(id);
          markers.append("[fact:").append(id).append(']');
        } else if (ref.charAt(0) == 'S' && index >= 1 && index <= children.size()) {
          Summary child = children.get(index - 1);
          citedSummaries.add(child.key());
          markers.append("[summary:").append(child.entity()).append(']');
        } else {
          unknown.add(ref);
        }
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(markers.toString()));
    }
    matcher.appendTail(out);
    if (!unknown.isEmpty()) {
      throw new Rejected("it cites references that are not in the input: " + unknown + ".");
    }
    if (citedFacts.isEmpty() && citedSummaries.isEmpty()) {
      throw new Rejected("it cites no references.");
    }
    return new Checked(
        out.toString(), new ArrayList<>(citedFacts), new ArrayList<>(citedSummaries));
  }

  private static int parseIndex(String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Why an answer was rejected, phrased to be sent back to the model. */
  static final class Rejected extends RuntimeException {
    Rejected(String reason) {
      super(reason);
    }
  }
}
