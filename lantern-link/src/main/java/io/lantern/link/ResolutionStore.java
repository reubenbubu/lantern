package io.lantern.link;

import java.util.Optional;

/** Where the LLM resolver's answers are cached across runs, by the hash of their prompt. */
public interface ResolutionStore {

  /** The answer stored under the key, if any. */
  Optional<Resolution> find(String key);

  /** The answer stored last for the link with that stable ID, whatever its key, if any. */
  Optional<Resolution> latest(String link);

  /** Stores an answer; one already stored under its key is kept. */
  void save(Resolution resolution);
}
