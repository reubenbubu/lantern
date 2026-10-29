package io.lantern.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The HTML template of a report spec in {@code report-specs/<spec>/<spec>.html}, which embeds the
 * document's data as JSON in one {@code <script type="application/json" id="...">} element. The
 * template ships with sample data in that element; rendering replaces it (ADR 0031).
 *
 * <p>Thread-safe.
 */
final class ReportSpecTemplate {

  /** Where the build copies {@code report-specs/} on the class path. */
  static final String RESOURCE_ROOT = "/report-specs/";

  private static final String CLOSE = "</script>";

  private final String before;
  private final String after;

  /**
   * A template whose data element, {@code <script type="application/json" id="<dataId>">}, occurs
   * exactly once.
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
    this.before = template.substring(0, start);
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
   * The template with the JSON as its data. Every {@code <} is written as its JSON unicode escape,
   * so the text can never close the script element, as for Lantern's own template (ADR 0013).
   */
  String render(String json) {
    Objects.requireNonNull(json, "json");
    return before + "\n" + json.replace("<", "\\u003c") + "\n" + after;
  }
}
