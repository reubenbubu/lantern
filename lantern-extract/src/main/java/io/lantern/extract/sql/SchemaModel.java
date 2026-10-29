package io.lantern.extract.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A schema as migrations build it up: tables, their columns and keys, each with where it was last
 * defined. Changes that name a table or column the schema does not have are ignored, as are those
 * that would make it inconsistent, so a script the parser only partly understood still gives a
 * usable schema.
 *
 * <p>Names are compared as {@link #name} normalizes them. A table name may be qualified with a
 * database schema ({@code public.orders}); a change naming the table without its qualifier, or with
 * one when it was created without, finds it when exactly one table has that unqualified name. Two
 * different qualifiers never match.
 */
final class SchemaModel {

  /**
   * Where something was declared: a file at some lines, read with some confidence.
   *
   * @param path the file's path in the repository
   * @param hash the file's SHA-256
   * @param start the first line, 1-based
   * @param end the last line
   * @param confidence the confidence of facts drawn from it
   */
  record Where(String path, String hash, int start, int end, double confidence) {}

  /** A column of a table. */
  static final class Column {
    String name;
    final Where defined;
    String type;
    Where typeAt;
    boolean nullable;
    Where nullableAt;

    Column(String name, String type, boolean nullable, Where where) {
      this.name = name;
      this.defined = where;
      this.type = type;
      this.typeAt = where;
      this.nullable = nullable;
      this.nullableAt = where;
    }
  }

  /** A primary or unique key: its name if it has one, and its columns in order. */
  record Key(String name, List<String> columns, Where where) {
    Key {
      columns = List.copyOf(columns);
    }
  }

  /**
   * A foreign key: its name if it has one, its columns, the table it references and the columns
   * there, empty when it references that table's primary key without naming the columns.
   */
  record ForeignKey(
      String name, List<String> columns, String table, List<String> referenced, Where where) {
    ForeignKey {
      columns = List.copyOf(columns);
      referenced = List.copyOf(referenced);
    }
  }

  /** A table, with its columns in order. */
  static final class Table {
    String name;
    Where defined;
    final Map<String, Column> columns = new LinkedHashMap<>();
    Key primaryKey;
    final List<Key> uniqueKeys = new ArrayList<>();
    final List<ForeignKey> foreignKeys = new ArrayList<>();

    Table(String name, Where where) {
      this.name = name;
      this.defined = where;
    }
  }

  private final Map<String, Table> tables = new LinkedHashMap<>();

  /**
   * A table or column name as the schema keys it: an unquoted name in lower case, as PostgreSQL
   * folds it; a quoted one ({@code "Name"}, {@code `Name`}, {@code [Name]}) as written, without the
   * quotes. Each part of a qualified name is normalized on its own. Returns null for a blank name.
   */
  static String name(String written) {
    if (written == null || written.isBlank()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    String s = written.strip();
    int i = 0;
    StringBuilder part = new StringBuilder();
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"' || c == '`' || c == '[') {
        char close = c == '[' ? ']' : c;
        int end = s.indexOf(close, i + 1);
        if (end < 0) {
          end = s.length();
        }
        part.append(s, i + 1, end);
        i = end + 1;
      } else if (c == '.') {
        parts.add(part.toString());
        part.setLength(0);
        i++;
      } else {
        part.append(Character.toLowerCase(c));
        i++;
      }
    }
    parts.add(part.toString());
    if (parts.stream().anyMatch(String::isBlank)) {
      return null;
    }
    return String.join(".", parts).strip();
  }

  /** The tables in the order they were created. */
  List<Table> tables() {
    return List.copyOf(tables.values());
  }

  /** The table with the name, or null; see the class comment for qualified names. */
  Table table(String name) {
    if (name == null) {
      return null;
    }
    Table exact = tables.get(name);
    if (exact != null) {
      return exact;
    }
    // A qualified name matches only an unqualified one, so app.orders never finds other.orders.
    boolean qualified = name.contains(".");
    String bare = unqualified(name);
    Table found = null;
    for (Table t : tables.values()) {
      if ((!qualified || !t.name.contains(".")) && unqualified(t.name).equals(bare)) {
        if (found != null) {
          return null;
        }
        found = t;
      }
    }
    return found;
  }

  private static String unqualified(String name) {
    return name.substring(name.lastIndexOf('.') + 1);
  }

  /** Creates a table, replacing one of the same name. */
  Table createTable(String name, Where where) {
    Table existing = table(name);
    if (existing != null) {
      tables.remove(existing.name);
    }
    Table table = new Table(name, where);
    tables.put(name, table);
    return table;
  }

  /** Drops a table and the foreign keys that reference it, as {@code CASCADE} does. */
  void dropTable(String name) {
    Table table = table(name);
    if (table != null) {
      tables.remove(table.name);
      for (Table other : tables.values()) {
        other.foreignKeys.removeIf(k -> k.table().equals(table.name));
      }
    }
  }

  void renameTable(String from, String to, Where where) {
    Table table = table(from);
    if (table == null || to == null || (table(to) != null && table(to) != table)) {
      return;
    }
    String old = table.name;
    tables.remove(old);
    table.name = to;
    table.defined = where;
    tables.put(to, table);
    for (Table t : tables.values()) {
      t.foreignKeys.replaceAll(
          fk ->
              fk.table().equals(old)
                  ? new ForeignKey(fk.name(), fk.columns(), to, fk.referenced(), fk.where())
                  : fk);
    }
  }

  /** Adds a column, replacing one of the same name. */
  void addColumn(String table, String column, String type, boolean nullable, Where where) {
    Table t = table(table);
    if (t == null || column == null) {
      return;
    }
    t.columns.put(column, new Column(column, type, nullable, where));
  }

  /** Drops a column and the keys that use it, as databases do. */
  void dropColumn(String table, String column) {
    Table t = table(table);
    if (t == null || t.columns.remove(column) == null) {
      return;
    }
    if (t.primaryKey != null && t.primaryKey.columns().contains(column)) {
      t.primaryKey = null;
    }
    t.uniqueKeys.removeIf(k -> k.columns().contains(column));
    t.foreignKeys.removeIf(k -> k.columns().contains(column));
    for (Table other : tables.values()) {
      other.foreignKeys.removeIf(k -> k.table().equals(t.name) && k.referenced().contains(column));
    }
  }

  void renameColumn(String table, String from, String to, Where where) {
    Table t = table(table);
    if (t == null || to == null || !t.columns.containsKey(from) || t.columns.containsKey(to)) {
      return;
    }
    Map<String, Column> renamed = new LinkedHashMap<>();
    for (Map.Entry<String, Column> e : t.columns.entrySet()) {
      Column c = e.getValue();
      if (e.getKey().equals(from)) {
        Column moved = new Column(to, c.type, c.nullable, where);
        moved.typeAt = c.typeAt;
        moved.nullableAt = c.nullableAt;
        renamed.put(to, moved);
      } else {
        renamed.put(e.getKey(), c);
      }
    }
    t.columns.clear();
    t.columns.putAll(renamed);
    if (t.primaryKey != null) {
      t.primaryKey =
          new Key(
              t.primaryKey.name(), rename(t.primaryKey.columns(), from, to), t.primaryKey.where());
    }
    t.uniqueKeys.replaceAll(k -> new Key(k.name(), rename(k.columns(), from, to), k.where()));
    t.foreignKeys.replaceAll(
        k ->
            new ForeignKey(
                k.name(), rename(k.columns(), from, to), k.table(), k.referenced(), k.where()));
    for (Table other : tables.values()) {
      other.foreignKeys.replaceAll(
          k ->
              k.table().equals(t.name)
                  ? new ForeignKey(
                      k.name(), k.columns(), k.table(), rename(k.referenced(), from, to), k.where())
                  : k);
    }
  }

  private static List<String> rename(List<String> columns, String from, String to) {
    return columns.stream().map(c -> c.equals(from) ? to : c).toList();
  }

  void setType(String table, String column, String type, Where where) {
    Column c = column(table, column);
    if (c != null && type != null) {
      c.type = type;
      c.typeAt = where;
    }
  }

  void setNullable(String table, String column, boolean nullable, Where where) {
    Column c = column(table, column);
    if (c != null) {
      c.nullable = nullable;
      c.nullableAt = where;
    }
  }

  private Column column(String table, String column) {
    Table t = table(table);
    return t == null ? null : t.columns.get(column);
  }

  /**
   * Sets the primary key, if every column exists, and makes its columns not nullable, as databases
   * do.
   */
  void setPrimaryKey(String table, String name, List<String> columns, Where where) {
    Table t = table(table);
    if (t == null || columns.isEmpty() || !t.columns.keySet().containsAll(columns)) {
      return;
    }
    t.primaryKey = new Key(name, columns, where);
    for (String column : columns) {
      Column c = t.columns.get(column);
      if (c.nullable) {
        c.nullable = false;
        c.nullableAt = where;
      }
    }
  }

  void dropPrimaryKey(String table) {
    Table t = table(table);
    if (t != null) {
      t.primaryKey = null;
    }
  }

  /** Adds a unique key, if every column exists and no key has the same columns. */
  void addUniqueKey(String table, String name, List<String> columns, Where where) {
    Table t = table(table);
    if (t == null
        || columns.isEmpty()
        || !t.columns.keySet().containsAll(columns)
        || t.uniqueKeys.stream().anyMatch(k -> k.columns().equals(columns))) {
      return;
    }
    t.uniqueKeys.add(new Key(name, columns, where));
  }

  /**
   * Adds a foreign key, if its columns exist. The referenced table need not be in the schema: it
   * may be created later, or belong to another schema.
   */
  void addForeignKey(
      String table,
      String name,
      List<String> columns,
      String referencedTable,
      List<String> referenced,
      Where where) {
    Table t = table(table);
    if (t == null
        || referencedTable == null
        || columns.isEmpty()
        || !t.columns.keySet().containsAll(columns)
        || (!referenced.isEmpty() && referenced.size() != columns.size())) {
      return;
    }
    Table target = table(referencedTable);
    String targetName = target == null ? referencedTable : target.name;
    t.foreignKeys.removeIf(k -> k.columns().equals(columns) && k.table().equals(targetName));
    t.foreignKeys.add(new ForeignKey(name, columns, targetName, referenced, where));
  }

  /** Drops the primary, unique or foreign key with the name, compared ignoring case. */
  void dropConstraint(String table, String name) {
    Table t = table(table);
    if (t == null || name == null) {
      return;
    }
    if (t.primaryKey != null && named(t.primaryKey.name(), t.name, List.of(), "pkey", name)) {
      t.primaryKey = null;
    }
    t.uniqueKeys.removeIf(k -> named(k.name(), t.name, k.columns(), "key", name));
    t.foreignKeys.removeIf(k -> named(k.name(), t.name, k.columns(), "fkey", name));
  }

  /**
   * Whether a key has the name: the one it was declared with, or for a key declared without one,
   * the name PostgreSQL gives it ({@code orders_pkey}, {@code orders_code_key}, {@code
   * orders_customer_id_fkey}).
   */
  private static boolean named(
      String declared, String table, List<String> columns, String suffix, String name) {
    if (declared != null) {
      return sameName(declared, name);
    }
    List<String> parts = new ArrayList<>();
    parts.add(unqualified(table));
    parts.addAll(columns);
    parts.add(suffix);
    return sameName(String.join("_", parts), name);
  }

  /** Drops the unique key with the name; without a name, the one with exactly these columns. */
  void dropUniqueKey(String table, String name, List<String> columns) {
    Table t = table(table);
    if (t == null) {
      return;
    }
    if (name != null) {
      t.uniqueKeys.removeIf(k -> sameName(k.name(), name));
    } else {
      t.uniqueKeys.removeIf(k -> k.columns().equals(columns));
    }
  }

  private static boolean sameName(String a, String b) {
    return a != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
  }

  /** The columns a foreign key references: those it names, or the target's primary key. */
  List<String> referencedColumns(ForeignKey fk) {
    if (!fk.referenced().isEmpty()) {
      return fk.referenced();
    }
    Table target = table(fk.table());
    if (target == null || target.primaryKey == null) {
      return List.of();
    }
    List<String> pk = target.primaryKey.columns();
    return pk.size() == fk.columns().size() ? pk : List.of();
  }
}
