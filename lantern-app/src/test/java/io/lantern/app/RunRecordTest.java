package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.Capability;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RunRecordTest {

  @TempDir Path directory;

  private static ModelConfig model(String provider, String id) {
    return new ModelConfig(provider, id, EnumSet.noneOf(Capability.class), 200_000, Pricing.FREE);
  }

  private static Map<Role, ModelConfig> models(ModelSettings settings) {
    Map<Role, ModelConfig> models = new EnumMap<>(Role.class);
    for (Role role : Role.values()) {
      models.put(role, settings.forRole(role));
    }
    return models;
  }

  @Test
  void recordsEveryRolesModelAndTheRunsCost() throws IOException {
    ModelSettings settings =
        ModelSettings.byTier(model("anthropic", "haiku"), model("anthropic", "sonnet"))
            .with(Role.SUMMARIZE, model("fake", "fake-large"));
    List<JdbcLlmCallLog.ModelSpending> spent =
        List.of(
            new JdbcLlmCallLog.ModelSpending(
                Role.SUMMARIZE, "fake", "fake-large", 4, 1000, new BigDecimal("0.0400")),
            new JdbcLlmCallLog.ModelSpending(
                Role.INFER_INTENT, "anthropic", "sonnet", 2, 500, new BigDecimal("0.0150")));

    Path file = RunRecord.write(directory, RunRecord.of("shop", 7, models(settings), spent));

    assertThat(file.getFileName().toString()).isEqualTo("run.json");
    JsonNode record = JsonMapper.builder().build().readTree(Files.readString(file));
    assertThat(record.get("system").asString()).isEqualTo("shop");
    assertThat(record.get("snapshot").asLong()).isEqualTo(7);
    assertThat(record.get("calls").asLong()).isEqualTo(6);
    assertThat(record.get("tokens").asLong()).isEqualTo(1500);
    assertThat(record.get("costUsd").decimalValue()).isEqualByComparingTo("0.055");
    // Every role, with the model it was configured with, whether or not it made a call.
    assertThat(record.get("roles")).hasSize(Role.values().length);
    JsonNode summarize = record.get("roles").get(Role.SUMMARIZE.ordinal());
    assertThat(summarize.get("role").asString()).isEqualTo("SUMMARIZE");
    assertThat(summarize.get("provider").asString()).isEqualTo("fake");
    assertThat(summarize.get("model").asString()).isEqualTo("fake-large");
    assertThat(summarize.get("calls").asLong()).isEqualTo(4);
    assertThat(summarize.get("costUsd").decimalValue()).isEqualByComparingTo("0.04");
    JsonNode compose = record.get("roles").get(Role.COMPOSE.ordinal());
    assertThat(compose.get("model").asString()).isEqualTo("haiku");
    assertThat(compose.get("calls").asLong()).isZero();
    JsonNode resolve = record.get("roles").get(Role.RESOLVE.ordinal());
    assertThat(resolve.get("model").asString()).isEqualTo("sonnet");
    assertThat(record.get("byModel")).hasSize(2);
    assertThat(record.get("byModel").get(1).get("model").asString()).isEqualTo("sonnet");
  }
}
