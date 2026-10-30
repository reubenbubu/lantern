package io.lantern.extract.docs;

import io.lantern.extract.docs.Markdown.Block;
import io.lantern.extract.docs.Markdown.Field;
import io.lantern.extract.docs.Markdown.Kind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An AsciiDoc file split into the same blocks as {@link Markdown}, so that READMEs, documents and
 * decision records written in AsciiDoc are read like Markdown ones. Attribute entries ({@code
 * :status: accepted}) stand in for the front matter. Like the Markdown reader it is not a full
 * parser: inline markup is kept as written, nested lists are flattened, includes are not followed
 * and a table's cells are grouped into rows by the width of its first row.
 */
final class AsciiDoc {

  private static final Pattern ATTRIBUTE =
      Pattern.compile("^:!?([A-Za-z0-9_][A-Za-z0-9_-]*)!?:[ \\t]*(.*?)[ \\t]*$");
  private static final Pattern HEADING = Pattern.compile("^(={1,6}|#{1,6})[ \\t]+(.+?)[ \\t]*$");
  private static final Pattern BLOCK_ATTRIBUTES = Pattern.compile("^\\[(.*)]$");
  private static final Pattern BLOCK_TITLE = Pattern.compile("^\\.[^.\\s].*$");
  private static final Pattern DELIMITER =
      Pattern.compile("^(-{4,}|\\.{4,}|\\+{4,}|_{4,}|={4,}|\\*{4,}|--)[ \\t]*$");
  private static final Pattern FENCE = Pattern.compile("^(`{3,})(.*)$");
  private static final Pattern MACRO = Pattern.compile("^[a-z]+::\\S*\\[.*]$");
  private static final Pattern ITEM =
      Pattern.compile("^[ \\t]*(?:\\*{1,5}|-|\\.{1,5}|\\d{1,9}\\.)[ \\t]+(.*)$");
  private static final Pattern DESCRIPTION =
      Pattern.compile("^(\\S.*?)(?::{2,4}|;;)(?:[ \\t]+(.*))?$");
  private static final Pattern TABLE = Pattern.compile("^[|!]===+[ \\t]*$");

  private AsciiDoc() {}

  static Markdown parse(String content) {
    List<String> lines = new ArrayList<>(List.of(content.split("\r\n|\r|\n", -1)));
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    return new Parser(List.copyOf(lines)).parse();
  }

  private static final class Parser {

    private final List<String> lines;
    private final boolean[] skipped;
    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, Field> attributes = new LinkedHashMap<>();

    /** The attribute list of the next block, such as {@code source,mermaid}, or empty. */
    private String pending = "";

    private Kind open;
    private int openStart;
    private final List<String> openText = new ArrayList<>();

    Parser(List<String> lines) {
      this.lines = lines;
      this.skipped = new boolean[lines.size()];
    }

    Markdown parse() {
      int i = 0;
      while (i < lines.size()) {
        String line = lines.get(i);
        String stripped = line.strip();
        Matcher m;
        if (stripped.startsWith("////") && stripped.chars().allMatch(c -> c == '/')) {
          flush(i);
          i = comment(i, stripped);
          continue;
        }
        if (stripped.startsWith("//")) {
          skipped[i] = true; // a comment line, inside a paragraph or not
          i++;
          continue;
        }
        if (stripped.isEmpty()) {
          flush(i);
          i++;
          continue;
        }
        if ((m = ATTRIBUTE.matcher(line)).matches()) {
          flush(i);
          String key = m.group(1).toLowerCase(Locale.ROOT);
          if (!m.group(2).isEmpty() && !attributes.containsKey(key)) {
            attributes.put(key, new Field(m.group(2), i + 1));
          }
          skipped[i] = true;
          i++;
          continue;
        }
        if (open == null && (m = HEADING.matcher(line)).matches()) {
          String text = m.group(2).replaceAll("[ \\t]+[=#]+$", "");
          blocks.add(new Block(Kind.HEADING, m.group(1).length(), text.strip(), i + 1, i + 1));
          pending = "";
          i++;
          continue;
        }
        if (open == null && (m = BLOCK_ATTRIBUTES.matcher(stripped)).matches()) {
          pending = m.group(1);
          i++;
          continue;
        }
        if (open == null && BLOCK_TITLE.matcher(stripped).matches()) {
          i++;
          continue;
        }
        if ((m = FENCE.matcher(stripped)).matches()) {
          flush(i);
          i = delimited(i, m.group(1), m.group(2).isBlank() ? language() : m.group(2));
          continue;
        }
        if ((m = DELIMITER.matcher(stripped)).matches()) {
          flush(i);
          String d = m.group(1);
          if (d.startsWith("-") && d.length() >= 4 || d.startsWith(".")) {
            i = delimited(i, d, language());
          } else if (d.startsWith("+")) {
            i = passthrough(i, d);
          } else {
            i++; // an example, sidebar, quote or open block: its content is read as usual
          }
          pending = "";
          continue;
        }
        if (TABLE.matcher(stripped).matches()) {
          flush(i);
          i = table(i, stripped.substring(0, 4));
          pending = "";
          continue;
        }
        if (stripped.equals("'''") || stripped.equals("<<<") || MACRO.matcher(stripped).matches()) {
          flush(i);
          i++;
          continue;
        }
        if (stripped.equals("+")) {
          i++; // a list continuation
          continue;
        }
        if ((m = ITEM.matcher(line)).matches()) {
          flush(i);
          start(Kind.LIST_ITEM, i, m.group(1));
          i++;
          continue;
        }
        if ((open == null || open == Kind.LIST_ITEM)
            && (m = DESCRIPTION.matcher(stripped)).matches()) {
          flush(i);
          String definition = m.group(2) == null ? "" : m.group(2).strip();
          start(
              Kind.LIST_ITEM,
              i,
              m.group(1).strip() + (definition.isEmpty() ? "" : ": " + definition));
          i++;
          continue;
        }
        if (open == null) {
          start(Kind.PARAGRAPH, i, stripped);
          pending = "";
        } else {
          openText.add(stripped);
        }
        i++;
      }
      flush(lines.size());
      return new Markdown(
          lines, Collections.unmodifiableMap(attributes), List.copyOf(blocks), skipped);
    }

    /**
     * The language the pending attribute list names: {@code mermaid} in {@code [source,mermaid]}.
     */
    private String language() {
      String[] parts = pending.split(",");
      String first = parts[0].strip();
      String language =
          first.equals("source") || first.equals("listing") || first.isEmpty()
              ? (parts.length > 1 ? parts[1].strip() : "")
              : first;
      pending = "";
      return language;
    }

    /** Reads a listing or literal block starting on line i; returns the index after it. */
    private int delimited(int i, String delimiter, String language) {
      int j = i + 1;
      while (j < lines.size() && !lines.get(j).strip().equals(delimiter)) {
        j++;
      }
      int end = Math.min(j, lines.size() - 1);
      blocks.add(Markdown.codeBlock(lines.subList(i + 1, j), language, i + 1, end + 1));
      return end + 1;
    }

    /** Skips a passthrough block, raw HTML; returns the index after it. */
    private int passthrough(int i, String delimiter) {
      int j = i + 1;
      while (j < lines.size() && !lines.get(j).strip().equals(delimiter)) {
        j++;
      }
      int end = Math.min(j, lines.size() - 1);
      blocks.add(new Block(Kind.HTML, 0, "", i + 1, end + 1));
      return end + 1;
    }

    /** Skips a comment block; returns the index after it. */
    private int comment(int i, String delimiter) {
      int j = i + 1;
      while (j < lines.size() && !lines.get(j).strip().equals(delimiter)) {
        j++;
      }
      int end = Math.min(j, lines.size() - 1);
      for (int k = i; k <= end; k++) {
        skipped[k] = true;
      }
      return end + 1;
    }

    /**
     * Reads a table starting on line i into rows as wide as its first line, each {@code Header:
     * cell; ...} when the table has a header row (its first line followed by a blank line, or
     * {@code %header} or {@code options="header"}); returns the index after the closing delimiter.
     */
    private int table(int i, String delimiter) {
      boolean header = pending.contains("header");
      String separator = delimiter.substring(0, 1);
      List<String> cells = new ArrayList<>();
      List<Integer> cellLines = new ArrayList<>();
      int columns = 0;
      int firstLine = -1;
      int j = i + 1;
      for (; j < lines.size() && !lines.get(j).strip().equals(delimiter); j++) {
        String text = lines.get(j).strip();
        if (text.isEmpty()) {
          if (j - 1 == firstLine && cells.size() == columns) {
            header = true;
          }
          continue;
        }
        List<String> parts = split(text, separator);
        if (parts.isEmpty()) {
          if (!cells.isEmpty()) {
            int last = cells.size() - 1;
            cells.set(last, (cells.get(last) + " " + text).strip()); // a cell's later line
          }
          continue;
        }
        if (firstLine < 0) {
          firstLine = j;
          columns = parts.size();
        }
        for (String part : parts) {
          cells.add(part);
          cellLines.add(j + 1);
        }
      }
      if (columns > 0) {
        List<String> head = header ? cells.subList(0, Math.min(columns, cells.size())) : null;
        for (int c = header ? columns : 0; c < cells.size(); c += columns) {
          int last = Math.min(c + columns, cells.size());
          blocks.add(
              new Block(
                  Kind.TABLE_ROW,
                  0,
                  row(head, cells.subList(c, last)),
                  cellLines.get(c),
                  cellLines.get(last - 1)));
        }
      }
      return Math.min(j + 1, lines.size());
    }

    private static String row(List<String> head, List<String> cells) {
      List<String> parts = new ArrayList<>();
      for (int c = 0; c < cells.size(); c++) {
        if (cells.get(c).isEmpty()) {
          continue;
        }
        String h = head == null || c >= head.size() ? "" : head.get(c);
        parts.add(h.isEmpty() ? cells.get(c) : h + ": " + cells.get(c));
      }
      return String.join(head == null ? " | " : "; ", parts);
    }

    /** A table line's cells, each after its separator; none when it does not start a cell. */
    private static List<String> split(String row, String separator) {
      List<String> cells = new ArrayList<>();
      String[] parts = row.split("(?<!\\\\)" + Pattern.quote(separator), -1);
      if (parts.length < 2) {
        return cells;
      }
      for (int p = 1; p < parts.length; p++) {
        cells.add(parts[p].strip());
      }
      return cells;
    }

    private void start(Kind kind, int i, String text) {
      open = kind;
      openStart = i + 1;
      openText.clear();
      if (!text.isBlank()) {
        openText.add(text.strip());
      }
    }

    private void flush(int i) {
      if (open != null) {
        int end = i;
        while (end > openStart && lines.get(end - 1).isBlank()) {
          end--;
        }
        blocks.add(new Block(open, 0, String.join(" ", openText), openStart, end));
        open = null;
        openText.clear();
      }
    }
  }
}
