package io.lantern.extract.persistence;

import io.lantern.extract.sql.SqlExtractor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * The tables a SQL statement in code reads and writes, read with JSqlParser. See {@code
 * docs/adr/0036-persistence-extractor.md}.
 */
final class SqlAccess {

  /** How long the parser may take on one text before it is skipped. */
  private static final long PARSE_TIMEOUT_MILLIS = 10_000;

  /**
   * A table one statement uses.
   *
   * @param table the table's name as {@code table} entities key it ({@link SqlExtractor#tableName})
   * @param write whether the statement writes it (it is the target of an insert, update, delete,
   *     merge, upsert or truncate); otherwise it reads it
   * @param statement the statement's kind: {@code select}, {@code insert}, {@code update}, {@code
   *     delete}, {@code merge}, {@code upsert} or {@code truncate}
   */
  record Access(String table, boolean write, String statement) {}

  private SqlAccess() {}

  /**
   * The tables the statements of a text read and write, in the order they appear, each once per
   * statement. A text the parser cannot read, or a statement of another kind (DDL, a procedure
   * call), gives none.
   */
  static List<Access> of(String sql, ExecutorService executor) {
    Statements statements;
    try {
      statements =
          CCJSqlParserUtil.parseStatements(sql, executor, p -> p.withTimeOut(PARSE_TIMEOUT_MILLIS));
    } catch (JSQLParserException | RuntimeException | StackOverflowError e) {
      return List.of();
    }
    List<Access> found = new ArrayList<>();
    for (Statement statement : statements) {
      String kind = kind(statement);
      if (kind == null) {
        continue;
      }
      Set<String> written = new LinkedHashSet<>();
      for (Table t : targets(statement)) {
        String name = name(t.getFullyQualifiedName());
        if (name != null) {
          written.add(name);
        }
      }
      Set<String> read = new LinkedHashSet<>();
      try {
        for (String table : new TablesNamesFinder<>().getTables(statement)) {
          String name = name(table);
          if (name != null && !written.contains(name)) {
            read.add(name);
          }
        }
      } catch (RuntimeException e) {
        // A construct the finder does not visit; the written tables still hold.
      }
      written.forEach(t -> found.add(new Access(t, true, kind)));
      read.forEach(t -> found.add(new Access(t, false, kind)));
    }
    return found;
  }

  private static String kind(Statement statement) {
    return switch (statement) {
      case Select s -> "select";
      case Insert s -> "insert";
      case Update s -> "update";
      case Delete s -> "delete";
      case Merge s -> "merge";
      case Upsert s -> "upsert";
      case Truncate s -> "truncate";
      case null, default -> null;
    };
  }

  /** The tables a statement writes. */
  private static List<Table> targets(Statement statement) {
    List<Table> tables = new ArrayList<>();
    switch (statement) {
      case Insert s -> tables.add(s.getTable());
      case Update s -> tables.add(s.getTable());
      case Delete s -> {
        if (s.getTables() != null && !s.getTables().isEmpty()) {
          tables.addAll(s.getTables());
        } else {
          tables.add(s.getTable());
        }
      }
      case Merge s -> tables.add(s.getTable());
      case Upsert s -> tables.add(s.getTable());
      case Truncate s -> tables.add(s.getTable());
      default -> {}
    }
    tables.removeIf(t -> t == null || t.getName() == null);
    return tables;
  }

  private static String name(String written) {
    return written == null ? null : SqlExtractor.tableName(written);
  }
}
