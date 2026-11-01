package io.lantern.harness.verify;

import java.util.List;
import java.util.Set;

/**
 * A text of an answer as the checks see it.
 *
 * @param field its field, such as {@code responsibility} or {@code interactions.R1}
 * @param kind what kind of text it is, which decides the Form rules
 * @param text the text, without citations
 * @param cites the references it cites, such as {@code F1} or {@code repo:path:10-20}
 * @param wordLimit the most words it may have
 * @param subjectName the name of what it is about, for the rule that a responsibility must not
 *     restate it; empty if none
 * @param names the code names a proposal declares it names, for the Coverage check; empty for other
 *     texts
 */
public record CheckedText(
    String field,
    Kind kind,
    String text,
    List<String> cites,
    int wordLimit,
    String subjectName,
    Set<String> names) {

  /** The kinds of text, by the Form rules they follow. */
  public enum Kind {
    /** Prose: a summary, a narrative, a rationale. */
    PROSE,
    /** A responsibility sentence: must not restate its subject's name. */
    RESPONSIBILITY,
    /** An intent phrase: no banned opening, no protocol word. */
    INTENT
  }

  public CheckedText {
    cites = List.copyOf(cites);
    subjectName = subjectName == null ? "" : subjectName;
    names = Set.copyOf(names);
  }
}
