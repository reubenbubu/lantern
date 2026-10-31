package io.lantern.core.fact;

import java.util.Collection;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The identity of a claim: what a fact says, not where or when it was seen (ADR 0090, epic {@code
 * docs/epics/001-knowledge-build.md}, section 6). Every sighting of one thing, at any commit, by
 * any origin and at any lines, has the same claim ID; two things that differ in their subject,
 * predicate, object or a key attribute have different ones.
 */
public final class ClaimId {

  private ClaimId() {}

  /**
   * The claim ID: the SHA-256, as 64 lowercase hex characters, of the canonical JSON of the
   * subject, predicate, object and the key attributes the fact has. A key attribute that is absent
   * or {@code null} is left out, so a type that gains a key attribute keeps the IDs of facts
   * without it. Descriptive attributes, evidence, origin, confidence and validity are not part of
   * it.
   *
   * @param keyAttributes the names of the predicate's key attributes, as the vocabulary gives them
   */
  public static String of(
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Collection<String> keyAttributes) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(object, "object");
    Objects.requireNonNull(attributes, "attributes");
    Objects.requireNonNull(keyAttributes, "keyAttributes");
    ObjectNode keys = FactJson.newObject();
    for (String name : keyAttributes) {
      JsonNode value = attributes.get(name);
      if (value != null && !value.isNull()) {
        keys.set(name, value);
      }
    }
    ObjectNode content = FactJson.newObject();
    content.put("subject", subject.toString());
    content.put("predicate", predicate);
    content.set("object", FactJson.toJson(object));
    content.set("keyAttributes", keys);
    return FactJson.sha256(FactJson.canonical(content));
  }
}
