package io.lantern.core.document;

/**
 * The template a document is meant to be rendered with.
 *
 * @param name the template name
 * @param version the template version
 */
public record TemplateRef(String name, String version) {

  public TemplateRef {
    Checks.text(name, "template name");
    Checks.text(version, "template version");
  }
}
