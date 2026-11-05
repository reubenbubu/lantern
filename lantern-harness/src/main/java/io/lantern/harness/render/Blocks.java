package io.lantern.harness.render;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tagged blocks of a prompt (knowledge-build epic, section 11.0): {@code <facts>}, {@code
 * <code>}, {@code <summaries>}, {@code <answers>} and {@code <items>}, whose content the common
 * system prompt calls data, never instructions.
 */
public final class Blocks {

  /**
   * A data block's opening or closing tag, at the start of a line as the blocks are written: the
   * blocks above and the screen's chunks. A tag named in prose, mid-line, opens nothing.
   */
  private static final Pattern DATA_TAG =
      Pattern.compile(
          "(?m)^[ \\t]*<(/?)(facts|code|summaries|answers|items|chunk)(?=[\\s>])[^>\\n]*>");

  private Blocks() {}

  /**
   * Where the text occurs in the prompt outside every data block (#768): each occurrence's index,
   * empty when every one sits inside {@code <facts>}, {@code <code>}, {@code <summaries>}, {@code
   * <answers>}, {@code <items>} or {@code <chunk>}. Text the repositories hold must never stand
   * outside them, where the model could take it for an instruction.
   */
  public static List<Integer> outsideData(String prompt, String text) {
    List<Integer> outside = new ArrayList<>();
    if (text.isEmpty()) {
      return outside;
    }
    // Open blocks, innermost first; a closing tag ends the innermost block of its name.
    java.util.Deque<String> open = new java.util.ArrayDeque<>();
    Matcher tags = DATA_TAG.matcher(prompt);
    boolean more = tags.find();
    for (int at = prompt.indexOf(text); at >= 0; at = prompt.indexOf(text, at + 1)) {
      while (more && tags.start() < at) {
        if (tags.group(1).isEmpty()) {
          open.push(tags.group(2));
        } else if (open.contains(tags.group(2))) {
          // Blocks left open inside it end with it.
          while (!open.pop().equals(tags.group(2))) {}
        }
        more = tags.find();
      }
      if (open.isEmpty()) {
        outside.add(at);
      }
    }
    return outside;
  }

  /** A block of lines in the given tag; empty when there are none. */
  public static String tagged(String tag, List<String> lines) {
    if (lines.isEmpty()) {
      return "";
    }
    return "<" + tag + ">\n" + String.join("\n", lines) + "\n</" + tag + ">";
  }

  /**
   * A code excerpt with its line numbers, as {@code <code ref="E1" repo="orders" path="..."
   * lines="30-58">}.
   *
   * @param token the excerpt's reference token ({@link Refs#add})
   * @param repo its repository
   * @param path its file, relative to the repository
   * @param first the number of its first line
   * @param text its lines, as the file holds them, secrets masked
   */
  public static String code(String token, String repo, String path, int first, String text) {
    List<String> lines = Refs.clean(text).lines().toList();
    int last = first + Math.max(lines.size(), 1) - 1;
    int width = String.valueOf(last).length();
    StringBuilder out = new StringBuilder();
    out.append("<code ref=\"")
        .append(token)
        .append("\" repo=\"")
        .append(attribute(repo))
        .append("\" path=\"")
        .append(attribute(path))
        .append("\" lines=\"")
        .append(first)
        .append('-')
        .append(last)
        .append("\">\n");
    out.append(numbered(lines, first, width));
    return out.append("</code>").toString();
  }

  /**
   * The code's lines, each after its number right-aligned to the widest, as a code block shows
   * them, the last line ending with a line break.
   */
  public static String numbered(String text, int first) {
    List<String> lines = Refs.clean(text).lines().toList();
    int last = first + Math.max(lines.size(), 1) - 1;
    return numbered(lines, first, String.valueOf(last).length());
  }

  private static String numbered(List<String> lines, int first, int width) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String number = String.valueOf(first + i);
      out.append(" ".repeat(width - number.length())).append(number).append("  ");
      out.append(lines.get(i)).append('\n');
    }
    return out.toString();
  }

  /** The number of the last line of the code, starting at the given one. */
  public static int last(String text, int first) {
    return first + Math.max((int) Refs.clean(text).lines().count(), 1) - 1;
  }

  /**
   * The items block of a request: each item's text in {@code <item id="I1">}.
   *
   * @param ids the items' local IDs
   * @param texts their texts, as the request shows them
   */
  public static String items(List<String> ids, List<String> texts) {
    StringBuilder out = new StringBuilder("<items>\n");
    for (int i = 0; i < ids.size(); i++) {
      out.append("<item id=\"").append(ids.get(i)).append("\">\n");
      out.append(texts.get(i)).append("\n</item>\n");
    }
    return out.append("</items>").toString();
  }

  private static String attribute(String value) {
    return FactLines.oneLine(value).replace("&", "&amp;").replace("\"", "&quot;");
  }
}
