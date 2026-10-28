package io.lantern.core.document;

import java.util.List;

/** A block of a section: a paragraph, list, callout, table, diagram, tree or headline numbers. */
public sealed interface Block permits Paragraph, ListBlock, Callout, Table, Diagram, Tree, Metrics {

  /** The block ID, unique among the document's sections and blocks. */
  String id();

  /** The IDs of the facts the block came from, sorted. */
  List<String> facts();
}
