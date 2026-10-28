package io.lantern.app;

import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Renders a document model as one self-contained HTML file: the single-file template that {@code
 * lantern-web} builds, with the document's JSON written into its placeholder (ADR 0013). The same
 * template serves every document; it picks the layout from the document's template name.
 *
 * <p>Thread-safe.
 */
public final class HtmlRenderer {

  /** Where the {@code lantern-web} jar carries the template. */
  static final String TEMPLATE_RESOURCE = "/io/lantern/web/index.html";

  /** The empty element in the template that receives the document model JSON. */
  static final String PLACEHOLDER =
      "<script id=\"lantern-document\" type=\"application/json\"></script>";

  private static final String CLOSE = "</script>";

  private final String before;
  private final String after;

  /** A renderer with the given template, which must hold the placeholder exactly once. */
  HtmlRenderer(String template) {
    Objects.requireNonNull(template, "template");
    int at = template.indexOf(PLACEHOLDER);
    if (at < 0 || template.indexOf(PLACEHOLDER, at + 1) >= 0) {
      throw new IllegalArgumentException(
          "The template must hold the placeholder " + PLACEHOLDER + " exactly once");
    }
    this.before = template.substring(0, at + PLACEHOLDER.length() - CLOSE.length());
    this.after = template.substring(at + PLACEHOLDER.length() - CLOSE.length());
  }

  /**
   * A renderer with the template from the {@code lantern-web} jar.
   *
   * @throws IllegalStateException if the template is not on the class path
   */
  public static HtmlRenderer fromClassPath() {
    try (InputStream in = HtmlRenderer.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            "The HTML template " + TEMPLATE_RESOURCE + " is not on the class path (lantern-web)");
      }
      return new HtmlRenderer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the HTML template", e);
    }
  }

  /** The document as one HTML file. */
  public String render(Document document) {
    return renderJson(DocumentJson.write(document));
  }

  /**
   * The document model JSON in the template. Every {@code <} is written as the JSON escape {@code
   * <}, so the text can never close the script element or open a comment. The JSON is concatenated
   * in, not substituted with a replace function that would read {@code $} patterns.
   */
  String renderJson(String documentJson) {
    Objects.requireNonNull(documentJson, "documentJson");
    return before + documentJson.replace("<", "\\u003c") + after;
  }
}
