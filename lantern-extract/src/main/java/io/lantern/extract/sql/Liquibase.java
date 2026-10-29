package io.lantern.extract.sql;

import io.lantern.extract.sql.SchemaModel.Where;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * Applies Liquibase changelogs to a {@link SchemaModel}: XML, YAML and JSON changelogs and
 * formatted SQL ones, following {@code include} and {@code includeAll}. The changes that shape
 * tables, columns and keys are applied; the rest (data, indexes, views, sequences) are skipped.
 * Every change set is applied whatever its {@code dbms}, {@code context} or {@code labels}, and
 * rollbacks are not, since the schema is what the migrations build on a fresh database.
 */
final class Liquibase {

  /** The first line of a formatted SQL changelog. */
  private static final String FORMATTED_SQL = "--liquibase formatted sql";

  private static final List<String> EXTENSIONS = List.of(".xml", ".yaml", ".yml", ".json", ".sql");
  private static final String RESOURCES = "src/main/resources/";

  private final SortedSet<String> files;
  private final Function<String, RepoFiles.Content> reader;
  private final ExecutorService executor;
  private final double confidence;

  /** The files read, changelogs and the SQL files they include, so they are not read again. */
  private final Set<String> used = new LinkedHashSet<>();

  /**
   * @param files every file of the repository at the commit
   * @param reader reads a file of the repository, or returns null
   * @param executor runs the SQL parser
   * @param confidence the confidence of facts drawn from changelogs
   */
  Liquibase(
      SortedSet<String> files,
      Function<String, RepoFiles.Content> reader,
      ExecutorService executor,
      double confidence) {
    this.files = files;
    this.reader = reader;
    this.executor = executor;
    this.confidence = confidence;
  }

  /** Whether a file is a changelog: its root is {@code databaseChangeLog}, or formatted SQL. */
  static boolean isChangelog(String path, byte[] content) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".sql")) {
      return isFormattedSql(content);
    }
    return EXTENSIONS.stream().anyMatch(lower::endsWith)
        && new String(content, StandardCharsets.UTF_8).contains("databaseChangeLog")
        && ChangelogNode.read(path, content) != null;
  }

  private static boolean isFormattedSql(byte[] content) {
    String text = new String(content, StandardCharsets.UTF_8).stripLeading();
    return text.regionMatches(true, 0, FORMATTED_SQL, 0, FORMATTED_SQL.length());
  }

  /** The files this changelog includes directly, resolved to repository paths. */
  List<String> includes(String path) {
    RepoFiles.Content content = reader.apply(path);
    if (content == null || path.toLowerCase(Locale.ROOT).endsWith(".sql")) {
      return List.of();
    }
    ChangelogNode root = ChangelogNode.read(path, content.bytes());
    if (root == null) {
      return List.of();
    }
    List<String> included = new ArrayList<>();
    for (ChangelogNode node : root.children()) {
      included.addAll(resolveInclude(path, node));
    }
    return included;
  }

  /** The files read so far, in the order they were read. */
  List<String> used() {
    return List.copyOf(used);
  }

  /** Applies a changelog and those it includes, in order; each file at most once. */
  void apply(SchemaModel model, String path) {
    Deque<String> stack = new ArrayDeque<>();
    apply(model, path, stack);
  }

  private void apply(SchemaModel model, String path, Deque<String> stack) {
    if (!used.add(path)) {
      return;
    }
    RepoFiles.Content content = reader.apply(path);
    if (content == null) {
      return;
    }
    if (path.toLowerCase(Locale.ROOT).endsWith(".sql")) {
      SqlScript.apply(
          model,
          new String(content.bytes(), StandardCharsets.UTF_8),
          new Where(path, content.hash(), 1, 1, confidence),
          false,
          executor);
      return;
    }
    ChangelogNode root = ChangelogNode.read(path, content.bytes());
    if (root == null) {
      return;
    }
    stack.push(path);
    for (ChangelogNode node : root.children()) {
      switch (node.name()) {
        case "changeSet" -> {
          for (ChangelogNode change : node.children()) {
            change(model, path, content.hash(), change);
          }
        }
        case "include", "includeAll" -> {
          for (String included : resolveInclude(path, node)) {
            if (!stack.contains(included)) {
              apply(model, included, stack);
            }
          }
        }
        default -> {}
      }
    }
    stack.pop();
  }

  // ---------------------------------------------------------------------------------------------
  // Includes

  private List<String> resolveInclude(String changelog, ChangelogNode node) {
    if (node.name().equals("include")) {
      String file = node.attribute("file");
      String found =
          file == null ? null : resolve(changelog, file, node.flag("relativeToChangelogFile"));
      return found != null && files.contains(found) ? List.of(found) : List.of();
    }
    if (node.name().equals("includeAll")) {
      String path = node.attribute("path");
      if (path == null) {
        return List.of();
      }
      for (String directory : candidates(changelog, path, node.flag("relativeToChangelogFile"))) {
        String prefix =
            directory.isEmpty() || directory.endsWith("/") ? directory : directory + "/";
        List<String> found =
            files.tailSet(prefix).stream()
                .takeWhile(f -> f.startsWith(prefix))
                .filter(f -> EXTENSIONS.stream().anyMatch(f.toLowerCase(Locale.ROOT)::endsWith))
                .filter(f -> !f.equals(changelog))
                .toList();
        if (!found.isEmpty()) {
          return found;
        }
      }
    }
    return List.of();
  }

  /** The repository path of a file a changelog names, or null if none of the candidates exists. */
  private String resolve(String changelog, String file, boolean relative) {
    for (String candidate : candidates(changelog, file, relative)) {
      if (files.contains(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Where a name in a changelog may point: next to the changelog if it says so; otherwise on the
   * classpath, the {@code src/main/resources} the changelog is in; then next to the changelog, and
   * from the top of the repository.
   */
  private static Set<String> candidates(String changelog, String name, boolean relative) {
    String n = name.strip().replace('\\', '/');
    for (String prefix : List.of("classpath*:", "classpath:", "file:")) {
      if (n.startsWith(prefix)) {
        n = n.substring(prefix.length());
      }
    }
    String directory =
        changelog.contains("/") ? changelog.substring(0, changelog.lastIndexOf('/')) : "";
    int resources = changelog.indexOf(RESOURCES);
    String classpath =
        resources < 0 ? null : changelog.substring(0, resources + RESOURCES.length());
    Set<String> candidates = new LinkedHashSet<>();
    if (relative) {
      add(candidates, directory, n);
    }
    if (classpath != null) {
      add(candidates, classpath, n);
    }
    add(candidates, directory, n);
    add(candidates, "", n);
    return candidates;
  }

  private static void add(Set<String> candidates, String base, String name) {
    String joined = name.startsWith("/") || base.isEmpty() ? name : base + "/" + name;
    Deque<String> parts = new ArrayDeque<>();
    for (String part : joined.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (parts.isEmpty()) {
          return;
        }
        parts.removeLast();
      } else {
        parts.addLast(part);
      }
    }
    candidates.add(String.join("/", parts));
  }

  // ---------------------------------------------------------------------------------------------
  // Changes

  private void change(SchemaModel model, String path, String hash, ChangelogNode change) {
    Where where = new Where(path, hash, change.startLine(), change.endLine(), confidence);
    String table = table(change, "schemaName", "tableName");
    switch (change.name()) {
      case "createTable" -> {
        if (table != null) {
          model.createTable(table, where);
          columns(model, table, path, hash, change);
        }
      }
      case "addColumn" -> {
        if (table != null) {
          columns(model, table, path, hash, change);
        }
      }
      case "dropTable" -> model.dropTable(table);
      case "dropColumn" -> {
        String column = SchemaModel.name(change.attribute("columnName"));
        if (column != null) {
          model.dropColumn(table, column);
        }
        for (ChangelogNode c : change.children("column")) {
          model.dropColumn(table, SchemaModel.name(c.attribute("name")));
        }
      }
      case "renameTable" ->
          model.renameTable(
              table(change, "schemaName", "oldTableName"),
              table(change, "schemaName", "newTableName"),
              where);
      case "renameColumn" ->
          model.renameColumn(
              table,
              SchemaModel.name(change.attribute("oldColumnName")),
              SchemaModel.name(change.attribute("newColumnName")),
              where);
      case "modifyDataType" ->
          model.setType(
              table,
              SchemaModel.name(change.attribute("columnName")),
              SqlScript.type(change.attribute("newDataType")),
              where);
      case "addNotNullConstraint", "dropNotNullConstraint" ->
          model.setNullable(
              table,
              SchemaModel.name(change.attribute("columnName")),
              change.name().equals("dropNotNullConstraint"),
              where);
      case "addPrimaryKey" ->
          model.setPrimaryKey(
              table,
              SchemaModel.name(change.attribute("constraintName")),
              list(change.attribute("columnNames")),
              where);
      case "dropPrimaryKey" -> model.dropPrimaryKey(table);
      case "addUniqueConstraint" ->
          model.addUniqueKey(
              table,
              SchemaModel.name(change.attribute("constraintName")),
              list(change.attribute("columnNames")),
              where);
      case "dropUniqueConstraint" ->
          model.dropUniqueKey(
              table,
              SchemaModel.name(change.attribute("constraintName")),
              list(change.attribute("uniqueColumns")));
      case "addForeignKeyConstraint" ->
          model.addForeignKey(
              table(change, "baseTableSchemaName", "baseTableName"),
              SchemaModel.name(change.attribute("constraintName")),
              list(change.attribute("baseColumnNames")),
              table(change, "referencedTableSchemaName", "referencedTableName"),
              list(change.attribute("referencedColumnNames")),
              where);
      case "dropForeignKeyConstraint" ->
          model.dropConstraint(
              table(change, "baseTableSchemaName", "baseTableName"),
              SchemaModel.name(change.attribute("constraintName")));
      case "sql" -> {
        String sql = change.attribute("sql");
        if (sql == null) {
          sql = change.attribute(ChangelogNode.TEXT);
        }
        if (sql != null) {
          SqlScript.apply(model, sql, where, true, executor);
        }
      }
      case "sqlFile" -> {
        String file = change.attribute("path");
        String found =
            file == null ? null : resolve(path, file, change.flag("relativeToChangelogFile"));
        RepoFiles.Content content = found == null ? null : reader.apply(found);
        if (content != null) {
          used.add(found);
          SqlScript.apply(
              model,
              new String(content.bytes(), StandardCharsets.UTF_8),
              new Where(found, content.hash(), 1, 1, confidence),
              false,
              executor);
        }
      }
      default -> {}
    }
  }

  /** Adds a change's {@code column} elements to the table, each with its own lines. */
  private void columns(
      SchemaModel model, String table, String path, String hash, ChangelogNode change) {
    for (ChangelogNode column : change.children("column")) {
      String name = SchemaModel.name(column.attribute("name"));
      if (name == null) {
        continue;
      }
      Where where = new Where(path, hash, column.startLine(), column.endLine(), confidence);
      ChangelogNode constraints = column.child("constraints");
      boolean nullable =
          constraints == null || !"false".equalsIgnoreCase(constraints.attribute("nullable"));
      model.addColumn(table, name, SqlScript.type(column.attribute("type")), nullable, where);
      if (constraints == null) {
        continue;
      }
      if (constraints.flag("primaryKey")) {
        model.setPrimaryKey(
            table, SchemaModel.name(constraints.attribute("primaryKeyName")), List.of(name), where);
      }
      if (constraints.flag("unique")) {
        model.addUniqueKey(
            table,
            SchemaModel.name(constraints.attribute("uniqueConstraintName")),
            List.of(name),
            where);
      }
      String referencedTable = SchemaModel.name(constraints.attribute("referencedTableName"));
      List<String> referenced = list(constraints.attribute("referencedColumnNames"));
      String references = constraints.attribute("references");
      if (referencedTable == null && references != null) {
        // references="orders(id)"
        int open = references.indexOf('(');
        referencedTable = SchemaModel.name(open < 0 ? references : references.substring(0, open));
        referenced = open < 0 ? List.of() : list(references.substring(open + 1).replace(")", ""));
      }
      if (referencedTable != null) {
        model.addForeignKey(
            table,
            SchemaModel.name(constraints.attribute("foreignKeyName")),
            List.of(name),
            referencedTable,
            referenced,
            where);
      }
    }
  }

  /** A table name, qualified with its database schema if the change gives one. */
  private static String table(ChangelogNode change, String schemaAttribute, String tableAttribute) {
    String table = change.attribute(tableAttribute);
    if (table == null) {
      return null;
    }
    String schema = change.attribute(schemaAttribute);
    return SchemaModel.name(schema == null ? table : schema + "." + table);
  }

  /** Names from a comma-separated list. */
  private static List<String> list(String names) {
    if (names == null) {
      return List.of();
    }
    List<String> list = new ArrayList<>();
    for (String part : names.split(",")) {
      String name = SchemaModel.name(part);
      if (name != null) {
        list.add(name);
      }
    }
    return list;
  }

  /** The root changelogs among the given ones: those no other includes, in path order. */
  static List<String> roots(Set<String> changelogs, Liquibase liquibase) {
    Set<String> included = new HashSet<>();
    for (String changelog : changelogs) {
      included.addAll(liquibase.includes(changelog));
    }
    return new TreeSet<>(changelogs).stream().filter(c -> !included.contains(c)).toList();
  }
}
