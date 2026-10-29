package io.lantern.link;

import io.lantern.core.fact.EntityId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the resolver's answers in the {@code link_resolutions} table, so they are reused across
 * runs. The resolver runs inside the transaction that records the linker's facts, so each answer is
 * saved in a transaction of its own: an answer that was paid for stays cached even if recording the
 * facts fails afterwards.
 */
public final class JdbcResolutionStore implements ResolutionStore {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final TransactionTemplate own;

  public JdbcResolutionStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.own = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Optional<Resolution> find(String key) {
    Objects.requireNonNull(key, "key");
    return jdbc.sql(
            """
            SELECT cache_key, link, target, confidence, cited::text AS cited, rationale,
                   prompt_version, provider, model
            FROM link_resolutions WHERE cache_key = :key
            """)
        .param("key", key)
        .query(JdbcResolutionStore::mapRow)
        .optional();
  }

  @Override
  public Optional<Resolution> latest(String link) {
    Objects.requireNonNull(link, "link");
    return jdbc.sql(
            """
            SELECT cache_key, link, target, confidence, cited::text AS cited, rationale,
                   prompt_version, provider, model
            FROM link_resolutions WHERE link = :link
            ORDER BY created_at DESC, cache_key LIMIT 1
            """)
        .param("link", link)
        .query(JdbcResolutionStore::mapRow)
        .optional();
  }

  @Override
  public void save(Resolution resolution) {
    Objects.requireNonNull(resolution, "resolution");
    own.executeWithoutResult(
        status ->
            jdbc.sql(
                    """
                    INSERT INTO link_resolutions (cache_key, link, target, confidence, cited,
                                                  rationale, prompt_version, provider, model)
                    VALUES (:key, :link, :target, :confidence, CAST(:cited AS jsonb), :rationale,
                            :promptVersion, :provider, :model)
                    ON CONFLICT (cache_key) DO NOTHING
                    """)
                .param("key", resolution.key())
                .param("link", resolution.link())
                .param(
                    "target", resolution.target() == null ? null : resolution.target().toString())
                .param("confidence", resolution.confidence())
                .param("cited", JSON.writeValueAsString(resolution.cited()))
                .param("rationale", resolution.rationale())
                .param("promptVersion", resolution.promptVersion())
                .param("provider", resolution.provider())
                .param("model", resolution.model())
                .update());
  }

  private static Resolution mapRow(ResultSet rs, int rowNum) throws SQLException {
    String target = rs.getString("target");
    return new Resolution(
        rs.getString("cache_key"),
        rs.getString("link"),
        target == null ? null : EntityId.parse(target),
        rs.getString("confidence"),
        JSON.readValue(rs.getString("cited"), STRINGS),
        rs.getString("rationale"),
        rs.getString("prompt_version"),
        rs.getString("provider"),
        rs.getString("model"));
  }
}
