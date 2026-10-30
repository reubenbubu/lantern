package io.lantern.link;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the Investigate role's proposals in memory, for tests and runs without a database. */
public final class InMemoryInvestigationStore implements InvestigationStore {

  private final Map<String, Investigation> investigations = new ConcurrentHashMap<>();

  @Override
  public Optional<Investigation> find(String key) {
    return Optional.ofNullable(investigations.get(Objects.requireNonNull(key, "key")));
  }

  @Override
  public void save(Investigation investigation) {
    investigations.put(investigation.key(), investigation);
  }

  /** How many investigations are stored. */
  public int size() {
    return investigations.size();
  }
}
