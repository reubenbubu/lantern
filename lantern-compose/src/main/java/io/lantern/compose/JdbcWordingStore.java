package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the LLM's wordings of units in the {@code unit_wordings} table, reused across runs. */
public final class JdbcWordingStore implements WordingStore {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, WordingAnswer.Item>> ITEMS =
      new TypeReference<>() {};

  private final JdbcClient jdbc;

  public JdbcWordingStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<WordingAnswer> find(String key) {
    Objects.requireNonNull(key, "key");
    return jdbc.sql(
            """
            SELECT cache_key, unit, items::text AS items, prompt_version, provider, model
            FROM unit_wordings WHERE cache_key = :key
            """)
        .param("key", key)
        .query(JdbcWordingStore::mapRow)
        .optional();
  }

  @Override
  public void save(WordingAnswer answer) {
    Objects.requireNonNull(answer, "answer");
    jdbc.sql(
            """
            INSERT INTO unit_wordings (cache_key, unit, items, prompt_version, provider, model)
            VALUES (:key, :unit, CAST(:items AS jsonb), :promptVersion, :provider, :model)
            ON CONFLICT (cache_key) DO NOTHING
            """)
        .param("key", answer.key())
        .param("unit", answer.unit().toString())
        .param("items", JSON.writeValueAsString(answer.items()))
        .param("promptVersion", answer.promptVersion())
        .param("provider", answer.provider())
        .param("model", answer.model())
        .update();
  }

  private static WordingAnswer mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new WordingAnswer(
        rs.getString("cache_key"),
        EntityId.parse(rs.getString("unit")),
        JSON.readValue(rs.getString("items"), ITEMS),
        rs.getString("prompt_version"),
        rs.getString("provider"),
        rs.getString("model"));
  }
}
