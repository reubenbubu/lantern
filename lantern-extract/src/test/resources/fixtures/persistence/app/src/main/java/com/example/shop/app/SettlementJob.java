package com.example.shop.app;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Component;

@Component
public class SettlementJob {

  private static final String UNSETTLED = "DELETE FROM payment WHERE settled = FALSE";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final JdbcClient client;
  private final SimpleJdbcInsert audit;

  public SettlementJob(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, JdbcClient client) {
    this.jdbc = jdbc;
    this.named = named;
    this.client = client;
    this.audit = new SimpleJdbcInsert(jdbc).withTableName("audit_log");
  }

  public void settle() {
    List<Long> paid =
        jdbc.queryForList(
            "SELECT o.id FROM orders o LEFT JOIN payment p ON p.order_id = o.id"
                + " WHERE o.status = 'PAID' AND p.id IS NULL",
            Long.class);
    for (Long orderId : paid) {
      jdbc.update("INSERT INTO payment (order_id, settled) VALUES (?, TRUE)", orderId);
    }
    jdbc.update(UNSETTLED);
    jdbc.batchUpdate("TRUNCATE TABLE settlement_tmp", "INSERT INTO settlement_tmp SELECT * FROM payment");
    named.update(
        "UPDATE orders SET status = :status WHERE id IN (SELECT order_id FROM payment)",
        java.util.Map.of("status", "SETTLED"));
    client.sql("SELECT sum(amount) FROM ledger").query(Long.class).single();
  }

  public void dynamic(String table) {
    // Not a constant: no facts.
    jdbc.update("DELETE FROM " + table);
    // A local variable that is not final is not a constant either.
    String sql = "SELECT * FROM hidden";
    jdbc.queryForList(sql);
    final String constant = "SELECT * FROM visible";
    jdbc.queryForList(constant);
    // DDL reads and writes no rows.
    jdbc.execute("CREATE TABLE scratch (id bigint)");
  }
}
