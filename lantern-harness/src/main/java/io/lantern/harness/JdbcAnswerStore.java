package io.lantern.harness;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Keeps accepted answers in the {@code llm_answers} table, so they are reused across runs. */
public final class JdbcAnswerStore implements AnswerStore {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String COLUMNS =
      """
      fingerprint, role, subject, prompt_version, provider, model, omitted, answer::text AS answer,
      files_read::text AS files_read, inputs::text AS inputs
      """;

  private final JdbcClient jdbc;

  public JdbcAnswerStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<StoredAnswer> find(String fingerprint) {
    Objects.requireNonNull(fingerprint, "fingerprint");
    return jdbc.sql("SELECT " + COLUMNS + " FROM llm_answers WHERE fingerprint = :fingerprint")
        .param("fingerprint", fingerprint)
        .query(JdbcAnswerStore::mapRow)
        .optional();
  }

  @Override
  public Optional<StoredAnswer> latest(String role, String subject) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM llm_answers WHERE role = :role AND subject = :subject"
                + " ORDER BY seq DESC LIMIT 1")
        .param("role", role)
        .param("subject", subject)
        .query(JdbcAnswerStore::mapRow)
        .optional();
  }

  @Override
  public void save(StoredAnswer answer) {
    Objects.requireNonNull(answer, "answer");
    ArrayNode files = JSON.createArrayNode();
    answer
        .filesRead()
        .forEach(
            f ->
                files
                    .addObject()
                    .put("repo", f.repo())
                    .put("path", f.path())
                    .put("blob", f.blob()));
    ArrayNode inputs = JSON.createArrayNode();
    answer
        .inputs()
        .forEach(
            i ->
                inputs
                    .addObject()
                    .put("kind", i.kind().name())
                    .put("id", i.id())
                    .put("repo", i.repo())
                    .put("path", i.path()));
    jdbc.sql(
            """
            INSERT INTO llm_answers (fingerprint, role, subject, prompt_version, provider, model,
                                     omitted, answer, files_read, inputs)
            VALUES (:fingerprint, :role, :subject, :promptVersion, :provider, :model, :omitted,
                    CAST(:answer AS jsonb), CAST(:files AS jsonb), CAST(:inputs AS jsonb))
            ON CONFLICT (fingerprint) DO NOTHING
            """)
        .param("fingerprint", answer.fingerprint())
        .param("role", answer.role())
        .param("subject", answer.subject())
        .param("promptVersion", answer.promptVersion())
        .param("provider", answer.provider())
        .param("model", answer.model())
        .param("omitted", answer.omitted())
        .param("answer", answer.answer())
        .param("files", JSON.writeValueAsString(files))
        .param("inputs", JSON.writeValueAsString(inputs))
        .update();
  }

  private static StoredAnswer mapRow(ResultSet row, int number) throws SQLException {
    List<FileRead> files = new ArrayList<>();
    for (JsonNode file : JSON.readTree(row.getString("files_read"))) {
      files.add(
          new FileRead(
              file.get("repo").asString(),
              file.get("path").asString(),
              file.get("blob").asString()));
    }
    List<FingerprintInput> inputs = new ArrayList<>();
    for (JsonNode input : JSON.readTree(row.getString("inputs"))) {
      inputs.add(
          new FingerprintInput(
              FingerprintInput.Kind.valueOf(input.get("kind").asString()),
              input.get("id").asString(),
              input.get("repo").asString(),
              input.get("path").asString()));
    }
    return new StoredAnswer(
        row.getString("fingerprint"),
        row.getString("role"),
        row.getString("subject"),
        row.getString("prompt_version"),
        row.getString("provider"),
        row.getString("model"),
        row.getBoolean("omitted"),
        row.getString("answer"),
        files,
        inputs);
  }
}
