package io.lantern.extract.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.extract.persistence.SqlAccess.Access;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class SqlAccessTest {

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  @AfterAll
  static void close() {
    EXECUTOR.close();
  }

  @Test
  void selectReadsEveryTable() {
    assertThat(
            SqlAccess.of(
                "SELECT o.id FROM orders o LEFT JOIN payment p ON p.order_id = o.id", EXECUTOR))
        .containsExactly(
            new Access("orders", false, "select"), new Access("payment", false, "select"));
  }

  @Test
  void writesTheTargetAndReadsTheRest() {
    assertThat(SqlAccess.of("INSERT INTO payment (order_id) SELECT id FROM Orders", EXECUTOR))
        .containsExactly(
            new Access("payment", true, "insert"), new Access("orders", false, "insert"));
    assertThat(
            SqlAccess.of(
                "UPDATE orders SET status = :s WHERE id IN (SELECT order_id FROM payment)",
                EXECUTOR))
        .containsExactly(
            new Access("orders", true, "update"), new Access("payment", false, "update"));
    assertThat(SqlAccess.of("DELETE FROM sales.\"Orders\" WHERE id = ?", EXECUTOR))
        .containsExactly(new Access("sales.Orders", true, "delete"));
    assertThat(SqlAccess.of("TRUNCATE TABLE tmp", EXECUTOR))
        .containsExactly(new Access("tmp", true, "truncate"));
  }

  @Test
  void leavesOutCommonTableExpressionsDdlAndWhatCannotBeRead() {
    assertThat(SqlAccess.of("WITH x AS (SELECT * FROM a) SELECT * FROM x", EXECUTOR))
        .containsExactly(new Access("a", false, "select"));
    assertThat(SqlAccess.of("CREATE TABLE scratch (id bigint)", EXECUTOR)).isEmpty();
    assertThat(SqlAccess.of("this is not sql", EXECUTOR)).isEmpty();
  }

  @Test
  void readsSeveralStatements() {
    assertThat(SqlAccess.of("DELETE FROM a; SELECT * FROM b", EXECUTOR))
        .containsExactly(new Access("a", true, "delete"), new Access("b", false, "select"));
  }
}
