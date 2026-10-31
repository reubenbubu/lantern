package io.lantern.extract.docs;

import io.lantern.extract.docs.Markdown.Block;
import io.lantern.extract.docs.Markdown.Field;
import io.lantern.extract.docs.Markdown.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an architecture decision record says, read from its Markdown: title, number, status, date
 * and its context, decision and consequences sections. Understands the common formats: Michael
 * Nygard's (with adr-tools' {@code Date:} line and {@code ## Status} section), MADR with YAML front
 * matter (versions 3 and 4) or with a {@code * Status:} list under the title (version 2), and
 * {@code Status:} lines under the title.
 */
final class DecisionRecord {

  /**
   * A value and where it was found.
   *
   * @param value the value, Markdown kept as written
   * @param start the first line, counting from 1, or 0 when it comes from the file name
   * @param end the last line, or 0 when it comes from the file name
   */
  record Value<T>(T value, int start, int end) {}

  /** A section of the record. */
  enum Section {
    CONTEXT,
    OUTCOME,
    CONSEQUENCES
  }

  /**
   * A section's text.
   *
   * @param section which section
   * @param text the Markdown under its heading, with HTML comments and blank-line runs removed
   * @param start the first line of the text
   * @param end the last line of the text
   */
  record SectionText(Section section, String text, int start, int end) {}

  private static final Pattern FIELD_LINE =
      Pattern.compile(
          "^[ \\t]*(?:[-*+][ \\t]+)?[*_]{0,2}[ \\t]*(status|date)[ \\t]*[*_]{0,2}[ \\t]*:[ \\t]*[*_]{0,2}[ \\t]*(.*?)[ \\t]*$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TITLE_NUMBER =
      Pattern.compile(
          "^(adr[-_ ]?)?#?(\\d{1,9})([ \\t]*[.:)][ \\t]*|[ \\t]*[-\\u2013\\u2014][ \\t]+|[ \\t]+)(.+)$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern FILE_NUMBER =
      Pattern.compile("^(?:adr[-_ ]?)?(\\d{1,9})(?![0-9])(?:[-_ .].*)?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FILE_DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:[-_ .].*)?$");
  private static final Pattern HEADING_NUMBER = Pattern.compile("^\\d+(?:\\.\\d+)*\\.?[ \\t]+");

  final Value<String> title;
  final Value<Long> number;
  final Value<String> status;
  final Value<String> date;
  final List<SectionText> sections;

  private DecisionRecord(
      Value<String> title,
      Value<Long> number,
      Value<String> status,
      Value<String> date,
      List<SectionText> sections) {
    this.title = title;
    this.number = number;
    this.status = status;
    this.date = date;
    this.sections = sections;
  }

  /** Whether a file is named like a numbered record, such as {@code 0003-shared-read.md}. */
  static boolean numbered(String fileName) {
    String stem = fileName.replaceFirst("\\.[^.]*$", "");
    return FILE_NUMBER.matcher(stem).matches() && !FILE_DATE.matcher(stem).matches();
  }

  /**
   * Reads a decision record.
   *
   * @param fileName the file's name, such as {@code 0001-use-kafka.md}
   * @param doc the parsed file
   * @return the record, or null if the file has neither a status nor a context, decision or
   *     consequences section, and so does not look like a decision record
   */
  static DecisionRecord read(String fileName, Markdown doc) {
    String stem = fileName.replaceFirst("\\.[^.]*$", "");
    List<Block> blocks = doc.blocks;

    Value<String> title = null;
    Value<Long> number = null;
    Field titleField = doc.frontMatter.get("title");
    Block heading = firstHeading(blocks);
    if (titleField != null) {
      title = new Value<>(clean(titleField.value()), titleField.line(), titleField.line());
    } else if (heading != null && !heading.text().isEmpty()) {
      title = new Value<>(heading.text(), heading.start(), heading.end());
    }
    Matcher fileNumber = FILE_NUMBER.matcher(stem);
    if (fileNumber.matches() && !FILE_DATE.matcher(stem).matches()) {
      number = new Value<>(Long.parseLong(fileNumber.group(1)), 0, 0);
    }
    if (title != null) {
      Matcher m = TITLE_NUMBER.matcher(title.value());
      // A number followed only by a space, as in "12 Factor config", is cut only after "ADR" or
      // when the file name gives the same number.
      if (m.matches()
          && (!m.group(3).isBlank()
              || m.group(1) != null
              || number != null && number.value() == Long.parseLong(m.group(2)))) {
        if (number == null) {
          number = new Value<>(Long.parseLong(m.group(2)), title.start(), title.end());
        }
        title = new Value<>(m.group(4).strip(), title.start(), title.end());
      }
    } else {
      title = new Value<>(fromFileName(stem), 0, 0);
    }

    Value<String> status = field(doc, "status");
    Value<String> date = field(doc, "date");
    if (date == null) {
      Matcher m = FILE_DATE.matcher(stem);
      if (m.matches()) {
        date = new Value<>(m.group(1), 0, 0);
      }
    }

    List<SectionText> sections = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++) {
      Block b = blocks.get(i);
      if (b.kind() != Kind.HEADING) {
        continue;
      }
      Section section = section(b.text());
      if (section != null) {
        SectionText text = text(doc, i, section);
        if (text != null) {
          sections.add(text);
        }
      }
    }
    if (status == null && sections.isEmpty()) {
      return null;
    }
    return new DecisionRecord(title, number, status, date, List.copyOf(sections));
  }

  /**
   * The first level-1 heading, or else the first heading if it is not one of a section, a status or
   * a date.
   */
  private static Block firstHeading(List<Block> blocks) {
    Block first = null;
    for (Block b : blocks) {
      if (b.kind() == Kind.HEADING) {
        if (b.level() == 1) {
          return b;
        }
        if (first == null) {
          first = b;
        }
      }
    }
    if (first == null
        || section(first.text()) != null
        || headingName(first.text()).equals("status")
        || headingName(first.text()).equals("date")) {
      return null;
    }
    return first;
  }

  /**
   * A status or date: from the front matter, else from a {@code Status: ...} line, else from the
   * first block under a {@code Status} heading.
   */
  private static Value<String> field(Markdown doc, String name) {
    Field f = doc.frontMatter.get(name);
    if (f != null) {
      return new Value<>(clean(f.value()), f.line(), f.line());
    }
    for (Block b : doc.blocks) {
      if (b.kind() != Kind.PARAGRAPH && b.kind() != Kind.LIST_ITEM) {
        continue;
      }
      for (int line = b.start(); line <= b.end(); line++) {
        Matcher m = FIELD_LINE.matcher(doc.lines.get(line - 1));
        if (m.matches() && m.group(1).equalsIgnoreCase(name) && !clean(m.group(2)).isEmpty()) {
          return new Value<>(clean(m.group(2)), line, line);
        }
      }
    }
    List<Block> blocks = doc.blocks;
    for (int i = 0; i + 1 < blocks.size(); i++) {
      Block b = blocks.get(i);
      if (b.kind() == Kind.HEADING && headingName(b.text()).equals(name)) {
        Block next = blocks.get(i + 1);
        if (next.kind() == Kind.PARAGRAPH || next.kind() == Kind.LIST_ITEM) {
          String value = clean(next.text());
          if (!value.isEmpty()) {
            return new Value<>(value, next.start(), next.end());
          }
        }
      }
    }
    return null;
  }

  /** Which section a heading starts, if any. */
  private static Section section(String heading) {
    String name = headingName(heading);
    if (name.equals("context")
        || name.startsWith("context and ")
        || name.equals("problem")
        || name.equals("problem statement")
        || name.equals("background")) {
      return Section.CONTEXT;
    }
    if (name.equals("decision")
        || name.equals("decisions")
        || name.equals("the decision")
        || name.equals("decision made")
        || name.equals("decision outcome")) {
      return Section.OUTCOME;
    }
    if (name.contains("consequences")) {
      return Section.CONSEQUENCES;
    }
    return null;
  }

  /** A heading's text in lowercase, without numbering, emphasis or a trailing colon. */
  private static String headingName(String heading) {
    String name = clean(heading).toLowerCase(Locale.ROOT);
    name = HEADING_NUMBER.matcher(name).replaceFirst("");
    if (name.endsWith(":")) {
      name = name.substring(0, name.length() - 1).strip();
    }
    return name;
  }

  /**
   * The text under the heading at block index i: up to the next heading of the same or a higher
   * level, or the next heading that starts a section of its own.
   */
  private static SectionText text(Markdown doc, int i, Section section) {
    Block heading = doc.blocks.get(i);
    int first = 0;
    int last = 0;
    for (int j = i + 1; j < doc.blocks.size(); j++) {
      Block b = doc.blocks.get(j);
      if (b.kind() == Kind.HEADING && (b.level() <= heading.level() || section(b.text()) != null)) {
        break;
      }
      if (first == 0) {
        first = b.start();
      }
      last = b.end();
    }
    if (first == 0) {
      return null;
    }
    List<String> kept = new ArrayList<>();
    boolean blank = false;
    for (int line = first; line <= last; line++) {
      if (doc.skipped(line)) {
        continue;
      }
      String text = doc.lines.get(line - 1).stripTrailing();
      if (text.isEmpty()) {
        blank = true;
        continue;
      }
      if (blank && !kept.isEmpty()) {
        kept.add("");
      }
      blank = false;
      kept.add(text);
    }
    String text = String.join("\n", kept).stripIndent().strip();
    return text.isEmpty() ? null : new SectionText(section, text, first, last);
  }

  /** The value without surrounding emphasis markers and whitespace. */
  private static String clean(String value) {
    String v = value.strip();
    for (String marker : List.of("**", "__", "*", "_", "`")) {
      if (v.length() > 2 * marker.length() && v.startsWith(marker) && v.endsWith(marker)) {
        v = v.substring(marker.length(), v.length() - marker.length()).strip();
      }
    }
    return v;
  }

  /**
   * A title made from a file name such as {@code 0001-use-kafka} or {@code 2024-05-06-use-kafka}:
   * "Use kafka".
   */
  private static String fromFileName(String stem) {
    String words =
        stem.replaceFirst("(?i)^(?:\\d{4}-\\d{2}-\\d{2}|(?:adr[-_ ]?)?\\d+)[-_ .]*", "")
            .replaceAll("[-_]+", " ")
            .strip();
    if (words.isEmpty()) {
      words = stem;
    }
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
