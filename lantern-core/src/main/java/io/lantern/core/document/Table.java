package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.ArrayList;
import java.util.List;

/**
 * A table of rich-text cells.
 *
 * @param id the block ID
 * @param title the title, or {@code null}
 * @param columns the columns, at least one
 * @param rows the rows, each with one cell per column, and so each row folded under one
 * @param facts the IDs of the facts the table as a whole came from
 */
public record Table(
    String id, String title, List<Column> columns, List<Row> rows, List<String> facts)
    implements Block {

  public Table {
    Checks.elementId(id, "block ID");
    Checks.optionalText(title, "table title");
    columns = Checks.list(columns, "table columns");
    if (columns.isEmpty()) {
      throw new IllegalArgumentException("A table must have at least one column");
    }
    rows = Checks.list(rows, "table rows");
    for (Row row : allRows(rows)) {
      if (row.cells().size() != columns.size()) {
        throw new IllegalArgumentException(
            "Table "
                + id
                + " has "
                + columns.size()
                + " columns but a row with "
                + row.cells().size()
                + " cells");
      }
    }
    facts = Checks.factIds(facts, "table facts");
  }

  /** Every row, each followed by the rows folded under it. */
  public List<Row> allRows() {
    return allRows(rows);
  }

  private static List<Row> allRows(List<Row> rows) {
    List<Row> all = new ArrayList<>();
    for (Row row : rows) {
      all.add(row);
      all.addAll(row.children());
    }
    return all;
  }

  /**
   * A table column.
   *
   * @param label the column heading
   */
  public record Column(String label) {

    public Column {
      Checks.text(label, "column label");
    }
  }

  /**
   * A table row.
   *
   * @param entity the entity the row is about, or {@code null}
   * @param cells one cell per column, in column order; an empty cell is an empty list
   * @param facts the IDs of the facts the row came from
   * @param children the rows folded under this one, an expandable parent row: shown when a reader
   *     expands it, each with no children of its own (#747); none for a plain row
   */
  public record Row(
      EntityId entity, List<List<Inline>> cells, List<String> facts, List<Row> children) {

    public Row {
      List<List<Inline>> copy = new ArrayList<>();
      for (List<Inline> cell : Checks.list(cells, "row cells")) {
        copy.add(Checks.list(cell, "cell"));
      }
      cells = List.copyOf(copy);
      facts = Checks.factIds(facts, "row facts");
      children = Checks.list(children, "row children");
      for (Row child : children) {
        if (!child.children().isEmpty()) {
          throw new IllegalArgumentException("A row folded under another has no rows of its own");
        }
      }
    }

    /** A plain row, with no rows folded under it. */
    public Row(EntityId entity, List<List<Inline>> cells, List<String> facts) {
      this(entity, cells, facts, List.of());
    }
  }
}
