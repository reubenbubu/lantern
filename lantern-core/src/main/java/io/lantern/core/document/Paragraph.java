package io.lantern.core.document;

import java.util.List;

/**
 * A paragraph of rich text.
 *
 * @param id the block ID
 * @param content the text, not empty
 * @param facts the IDs of the facts the paragraph came from
 */
public record Paragraph(String id, List<Inline> content, List<String> facts) implements Block {

  public Paragraph {
    Checks.elementId(id, "block ID");
    content = Checks.content(content, "paragraph content");
    facts = Checks.factIds(facts, "paragraph facts");
  }
}
