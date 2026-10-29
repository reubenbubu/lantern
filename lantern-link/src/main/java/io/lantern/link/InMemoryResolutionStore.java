package io.lantern.link;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the resolver's answers in memory, for tests and runs without a database. */
public final class InMemoryResolutionStore implements ResolutionStore {

  private final Map<String, Resolution> resolutions = new ConcurrentHashMap<>();

  @Override
  public Optional<Resolution> find(String key) {
    return Optional.ofNullable(resolutions.get(Objects.requireNonNull(key, "key")));
  }

  @Override
  public void save(Resolution resolution) {
    resolutions.putIfAbsent(resolution.key(), resolution);
  }

  /** How many answers are stored. */
  public int size() {
    return resolutions.size();
  }
}
