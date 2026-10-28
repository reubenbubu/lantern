package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
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
  static final String VERSION = "4";

  static final String SYSTEM =
      """
      You write short summaries of parts of a Java code base for architecture documentation. \
      Readers include managers and other people who do not read code.
      You are given facts that static analysis extracted from the code, each with a reference \
      such as [F1], source files, each with a reference such as [E1], and summaries of the \
      parts the unit contains, each with a reference such as [S1].
      Begin with one sentence that says plainly what the unit is for: what it does for the \
      system or its users, in words a non-engineer understands. Then say how it does that, and \
      name only the dependencies that matter for understanding it.
      Work out what the unit is for from its names, annotations (such as @RestController, \
      @Entity or @KafkaListener), Javadoc and code, or from the summaries of its parts, and \
      state it directly. If the input does not make its purpose clear, begin instead with what \
      the unit is and what it contains. Never write that the input does not show something or \
      that a purpose cannot be determined; leave out what you cannot tell.
      Call things by their plain names, such as the class Order, the package \
      com.example.orders or the module shop-core, never by the identifiers the facts use, \
      which start with a kind and a colon.
      Do not restate the summaries of the parts sentence by sentence; say what the unit as a \
      whole is for, drawing on them.
      Say only what the input shows or what follows from it. End every sentence with the \
      references it is based on, in square brackets, for example [F1] or [F2, E1, S1]; a \
      sentence about purpose cites the facts, source files or summaries it is inferred from. \
      Cite only references from the input, each one on its own, such as [F1, F2, F3], never a \
      range such as [F1-F3].
      Write plain prose without headings, lists or a title.""";

  static final String RETRY =
      """
      Your summary was rejected: %s
      Write it again. End every sentence with the references it is based on, in square brackets, \
      and cite only references from the input, each one on its own, never a range.""";

  /** What a repository's prompt adds about its reader. */
  private static final String BUSINESS_READER =
      "Write for a reader who wants to know what this repository does for the system or the"
          + " business: lead with its role and main capabilities, and mention frameworks and"
          + " libraries briefly, if at all.\n";

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
      items.add(
          new Item(
              "S" + (i + 1),
              describe(child.level(), child.entity()) + ": " + withoutMarkers(child.text())));
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
      text.append("Summarize the ").append(describe(node.level(), node.entity())).append(' ');
      if (hasOnePart(node, children)) {
        // A unit with one part would only restate it at full length.
        text.append(length(SummaryLevel.ONE_PART_SENTENCES, SummaryLevel.ONE_PART_WORDS))
            .append(", covering what the ")
            .append(node.level().noun())
            .append(" as a whole is for and what it adds to its only part, such as its")
            .append(" dependencies. Do not restate the summary of that part [S1].\n");
      } else {
        text.append(length(node.level()))
            .append(", covering ")
            .append(node.level().content())
            .append(".\n");
      }
      if (node.level() == SummaryLevel.REPO) {
        text.append(BUSINESS_READER);
      }
    } else {
      text.append("What is known about the ")
          .append(describe(node.level(), node.entity()))
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
   * How long a summary of the level should be, such as "in 2 to 4 sentences and at most 80 words".
   */
  static String length(SummaryLevel level) {
    return "in "
        + level.minSentences()
        + " to "
        + level.maxSentences()
        + " sentences and at most "
        + level.maxWords()
        + " words";
  }

  private static String length(String sentences, int words) {
    return "in " + sentences + " sentences and at most " + words + " words";
  }

  /**
   * Whether a package, module or repository has a single part and nothing else to summarize but its
   * facts: its prompt then asks for a short summary that does not restate that part.
   */
  private static boolean hasOnePart(SummaryNode node, List<Item> children) {
    return children.size() == 1 && node.level() != SummaryLevel.TYPE;
  }

  /**
   * The user turn that merges summaries of parts of a unit's inputs into one.
   *
   * @param parts the parts' summaries, with the references they cite
   */
  static String merge(SummaryNode node, List<String> parts) {
    StringBuilder text = new StringBuilder();
    text.append("What is known about the ")
        .append(describe(node.level(), node.entity()))
        .append(" was too long for one request, so it was summarized in parts. Merge the ")
        .append("summaries of the parts below into one summary ")
        .append(length(node.level()))
        .append(", covering ")
        .append(node.level().content())
        .append(". Cite the references the parts cite for what you take from them.\n");
    if (node.level() == SummaryLevel.REPO) {
      text.append(BUSINESS_READER);
    }
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

  /**
   * A unit as the prompt names it: its kind and plain name, without the entity ID, which models
   * would otherwise copy into the text. A type is named with its package, such as {@code type
   * Order.Line in package com.example.shop}; a package by its Java name, a module by its artifact
   * ID and a repository by its name.
   */
  static String describe(SummaryLevel level, EntityId entity) {
    String id = entity.key();
    String name =
        switch (level) {
          case TYPE -> {
            int dot = id.lastIndexOf('.');
            String simple = id.substring(dot + 1).replace('$', '.');
            yield dot < 0 ? simple : simple + " in package " + id.substring(0, dot);
          }
          case PACKAGE, MODULE -> id.substring(id.lastIndexOf(':') + 1);
          case REPO -> id;
        };
    return level.noun() + " " + name;
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
