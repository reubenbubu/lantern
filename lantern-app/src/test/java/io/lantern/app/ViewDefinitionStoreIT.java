package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.compose.JdbcViewDefinitionStore;
import io.lantern.compose.Scope;
import io.lantern.compose.SectionKind;
import io.lantern.compose.ViewDefinition;
import io.lantern.core.document.TemplateRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * View definitions are kept in the database. The database is shared with other tests, so each test
 * uses view IDs of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ViewDefinitionStoreIT {

  private static final TemplateRef TEMPLATE = new TemplateRef("architecture-explorer", "1");

  @Autowired JdbcViewDefinitionStore store;
  @Autowired JdbcClient jdbc;

  private final String prefix = "it-" + UUID.randomUUID() + ".";

  @Test
  void savesFindsAndReplacesViewDefinitions() {
    ViewDefinition view = ViewDefinition.perRepo(prefix + "b", "shop", TEMPLATE);
    ViewDefinition other =
        new ViewDefinition(
            prefix + "a",
            "Shop for executives",
            Scope.repo("shop"),
            "executive",
            new TemplateRef("executive-brief", "1"),
            List.of(SectionKind.OVERVIEW, SectionKind.CONTEXT));

    store.save(view);
    store.save(other);

    assertThat(store.find(view.id())).contains(view);
    assertThat(store.find(prefix + "missing")).isEmpty();
    assertThat(store.list().stream().filter(v -> v.id().startsWith(prefix)))
        .containsExactly(other, view);

    ViewDefinition replaced =
        new ViewDefinition(
            view.id(),
            "New title",
            Scope.repo("base"),
            null,
            TEMPLATE,
            List.of(SectionKind.STRUCTURE));
    store.save(replaced);

    assertThat(store.find(view.id())).contains(replaced);
  }

  @Test
  void databaseRefusesADefinitionStoredUnderAnotherId() {
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO view_definitions (id, definition)"
                            + " VALUES (:id, CAST(:definition AS jsonb))")
                    .param("id", prefix + "x")
                    .param("definition", "{\"id\": \"other\"}")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
