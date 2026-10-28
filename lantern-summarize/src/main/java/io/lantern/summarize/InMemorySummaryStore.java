package io.lantern.summarize;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps summaries in memory, for tests and one-off runs. Thread-safe. */
public final class InMemorySummaryStore implements SummaryStore {

  private final Map<String, Summary> summaries = new ConcurrentHashMap<>();

  @Override
  public Optional<Summary> find(String key) {
    return Optional.ofNullable(summaries.get(Objects.requireNonNull(key, "key")));
  }

  @Override
  public void save(Summary summary) {
    Objects.requireNonNull(summary, "summary");
    summaries.putIfAbsent(summary.key(), summary);
  }

  /** How many summaries are stored. */
  public int size() {
    return summaries.size();
  }
}
