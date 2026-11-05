package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.harness.AnswerFormat;
import io.lantern.harness.PromptLayout;
import io.lantern.harness.Prompts;
import io.lantern.harness.Toml;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.harness.render.Blocks;
import io.lantern.harness.render.FactLines;
import io.lantern.harness.render.LeanFacts;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The summarize prompt (knowledge-build epic, section 11.3): block 1 is the common system prompt,
 * block 2 the role's instructions and answer format, read from {@code prompts/summarize.*}, and the
 * user turn holds one item, the unit, in {@code <items>}, written as a TOML document; the model
 * answers in TOML too, which costs fewer output tokens than JSON (#872). Its facts, source code and
 * the contained units' summaries are listed with short references ({@code F1}, {@code E1}, {@code
 * S1}) that the model cites, and that {@link Citations} maps back.
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
  static final String VERSION = "20";

  /**
   * The version answers are keyed by: {@link #VERSION}, with a digest of the prompt files when one
   * is edited in the prompts folder (ADR 0113).
   */
  static final String KEY =
      Prompts.version(
          VERSION,
          "common.system.txt",
          "summarize.task.txt",
          "summarize.schema.json",
          "summarize.closing.txt");

  /** Block 2's instructions, as the epic writes them. */
  static final String TASK = resource("summarize.task.txt");

  /**
   * The answer schema, as the epic writes it: what the checks hold an answer to once its TOML is
   * read. The model is shown the format and an example instead (#872).
   */
  static final String SCHEMA = resource("summarize.schema.json");

  /** The format the model answers in (#872). */
  static final AnswerFormat FORMAT = AnswerFormat.TOML;

  /**
   * What follows the instructions: the answer's format with an example, a checklist and what not to
   * say, placed last as the prompt experiment of #685 tested them
   * (docs/research/summary-prompt-rules.md).
   */
  static final String CLOSING = resource("summarize.closing.txt");

  /** Block 2: the role's instructions, then the answer's format and the closing. */
  static final String ROLE = TASK + "\n\n" + CLOSING;

  /** Block 1, in the answer's format. */
  static final String COMMON = CommonPrompt.system(FORMAT);

  /** The whole system prompt, blocks 1 and 2, as a request sends it. */
  static final String SYSTEM = COMMON + "\n\n" + ROLE;

  /** The item's ID in the request: every request holds one unit. */
  static final String ITEM = "I1";

  /** The words of one sentence the checks accept; the schema's 300 characters bound it too. */
  static final io.lantern.harness.Contract.Range SENTENCE_WORDS =
      io.lantern.harness.Contract.of("summarize").acceptedWords("sentence");

  /**
   * The words of one sentence the prompt asks for: a narrower range than the checks accept, unsaid,
   * so a sentence a little outside what was asked is not sent back (#685).
   */
  static final io.lantern.harness.Contract.Range ASKED_WORDS =
      io.lantern.harness.Contract.of("summarize").requestedWords("sentence");

  private static final Pattern MARKER = Pattern.compile("\\s*\\[(?:fact|summary):[^\\]\\s]+\\]");

  private Prompt() {}

  /**
   * One input as a prompt lists it.
   *
   * @param ref its reference, such as {@code F1}
   * @param text what the reference stands for: a fact's line, a summary, or a whole code block
   * @param heading the heading of the group of a fact sheet it is listed under, or {@code null}
   * @param fact the fact it shows, which the prompt writes in the lean form ({@link LeanFacts}), or
   *     {@code null} for a summary, a code block or a placeholder
   */
  record Item(String ref, String text, String heading, Fact fact) {

    Item(String ref, String text) {
      this(ref, text, null, null);
    }

    Item(String ref, String text, String heading) {
      this(ref, text, heading, null);
    }

    /** The item as the prompt writes it. */
    String rendered() {
      return ref.startsWith("E") ? text : "[" + ref + "] " + text + "\n";
    }
  }

  /** A part of a unit's inputs, when they do not fit one prompt. */
  record Part(int number, int of) {}

  /** The request of a unit, or of one part of it, or of a merge. */
  static LlmRequest request(Role role, String purpose, String item, int maxOutputTokens) {
    return new PromptLayout(COMMON, ROLE, "", "", false, items(item))
        .request(role, purpose, maxOutputTokens, List.of());
  }

  /** The user turn's items block: the items' TOML document in {@code <items>}, which is data. */
  static String items(String toml) {
    return "<items>\n" + toml.stripTrailing() + "\n</items>";
  }

  /** The item's table, {@code item.I1}. */
  private static String table() {
    return "item." + ITEM;
  }

  /** The facts as prompt items, {@code F1} first. */
  static List<Item> facts(List<Fact> facts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < facts.size(); i++) {
      items.add(new Item("F" + (i + 1), fact(facts.get(i)), null, facts.get(i)));
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
      items.add(new Item("F" + (facts + i + 1), fact(entry.fact()), entry.heading(), entry.fact()));
    }
    return items;
  }

  /** The source files as prompt items, {@code E1} first, each a code block with line numbers. */
  static List<Item> excerpts(List<Excerpts.Excerpt> excerpts) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < excerpts.size(); i++) {
      Excerpts.Excerpt excerpt = excerpts.get(i);
      String ref = "E" + (i + 1);
      items.add(new Item(ref, code(ref, excerpt.repo(), excerpt.path(), excerpt.code(), 1)));
    }
    return items;
  }

  /**
   * A source file as one code table, or, when that is more than the most tokens a part may show, as
   * tables of consecutive line ranges under the same reference, each within it, so a very large
   * class is summarized in parts that each stay under the request ceiling.
   */
  static List<Item> slices(String ref, Excerpts.Excerpt excerpt, long most) {
    String whole = code(ref, excerpt.repo(), excerpt.path(), excerpt.code(), 1);
    if (io.lantern.llm.Tokens.estimate(whole) <= most) {
      return List.of(new Item(ref, whole));
    }
    List<Item> slices = new ArrayList<>();
    List<String> lines = excerpt.code().lines().toList();
    long overhead =
        io.lantern.llm.Tokens.estimate(code(ref, excerpt.repo(), excerpt.path(), "", 1));
    int first = 0;
    while (first < lines.size()) {
      int last = first;
      long used = overhead;
      while (last < lines.size()) {
        long line = io.lantern.llm.Tokens.estimate(lines.get(last)) + 2;
        if (last > first && used + line > most) {
          break;
        }
        used += line;
        last++;
      }
      String slice = String.join("\n", lines.subList(first, last));
      slices.add(new Item(ref, code(ref, excerpt.repo(), excerpt.path(), slice, first + 1)));
      first = last;
    }
    return slices;
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
    StringBuilder ask = new StringBuilder();
    if (part == null) {
      if (hasOnePart(node, children)) {
        // A unit with one part would only restate it at full length.
        ask.append("Write ")
            .append(SummaryLevel.ONE_PART_SENTENCES)
            .append(" sentences of ")
            .append(ASKED_WORDS)
            .append(" words each. It contains only one unit [S1]: say what it adds to that unit,")
            .append(" such as its dependencies, and do not restate that unit's summary.");
      } else {
        ask.append("Write ").append(length(node.level())).append('.');
      }
    } else {
      ask.append("What is known about this unit is too long for one request, so it comes in ")
          .append(part.of())
          .append(" parts. This is part ")
          .append(part.number())
          .append(". Write ")
          .append(SummaryLevel.ONE_PART_SENTENCES)
          .append(" sentences of ")
          .append(ASKED_WORDS)
          .append(" words each on what this part shows about it; the summaries of the parts are")
          .append(" merged later.");
    }
    StringBuilder text = new StringBuilder();
    text.append('[').append(table()).append("]\n");
    text.append("unit = ").append(Toml.string(describe(node.level(), node.entity()))).append('\n');
    text.append("ask = ").append(Toml.string(ask.toString())).append('\n');
    // One list of short names serves the unit's facts and its fact sheet (#873).
    List<Fact> shown = new ArrayList<>();
    facts.stream().map(Item::fact).filter(f -> f != null).forEach(shown::add);
    sheet.stream().map(Item::fact).filter(f -> f != null).forEach(shown::add);
    LeanFacts.Names names = new LeanFacts.Names(shown);
    lines(text, "facts", facts, names);
    lines(text, "summaries", children, names);
    StringBuilder tables = new StringBuilder();
    sheet(tables, sheet, names);
    String legend = names.legend();
    if (!legend.isEmpty()) {
      text.append("names = ").append(Toml.string(legend)).append('\n');
    }
    text.append(tables);
    excerpts.forEach(item -> text.append(item.rendered()));
    return text.toString();
  }

  /** How many sentences of how many words a summary of the level should have. */
  static String length(SummaryLevel level) {
    if (level == SummaryLevel.TYPE) {
      // The model writes towards the low end of a range, the more so when types share a request
      // (#874): the range asked for starts one higher, and its low end is left for little to say.
      return (level.minSentences() + 1)
          + " to "
          + level.maxSentences()
          + " sentences of "
          + ASKED_WORDS
          + " words each ("
          + level.minSentences()
          + " when its inputs give too little for more)";
    }
    return level.minSentences()
        + " to "
        + level.maxSentences()
        + " sentences of "
        + ASKED_WORDS
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
    StringBuilder list = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      list.append("Part ")
          .append(i + 1)
          .append(": ")
          .append(FactLines.oneLine(parts.get(i).strip()))
          .append('\n');
    }
    StringBuilder text = new StringBuilder();
    text.append('[').append(table()).append("]\n");
    text.append("unit = ").append(Toml.string(describe(node.level(), node.entity()))).append('\n');
    text.append("ask = ")
        .append(
            Toml.string(
                "What is known about this unit was too long for one request, so it was summarised"
                    + " in parts. Merge the summaries of the parts into one summary of "
                    + length(node.level())
                    + ". Cite the references the parts cite for what you take from them."))
        .append('\n');
    text.append("parts = ").append(Toml.string(list.toString())).append('\n');
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

  /**
   * The items as one multi-line string under the key, one per line, facts in the lean form ({@link
   * LeanFacts}); nothing when there are none.
   */
  private static void lines(
      StringBuilder text, String key, List<Item> items, LeanFacts.Names names) {
    if (items.isEmpty()) {
      return;
    }
    StringBuilder lines = new StringBuilder();
    List<LeanFacts.Labeled> facts =
        items.stream()
            .filter(item -> item.fact() != null)
            .map(item -> new LeanFacts.Labeled(item.ref(), item.fact()))
            .toList();
    if (!facts.isEmpty()) {
      lines.append(LeanFacts.render(facts, names));
    }
    items.stream().filter(item -> item.fact() == null).forEach(i -> lines.append(i.rendered()));
    text.append(key).append(" = ").append(Toml.string(lines.toString())).append('\n');
  }

  /** The fact sheet, one table per group, under its heading. */
  private static void sheet(StringBuilder text, List<Item> sheet, LeanFacts.Names names) {
    List<Item> group = new ArrayList<>();
    String heading = null;
    for (Item item : sheet) {
      if (!item.heading().equals(heading)) {
        group(text, heading, group, names);
        group = new ArrayList<>();
        heading = item.heading();
      }
      group.add(item);
    }
    group(text, heading, group, names);
  }

  private static void group(
      StringBuilder text, String heading, List<Item> group, LeanFacts.Names names) {
    if (group.isEmpty()) {
      return;
    }
    text.append("[[").append(table()).append(".contained]]\n");
    text.append("about = ")
        .append(Toml.string("Of the units it contains, " + lowerFirst(heading)))
        .append('\n');
    lines(text, "facts", group, names);
  }

  /** A source file as a code table: its reference, repository, path, lines and numbered text. */
  private static String code(String ref, String repo, String path, String code, int first) {
    return "[["
        + table()
        + ".code]]\n"
        + "ref = "
        + Toml.string(ref)
        + "\nrepo = "
        + Toml.string(FactLines.oneLine(repo))
        + "\npath = "
        + Toml.string(FactLines.oneLine(path))
        + "\nlines = "
        + Toml.string(first + "-" + Blocks.last(code, first))
        + "\ntext = "
        + Toml.string(Blocks.numbered(code, first))
        + "\n";
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
    return Prompts.text(name);
  }
}
