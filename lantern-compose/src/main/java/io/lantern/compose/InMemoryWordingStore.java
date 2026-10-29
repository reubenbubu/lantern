package io.lantern.compose;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the wordings in memory, for tests and runs without a database. */
public final class InMemoryWordingStore implements WordingStore {

  private final Map<String, WordingAnswer> answers = new ConcurrentHashMap<>();

  @Override
  public Optional<WordingAnswer> find(String key) {
    return Optional.ofNullable(answers.get(Objects.requireNonNull(key, "key")));
  }

  @Override
  public void save(WordingAnswer answer) {
    answers.putIfAbsent(answer.key(), answer);
  }

  /** How many answers are stored. */
  public int size() {
    return answers.size();
  }
}
