package io.lantern.summarize;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ObjectNode;

/**
 * The summarize prompt. Facts, source excerpts and the contained units' summaries are listed with
 * short references ({@code F1}, {@code E1}, {@code S1}) that the model cites, and that {@link
 * Citations} maps back.
 *
 * <p>A unit whose inputs do not fit one prompt is summarized in parts, each prompt showing some of
 * the inputs under their usual references, and the parts' summaries are then merged into one, which
 * keeps the references they cite.
 *
 * <p>Change {@link #VERSION} whenever the text of the prompt or the way inputs are written into it
 * changes (including which source files {@link Excerpts} shows), so cached summaries made with the
 * old prompt are not reused. {@code PromptTest} fails until it is changed.
 */
final class Prompt {

  /** The prompt's version, part of every summary's cache key. */
  static final String VERSION = "2";

  static final String SYSTEM =
      """
      You write short summaries of parts of a Java code base for architecture documentation.
      You are given facts that static analysis extracted from the code, each with a reference \
      such as [F1], source files, each with a reference such as [E1], and summaries of the \
      parts the unit contains, each with a reference such as [S1].
      Say what the unit is for and what it does, and name its most important dependencies.
      Use only the facts, source files and summaries given. Where they do not show what something is \
      for, say what it contains rather than guessing.
      End every sentence with the references it is based on, in square brackets, for example \
      [F1] or [F2, E1, S1]. Cite only references from the input.
      Write plain prose without headings, lists or a title.""";

  static final String RETRY =
      """
      Your summary was rejected: %s
      Write it again. End every sentence with the references it is based on, in square brackets, \
      and cite only references from the input.""";

  private static final Pattern MARKER = Pattern.compile("\\s*\\[(?:fact|summary):[^\\]\\s]+\\]");

  private Prompt() {}

  /**
   * One input as a prompt lists it.
   *
   * @param ref its reference, such as {@code F1}
   * @param text what follows the reference
   */
  record Item(String ref, String text) {

    /** The item as the prompt writes it. */
    String rendered() {
      return "[" + ref + "] " + text + "\n";
    }
  }

  /** A part of a unit's inputs, when they do not fit one prompt. */
  record Part(int number, int of) {}

  /** The facts as prompt items, {@code F1} first. */
  static List<Item> facts(List<Fact> facts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < facts.size(); i++) {
      items.add(new Item("F" + (i + 1), fact(facts.get(i))));
    }
    return items;
  }

  /** The source files as prompt items, {@code E1} first. */
  static List<Item> excerpts(List<Excerpts.Excerpt> excerpts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < excerpts.size(); i++) {
      Excerpts.Excerpt excerpt = excerpts.get(i);
      String fence = fence(excerpt.code());
      items.add(
          new Item(
              "E" + (i + 1),
              excerpt.path() + ":\n" + fence + "java\n" + excerpt.code() + "\n" + fence));
    }
    return items;
  }

  /** A code fence longer than any run of backticks in the code, at least three. */
  static String fence(String code) {
    int longest = 0;
    int run = 0;
    for (int i = 0; i < code.length(); i++) {
      run = code.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    return "`".repeat(Math.max(3, longest + 1));
  }

  /** The contained units' summaries as prompt items, {@code S1} first. */
  static List<Item> children(List<Summary> children) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < children.size(); i++) {
      Summary child = children.get(i);
      items.add(new Item("S" + (i + 1), child.entity() + ": " + withoutMarkers(child.text())));
    }
    return items;
  }

  /**
   * The user turn for a unit whose inputs fit one prompt.
   *
   * @param children the contained units' summaries, in the order they are referenced
   */
  static String user(SummaryNode node, List<Excerpts.Excerpt> excerpts, List<Summary> children) {
    return user(node, facts(node.facts()), excerpts(excerpts), children(children), null);
  }

  /**
   * The user turn for a unit, or for one part of its inputs.
   *
   * @param part which part of the inputs this is, or {@code null} for all of them
   */
  static String user(
      SummaryNode node, List<Item> facts, List<Item> excerpts, List<Item> children, Part part) {
    StringBuilder text = new StringBuilder();
    if (part == null) {
      text.append("Summarize the ")
          .append(node.level().noun())
          .append(' ')
          .append(node.entity())
          .append(" in at most ")
          .append(node.level().maxWords())
          .append(" words.\n");
    } else {
      text.append("What is known about the ")
          .append(node.level().noun())
          .append(' ')
          .append(node.entity())
          .append(" is too long for one request, so it comes in ")
          .append(part.of())
          .append(" parts. This is part ")
          .append(part.number())
          .append(". Summarize what this part shows about it in at most ")
          .append(node.level().maxWords())
          .append(" words; the summaries of the parts will be merged later.\n");
    }
    section(text, "Facts:", facts);
    section(text, "Source files:", excerpts);
    section(text, "Summaries of the parts it contains:", children);
    return text.toString();
  }

  /**
   * The user turn that merges summaries of parts of a unit's inputs into one.
   *
   * @param parts the parts' summaries, with the references they cite
   */
  static String merge(SummaryNode node, List<String> parts) {
    StringBuilder text = new StringBuilder();
    text.append("What is known about the ")
        .append(node.level().noun())
        .append(' ')
        .append(node.entity())
        .append(" was too long for one request, so it was summarized in parts. Merge the ")
        .append("summaries of the parts below into one summary of at most ")
        .append(node.level().maxWords())
        .append(" words. Cite the references the parts cite for what you take from them.\n");
    text.append("\nSummaries of the parts:\n");
    for (int i = 0; i < parts.size(); i++) {
      text.append("Part ")
          .append(i + 1)
          .append(": ")
          .append(parts.get(i).strip().replace('\n', ' '))
          .append('\n');
    }
    return text.toString();
  }

  private static void section(StringBuilder text, String heading, List<Item> items) {
    if (items.isEmpty()) {
      return;
    }
    text.append('\n').append(heading).append('\n');
    items.forEach(item -> text.append(item.rendered()));
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
