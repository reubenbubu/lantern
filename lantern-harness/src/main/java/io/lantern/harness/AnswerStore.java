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

  /**
   * Stores an answer. One already stored under its fingerprint is replaced and becomes the latest:
   * the harness asks again under an unchanged fingerprint only when the stored answer no longer
   * holds (a file it read changed), so the new answer must take its place.
   */
  void save(StoredAnswer answer);
}
