package io.lantern.summarize;

import java.util.Optional;

/** Where summaries are kept, by cache key, so unchanged units are never summarized twice. */
public interface SummaryStore {

  /** The summary with the given cache key, if one was stored. */
  Optional<Summary> find(String key);

  /**
   * Stores a summary. A summary with the same key that is already stored is kept; both were made
   * from the same inputs.
   */
  void save(Summary summary);
}
