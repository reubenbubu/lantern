package io.lantern.compose;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** View definitions kept in the {@code view_definitions} table, as JSON. */
public final class JdbcViewDefinitionStore implements ViewDefinitionStore {

  private final JdbcClient jdbc;

  public JdbcViewDefinitionStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void save(ViewDefinition view) {
    Objects.requireNonNull(view, "view");
    jdbc.sql(
            """
            INSERT INTO view_definitions (id, definition)
            VALUES (:id, CAST(:definition AS jsonb))
            ON CONFLICT (id) DO UPDATE SET definition = EXCLUDED.definition, updated_at = now()
            """)
        .param("id", view.id())
        .param("definition", ViewDefinitionJson.write(view))
        .update();
  }

  @Override
  public Optional<ViewDefinition> find(String id) {
    Objects.requireNonNull(id, "id");
    return jdbc.sql("SELECT definition::text FROM view_definitions WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .optional()
        .map(ViewDefinitionJson::read);
  }

  @Override
  public List<ViewDefinition> list() {
    return jdbc
        .sql("SELECT definition::text FROM view_definitions ORDER BY id")
        .query(String.class)
        .list()
        .stream()
        .map(ViewDefinitionJson::read)
        .toList();
  }
}
