package io.lantern.link;

import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rule runs in the table {@code link_rule_runs} (ADR 0095), written in the transaction that records
 * the rule's facts, so the two never disagree.
 */
public final class JdbcRuleRuns implements RuleRuns {

  private final JdbcClient jdbc;

  public JdbcRuleRuns(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<Run> last(String scope, String rule) {
    return jdbc.sql(
            """
            SELECT version, code, inputs, outputs, snapshot FROM link_rule_runs
            WHERE scope = :scope AND rule = :rule
            """)
        .param("scope", Objects.requireNonNull(scope, "scope"))
        .param("rule", Objects.requireNonNull(rule, "rule"))
        .query(
            (rs, n) ->
                new Run(
                    rs.getString("version"),
                    rs.getString("code"),
                    rs.getString("inputs"),
                    rs.getString("outputs"),
                    rs.getLong("snapshot")))
        .optional();
  }

  @Override
  public void record(String scope, String rule, Run run) {
    Objects.requireNonNull(run, "run");
    jdbc.sql(
            """
            INSERT INTO link_rule_runs (scope, rule, version, code, inputs, outputs, snapshot)
            VALUES (:scope, :rule, :version, :code, :inputs, :outputs, :snapshot)
            ON CONFLICT (scope, rule) DO UPDATE SET version = EXCLUDED.version, code = EXCLUDED.code,
              inputs = EXCLUDED.inputs, outputs = EXCLUDED.outputs, snapshot = EXCLUDED.snapshot
            """)
        .param("scope", Objects.requireNonNull(scope, "scope"))
        .param("rule", Objects.requireNonNull(rule, "rule"))
        .param("version", run.version())
        .param("code", run.code())
        .param("inputs", run.inputs())
        .param("outputs", run.outputs())
        .param("snapshot", run.snapshot())
        .update();
  }
}
