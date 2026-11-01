package io.lantern.harness;

import java.util.Optional;

/**
 * The only answer cache (knowledge-build epic, section 10): accepted answers by item fingerprint,
 * {@code llm_answers} in the app.
 */
public interface AnswerStore {

  /** The answer stored under the fingerprint, if any. */
  Optional<StoredAnswer> find(String fingerprint);

  /** The answer stored last for the role's subject, whatever its fingerprint, if any. */
  Optional<StoredAnswer> latest(String role, String subject);

  /** Stores an answer; one already stored under its fingerprint is kept. */
  void save(StoredAnswer answer);
}
