package io.lantern.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An answer cache in memory, for tests and runs without a database. Thread-safe. */
public final class InMemoryAnswerStore implements AnswerStore {

  private final Map<String, StoredAnswer> answers = new LinkedHashMap<>();
  private final List<StoredAnswer> order = new ArrayList<>();

  @Override
  public synchronized Optional<StoredAnswer> find(String fingerprint) {
    return Optional.ofNullable(answers.get(fingerprint));
  }

  @Override
  public synchronized Optional<StoredAnswer> latest(String role, String subject) {
    for (int i = order.size() - 1; i >= 0; i--) {
      StoredAnswer answer = order.get(i);
      if (answer.role().equals(role) && answer.subject().equals(subject)) {
        return Optional.of(answer);
      }
    }
    return Optional.empty();
  }

  @Override
  public synchronized void save(StoredAnswer answer) {
    if (answers.putIfAbsent(answer.fingerprint(), answer) == null) {
      order.add(answer);
    }
  }

  /** How many answers it holds. */
  public synchronized int size() {
    return answers.size();
  }
}
