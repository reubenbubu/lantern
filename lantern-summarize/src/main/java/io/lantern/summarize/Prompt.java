package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The summarize prompt (knowledge-build epic, section 11.3): block 1 is the common system prompt,
 * block 2 the role's instructions and answer schema, read from {@code prompts/summarize.*}, and the
 * user turn holds one item, the unit, in {@code <items>}. Its facts, source code and the contained
 * units' summaries are listed with short references ({@code F1}, {@code E1}, {@code S1}) that the
 * model cites, and that {@link Citations} maps back.
 *
 * <p>A module's or repository's item also shows its fact sheet ({@link FactSheet}): facts about its
 * parts, listed after its own facts and cited as facts.
 *
 * <p>A unit whose inputs do not fit one prompt is summarized in parts, each prompt showing some of
 * the inputs under their usual references, and the parts' summaries are then merged into one, which
 * keeps the references they cite (ADR 0024).
 *
 * <p>Change {@link #VERSION} whenever the text of the prompt or the way inputs are written into it
 * changes (including which source files {@link Excerpts} shows, what is masked in them, and which
 * facts {@link FactSheet} lists and which README claims {@link SummaryTree} gives a repository), so
 * cached summaries made with the old prompt are not reused. {@code PromptTest} fails until it is
 * changed.
 */
final class Prompt {

  /** The prompt's version, part of every summary's cache key. */
  static final String VERSION = "16";

  /** Block 2's instructions, as the epic writes them. */
  static final String TASK = resource("summarize.task.txt");

  /** The answer schema, as the epic writes it. */
  static final String SCHEMA = resource("summarize.schema.json");

  /** What separates a role's instructions from its answer schema in block 2. */
  static final String SCHEMA_HEADING = "\n\nAnswer schema:\n";

  /** Block 2: the role's instructions and answer schema. */
  static final String ROLE = TASK + SCHEMA_HEADING + SCHEMA;

  /** The whole system prompt, blocks 1 and 2, as a request sends it. */
  static final String SYSTEM = CommonPrompt.SYSTEM + "\n\n" + ROLE;

  /** The item's ID in the request: every request holds one unit. */
  static final String ITEM = "I1";

  /** The most words of one sentence; the schema's 300 characters are its safety bound. */
  static final int SENTENCE_WORDS = 35;

  private static final Pattern MARKER = Pattern.compile("\\s*\\[(?:fact|summary):[^\\]\\s]+\\]");

  private Prompt() {}

  /**
   * One input as a prompt lists it.
   *
   * @param ref its reference, such as {@code F1}
   * @param text what the reference stands for: a fact's line, a summary, or a whole code block
   * @param heading the heading of the group of a fact sheet it is listed under, or {@code null}
   */
  record Item(String ref, String text, String heading) {

    Item(String ref, String text) {
      this(ref, text, null);
    }

    /** The item as the prompt writes it. */
    String rendered() {
      return ref.startsWith("E") ? text + "\n" : "[" + ref + "] " + text + "\n";
    }
  }

  /** A part of a unit's inputs, when they do not fit one prompt. */
  record Part(int number, int of) {}

  /** The request of a unit, or of one part of it, or of a merge. */
  static LlmRequest request(Role role, String purpose, String item, int maxOutputTokens) {
    return new PromptLayout(
            CommonPrompt.SYSTEM,
            ROLE,
            "",
            "",
            false,
            Blocks.items(List.of(ITEM), List.of(item.stripTrailing())))
        .request(role, purpose, maxOutputTokens, List.of());
  }

  /** The facts as prompt items, {@code F1} first. */
  static List<Item> facts(List<Fact> facts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < facts.size(); i++) {
      items.add(new Item("F" + (i + 1), fact(facts.get(i))));
    }
    return items;
  }

  /**
   * A fact sheet as prompt items, numbered after the unit's own facts.
   *
   * @param facts how many facts of its own the unit lists before it
   */
  static List<Item> sheet(List<FactSheet.Entry> sheet, int facts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < sheet.size(); i++) {
      FactSheet.Entry entry = sheet.get(i);
      items.add(new Item("F" + (facts + i + 1), fact(entry.fact()), entry.heading()));
    }
    return items;
  }

  /** The source files as prompt items, {@code E1} first, each a code block with line numbers. */
  static List<Item> excerpts(List<Excerpts.Excerpt> excerpts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < excerpts.size(); i++) {
      Excerpts.Excerpt excerpt = excerpts.get(i);
      String ref = "E" + (i + 1);
      items.add(new Item(ref, Blocks.code(ref, excerpt.repo(), excerpt.path(), 1, excerpt.code())));
    }
    return items;
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
   * The item text for a unit whose inputs fit one prompt.
   *
   * @param children the contained units' summaries, in the order they are referenced
   */
  static String user(SummaryNode node, List<Excerpts.Excerpt> excerpts, List<Summary> children) {
    return user(
        node,
        facts(node.facts()),
        sheet(FactSheet.of(node), node.facts().size()),
        excerpts(excerpts),
        children(children),
        null);
  }

  /**
   * The item text for a unit, or for one part of its inputs.
   *
   * @param sheet the items of the unit's fact sheet
   * @param part which part of the inputs this is, or {@code null} for all of them
   */
  static String user(
      SummaryNode node,
      List<Item> facts,
      List<Item> sheet,
      List<Item> excerpts,
      List<Item> children,
      Part part) {
    StringBuilder text = new StringBuilder();
    text.append("Unit: ").append(describe(node.level(), node.entity())).append('\n');
    if (part == null) {
      if (hasOnePart(node, children)) {
        // A unit with one part would only restate it at full length.
        text.append("Write ")
            .append(SummaryLevel.ONE_PART_SENTENCES)
            .append(" sentences of at most ")
            .append(SENTENCE_WORDS)
            .append(" words each. It contains only one unit [S1]: say what it adds to that unit,")
            .append(" such as its dependencies, and do not restate that unit's summary.\n");
      } else {
        text.append("Write ").append(length(node.level())).append(".\n");
      }
    } else {
      text.append("What is known about this unit is too long for one request, so it comes in ")
          .append(part.of())
          .append(" parts. This is part ")
          .append(part.number())
          .append(". Write ")
          .append(SummaryLevel.ONE_PART_SENTENCES)
          .append(" sentences of at most ")
          .append(SENTENCE_WORDS)
          .append(" words each on what this part shows about it; the summaries of the parts are")
          .append(" merged later.\n");
    }
    facts(text, "Its facts:", facts);
    sheet(text, sheet);
    if (!excerpts.isEmpty()) {
      text.append("Its source code:\n");
      excerpts.forEach(item -> text.append(item.rendered()));
    }
    if (!children.isEmpty()) {
      text.append("Summaries of the units it contains:\n<summaries>\n");
      children.forEach(item -> text.append(item.rendered()));
      text.append("</summaries>\n");
    }
    return text.toString();
  }

  /** How many sentences of how many words a summary of the level should have. */
  static String length(SummaryLevel level) {
    return level.minSentences()
        + " to "
        + level.maxSentences()
        + " sentences of at most "
        + SENTENCE_WORDS
        + " words each";
  }

  /**
   * Whether a package, module or repository has a single part and nothing else to summarize but its
   * facts: its prompt then asks for a short summary that does not restate that part.
   */
  private static boolean hasOnePart(SummaryNode node, List<Item> children) {
    return children.size() == 1 && node.level() != SummaryLevel.TYPE;
  }

  /**
   * The item text that merges summaries of parts of a unit's inputs into one.
   *
   * @param parts the parts' summaries, each sentence ending with the references it cites
   */
  static String merge(SummaryNode node, List<String> parts) {
    StringBuilder text = new StringBuilder();
    text.append("Unit: ").append(describe(node.level(), node.entity())).append('\n');
    text.append("What is known about this unit was too long for one request, so it was summarised")
        .append(" in parts. Merge the summaries of the parts below into one summary of ")
        .append(length(node.level()))
        .append(". Cite the references the parts cite for what you take from them.\n")
        .append("<summaries>\n");
    for (int i = 0; i < parts.size(); i++) {
      text.append("Part ")
          .append(i + 1)
          .append(": ")
          .append(FactLines.oneLine(parts.get(i).strip()))
          .append('\n');
    }
    return text.append("</summaries>\n").toString();
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

  private static void facts(StringBuilder text, String heading, List<Item> items) {
    if (items.isEmpty()) {
      return;
    }
    text.append(heading).append("\n<facts>\n");
    items.forEach(item -> text.append(item.rendered()));
    text.append("</facts>\n");
  }

  /** The fact sheet, one block per group under its heading. */
  private static void sheet(StringBuilder text, List<Item> sheet) {
    List<Item> group = new ArrayList<>();
    String heading = null;
    for (Item item : sheet) {
      if (!item.heading().equals(heading)) {
        if (!group.isEmpty()) {
          facts(text, "Of the units it contains, " + lowerFirst(heading) + ":", group);
        }
        group = new ArrayList<>();
        heading = item.heading();
      }
      group.add(item);
    }
    if (!group.isEmpty()) {
      facts(text, "Of the units it contains, " + lowerFirst(heading) + ":", group);
    }
  }

  private static String lowerFirst(String text) {
    return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
  }

  /** A fact on one line, as every role's prompt writes it ({@link FactLines}). */
  static String fact(Fact fact) {
    return FactLines.line(fact);
  }

  /** A child's text as the parent's prompt shows it: its own citations would only confuse. */
  static String withoutMarkers(String text) {
    return MARKER.matcher(text).replaceAll("").replace('\n', ' ').strip();
  }

  /** Reads a prompt resource as written. */
  private static String resource(String name) {
    String path = "/prompts/" + name;
    try (InputStream in = Prompt.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + path, e);
    }
  }
}
