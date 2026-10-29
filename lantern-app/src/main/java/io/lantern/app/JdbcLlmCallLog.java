package io.lantern.app;

import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmCallLog;
import io.lantern.llm.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Stores every LLM call in the {@code llm_calls} table, so spending is known across runs. */
public final class JdbcLlmCallLog implements LlmCallLog {

  private final JdbcClient jdbc;

  public JdbcLlmCallLog(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void record(LlmCall call) {
    jdbc.sql(
            """
            INSERT INTO llm_calls (started_at, duration_ms, role, purpose, provider, model, outcome,
                input_tokens, output_tokens, cache_write_tokens, cache_read_tokens, cost_usd, error)
            VALUES (:startedAt, :durationMs, :role, :purpose, :provider, :model, :outcome,
                :input, :output, :cacheWrite, :cacheRead, :cost, :error)
            """)
        .param("startedAt", call.startedAt().atOffset(ZoneOffset.UTC))
        .param("durationMs", Math.max(0, call.duration().toMillis()))
        .param("role", call.role().name())
        .param("purpose", call.purpose())
        .param("provider", call.provider())
        .param("model", call.model())
        .param("outcome", call.outcome().name())
        .param("input", call.usage().input())
        .param("output", call.usage().output())
        .param("cacheWrite", call.usage().cacheWrite())
        .param("cacheRead", call.usage().cacheRead())
        .param("cost", call.costUsd())
        .param("error", call.error())
        .update();
  }

  /**
   * How many calls started at or after the given time, whatever their outcome, by role, and what
   * they used in tokens and US dollars.
   */
  public Spending spentSince(Instant since) {
    Map<Role, Long> byRole = new EnumMap<>(Role.class);
    long[] totals = new long[2];
    BigDecimal[] cost = {BigDecimal.ZERO};
    jdbc.sql(
            """
            SELECT role, count(*) AS calls,
                   coalesce(sum(input_tokens + output_tokens + cache_write_tokens
                       + cache_read_tokens), 0) AS tokens,
                   coalesce(sum(cost_usd), 0) AS cost
            FROM llm_calls
            WHERE started_at >= :since
            GROUP BY role
            """)
        .param("since", since.atOffset(ZoneOffset.UTC))
        .query(
            rs -> {
              long calls = rs.getLong("calls");
              byRole.put(Role.valueOf(rs.getString("role")), calls);
              totals[0] += calls;
              totals[1] += rs.getLong("tokens");
              cost[0] = cost[0].add(rs.getBigDecimal("cost"));
            });
    return new Spending(totals[0], totals[1], cost[0], byRole);
  }

  /**
   * Tokens and money used by a set of calls.
   *
   * @param calls how many calls were logged, including failed and refused ones
   * @param tokens all tokens, input of every kind plus output
   * @param costUsd US dollars
   * @param callsByRole how many of the calls were logged under each role, in the roles' order; a
   *     role without calls is left out
   */
  public record Spending(long calls, long tokens, BigDecimal costUsd, Map<Role, Long> callsByRole) {

    public Spending {
      Map<Role, Long> copy = new EnumMap<>(Role.class);
      copy.putAll(callsByRole);
      callsByRole = Collections.unmodifiableMap(copy);
    }
  }
}
