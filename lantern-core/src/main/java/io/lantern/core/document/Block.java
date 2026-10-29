package io.lantern.core.document;

import java.util.List;

/**
 * A block of a section: a paragraph, list, callout, table, diagram, sequence diagram, tree,
 * headline numbers or quality report.
 */
public sealed interface Block
    permits Paragraph,
        ListBlock,
        Callout,
        Table,
        Diagram,
        SequenceDiagram,
        Tree,
        Metrics,
        QualityReport {

  /** The block ID, unique among the document's sections and blocks. */
  String id();

  /** The IDs of the facts the block came from, sorted. */
  List<String> facts();
}
