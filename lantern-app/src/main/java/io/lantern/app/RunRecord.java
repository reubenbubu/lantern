package io.lantern.app;

import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a run used the LLM for, written as {@code run.json} next to its documents, so that a
 * scorecard can say which provider and model served each role and what the run cost (design,
 * Quality bar; ADR 0072). Every role is listed with the model it was configured with, whether or
 * not it made a call; the calls are also listed by the model that actually served them. Each role
 * also counts its conversations, their turns, tool calls by tool and endings, and every
 * conversation is listed with its turns' tokens, cost and tool calls (ADR 0081).
 */
final class RunRecord {

  /** The file name, in the snapshot's documents directory. */
  static final String FILE = "run.json";

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private RunRecord() {}

  /**
   * The record of a run.
   *
   * @param system the system the run analyzed
   * @param snapshot the snapshot the run made
   * @param models the model each role was configured with
   * @param spent the run's calls by role, provider and model
   */
  static ObjectNode of(
      String system,
      long snapshot,
      Map<Role, ModelConfig> models,
      List<JdbcLlmCallLog.ModelSpending> spent) {
    return of(system, snapshot, models, spent, List.of());
  }

  /**
   * The record of a run with its conversations.
   *
   * @param system the system the run analyzed
   * @param snapshot the snapshot the run made
   * @param models the model each role was configured with
   * @param spent the run's calls by role, provider and model
   * @param conversations the run's conversations, oldest first
   */
  static ObjectNode of(
      String system,
      long snapshot,
      Map<Role, ModelConfig> models,
      List<JdbcLlmCallLog.ModelSpending> spent,
      List<JdbcLlmCallLog.ConversationSpending> conversations) {
    Objects.requireNonNull(conversations, "conversations");
    Objects.requireNonNull(models, "models");
    Objects.requireNonNull(spent, "spent");
    ObjectNode root = MAPPER.createObjectNode();
    root.put("system", system);
    root.put("snapshot", snapshot);
    long calls = 0;
    long tokens = 0;
    BigDecimal cost = BigDecimal.ZERO;
    for (JdbcLlmCallLog.ModelSpending s : spent) {
      calls += s.calls();
      tokens += s.tokens();
      cost = cost.add(s.costUsd());
    }
    root.put("calls", calls);
    root.put("tokens", tokens);
    root.put("costUsd", cost);
    ArrayNode roles = root.putArray("roles");
    for (Role role : Role.values()) {
      ModelConfig model = models.get(role);
      if (model == null) {
        continue;
      }
      long roleCalls = 0;
      long roleTokens = 0;
      BigDecimal roleCost = BigDecimal.ZERO;
      for (JdbcLlmCallLog.ModelSpending s : spent) {
        if (s.role() == role) {
          roleCalls += s.calls();
          roleTokens += s.tokens();
          roleCost = roleCost.add(s.costUsd());
        }
      }
      ObjectNode entry =
          roles
              .addObject()
              .put("role", role.name())
              .put("provider", model.provider())
              .put("model", model.model())
              .put("calls", roleCalls)
              .put("tokens", roleTokens)
              .put("costUsd", roleCost);
      List<JdbcLlmCallLog.ConversationSpending> ofRole =
          conversations.stream().filter(c -> c.role() == role).toList();
      if (!ofRole.isEmpty()) {
        Map<String, Integer> tools = new TreeMap<>();
        Map<String, Integer> endings = new TreeMap<>();
        int turns = 0;
        int most = 0;
        for (JdbcLlmCallLog.ConversationSpending c : ofRole) {
          turns += c.turns();
          most = Math.max(most, c.turns());
          c.toolCalls().forEach((tool, n) -> tools.merge(tool, n, Integer::sum));
          endings.merge(c.ending().name(), 1, Integer::sum);
        }
        ObjectNode summary =
            entry
                .putObject("conversations")
                .put("count", ofRole.size())
                .put("turns", turns)
                .put("mostTurns", most);
        ObjectNode byTool = summary.putObject("toolCalls");
        tools.forEach(byTool::put);
        ObjectNode byEnding = summary.putObject("endings");
        endings.forEach(byEnding::put);
      }
    }
    ArrayNode byModel = root.putArray("byModel");
    for (JdbcLlmCallLog.ModelSpending s : spent) {
      byModel
          .addObject()
          .put("role", s.role().name())
          .put("provider", s.provider())
          .put("model", s.model())
          .put("calls", s.calls())
          .put("tokens", s.tokens())
          .put("costUsd", s.costUsd());
    }
    ArrayNode all = root.putArray("conversations");
    for (JdbcLlmCallLog.ConversationSpending c : conversations) {
      ObjectNode entry =
          all.addObject()
              .put("role", c.role().name())
              .put("purpose", c.purpose())
              .put("provider", c.provider())
              .put("model", c.model())
              .put("turns", c.turns());
      ObjectNode tools = entry.putObject("toolCalls");
      c.toolCalls().forEach(tools::put);
      entry.put("tokens", c.tokens()).put("costUsd", c.costUsd()).put("ending", c.ending().name());
      if (c.error() != null) {
        entry.put("error", c.error());
      }
      ArrayNode perTurn = entry.putArray("perTurn");
      for (JdbcLlmCallLog.TurnSpending t : c.perTurn()) {
        ObjectNode turn =
            perTurn
                .addObject()
                .put("turn", t.turn())
                .put("tokens", t.tokens())
                .put("costUsd", t.costUsd())
                .put("answered", t.answered());
        ObjectNode turnTools = turn.putObject("toolCalls");
        t.toolCalls().forEach(turnTools::put);
      }
    }
    return root;
  }

  /** Writes the record into the directory and returns the file. */
  static Path write(Path directory, ObjectNode record) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(FILE);
      Files.writeString(file, MAPPER.writeValueAsString(record) + "\n");
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the run record in " + directory, e);
    }
  }
}
