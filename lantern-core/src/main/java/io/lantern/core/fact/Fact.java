package io.lantern.core.fact;

import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fact in the core envelope. The JSON form is defined by {@code schema/fact-envelope.schema.json}
 * and written and read by {@link FactJson}.
 *
 * <p>Every fact has an origin and evidence; constructing one without either fails. The ID must
 * match the content, and attributes are held in canonical form (see {@link FactJson#normalize}).
 * Use {@link #create} to build a new fact, which derives its ID from its content.
 *
 * @param id the content-derived fact ID, see {@link FactJson#factId}
 * @param subject the entity the fact is about
 * @param predicate the relationship or attribute type, for example {@code CONSUMES} or {@code
 *     owner}
 * @param object an entity ID or a typed value
 * @param attributes further attributes as a JSON object; empty when there are none
 * @param origin where the fact came from
 * @param evidence what the fact was observed in
 * @param confidence how certain the fact is, from 0 to 1
 * @param validity the snapshot range in which the fact holds
 */
public record Fact(
    String id,
    EntityId subject,
    String predicate,
    FactObject object,
    ObjectNode attributes,
    Origin origin,
    Evidence evidence,
    double confidence,
    Validity validity) {

  private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREDICATE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

  public Fact {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(object, "object");
    Objects.requireNonNull(attributes, "attributes");
    Objects.requireNonNull(origin, "A fact must have an origin");
    Objects.requireNonNull(evidence, "A fact must have evidence");
    Objects.requireNonNull(validity, "validity");
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Invalid fact ID: '" + id + "'");
    }
    if (!PREDICATE.matcher(predicate).matches()) {
      throw new IllegalArgumentException("Invalid predicate: '" + predicate + "'");
    }
    if (!(confidence >= 0.0 && confidence <= 1.0)) {
      throw new IllegalArgumentException("Confidence must be between 0 and 1: " + confidence);
    }
    attributes = FactJson.normalize(attributes);
    String expected = FactJson.factId(subject, predicate, object, attributes, origin, evidence);
    if (!expected.equals(id)) {
      throw new IllegalArgumentException(
          "Fact ID " + id + " does not match its content (expected " + expected + ")");
    }
  }

  /** Whether the text is a valid predicate name, the form relationship and attribute types use. */
  public static boolean isValidPredicate(String predicate) {
    return predicate != null && PREDICATE.matcher(predicate).matches();
  }

  /** Creates a fact whose ID is derived from its content. */
  public static Fact create(
      EntityId subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Origin origin,
      Evidence evidence,
      double confidence,
      Validity validity) {
    Objects.requireNonNull(attributes, "attributes");
    ObjectNode normalized = FactJson.normalize(attributes);
    String id = FactJson.factId(subject, predicate, object, normalized, origin, evidence);
    return new Fact(
        id, subject, predicate, object, normalized, origin, evidence, confidence, validity);
  }

  /** A copy of the attributes, so the fact stays immutable. */
  @Override
  public ObjectNode attributes() {
    return attributes.deepCopy();
  }
}
