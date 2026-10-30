package io.lantern.extract.sql;

import io.lantern.extract.config.UrlCredentials;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JDBC (or R2DBC) URL as a datasource binding records it: without credentials, with the database
 * technology it names and, where the URL's form gives them, the server and database. See {@code
 * docs/adr/0036-persistence-extractor.md}.
 *
 * @param url the URL without credentials: no user or password before the host, and no user,
 *     password or other secret property
 * @param technology the database's name, such as {@code PostgreSQL}, or null if the URL does not
 *     say
 * @param server the host and port as written, such as {@code shop-db:5432}, or null
 * @param database the database, service or schema name the URL names, or null
 */
public record JdbcUrl(String url, String technology, String server, String database) {

  /** Database technologies by JDBC subprotocol. */
  private static final Map<String, String> TECHNOLOGIES =
      Map.ofEntries(
          Map.entry("postgresql", "PostgreSQL"),
          Map.entry("pgsql", "PostgreSQL"),
          Map.entry("mysql", "MySQL"),
          Map.entry("mariadb", "MariaDB"),
          Map.entry("oracle", "Oracle"),
          Map.entry("sqlserver", "SQL Server"),
          Map.entry("jtds", "SQL Server"),
          Map.entry("h2", "H2"),
          Map.entry("hsqldb", "HSQLDB"),
          Map.entry("derby", "Derby"),
          Map.entry("db2", "Db2"),
          Map.entry("sqlite", "SQLite"),
          Map.entry("clickhouse", "ClickHouse"),
          Map.entry("snowflake", "Snowflake"),
          Map.entry("redshift", "Amazon Redshift"),
          Map.entry("cockroachdb", "CockroachDB"),
          Map.entry("sap", "SAP HANA"),
          Map.entry("informix-sqli", "Informix"),
          Map.entry("firebirdsql", "Firebird"));

  /** Database technologies by JDBC driver class, for a URL that does not name one. */
  private static final Map<String, String> DRIVERS =
      Map.ofEntries(
          Map.entry("org.postgresql.Driver", "PostgreSQL"),
          Map.entry("com.mysql.cj.jdbc.Driver", "MySQL"),
          Map.entry("com.mysql.jdbc.Driver", "MySQL"),
          Map.entry("org.mariadb.jdbc.Driver", "MariaDB"),
          Map.entry("oracle.jdbc.OracleDriver", "Oracle"),
          Map.entry("oracle.jdbc.driver.OracleDriver", "Oracle"),
          Map.entry("com.microsoft.sqlserver.jdbc.SQLServerDriver", "SQL Server"),
          Map.entry("net.sourceforge.jtds.jdbc.Driver", "SQL Server"),
          Map.entry("org.h2.Driver", "H2"),
          Map.entry("org.hsqldb.jdbc.JDBCDriver", "HSQLDB"),
          Map.entry("org.hsqldb.jdbcDriver", "HSQLDB"),
          Map.entry("org.apache.derby.jdbc.EmbeddedDriver", "Derby"),
          Map.entry("org.apache.derby.jdbc.ClientDriver", "Derby"),
          Map.entry("com.ibm.db2.jcc.DB2Driver", "Db2"),
          Map.entry("org.sqlite.JDBC", "SQLite"));

  /** {@code scheme:[sub:]...//}, where the authority starts. */
  private static final Pattern AUTHORITY = Pattern.compile("^([A-Za-z0-9:_+.\\-]*?)//");

  /** Oracle's {@code @//host:port/service} or {@code @host:port:SID}. */
  private static final Pattern ORACLE_TARGET =
      Pattern.compile("@(?://)?([^/:()]+(?::\\d+)?)[/:]([^/?:()]+)$");

  /**
   * Reads a URL.
   *
   * @param value the URL, possibly with placeholders that could not be resolved
   * @param driver the driver class configured with it, or null
   * @return the URL without credentials; its URL is null if a credential might be left in it
   */
  public static JdbcUrl of(String value, String driver) {
    String url = UrlCredentials.withoutCredentials(value.strip());
    String technology = technology(url);
    if (technology == null && driver != null) {
      technology = DRIVERS.get(driver.strip());
    }
    if (UrlCredentials.mayHoldCredential(url)) {
      return new JdbcUrl(null, technology, null, null);
    }
    String server = null;
    String database = null;
    String lower = url.toLowerCase(Locale.ROOT);
    if (lower.startsWith("jdbc:oracle:")) {
      Matcher m = ORACLE_TARGET.matcher(url);
      if (m.find()) {
        server = m.group(1);
        database = m.group(2);
      }
    } else if (lower.startsWith("jdbc:h2:") || lower.startsWith("jdbc:hsqldb:")) {
      String rest = url.substring(url.indexOf(':', 5) + 1);
      int end = firstOf(rest, ";?");
      String target = rest.substring(0, end);
      if (target.startsWith("tcp://") || target.startsWith("hsql://")) {
        String authority = target.substring(target.indexOf("//") + 2);
        int slash = authority.indexOf('/');
        server = slash < 0 ? authority : authority.substring(0, slash);
        database = slash < 0 ? null : authority.substring(slash + 1);
      } else {
        String name = target.substring(target.indexOf(':') + 1);
        database = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
      }
    } else {
      Matcher m = AUTHORITY.matcher(url);
      if (m.find()) {
        String rest = url.substring(m.end());
        int end = firstOf(rest, "/;?");
        server = rest.substring(0, end);
        if (end < rest.length() && rest.charAt(end) == '/') {
          String path = rest.substring(end + 1);
          database = path.substring(0, firstOf(path, "/;?"));
        }
        String named = property(rest, "databasename", "database");
        if (database == null || database.isEmpty()) {
          database = named;
        }
      }
    }
    return new JdbcUrl(url, technology, blankToNull(server), blankToNull(database));
  }

  /** The technology the URL's subprotocol names, or null. */
  private static String technology(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    List<String> parts = new ArrayList<>(List.of(lower.split(":", 4)));
    if (parts.size() < 2 || !(parts.get(0).equals("jdbc") || parts.get(0).equals("r2dbc"))) {
      return null;
    }
    int sub = 1;
    // Testcontainers (jdbc:tc:postgresql:15:///db) and R2DBC pools (r2dbc:pool:postgresql://).
    if ((parts.get(1).equals("tc") || parts.get(1).equals("pool")) && parts.size() > 2) {
      sub = 2;
    }
    String name = parts.get(sub);
    int slash = name.indexOf('/');
    if (slash >= 0) {
      name = name.substring(0, slash);
    }
    return TECHNOLOGIES.get(name);
  }

  /** A {@code ;name=value} or {@code ?name=value} property, by the first name found, or null. */
  private static String property(String text, String... names) {
    for (String name : names) {
      Matcher m =
          Pattern.compile("[;?&]" + Pattern.quote(name) + "=([^;&?]*)", Pattern.CASE_INSENSITIVE)
              .matcher(text);
      if (m.find()) {
        return m.group(1);
      }
    }
    return null;
  }

  /** The index of the first of the characters in the text, or its length if none is there. */
  private static int firstOf(String text, String chars) {
    for (int i = 0; i < text.length(); i++) {
      if (chars.indexOf(text.charAt(i)) >= 0) {
        return i;
      }
    }
    return text.length();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
