package io.lantern.harness;

import java.util.List;
import java.util.Objects;

/**
 * An item's accepted answer under its fingerprint, as {@code llm_answers} keeps it.
 *
 * @param fingerprint the item's fingerprint
 * @param role the role's name
 * @param subject the item's subject
 * @param promptVersion the role's prompt version
 * @param provider the model's provider
 * @param model the model
 * @param omitted whether the answer was {@code {"omitted": true}}, a correct answer with nothing in
 *     it
 * @param answer the accepted answer as the role stores it (JSON); {@code null} when omitted
 * @param filesRead the files the item's tools read, with their blobs; empty for an item without
 *     tools
 * @param inputs the item's fingerprint inputs
 */
public record StoredAnswer(
    String fingerprint,
    String role,
    String subject,
    String promptVersion,
    String provider,
    String model,
    boolean omitted,
    String answer,
    List<FileRead> filesRead,
    List<FingerprintInput> inputs) {

  public StoredAnswer {
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(subject, "subject");
    if (omitted != (answer == null)) {
      throw new IllegalArgumentException("An answer is omitted exactly when it holds nothing");
    }
    filesRead = List.copyOf(filesRead);
    inputs = List.copyOf(inputs);
  }
}
