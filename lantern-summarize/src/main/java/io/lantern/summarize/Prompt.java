package io.lantern.summarize;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ObjectNode;

/**
 * The summarize prompt. Facts and the contained units' summaries are listed with short references
 * ({@code F1}, {@code S1}) that the model cites, and that {@link Citations} maps back.
 *
 * <p>Change {@link #VERSION} whenever the text of the prompt or the way inputs are written into it
 * changes, so cached summaries made with the old prompt are not reused. {@code PromptTest} fails
 * until it is changed.
 */
final class Prompt {

  /** The prompt's version, part of every summary's cache key. */
  static final String VERSION = "1";

  static final String SYSTEM =
      """
      You write short summaries of parts of a Java code base for architecture documentation.
      You are given facts that static analysis extracted from the code, each with a reference \
      such as [F1], and summaries of the parts the unit contains, each with a reference such as \
      [S1].
      Say what the unit is for and what it does, and name its most important dependencies.
      Use only the facts and summaries given. Where they do not show what something is for, say \
      what it contains rather than guessing.
      End every sentence with the references it is based on, in square brackets, for example \
      [F1] or [F2, S1]. Cite only references from the input.
      Write plain prose without headings, lists or a title.""";

  static final String RETRY =
      """
      Your summary was rejected: %s
      Write it again. End every sentence with the references it is based on, in square brackets, \
      and cite only references from the input.""";

  private static final Pattern MARKER = Pattern.compile("\\s*\\[(?:fact|summary):[^\\]\\s]+\\]");

  private Prompt() {}

  /**
   * The user turn for a unit.
   *
   * @param children the contained units' summaries, in the order they are referenced
   */
  static String user(SummaryNode node, List<Summary> children) {
    StringBuilder text = new StringBuilder();
    text.append("Summarize the ")
        .append(node.level().noun())
        .append(' ')
        .append(node.entity())
        .append(" in at most ")
        .append(node.level().maxWords())
        .append(" words.\n");
    if (!node.facts().isEmpty()) {
      text.append("\nFacts:\n");
      for (int i = 0; i < node.facts().size(); i++) {
        text.append("[F").append(i + 1).append("] ").append(fact(node.facts().get(i))).append('\n');
      }
    }
    if (!children.isEmpty()) {
      text.append("\nSummaries of the parts it contains:\n");
      for (int i = 0; i < children.size(); i++) {
        Summary child = children.get(i);
        text.append("[S")
            .append(i + 1)
            .append("] ")
            .append(child.entity())
            .append(": ")
            .append(withoutMarkers(child.text()))
            .append('\n');
      }
    }
    return text.toString();
  }

  /** A fact on one line: subject, predicate, object, and attributes if it has any. */
  static String fact(Fact fact) {
    StringBuilder line = new StringBuilder();
    line.append(fact.subject()).append(' ').append(fact.predicate()).append(' ');
    FactObject object = fact.object();
    if (object.kind() == FactObject.Kind.STRING) {
      line.append('"')
          .append(object.value().replace("\\", "\\\\").replace("\"", "\\\""))
          .append('"');
    } else {
      line.append(object.value());
    }
    ObjectNode attributes = fact.attributes();
    if (!attributes.isEmpty()) {
      line.append(' ').append(attributes);
    }
    return line.toString().replace('\n', ' ');
  }

  /** A child's text as the parent's prompt shows it: its own citations would only confuse. */
  static String withoutMarkers(String text) {
    return MARKER.matcher(text).replaceAll("").replace('\n', ' ').strip();
  }
}
