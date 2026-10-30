package io.lantern.extract.docs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Markdown file split into blocks, enough to find headings, paragraphs, list items and table rows
 * with their line numbers. It is not a full CommonMark parser: inline Markdown is kept as written,
 * nested lists are flattened into items, and a list item's later paragraphs are left out.
 */
final class Markdown {

  /** The kinds of block. */
  enum Kind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE_ROW,
    QUOTE,
    CODE,
    DIAGRAM,
    HTML
  }

  /**
   * One block.
   *
   * @param kind the kind
   * @param level the heading level (1 to 6), or 0 for other blocks
   * @param text the text, its lines stripped and joined with single spaces; a list item's marker, a
   *     quote's {@code >} and a heading's {@code #}s are left out; empty for code; a diagram's
   *     lines as written, joined with line feeds
   * @param language a diagram's language, such as {@code mermaid}, or {@value #DRAWN} for one drawn
   *     with box characters; null for other blocks
   * @param start the first line, counting from 1
   * @param end the last line
   */
  record Block(Kind kind, int level, String text, int start, int end, String language) {

    Block(Kind kind, int level, String text, int start, int end) {
      this(kind, level, text, start, end, null);
    }
  }

  /** The language of a diagram drawn with box characters rather than written in a language. */
  static final String DRAWN = "text";

  /** Code block languages that are diagrams, by the name a fence's info string gives them. */
  private static final Map<String, String> DIAGRAM_LANGUAGES =
      Map.of(
          "mermaid", "mermaid",
          "plantuml", "plantuml",
          "puml", "plantuml",
          "c4plantuml", "plantuml",
          "ditaa", "ditaa",
          "graphviz", "graphviz",
          "dot", "graphviz");

  /** Languages of a code block that may hold a drawing: none, or plain text. */
  private static final Set<String> PLAIN_LANGUAGES =
      Set.of("", "text", "txt", "plain", "plaintext", "ascii", "none");

  /** Box-drawing characters, or an ASCII box's corner and edge. */
  private static final Pattern DRAWING = Pattern.compile("[\\u2500-\\u257F]|\\+-{3,}\\+");

  /**
   * A value of the YAML front matter.
   *
   * @param value the value, without surrounding quotes
   * @param line its line, counting from 1
   */
  record Field(String value, int line) {}

  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}(?=[^`]*$)|~{3,}).*$");
  private static final Pattern ATX = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*$");
  private static final Pattern ATX_CLOSE = Pattern.compile("(?:^|[ \\t]+)#+[ \\t]*$");
  private static final Pattern SETEXT = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$");
  private static final Pattern BREAK = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");
  private static final Pattern ITEM =
      Pattern.compile("^[ \\t]*(?:[-*+]|\\d{1,9}[.)])(?:[ \\t]+(.*))?$");
  private static final Pattern QUOTE = Pattern.compile("^ {0,3}>[ \\t]?(.*)$");
  private static final Pattern HTML = Pattern.compile("^ {0,3}</?[A-Za-z][^>]*>?.*$");
  private static final Pattern TABLE_SEPARATOR =
      Pattern.compile("^[ \\t]*\\|?[ \\t]*:?-+:?[ \\t]*(?:\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$");
  private static final Pattern FRONT_MATTER_FIELD =
      Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*)[ \\t]*:[ \\t]*(.*?)[ \\t]*$");

  /** The file's lines, without line terminators. */
  final List<String> lines;

  /** The front matter's top-level {@code key: value} fields, keys in lowercase. */
  final Map<String, Field> frontMatter;

  /** The blocks in file order. HTML comments and the front matter are not blocks. */
  final List<Block> blocks;

  /** Per line (index = line - 1), whether it is part of an HTML comment or the front matter. */
  private final boolean[] skipped;

  Markdown(
      List<String> lines, Map<String, Field> frontMatter, List<Block> blocks, boolean[] skipped) {
    this.lines = lines;
    this.frontMatter = frontMatter;
    this.blocks = blocks;
    this.skipped = skipped;
  }

  /** Whether the line, counting from 1, is part of an HTML comment or the front matter. */
  boolean skipped(int line) {
    return skipped[line - 1];
  }

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
    private final Map<String, Field> frontMatter = new LinkedHashMap<>();

    /** The header cells of the table being read, or null. */
    private List<String> tableHeader;

    /** The block being collected, or null. */
    private Kind open;

    private int openStart;
    private final List<String> openText = new ArrayList<>();

    Parser(List<String> lines) {
      this.lines = lines;
      this.skipped = new boolean[lines.size()];
    }

    Markdown parse() {
      int i = frontMatter();
      while (i < lines.size()) {
        String line = lines.get(i);
        String stripped = line.strip();
        if (!stripped.startsWith("|")) {
          tableHeader = null;
        }
        if (stripped.startsWith("<!--")) {
          flush(i);
          i = comment(i);
          continue;
        }
        Matcher m;
        if ((m = FENCE.matcher(line)).matches()) {
          flush(i);
          i = fence(i, m.group(1), line.strip().substring(m.group(1).length()));
          continue;
        }
        if (stripped.isEmpty()) {
          flush(i);
          i++;
          continue;
        }
        if ((m = ATX.matcher(line)).matches()) {
          flush(i);
          String text = m.group(2) == null ? "" : ATX_CLOSE.matcher(m.group(2)).replaceAll("");
          blocks.add(new Block(Kind.HEADING, m.group(1).length(), text.strip(), i + 1, i + 1));
          i++;
          continue;
        }
        if (open == Kind.PARAGRAPH && (m = SETEXT.matcher(line)).matches()) {
          int level = m.group(1).charAt(0) == '=' ? 1 : 2;
          blocks.add(new Block(Kind.HEADING, level, String.join(" ", openText), openStart, i + 1));
          open = null;
          openText.clear();
          i++;
          continue;
        }
        if (BREAK.matcher(line).matches()) {
          flush(i);
          i++;
          continue;
        }
        if (stripped.startsWith("|")) {
          flush(i);
          boolean header =
              i + 1 < lines.size() && TABLE_SEPARATOR.matcher(lines.get(i + 1)).matches();
          if (header) {
            tableHeader = cells(stripped);
          } else if (!TABLE_SEPARATOR.matcher(line).matches()) {
            blocks.add(new Block(Kind.TABLE_ROW, 0, row(stripped), i + 1, i + 1));
          }
          i++;
          continue;
        }
        if ((m = ITEM.matcher(line)).matches()) {
          flush(i);
          start(Kind.LIST_ITEM, i, m.group(1) == null ? "" : m.group(1));
          i++;
          continue;
        }
        if ((m = QUOTE.matcher(line)).matches()) {
          if (open != Kind.QUOTE) {
            flush(i);
            start(Kind.QUOTE, i, m.group(1));
          } else if (m.group(1).isBlank()) {
            flush(i);
          } else {
            openText.add(m.group(1).strip());
          }
          i++;
          continue;
        }
        if (open == null && HTML.matcher(line).matches()) {
          int start = i;
          while (i < lines.size() && !lines.get(i).isBlank()) {
            i++;
          }
          blocks.add(new Block(Kind.HTML, 0, "", start + 1, i));
          continue;
        }
        if (open == null && (line.startsWith("    ") || line.startsWith("\t"))) {
          int start = i;
          while (i < lines.size()
              && (lines.get(i).isBlank()
                  || lines.get(i).startsWith("    ")
                  || lines.get(i).startsWith("\t"))) {
            i++;
          }
          int end = i;
          while (end > start && lines.get(end - 1).isBlank()) {
            end--;
          }
          blocks.add(new Block(Kind.CODE, 0, "", start + 1, end));
          continue;
        }
        if (open == null) {
          start(Kind.PARAGRAPH, i, stripped);
        } else {
          openText.add(stripped); // a continuation line
        }
        i++;
      }
      flush(lines.size());
      return new Markdown(
          lines, Collections.unmodifiableMap(frontMatter), List.copyOf(blocks), skipped);
    }

    /**
     * Reads the YAML front matter, if any, and returns the index of the first line after it. Lines
     * between the {@code ---} lines must be {@code key: value} lines, indented lines (nested
     * values), comments or blank, or else the first {@code ---} is a thematic break.
     */
    private int frontMatter() {
      if (lines.isEmpty() || !lines.getFirst().strip().equals("---")) {
        return 0;
      }
      for (int j = 1; j < lines.size(); j++) {
        String line = lines.get(j).strip();
        if (!line.isEmpty()
            && !line.startsWith("#")
            && !Character.isWhitespace(lines.get(j).charAt(0))
            && !line.equals("---")
            && !line.equals("...")
            && !FRONT_MATTER_FIELD.matcher(lines.get(j)).matches()) {
          return 0; // not YAML key: value lines, so a thematic break and not front matter
        }
        if (line.equals("---") || line.equals("...")) {
          for (int k = 1; k < j; k++) {
            Matcher m = FRONT_MATTER_FIELD.matcher(lines.get(k));
            if (m.matches()) {
              String value = unquote(m.group(2));
              String key = m.group(1).toLowerCase(Locale.ROOT);
              if (!value.isEmpty() && !frontMatter.containsKey(key)) {
                frontMatter.put(key, new Field(value, k + 1));
              }
            }
          }
          for (int k = 0; k <= j; k++) {
            skipped[k] = true;
          }
          return j + 1;
        }
      }
      return 0;
    }

    /** Skips an HTML comment starting on line i and returns the index of the line after it. */
    private int comment(int i) {
      String first = lines.get(i);
      int open = first.indexOf("<!--");
      int j = i;
      int from = open + 4;
      while (j < lines.size()) {
        int close = lines.get(j).indexOf("-->", j == i ? from : 0);
        skipped[j] = true;
        if (close >= 0) {
          return j + 1;
        }
        j++;
      }
      return j;
    }

    /**
     * Reads a fenced code block starting on line i, as a diagram or as code, and returns the index
     * of the line after it.
     */
    private int fence(int i, String marker, String info) {
      Pattern close =
          Pattern.compile(
              "^ {0,3}"
                  + Pattern.quote(String.valueOf(marker.charAt(0)))
                  + "{"
                  + marker.length()
                  + ",}[ \\t]*$");
      int j = i + 1;
      while (j < lines.size() && !close.matcher(lines.get(j)).matches()) {
        j++;
      }
      int end = Math.min(j, lines.size() - 1);
      blocks.add(codeBlock(lines.subList(i + 1, j), info, i + 1, end + 1));
      return end + 1;
    }

    /**
     * A table row's text: each cell after its column's header, as {@code Header: cell; ...}, or the
     * row as written when the table has no header of the same width.
     */
    private String row(String row) {
      List<String> cells = cells(row);
      if (tableHeader == null || tableHeader.size() != cells.size()) {
        return row;
      }
      List<String> parts = new ArrayList<>();
      for (int c = 0; c < cells.size(); c++) {
        if (!cells.get(c).isEmpty()) {
          String head = tableHeader.get(c);
          parts.add(head.isEmpty() ? cells.get(c) : head + ": " + cells.get(c));
        }
      }
      return String.join("; ", parts);
    }

    private static List<String> cells(String row) {
      String inner = row.strip();
      inner = inner.startsWith("|") ? inner.substring(1) : inner;
      inner =
          inner.endsWith("|") && !inner.endsWith("\\|")
              ? inner.substring(0, inner.length() - 1)
              : inner;
      List<String> cells = new ArrayList<>();
      for (String cell : inner.split("(?<!\\\\)\\|", -1)) {
        cells.add(cell.strip());
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

    /** Ends the open block, if any, before line index i. */
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

  /**
   * A delimited block's lines as a diagram, when its language is one or it is drawn with box
   * characters, else as code.
   *
   * @param content the lines between the delimiters
   * @param language the language the block names, or empty
   */
  static Block codeBlock(List<String> content, String language, int start, int end) {
    String name = language.strip().split("[\\s,{]", 2)[0].toLowerCase(Locale.ROOT);
    String diagram = DIAGRAM_LANGUAGES.get(name);
    if (diagram == null
        && PLAIN_LANGUAGES.contains(name)
        && content.stream().anyMatch(l -> DRAWING.matcher(l).find())) {
      diagram = DRAWN;
    }
    if (diagram == null) {
      return new Block(Kind.CODE, 0, "", start, end);
    }
    // Leading blank lines go, but not the first line's indentation, which a drawing needs.
    String text =
        String.join("\n", content.stream().map(String::stripTrailing).toList())
            .replaceFirst("^(?:[ \\t]*\\n)+", "")
            .stripTrailing();
    return new Block(Kind.DIAGRAM, 0, text, start, end, diagram);
  }

  private static String unquote(String value) {
    String v = value.strip();
    if (v.length() >= 2
        && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
      return v.substring(1, v.length() - 1).strip();
    }
    return v;
  }
}
