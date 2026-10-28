package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Keeps summaries in the {@code summaries} table, so they are reused across runs. */
public final class JdbcSummaryStore implements SummaryStore {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcClient jdbc;

  public JdbcSummaryStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<Summary> find(String key) {
    Objects.requireNonNull(key, "key");
    return jdbc.sql(
            """
            SELECT cache_key, entity, level, text, cited_facts::text AS cited_facts,
                   cited_summaries::text AS cited_summaries, input_facts::text AS input_facts,
                   input_summaries::text AS input_summaries, prompt_version, provider, model
            FROM summaries WHERE cache_key = :key
            """)
        .param("key", key)
        .query(JdbcSummaryStore::mapRow)
        .optional();
  }

  @Override
  public void save(Summary summary) {
    Objects.requireNonNull(summary, "summary");
    jdbc.sql(
            """
            INSERT INTO summaries (cache_key, entity, level, text, cited_facts, cited_summaries,
                                   input_facts, input_summaries, prompt_version, provider, model)
            VALUES (:key, :entity, :level, :text, CAST(:citedFacts AS jsonb),
                    CAST(:citedSummaries AS jsonb), CAST(:inputFacts AS jsonb),
                    CAST(:inputSummaries AS jsonb), :promptVersion, :provider, :model)
            ON CONFLICT (cache_key) DO NOTHING
            """)
        .param("key", summary.key())
        .param("entity", summary.entity().toString())
        .param("level", summary.level().name())
        .param("text", summary.text())
        .param("citedFacts", JSON.writeValueAsString(summary.citedFacts()))
        .param("citedSummaries", JSON.writeValueAsString(summary.citedSummaries()))
        .param("inputFacts", JSON.writeValueAsString(summary.inputFacts()))
        .param("inputSummaries", JSON.writeValueAsString(summary.inputSummaries()))
        .param("promptVersion", summary.promptVersion())
        .param("provider", summary.provider())
        .param("model", summary.model())
        .update();
  }

  private static Summary mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Summary(
        rs.getString("cache_key"),
        EntityId.parse(rs.getString("entity")),
        SummaryLevel.valueOf(rs.getString("level")),
        rs.getString("text"),
        strings(rs.getString("cited_facts")),
        strings(rs.getString("cited_summaries")),
        strings(rs.getString("input_facts")),
        strings(rs.getString("input_summaries")),
        rs.getString("prompt_version"),
        rs.getString("provider"),
        rs.getString("model"));
  }

  private static List<String> strings(String json) {
    return JSON.readValue(json, STRINGS);
  }
}
