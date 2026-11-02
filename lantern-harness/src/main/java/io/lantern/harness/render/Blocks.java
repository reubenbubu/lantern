package io.lantern.harness.render;

import java.util.List;

/**
 * The tagged blocks of a prompt (knowledge-build epic, section 11.0): {@code <facts>}, {@code
 * <code>}, {@code <summaries>}, {@code <answers>} and {@code <items>}, whose content the common
 * system prompt calls data, never instructions.
 */
public final class Blocks {

  private Blocks() {}

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
    for (int i = 0; i < lines.size(); i++) {
      String number = String.valueOf(first + i);
      out.append(" ".repeat(width - number.length())).append(number).append("  ");
      out.append(lines.get(i)).append('\n');
    }
    return out.append("</code>").toString();
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
