package io.lantern.extract.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.sql.SchemaModel.Where;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class SqlScriptTest {

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  @AfterAll
  static void close() {
    EXECUTOR.close();
  }

  /** The schema after the script, one line per table: columns, then keys. */
  private static List<String> schema(String script) {
    SchemaModel model = new SchemaModel();
    SqlScript.apply(model, script, new Where("s.sql", null, 1, 1, 1.0), false, EXECUTOR);
    List<String> lines = new ArrayList<>();
    for (SchemaModel.Table t : model.tables()) {
      StringBuilder line = new StringBuilder(t.name).append('(');
      List<String> columns = new ArrayList<>();
      for (SchemaModel.Column c : t.columns.values()) {
        columns.add(c.name + " " + c.type + (c.nullable ? "" : " not null"));
      }
      line.append(String.join(", ", columns)).append(')');
      if (t.primaryKey != null) {
        line.append(" pk").append(t.primaryKey.columns());
      }
      for (SchemaModel.Key k : t.uniqueKeys) {
        line.append(" uk").append(k.columns());
      }
      for (SchemaModel.ForeignKey k : t.foreignKeys) {
        line.append(" fk")
            .append(k.columns())
            .append("->")
            .append(k.table())
            .append(k.referenced());
      }
      lines.add(line.toString());
    }
    return lines;
  }

  @Test
  void splitsAtSemicolonsOutsideQuotesAndComments() {
    List<SqlScript.Part> parts =
        SqlScript.split(
            """
            -- a comment; not a statement
            INSERT INTO t VALUES ('a;b', "c;d");
            /* block;
               comment */ CREATE TABLE x (
              id INT
            );
            CREATE FUNCTION f() RETURNS int AS $body$ SELECT 1; $body$ LANGUAGE sql;
            SELECT 1
            """);

    assertThat(parts)
        .extracting(p -> p.startLine() + "-" + p.endLine())
        .containsExactly("2-2", "4-6", "7-7", "8-8");
    assertThat(parts.get(1).sql()).startsWith("CREATE TABLE x (");
  }

  @Test
  void mysqlTablesWithBackticksAndTableKeys() {
    assertThat(
            schema(
                """
                CREATE TABLE `Account` (
                  `id` bigint(20) NOT NULL AUTO_INCREMENT,
                  `name` varchar(20) DEFAULT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_name` (`name`),
                  KEY `idx_name` (`name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                ALTER TABLE `Account` CHANGE `name` `full_name` varchar(40) NOT NULL;
                ALTER TABLE `Account` MODIFY `full_name` varchar(80);
                """))
        .containsExactly(
            "Account(id bigint(20) not null, full_name varchar(80)) pk[id] uk[full_name]");
  }

  @Test
  void unquotedNamesAreLowerCaseAndQuotedOnesKeepTheirCase() {
    assertThat(schema("CREATE TABLE Orders (ID int, \"Total\" int); ALTER TABLE ORDERS DROP id;"))
        .containsExactly("orders(Total int)");
  }

  @Test
  void namedTableLevelCheckConstraintsAreSkipped() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int, n int, CONSTRAINT ck_n CHECK (n > 0), CHECK (n < 100), CONSTRAINT a_pk PRIMARY KEY (id));
                CREATE TABLE b (id int, n int);
                ALTER TABLE b ADD CONSTRAINT ck_b CHECK (n > 0);
                ALTER TABLE b ADD CHECK (n < 100);
                ALTER TABLE b ADD CONSTRAINT b_pk PRIMARY KEY (id);
                """))
        .containsExactly("a(id int not null, n int) pk[id]", "b(id int not null, n int) pk[id]");
  }

  @Test
  void keysAreAddedAndDroppedByName() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int, code varchar(5));
                CREATE TABLE b (id int, a_id int, a_code varchar(5));
                ALTER TABLE a ADD CONSTRAINT a_pk PRIMARY KEY (id);
                ALTER TABLE a ADD CONSTRAINT a_code_uk UNIQUE (code);
                ALTER TABLE b ADD CONSTRAINT b_a_fk FOREIGN KEY (a_id) REFERENCES a (id);
                ALTER TABLE b ADD CONSTRAINT b_code_fk FOREIGN KEY (a_code) REFERENCES a (code);
                ALTER TABLE b DROP CONSTRAINT b_code_fk;
                ALTER TABLE a DROP CONSTRAINT A_CODE_UK;
                ALTER TABLE b ADD PRIMARY KEY (id);
                """))
        .containsExactly(
            "a(id int not null, code varchar(5)) pk[id]",
            "b(id int not null, a_id int, a_code varchar(5)) pk[id] fk[a_id]->a[id]");
  }

  @Test
  void mysqlDropForeignKeyAndPrimaryKey() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int PRIMARY KEY);
                CREATE TABLE b (id int PRIMARY KEY, a_id int, CONSTRAINT fk_a FOREIGN KEY (a_id) REFERENCES a (id));
                ALTER TABLE b DROP FOREIGN KEY fk_a;
                ALTER TABLE b DROP PRIMARY KEY;
                """))
        .containsExactly("a(id int not null) pk[id]", "b(id int not null, a_id int)");
  }

  @Test
  void renamingATableKeepsItsKeysAndTheForeignKeysToIt() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int PRIMARY KEY);
                CREATE TABLE b (a_id int REFERENCES a);
                ALTER TABLE a RENAME TO c;
                RENAME TABLE b TO d;
                """))
        .containsExactly("c(id int not null) pk[id]", "d(a_id int) fk[a_id]->c[]");
  }

  @Test
  void droppingAColumnDropsTheKeysThatUseIt() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int PRIMARY KEY, code int UNIQUE);
                CREATE TABLE b (a_code int REFERENCES a (code), x int);
                ALTER TABLE a DROP COLUMN code;
                """))
        .containsExactly("a(id int not null) pk[id]", "b(a_code int, x int)");
  }

  @Test
  void postgresAlterColumn() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int, name text NOT NULL, code char(2));
                ALTER TABLE a ALTER COLUMN id SET NOT NULL;
                ALTER TABLE a ALTER COLUMN name DROP NOT NULL;
                ALTER TABLE a ALTER COLUMN code SET DATA TYPE varchar(3);
                ALTER TABLE a ALTER COLUMN code SET DEFAULT 'xx';
                """))
        .containsExactly("a(id int not null, name text, code varchar(3))");
  }

  @Test
  void qualifiedAndUnqualifiedNamesFindTheSameTable() {
    assertThat(
            schema(
                """
                CREATE TABLE app.orders (id int);
                ALTER TABLE orders ADD COLUMN total numeric(10, 2);
                CREATE TABLE other.orders (id int);
                ALTER TABLE orders ADD COLUMN ambiguous int;
                DROP TABLE app.orders, missing CASCADE;
                """))
        .containsExactly("other.orders(id int)");
  }

  @Test
  void statementsTheParserCannotReadAreSkippedAlone() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int);
                CREATE TABLE [dbo].[b] ([id] int);
                CREATE TEMPORARY TABLE tmp (id int);
                ALTER TABLE a ADD COLUMN b int;
                """))
        .containsExactly("a(id int, b int)");
  }

  @Test
  void createTableIfNotExistsKeepsAnExistingTable() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int, name text);
                CREATE TABLE IF NOT EXISTS a (id int);
                CREATE TABLE IF NOT EXISTS b (id int);
                """))
        .containsExactly("a(id int, name text)", "b(id int)");
  }

  @Test
  void droppingATableDropsTheForeignKeysToIt() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int PRIMARY KEY);
                CREATE TABLE b (a_id int REFERENCES a (id));
                DROP TABLE a CASCADE;
                """))
        .containsExactly("b(a_id int)");
  }

  @Test
  void keysDeclaredWithoutANameAreDroppedByPostgresDefaultNames() {
    assertThat(
            schema(
                """
                CREATE TABLE a (id int PRIMARY KEY, code int UNIQUE, x int, y int, UNIQUE (x, y));
                CREATE TABLE b (id int, a_id int REFERENCES a (id), a_code int REFERENCES a (code));
                ALTER TABLE a DROP CONSTRAINT a_pkey;
                ALTER TABLE a DROP CONSTRAINT a_x_y_key;
                ALTER TABLE b DROP CONSTRAINT b_a_id_fkey;
                """))
        .containsExactly(
            "a(id int not null, code int, x int, y int) uk[code]",
            "b(id int, a_id int, a_code int) fk[a_code]->a[code]");
  }

  @Test
  void aStatementTheParserCannotReadIsLoggedWithItsFileAndLines() {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger(SqlScript.class.getName());
    List<String> messages = new ArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            messages.add(
                java.text.MessageFormat.format(record.getMessage(), record.getParameters()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      schema("CREATE TABLE a (id int);\n\nCREATE TABLE [dbo].[b] (\n[id] int);\n");
    } finally {
      logger.removeHandler(handler);
    }

    assertThat(messages)
        .containsExactly("Skipped a statement the SQL parser cannot read, in s.sql lines 3-4");
  }
}
