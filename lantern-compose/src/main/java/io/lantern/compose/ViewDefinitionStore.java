package io.lantern.compose;

import java.util.List;
import java.util.Optional;

/** Where view definitions are kept, by view ID. */
public interface ViewDefinitionStore {

  /** Stores a view definition, replacing the one with the same ID if there is one. */
  void save(ViewDefinition view);

  /** The view definition with the ID, if any. */
  Optional<ViewDefinition> find(String id);

  /** All view definitions, sorted by ID. */
  List<ViewDefinition> list();
}
