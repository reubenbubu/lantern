package io.lantern.extract.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The database a file or change set is written for, where one database's copy stands beside
 * another's: a folder named after the database, as Flyway's {@code {vendor}} locations and many
 * projects lay them out ({@code db/migration/postgresql/}); Spring's {@code schema-<platform>.sql}
 * and {@code data-<platform>.sql}; and Liquibase's {@code dbms} attribute.
 */
final class Vendors {

  /** Database technologies, as {@link JdbcUrl} names them, by the names folders and tools use. */
  private static final Map<String, String> TECHNOLOGIES =
      Map.ofEntries(
          Map.entry("postgresql", "PostgreSQL"),
          Map.entry("postgres", "PostgreSQL"),
          Map.entry("pgsql", "PostgreSQL"),
          Map.entry("mysql", "MySQL"),
          Map.entry("mariadb", "MariaDB"),
          Map.entry("h2", "H2"),
          Map.entry("hsqldb", "HSQLDB"),
          Map.entry("hsql", "HSQLDB"),
          Map.entry("oracle", "Oracle"),
          Map.entry("sqlserver", "SQL Server"),
          Map.entry("mssql", "SQL Server"),
          Map.entry("db2", "Db2"),
          Map.entry("derby", "Derby"),
          Map.entry("sqlite", "SQLite"),
          Map.entry("cockroachdb", "CockroachDB"),
          Map.entry("clickhouse", "ClickHouse"),
          Map.entry("snowflake", "Snowflake"),
          Map.entry("redshift", "Amazon Redshift"));

  /** Spring's per-platform scripts. */
  private static final Pattern PLATFORM_SCRIPT =
      Pattern.compile("(?:schema|data)-([a-z0-9]+)\\.sql");

  private Vendors() {}

  /** The technology a name means, such as {@code PostgreSQL} for {@code postgres}, or null. */
  static String technology(String name) {
    return name == null ? null : TECHNOLOGIES.get(name.strip().toLowerCase(Locale.ROOT));
  }

  /** The technology a file is written for, by its folders or name, or null if it does not say. */
  static String of(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    String[] parts = lower.split("/");
    Matcher m = PLATFORM_SCRIPT.matcher(parts[parts.length - 1]);
    if (m.matches() && TECHNOLOGIES.containsKey(m.group(1))) {
      return TECHNOLOGIES.get(m.group(1));
    }
    // Folders below src/main only, so a module named after its database does not count.
    int top = 0;
    for (int i = 0; i + 1 < parts.length; i++) {
      if (parts[i].equals("src") && parts[i + 1].equals("main")) {
        top = i + 2;
      }
    }
    for (int i = parts.length - 2; i >= top; i--) {
      String technology = TECHNOLOGIES.get(parts[i]);
      if (technology != null) {
        return technology;
      }
    }
    return null;
  }

  /**
   * The files to read when the module runs on a database: those written for another database are
   * left out, when at least one file is written for this one. If the database is not known, or no
   * file is written for it, every file is kept.
   */
  static List<String> select(List<String> files, String technology) {
    if (technology == null || files.stream().noneMatch(f -> technology.equals(of(f)))) {
      return files;
    }
    List<String> kept = new ArrayList<>();
    for (String file : files) {
      String vendor = of(file);
      if (vendor == null || vendor.equals(technology)) {
        kept.add(file);
      }
    }
    return kept;
  }

  /**
   * Whether a Liquibase {@code dbms} list lets a change run on a database: {@code all} or a list
   * naming it, and no {@code !} entry excluding it; {@code none} never. If the database is not
   * known, only {@code none} stops it.
   */
  static boolean dbms(String list, String technology) {
    if (list == null || list.isBlank()) {
      return true;
    }
    boolean included = false;
    boolean anyIncluded = false;
    for (String entry : list.split(",")) {
      String name = entry.strip().toLowerCase(Locale.ROOT);
      if (name.equals("none")) {
        return false;
      }
      if (name.isEmpty() || name.equals("all")) {
        continue;
      }
      boolean not = name.startsWith("!");
      String target = technology(not ? name.substring(1).strip() : name);
      if (not) {
        if (technology != null && technology.equals(target)) {
          return false;
        }
      } else {
        anyIncluded = true;
        included |= technology != null && technology.equals(target);
      }
    }
    return technology == null || !anyIncluded || included;
  }
}
