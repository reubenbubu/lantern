package io.lantern.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the Investigate role's verified proposals in the {@code llm_investigations} table, so they
 * are reused across runs. The investigator runs inside the transaction that records the linker's
 * facts, so each investigation is saved in a transaction of its own: one that was paid for stays
 * cached even if recording the facts fails afterwards.
 */
public final class JdbcInvestigationStore implements InvestigationStore {

  private final JdbcClient jdbc;
  private final TransactionTemplate own;

  public JdbcInvestigationStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.own = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Optional<Investigation> find(String key) {
    Objects.requireNonNull(key, "key");
    return jdbc.sql(
            """
            SELECT cache_key, scope, proposals::text AS proposals, prompt_version, provider, model
            FROM llm_investigations WHERE cache_key = :key
            """)
        .param("key", key)
        .query(JdbcInvestigationStore::mapRow)
        .optional();
  }

  @Override
  public void save(Investigation investigation) {
    Objects.requireNonNull(investigation, "investigation");
    own.executeWithoutResult(
        status ->
            jdbc.sql(
                    """
                    INSERT INTO llm_investigations (cache_key, scope, proposals, prompt_version,
                                                    provider, model)
                    VALUES (:key, :scope, CAST(:proposals AS jsonb), :promptVersion, :provider,
                            :model)
                    ON CONFLICT (cache_key) DO UPDATE
                    SET scope = EXCLUDED.scope, proposals = EXCLUDED.proposals,
                        created_at = now()
                    """)
                .param("key", investigation.key())
                .param("scope", investigation.scope())
                .param("proposals", investigation.proposals())
                .param("promptVersion", investigation.promptVersion())
                .param("provider", investigation.provider())
                .param("model", investigation.model())
                .update());
  }

  private static Investigation mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Investigation(
        rs.getString("cache_key"),
        rs.getString("scope"),
        rs.getString("proposals"),
        rs.getString("prompt_version"),
        rs.getString("provider"),
        rs.getString("model"));
  }
}
