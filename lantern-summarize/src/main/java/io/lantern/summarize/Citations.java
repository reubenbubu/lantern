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
 * {@code F<n>} for the n-th fact, {@code E<n>} for the n-th source excerpt and {@code S<n>} for the
 * n-th contained summary in the prompt, cited in square brackets, alone or several separated by
 * commas ({@code [F1, E2, S2]}). A range of one kind, such as {@code [F1-F3]} or {@code [F1–F3]},
 * cites every reference in it. An excerpt is cited as the fact whose evidence it was taken from.
 */
final class Citations {

  /** A reference, or a range of them separated by a hyphen, en dash or em dash. */
  private static final String REF = "[FES]\\d+(?:\\s*[-\u2013\u2014]\\s*[FES]?\\d+)?";

  private static final Pattern GROUP =
      Pattern.compile("\\[\\s*(" + REF + "(?:\\s*,\\s*" + REF + ")*)\\s*\\]");
  private static final Pattern RANGE =
      Pattern.compile("([FES])(\\d+)\\s*[-\u2013\u2014]\\s*([FES]?)(\\d+)");
  private static final Pattern MARKER = Pattern.compile("\\[(?:fact|summary):");
  private static final Pattern SPLIT = Pattern.compile("\\s*,\\s*");

  /** The most references a range may span to be expanded; no prompt gives this many. */
  private static final int MAX_RANGE = 10_000;

  /** The most references a rejection lists. */
  private static final int MAX_LISTED = 10;

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
   * @param excerpts the IDs of the facts the source excerpts were taken from, {@code E1} first
   * @param children the contained summaries, {@code S1} first
   * @throws Rejected if the answer is empty, cites nothing, cites a reference that was not given or
   *     writes stored-form markers itself
   */
  static Checked check(
      String answer, List<String> facts, List<String> excerpts, List<Summary> children) {
    return check(answer, facts, excerpts, children, null);
  }

  /**
   * Checks an answer against the references a prompt gave when it showed only some of them.
   *
   * @param allowed the references the prompt showed, such as {@code F3}, or {@code null} for all
   * @throws Rejected as {@link #check(String, List, List, List)}, and if the answer cites a
   *     reference the prompt did not show
   */
  static Checked check(
      String answer,
      List<String> facts,
      List<String> excerpts,
      List<Summary> children,
      Set<String> allowed) {
    String text = answer == null ? "" : answer.strip();
    if (text.isEmpty()) {
      throw new Rejected("it is empty.");
    }
    if (MARKER.matcher(text).find()) {
      throw new Rejected(
          "it contains [fact:...] or [summary:...] markers; cite only with the references given,"
              + " such as [F1] or [S1].");
    }
    Set<String> citedFacts = new LinkedHashSet<>();
    Set<String> citedSummaries = new LinkedHashSet<>();
    Set<String> unknown = new TreeSet<>();
    Set<String> unreadable = new TreeSet<>();
    Set<String> beyond = new TreeSet<>();
    StringBuilder out = new StringBuilder();
    Matcher matcher = GROUP.matcher(text);
    while (matcher.find()) {
      StringBuilder markers = new StringBuilder();
      List<String> refs = new ArrayList<>();
      for (String token : SPLIT.split(matcher.group(1))) {
        Range range = Range.of(token);
        if (range == null) {
          refs.add(token);
        } else if (!range.readable()) {
          unreadable.add(range.written());
        } else if (range.first() < 1
            || range.last()
                > switch (range.kind()) {
                  case 'F' -> facts.size();
                  case 'E' -> excerpts.size();
                  default -> children.size();
                }) {
          beyond.add(range.written());
        } else {
          refs.addAll(range.refs());
        }
      }
      for (String ref : refs) {
        int index = parseIndex(ref.substring(1));
        if (allowed != null && !allowed.contains(ref)) {
          unknown.add(ref);
        } else if (ref.charAt(0) == 'F' && index >= 1 && index <= facts.size()) {
          String id = facts.get(index - 1);
          citedFacts.add(id);
          markers.append("[fact:").append(id).append(']');
        } else if (ref.charAt(0) == 'E' && index >= 1 && index <= excerpts.size()) {
          String id = excerpts.get(index - 1);
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
    List<String> problems = new ArrayList<>();
    if (!unreadable.isEmpty()) {
      problems.add(
          "it cites ranges that are not of one kind in ascending order, or are too long: "
              + listed(unreadable)
              + "; cite each reference on its own");
    }
    if (!beyond.isEmpty()) {
      problems.add(
          "it cites ranges that reach past the references in the input: " + listed(beyond));
    }
    if (!unknown.isEmpty()) {
      problems.add("it cites references that are not in the input: " + listed(unknown));
    }
    if (!problems.isEmpty()) {
      throw new Rejected(String.join("; ", problems) + ".");
    }
    if (citedFacts.isEmpty() && citedSummaries.isEmpty()) {
      throw new Rejected("it cites no references.");
    }
    return new Checked(
        out.toString(), new ArrayList<>(citedFacts), new ArrayList<>(citedSummaries));
  }

  /**
   * The references a text cites, such as {@code F3} and {@code S1}, in order of first citation,
   * with a range listed as every reference in it. A range that cannot be expanded is listed as
   * written, without spaces, so it matches no reference.
   */
  static Set<String> references(String text) {
    Set<String> refs = new LinkedHashSet<>();
    Matcher matcher = GROUP.matcher(text);
    while (matcher.find()) {
      for (String token : SPLIT.split(matcher.group(1))) {
        Range range = Range.of(token);
        if (range == null) {
          refs.add(token);
        } else if (range.readable() && range.first() >= 1) {
          refs.addAll(range.refs());
        } else {
          refs.add(range.written());
        }
      }
    }
    return refs;
  }

  /** The first {@link #MAX_LISTED} of some references, and how many more there are. */
  private static String listed(Set<String> refs) {
    if (refs.size() <= MAX_LISTED) {
      return refs.toString();
    }
    return refs.stream().limit(MAX_LISTED).toList()
        + " and "
        + (refs.size() - MAX_LISTED)
        + " more";
  }

  /**
   * A range of references as cited, such as {@code F1-F3}.
   *
   * @param kind the first reference's kind, {@code F}, {@code E} or {@code S}
   * @param first the first index, or -1 if it is too large to read
   * @param last the last index, or -1 if it is too large to read
   * @param readable whether both indexes could be read and it is of one kind, in ascending order
   *     and spans at most {@link #MAX_RANGE} references, so it can be expanded
   * @param written the range as cited, without spaces
   */
  private record Range(char kind, long first, long last, boolean readable, String written) {

    /** The range a token cites, or {@code null} if it is a single reference. */
    static Range of(String token) {
      Matcher matcher = RANGE.matcher(token);
      if (!matcher.matches()) {
        return null;
      }
      char kind = matcher.group(1).charAt(0);
      long first = parseLong(matcher.group(2));
      long last = parseLong(matcher.group(4));
      boolean sameKind = matcher.group(3).isEmpty() || matcher.group(3).charAt(0) == kind;
      boolean parsed = first >= 0 && last >= 0;
      boolean readable = sameKind && parsed && first <= last && last - first < MAX_RANGE;
      return new Range(kind, first, last, readable, token.replaceAll("\\s+", ""));
    }

    /** Every reference in the range, which must be readable. */
    List<String> refs() {
      List<String> refs = new ArrayList<>();
      for (long offset = 0; offset <= last - first; offset++) {
        refs.add(kind + Long.toString(first + offset));
      }
      return refs;
    }
  }

  private static long parseLong(String digits) {
    try {
      return Long.parseLong(digits);
    } catch (NumberFormatException e) {
      return -1;
    }
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
