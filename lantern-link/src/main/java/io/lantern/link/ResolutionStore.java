package io.lantern.link;

import java.util.Optional;

/** Where the LLM resolver's answers are cached across runs, by the hash of their prompt. */
public interface ResolutionStore {

  /** The answer stored under the key, if any. */
  Optional<Resolution> find(String key);

  /** Stores an answer; one already stored under its key is kept. */
  void save(Resolution resolution);
}
