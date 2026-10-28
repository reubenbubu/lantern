package io.lantern.compose;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** View definitions held in memory, for tests and tools that do not use the database. */
public final class InMemoryViewDefinitionStore implements ViewDefinitionStore {

  private final Map<String, ViewDefinition> views = new TreeMap<>();

  @Override
  public synchronized void save(ViewDefinition view) {
    Objects.requireNonNull(view, "view");
    views.put(view.id(), view);
  }

  @Override
  public synchronized Optional<ViewDefinition> find(String id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(views.get(id));
  }

  @Override
  public synchronized List<ViewDefinition> list() {
    return List.copyOf(views.values());
  }
}
