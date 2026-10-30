package io.lantern.link;

import java.util.Optional;

/**
 * Where the Investigate role's verified proposals are cached across runs, by their prompt's hash.
 */
public interface InvestigationStore {

  /** The investigation stored under the key, if any. */
  Optional<Investigation> find(String key);

  /** Stores an investigation, replacing one stored under its key. */
  void save(Investigation investigation);
}
