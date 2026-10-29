package io.lantern.compose;

import java.util.Optional;

/** Where the LLM's wordings of units are cached across runs, by the hash of their prompt. */
public interface WordingStore {

  /** The answer stored under the key, if any. */
  Optional<WordingAnswer> find(String key);

  /** Stores an answer; one already stored under its key is kept. */
  void save(WordingAnswer answer);
}
