package io.lantern.app;

import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmCallLog;
import io.lantern.llm.LlmConversation;
import io.lantern.llm.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores every LLM call in the {@code llm_calls} table, so spending is known across runs, and every
 * conversation with its tool loop in {@code llm_conversations} (ADR 0081).
 */
public final class JdbcLlmCallLog implements LlmCallLog {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() {};

  private final JdbcClient jdbc;

  public JdbcLlmCallLog(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void record(LlmCall call) {
    jdbc.sql(
            """
            INSERT INTO llm_calls (started_at, duration_ms, role, purpose, provider, model, outcome,
                input_tokens, output_tokens, cache_write_tokens, cache_read_tokens, cost_usd, error,
                conversation_id, turn, tool_calls)
            VALUES (:startedAt, :durationMs, :role, :purpose, :provider, :model, :outcome,
                :input, :output, :cacheWrite, :cacheRead, :cost, :error,
                :conversation, :turn, CAST(:toolCalls AS jsonb))
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
        .param("conversation", call.conversation())
        .param("turn", call.turn())
        .param("toolCalls", JSON.writeValueAsString(call.toolCalls()))
        .update();
  }

  @Override
  public void recordConversation(LlmConversation conversation) {
    jdbc.sql(
            """
            INSERT INTO llm_conversations (id, started_at, duration_ms, role, purpose, provider,
                model, turns, tool_calls, input_tokens, output_tokens, cache_write_tokens,
                cache_read_tokens, cost_usd, ending, error)
            VALUES (:id, :startedAt, :durationMs, :role, :purpose, :provider, :model, :turns,
                CAST(:toolCalls AS jsonb), :input, :output, :cacheWrite, :cacheRead, :cost,
                :ending, :error)
            """)
        .param("id", conversation.id())
        .param("startedAt", conversation.startedAt().atOffset(ZoneOffset.UTC))
        .param("durationMs", Math.max(0, conversation.duration().toMillis()))
        .param("role", conversation.role().name())
        .param("purpose", conversation.purpose())
        .param("provider", conversation.provider())
        .param("model", conversation.model())
        .param("turns", conversation.turns())
        .param("toolCalls", JSON.writeValueAsString(conversation.toolCalls()))
        .param("input", conversation.usage().input())
        .param("output", conversation.usage().output())
        .param("cacheWrite", conversation.usage().cacheWrite())
        .param("cacheRead", conversation.usage().cacheRead())
        .param("cost", conversation.costUsd())
        .param("ending", conversation.ending().name())
        .param("error", conversation.error())
        .update();
  }

  /**
   * The conversations that started at or after the given time, oldest first, each with its turns'
   * tokens, cost and tool calls from the call log.
   */
  public List<ConversationSpending> conversationsSince(Instant since) {
    // Each turn's rows, its retries included, added up in Java so no newer SQL is needed.
    Map<String, Map<Integer, TurnSpending>> turns = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT conversation_id, turn,
                   input_tokens + output_tokens + cache_write_tokens + cache_read_tokens AS tokens,
                   cost_usd, tool_calls, outcome
            FROM llm_calls
            WHERE conversation_id IS NOT NULL AND started_at >= :since
            ORDER BY conversation_id, turn, id
            """)
        .param("since", since.atOffset(ZoneOffset.UTC))
        .query(
            rs -> {
              int turn = rs.getInt("turn");
              Map<String, Integer> calls = JSON.readValue(rs.getString("tool_calls"), COUNTS);
              String outcome = rs.getString("outcome");
              TurnSpending row =
                  new TurnSpending(
                      turn,
                      rs.getLong("tokens"),
                      rs.getBigDecimal("cost_usd"),
                      calls,
                      outcome.equals("SUCCEEDED") || outcome.equals("TRUNCATED"));
              turns
                  .computeIfAbsent(rs.getString("conversation_id"), id -> new TreeMap<>())
                  .merge(turn, row, TurnSpending::plus);
            });
    return jdbc.sql(
            """
            SELECT id, role, purpose, provider, model, turns, tool_calls,
                   input_tokens + output_tokens + cache_write_tokens + cache_read_tokens AS tokens,
                   cost_usd, ending, error
            FROM llm_conversations
            WHERE started_at >= :since
            ORDER BY started_at, id
            """)
        .param("since", since.atOffset(ZoneOffset.UTC))
        .query(
            (rs, row) ->
                new ConversationSpending(
                    rs.getString("id"),
                    Role.valueOf(rs.getString("role")),
                    rs.getString("purpose"),
                    rs.getString("provider"),
                    rs.getString("model"),
                    rs.getInt("turns"),
                    JSON.readValue(rs.getString("tool_calls"), COUNTS),
                    rs.getLong("tokens"),
                    rs.getBigDecimal("cost_usd"),
                    LlmConversation.Ending.valueOf(rs.getString("ending")),
                    rs.getString("error"),
                    List.copyOf(turns.getOrDefault(rs.getString("id"), Map.of()).values())))
        .list();
  }

  /**
   * One turn of a conversation as the call log has it, its retries included.
   *
   * @param turn the turn's number, from 1
   * @param tokens all its tokens
   * @param costUsd what it cost
   * @param toolCalls the tool calls its answer asked for, by tool name
   * @param answered whether the model answered it, rather than it failing or being refused
   */
  public record TurnSpending(
      int turn, long tokens, BigDecimal costUsd, Map<String, Integer> toolCalls, boolean answered) {

    public TurnSpending {
      toolCalls = Collections.unmodifiableMap(new TreeMap<>(toolCalls));
    }

    /** This turn with another row of it, such as the attempt after a failed one. */
    TurnSpending plus(TurnSpending other) {
      Map<String, Integer> calls = new TreeMap<>(toolCalls);
      other.toolCalls.forEach((tool, n) -> calls.merge(tool, n, Integer::sum));
      return new TurnSpending(
          turn,
          tokens + other.tokens,
          costUsd.add(other.costUsd),
          calls,
          answered || other.answered);
    }
  }

  /**
   * A conversation as the call log has it.
   *
   * @param id its id
   * @param role what it was for
   * @param purpose the caller's description of it
   * @param provider the provider name
   * @param model the model identifier
   * @param turns how many turns it sent
   * @param toolCalls the tool calls the model asked for, by tool name
   * @param tokens all tokens of its turns
   * @param costUsd what its turns cost
   * @param ending how it ended
   * @param error why it stopped, or null
   * @param perTurn its turns, in order
   */
  public record ConversationSpending(
      String id,
      Role role,
      String purpose,
      String provider,
      String model,
      int turns,
      Map<String, Integer> toolCalls,
      long tokens,
      BigDecimal costUsd,
      LlmConversation.Ending ending,
      String error,
      List<TurnSpending> perTurn) {

    public ConversationSpending {
      toolCalls = Collections.unmodifiableMap(new TreeMap<>(toolCalls));
      perTurn = List.copyOf(perTurn);
    }
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
   * The calls that started at or after the given time, whatever their outcome, grouped by role,
   * provider and model, in the roles' order and then by provider and model.
   */
  public List<ModelSpending> spentByModelSince(Instant since) {
    return jdbc
        .sql(
            """
            SELECT role, provider, model, count(*) AS calls,
                   coalesce(sum(input_tokens + output_tokens + cache_write_tokens
                       + cache_read_tokens), 0) AS tokens,
                   coalesce(sum(cost_usd), 0) AS cost
            FROM llm_calls
            WHERE started_at >= :since
            GROUP BY role, provider, model
            """)
        .param("since", since.atOffset(ZoneOffset.UTC))
        .query(
            (rs, row) ->
                new ModelSpending(
                    Role.valueOf(rs.getString("role")),
                    rs.getString("provider"),
                    rs.getString("model"),
                    rs.getLong("calls"),
                    rs.getLong("tokens"),
                    rs.getBigDecimal("cost")))
        .list()
        .stream()
        .sorted(
            Comparator.comparing(ModelSpending::role)
                .thenComparing(ModelSpending::provider)
                .thenComparing(ModelSpending::model))
        .toList();
  }

  /**
   * What the calls of one role to one model used.
   *
   * @param role what the calls were for
   * @param provider the provider name
   * @param model the model identifier
   * @param calls how many calls were logged, including failed and refused ones
   * @param tokens all tokens, input of every kind plus output
   * @param costUsd US dollars
   */
  public record ModelSpending(
      Role role, String provider, String model, long calls, long tokens, BigDecimal costUsd) {}

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
