package io.lantern.extract.sql;

import io.lantern.extract.sql.SchemaModel.Where;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;

/**
 * Applies the DDL of a SQL script to a {@link SchemaModel}: {@code CREATE TABLE}, {@code ALTER
 * TABLE}, {@code DROP TABLE} and {@code RENAME TABLE}. Other statements (data, indexes, views,
 * functions) do not change tables, columns or keys and are skipped. The script is split into
 * statements here, so each keeps its lines and a statement the parser cannot read is skipped alone.
 */
final class SqlScript {

  /** How long the parser may spend on one statement before it is skipped. */
  private static final long PARSE_TIMEOUT_MILLIS = 10_000;

  private static final Pattern DDL =
      Pattern.compile(
          "(?is)(CREATE\\s+(OR\\s+REPLACE\\s+)?(UNLOGGED\\s+)?TABLE|ALTER\\s+TABLE|RENAME\\s+TABLE)\\b.*");
  private static final Pattern DROP_TABLE =
      Pattern.compile(
          "(?is)DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(.+?)(\\s+(CASCADE|RESTRICT)(\\s+CONSTRAINTS)?)?\\s*");
  private static final Pattern SET_DATA_TYPE = Pattern.compile("(?i)\\bSET\\s+DATA\\s+TYPE\\b");
  private static final Pattern DROP_FOREIGN_KEY =
      Pattern.compile("(?i)\\bDROP\\s+FOREIGN\\s+KEY\\b");
  private static final Pattern DOLLAR_TAG = Pattern.compile("\\$[A-Za-z_]*\\$");

  private static final System.Logger LOG = System.getLogger(SqlScript.class.getName());

  private SqlScript() {}

  /** A statement of a script and the lines it spans. */
  record Part(String sql, int startLine, int endLine) {}

  /**
   * Splits a script into statements at semicolons outside quotes, quoted identifiers, dollar quotes
   * and comments. Comments before a statement are left out of it; a statement's lines run from its
   * first character to its last.
   */
  static List<Part> split(String script) {
    List<Part> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int line = 1;
    int startLine = 0;
    int endLine = 0;
    int i = 0;
    int n = script.length();
    while (i < n) {
      char c = script.charAt(i);
      if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
        int end = script.indexOf('\n', i);
        i = end < 0 ? n : end;
        continue;
      }
      if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
        int end = script.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        line += count(script, i, end);
        i = end;
        current.append(' ');
        continue;
      }
      if (c == ';') {
        add(parts, current, startLine, endLine);
        current.setLength(0);
        startLine = 0;
        i++;
        continue;
      }
      if (c == '\n') {
        line++;
        current.append(c);
        i++;
        continue;
      }
      if (Character.isWhitespace(c)) {
        current.append(c);
        i++;
        continue;
      }
      if (startLine == 0) {
        startLine = line;
      }
      int end = i + 1;
      if (c == '\'' || c == '"' || c == '`') {
        end = closingQuote(script, i, c);
      } else if (c == '[') {
        int close = script.indexOf(']', i + 1);
        end = close < 0 ? n : close + 1;
      } else if (c == '$') {
        Matcher tag = DOLLAR_TAG.matcher(script).region(i, n);
        if (tag.lookingAt()) {
          int close = script.indexOf(tag.group(), tag.end());
          end = close < 0 ? n : close + tag.group().length();
        }
      }
      current.append(script, i, end);
      line += count(script, i, end);
      endLine = line;
      i = end;
    }
    add(parts, current, startLine, endLine);
    return parts;
  }

  private static int closingQuote(String script, int open, char quote) {
    int i = open + 1;
    while (i < script.length()) {
      char c = script.charAt(i);
      if (c == quote) {
        if (i + 1 < script.length() && script.charAt(i + 1) == quote) {
          i += 2;
          continue;
        }
        return i + 1;
      }
      if (c == '\\' && quote == '\'' && i + 1 < script.length()) {
        // MySQL escapes; harmless elsewhere, where a backslash before a quote is rare.
        i += 2;
        continue;
      }
      i++;
    }
    return script.length();
  }

  private static int count(String s, int from, int to) {
    int lines = 0;
    for (int i = from; i < to; i++) {
      if (s.charAt(i) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  private static void add(List<Part> parts, StringBuilder current, int startLine, int endLine) {
    String sql = current.toString().strip();
    if (!sql.isEmpty() && startLine > 0) {
      parts.add(new Part(sql, startLine, endLine));
    }
  }

  /**
   * Applies the script's DDL to the schema.
   *
   * @param where the file the script is in; its lines are replaced by each statement's, offset by
   *     {@code where.start() - 1}, unless {@code wholeRange} is set
   * @param wholeRange whether every statement points at {@code where}'s lines as given, as for SQL
   *     embedded in a Liquibase change
   */
  static void apply(
      SchemaModel model, String script, Where where, boolean wholeRange, ExecutorService executor) {
    for (Part part : split(script)) {
      Where at =
          wholeRange
              ? where
              : new Where(
                  where.path(),
                  where.hash(),
                  where.start() - 1 + part.startLine(),
                  where.start() - 1 + part.endLine(),
                  where.confidence());
      applyStatement(model, part.sql(), at, executor);
    }
  }

  private static void applyStatement(
      SchemaModel model, String sql, Where where, ExecutorService executor) {
    Matcher drop = DROP_TABLE.matcher(sql);
    if (drop.matches()) {
      for (String table : drop.group(2).split(",")) {
        model.dropTable(SchemaModel.name(table));
      }
      return;
    }
    if (!DDL.matcher(sql).matches()) {
      return;
    }
    String text = DROP_FOREIGN_KEY.matcher(sql).replaceAll("DROP CONSTRAINT");
    text = SET_DATA_TYPE.matcher(text).replaceAll("TYPE");
    Statement statement = parse(text, executor);
    if (statement == null && text.regionMatches(true, 0, "CREATE", 0, 6)) {
      // Table options the parser does not know (PARTITION BY, INHERITS, TABLESPACE, ...) follow
      // the column list; the columns and keys are what matter.
      String columnsOnly = withoutTableOptions(text);
      if (columnsOnly != null) {
        statement = parse(columnsOnly, executor);
      }
    }
    switch (statement) {
      case CreateTable create -> createTable(model, create, where);
      case Alter alter -> alter(model, alter, where);
      case RenameTableStatement rename -> {
        for (var entry : rename.getTableNames()) {
          model.renameTable(name(entry.getKey()), name(entry.getValue()), where);
        }
      }
      case null, default ->
          LOG.log(
              Level.WARNING,
              "Skipped a statement the SQL parser cannot read, in {0} lines {1}-{2}",
              where.path(),
              where.start(),
              where.end());
    }
  }

  /** The statement, or null if the parser fails, runs out of time, or does not know its kind. */
  private static Statement parse(String sql, ExecutorService executor) {
    try {
      Statement statement =
          CCJSqlParserUtil.parse(sql, executor, p -> p.withTimeOut(PARSE_TIMEOUT_MILLIS));
      return statement instanceof UnsupportedStatement ? null : statement;
    } catch (JSQLParserException | RuntimeException | StackOverflowError e) {
      return null;
    }
  }

  /** The statement cut after the parenthesis that closes the column list, or null. */
  private static String withoutTableOptions(String sql) {
    int open = sql.indexOf('(');
    if (open < 0) {
      return null;
    }
    int depth = 0;
    for (int i = open; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        i = closingQuote(sql, i, c) - 1;
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i + 1 < sql.length() ? sql.substring(0, i + 1) : null;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // CREATE TABLE

  private static void createTable(SchemaModel model, CreateTable create, Where where) {
    String table = name(create.getTable());
    if (table == null || (create.isIfNotExists() && model.table(table) != null)) {
      return;
    }
    model.createTable(table, where);
    List<ColumnDefinition> columns =
        create.getColumnDefinitions() == null ? List.of() : create.getColumnDefinitions();
    List<Inline> inline = new ArrayList<>();
    for (ColumnDefinition column : columns) {
      Inline spec = Inline.of(column.getColumnSpecs());
      String name = SchemaModel.name(column.getColumnName());
      model.addColumn(table, name, type(column.getColDataType()), !spec.notNull, where);
      inline.add(spec.forColumn(name));
    }
    for (Inline spec : inline) {
      spec.apply(model, table, where);
    }
    if (create.getIndexes() != null) {
      for (Index index : create.getIndexes()) {
        addConstraint(model, table, index, where);
      }
    }
  }

  /** What a column definition's specs say beyond its type. */
  private static final class Inline {
    boolean notNull;
    boolean primaryKey;
    boolean unique;
    String constraint;
    String primaryKeyName;
    String uniqueName;
    String foreignKeyName;
    String references;
    List<String> referenced = List.of();
    String column;

    static Inline of(List<String> specs) {
      Inline in = new Inline();
      if (specs == null) {
        return in;
      }
      for (int i = 0; i < specs.size(); i++) {
        String token = specs.get(i).toUpperCase(Locale.ROOT);
        switch (token) {
          case "NOT" -> {
            if (i + 1 < specs.size() && specs.get(i + 1).equalsIgnoreCase("NULL")) {
              in.notNull = true;
              in.constraint = null;
              i++;
            }
          }
          case "DEFAULT" -> i++;
          case "CONSTRAINT" -> {
            if (i + 1 < specs.size()) {
              in.constraint = SchemaModel.name(specs.get(++i));
            }
          }
          case "PRIMARY" -> {
            if (i + 1 < specs.size() && specs.get(i + 1).equalsIgnoreCase("KEY")) {
              in.primaryKey = true;
              in.primaryKeyName = in.constraint;
              in.constraint = null;
              i++;
            }
          }
          case "UNIQUE" -> {
            in.unique = true;
            in.uniqueName = in.constraint;
            in.constraint = null;
          }
          case "REFERENCES" -> {
            if (i + 1 < specs.size()) {
              in.references = SchemaModel.name(specs.get(++i));
              in.foreignKeyName = in.constraint;
              in.constraint = null;
              if (i + 1 < specs.size() && specs.get(i + 1).startsWith("(")) {
                in.referenced = columnList(specs.get(++i));
              }
            }
          }
          default -> {}
        }
      }
      return in;
    }

    Inline forColumn(String name) {
      column = name;
      return this;
    }

    void apply(SchemaModel model, String table, Where where) {
      if (column == null) {
        return;
      }
      if (primaryKey) {
        model.setPrimaryKey(table, primaryKeyName, List.of(column), where);
      }
      if (unique) {
        model.addUniqueKey(table, uniqueName, List.of(column), where);
      }
      if (references != null) {
        model.addForeignKey(table, foreignKeyName, List.of(column), references, referenced, where);
      }
    }
  }

  /** Column names from a parenthesized list such as {@code (a, "B")}. */
  private static List<String> columnList(String text) {
    String inner = text.strip();
    if (inner.startsWith("(")) {
      inner = inner.substring(1);
    }
    if (inner.endsWith(")")) {
      inner = inner.substring(0, inner.length() - 1);
    }
    List<String> names = new ArrayList<>();
    for (String part : inner.split(",")) {
      String name = SchemaModel.name(part);
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  /** Adds a primary, unique or foreign key; other constraints and plain indexes are skipped. */
  private static void addConstraint(SchemaModel model, String table, Index index, Where where) {
    String name = SchemaModel.name(index.getName());
    if (index instanceof ForeignKeyIndex fk) {
      model.addForeignKey(
          table,
          name,
          names(fk.getColumnsNames()),
          name(fk.getTable()),
          names(fk.getReferencedColumnNames()),
          where);
      return;
    }
    String type = index.getType() == null ? "" : index.getType().toUpperCase(Locale.ROOT);
    // Columns are read only for the kinds mapped here: check and exclude constraints have none.
    if (type.startsWith("PRIMARY")) {
      model.setPrimaryKey(table, name, names(index.getColumnsNames()), where);
    } else if (type.startsWith("UNIQUE")) {
      model.addUniqueKey(table, name, names(index.getColumnsNames()), where);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // ALTER TABLE

  private static void alter(SchemaModel model, Alter alter, Where where) {
    String table = name(alter.getTable());
    if (table == null || alter.getAlterExpressions() == null) {
      return;
    }
    for (AlterExpression e : alter.getAlterExpressions()) {
      AlterOperation op = e.getOperation();
      if (op == null) {
        continue;
      }
      switch (op) {
        case ADD -> add(model, table, e, where);
        case DROP -> {
          if (e.getColumnName() != null) {
            model.dropColumn(table, SchemaModel.name(e.getColumnName()));
          } else if (e.getConstraintName() != null) {
            model.dropConstraint(table, SchemaModel.name(e.getConstraintName()));
          }
        }
        case DROP_PRIMARY_KEY -> model.dropPrimaryKey(table);
        case DROP_UNIQUE ->
            model.dropUniqueKey(
                table, SchemaModel.name(e.getConstraintName()), names(e.getUkColumns()));
        case DROP_FOREIGN_KEY ->
            model.dropConstraint(table, SchemaModel.name(e.getConstraintName()));
        case RENAME -> {
          if (e.getColumnOldName() != null && e.getColumnName() != null) {
            model.renameColumn(
                table,
                SchemaModel.name(e.getColumnOldName()),
                SchemaModel.name(e.getColumnName()),
                where);
          }
        }
        case RENAME_TABLE -> model.renameTable(table, SchemaModel.name(e.getNewTableName()), where);
        case ALTER, MODIFY, CHANGE -> change(model, table, e, where);
        default -> {}
      }
    }
  }

  private static void add(SchemaModel model, String table, AlterExpression e, Where where) {
    if (e.getColDataTypeList() != null) {
      for (AlterExpression.ColumnDataType column : e.getColDataTypeList()) {
        Inline spec = Inline.of(column.getColumnSpecs());
        String name = SchemaModel.name(column.getColumnName());
        model.addColumn(table, name, type(column.getColDataType()), !spec.notNull, where);
        spec.forColumn(name).apply(model, table, where);
      }
      return;
    }
    String constraint = SchemaModel.name(e.getConstraintName());
    if (e.getIndex() != null) {
      addConstraint(model, table, e.getIndex(), where);
    } else if (e.getPkColumns() != null) {
      model.setPrimaryKey(table, constraint, names(e.getPkColumns()), where);
    } else if (e.getUkColumns() != null) {
      String name = e.getUkName() != null ? SchemaModel.name(e.getUkName()) : constraint;
      model.addUniqueKey(table, name, names(e.getUkColumns()), where);
    } else if (e.getFkColumns() != null) {
      model.addForeignKey(
          table,
          constraint,
          names(e.getFkColumns()),
          SchemaModel.name(e.getFkSourceTable()),
          names(e.getFkSourceColumns()),
          where);
    }
  }

  /** {@code ALTER COLUMN} (PostgreSQL), {@code MODIFY} and {@code CHANGE} (MySQL). */
  private static void change(SchemaModel model, String table, AlterExpression e, Where where) {
    if (e.getColumnDropNotNullList() != null) {
      for (AlterExpression.ColumnDropNotNull column : e.getColumnDropNotNullList()) {
        model.setNullable(table, SchemaModel.name(column.getColumnName()), true, where);
      }
    }
    if (e.getColDataTypeList() == null) {
      return;
    }
    for (AlterExpression.ColumnDataType column : e.getColDataTypeList()) {
      String name = SchemaModel.name(column.getColumnName());
      List<String> specs = column.getColumnSpecs() == null ? List.of() : column.getColumnSpecs();
      String upper = String.join(" ", specs).toUpperCase(Locale.ROOT);
      ColDataType dataType = column.getColDataType();
      String keyword =
          dataType == null || dataType.getDataType() == null
              ? ""
              : dataType.getDataType().toUpperCase(Locale.ROOT);
      if (e.getOperation() == AlterOperation.ALTER) {
        // PostgreSQL: the parser reads SET NOT NULL as a column of type SET.
        if (upper.startsWith("DROP NOT NULL")) {
          model.setNullable(table, name, true, where);
        } else if (keyword.equals("SET") && upper.startsWith("NOT NULL")) {
          model.setNullable(table, name, false, where);
        } else if (dataType != null && !keyword.equals("SET") && !keyword.equals("DROP")) {
          model.setType(table, name, type(dataType), where);
        }
        continue;
      }
      // MySQL redefines the whole column; CHANGE may rename it too.
      if (e.getOperation() == AlterOperation.CHANGE && e.getColumnOldName() != null) {
        model.renameColumn(table, SchemaModel.name(e.getColumnOldName()), name, where);
      }
      Inline spec = Inline.of(specs);
      model.setType(table, name, type(dataType), where);
      model.setNullable(table, name, !spec.notNull, where);
      spec.forColumn(name).apply(model, table, where);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Names and types

  private static String name(Table table) {
    return table == null ? null : SchemaModel.name(table.getFullyQualifiedName());
  }

  private static List<String> names(List<String> written) {
    if (written == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (String w : written) {
      String name = SchemaModel.name(w);
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  /** A column type as written, in lower case and with single spaces: {@code varchar(20)}. */
  static String type(ColDataType type) {
    return type == null ? null : type(type.toString());
  }

  /** A column type in lower case and with single spaces, no space before a parenthesis. */
  static String type(String written) {
    if (written == null || written.isBlank()) {
      return null;
    }
    return written
        .strip()
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .replace(" (", "(")
        .replace("( ", "(")
        .replace(" )", ")");
  }
}
