package io.lantern.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The HTML template of a report spec in {@code report-specs/<spec>/<spec>.html}, which embeds the
 * document's data as JSON in one {@code <script type="application/json" id="...">} element. The
 * template ships with sample data in that element, and a sample {@code <title>}; rendering replaces
 * both (ADR 0031, #981).
 *
 * <p>Thread-safe.
 */
final class ReportSpecTemplate {

  /** Where the build copies {@code report-specs/} on the class path. */
  static final String RESOURCE_ROOT = "/report-specs/";

  private static final String CLOSE = "</script>";
  private static final String TITLE_OPEN = "<title>";
  private static final String TITLE_CLOSE = "</title>";

  /** The template up to its title's text, or up to its data when it has no title before it. */
  private final String head;

  /** From the end of the title's text up to the data; null when there is no title. */
  private final String middle;

  private final String after;

  /**
   * A template whose data element, {@code <script type="application/json" id="<dataId>">}, occurs
   * exactly once. The first {@code <title>} element before it, if any, is the document's title.
   */
  ReportSpecTemplate(String template, String dataId) {
    Objects.requireNonNull(template, "template");
    String open = "<script type=\"application/json\" id=\"" + dataId + "\">";
    int at = template.indexOf(open);
    if (at < 0 || template.indexOf(open, at + 1) >= 0) {
      throw new IllegalArgumentException("The template must hold " + open + " exactly once");
    }
    int start = at + open.length();
    int end = template.indexOf(CLOSE, start);
    if (end < 0) {
      throw new IllegalArgumentException("The template's " + open + " is not closed");
    }
    String before = template.substring(0, start);
    int titleOpen = before.indexOf(TITLE_OPEN);
    int titleClose = titleOpen < 0 ? -1 : before.indexOf(TITLE_CLOSE, titleOpen);
    if (titleClose < 0) {
      this.head = before;
      this.middle = null;
    } else {
      this.head = before.substring(0, titleOpen + TITLE_OPEN.length());
      this.middle = before.substring(titleClose);
    }
    this.after = template.substring(end);
  }

  /**
   * The template of the report spec {@code spec} from the class path.
   *
   * @throws IllegalStateException if it is not on the class path
   */
  static ReportSpecTemplate fromClassPath(String spec, String dataId) {
    String resource = RESOURCE_ROOT + spec + "/" + spec + ".html";
    try (InputStream in = ReportSpecTemplate.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "The report spec template " + resource + " is not on the class path");
      }
      return new ReportSpecTemplate(new String(in.readAllBytes(), StandardCharsets.UTF_8), dataId);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the report spec template " + resource, e);
    }
  }

  /**
   * The template with the JSON as its data and {@code title}, HTML-escaped, as the text of its
   * {@code <title>}, so the file names its own system before any script runs (#981). Every {@code
   * <} of the JSON is written as its JSON unicode escape, so the text can never close the script
   * element, as for Lantern's own template (ADR 0013).
   */
  String render(String json, String title) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(title, "title");
    String start = middle == null ? head : head + escapeHtml(title) + middle;
    return start + "\n" + json.replace("<", "\\u003c") + "\n" + after;
  }

  private static String escapeHtml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
