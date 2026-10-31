package io.lantern.core.fact;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fact in the core envelope. The JSON form is defined by {@code schema/fact-envelope.schema.json}
 * and written and read by {@link FactJson}.
 *
 * <p>A fact is one observation of a claim (ADR 0091): what it claims (subject, predicate, object
 * and the type's key attributes, whose hash is the claim ID) and one sighting of it (descriptive
 * attributes, lineage, origin, evidence, confidence and validity). Every fact has an origin and
 * evidence; constructing one without either fails. The ID must match the content, and attributes
 * are held in canonical form (see {@link FactJson#normalize}). Use {@link #create} to build a new
 * fact, which derives its ID from its content.
 *
 * @param id the content-derived observation ID, see {@link FactJson#factId}
 * @param claimId the ID of the claim the fact is an observation of ({@link ClaimId}), set on every
 *     fact read from the store, or {@code null} for one not stored yet; it is not part of the ID,
 *     since the vocabulary, not the fact, says which attributes are key
 * @param subject the entity the fact is about
 * @param predicate the relationship or attribute type, for example {@code CONSUMES} or {@code
 *     owner}
 * @param object an entity ID or a typed value
 * @param attributes further attributes as a JSON object; empty when there are none
 * @param lineage the claim IDs the fact was derived from, distinct and in the order given; empty
 *     for a fact observed in the code
 * @param origin where the fact came from
 * @param evidence what the fact was observed in
 * @param confidence how certain the fact is, from 0 to 1
 * @param validity the snapshot range in which the fact holds
 */
public record Fact(
    String id,
    String claimId,
    EntityId subject,
    String predicate,
    FactObject object,
    ObjectNode attributes,
    List<String> lineage,
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
    if (claimId != null && !ID.matcher(claimId).matches()) {
      throw new IllegalArgumentException("Invalid claim ID: '" + claimId + "'");
    }
    lineage = lineage(lineage);
    if (!PREDICATE.matcher(predicate).matches()) {
      throw new IllegalArgumentException("Invalid predicate: '" + predicate + "'");
    }
    if (!(confidence >= 0.0 && confidence <= 1.0)) {
      throw new IllegalArgumentException("Confidence must be between 0 and 1: " + confidence);
    }
    attributes = FactJson.normalize(attributes);
    String expected =
        FactJson.factId(subject, predicate, object, attributes, lineage, origin, evidence);
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
    String id =
        FactJson.factId(subject, predicate, object, normalized, List.of(), origin, evidence);
    return new Fact(
        id,
        null,
        subject,
        predicate,
        object,
        normalized,
        List.of(),
        origin,
        evidence,
        confidence,
        validity);
  }

  /**
   * This fact derived from the given claims: the same fact with that lineage, under the ID its
   * content then gives.
   *
   * @param claims the claim IDs, in order; repeats are kept once
   */
  public Fact withLineage(Collection<String> claims) {
    List<String> list = lineage(List.copyOf(claims));
    String newId = FactJson.factId(subject, predicate, object, attributes, list, origin, evidence);
    return new Fact(
        newId,
        null,
        subject,
        predicate,
        object,
        attributes,
        list,
        origin,
        evidence,
        confidence,
        validity);
  }

  /** This fact with other evidence, under the ID its content then gives, not yet stored. */
  public Fact withEvidence(Evidence other) {
    String newId = FactJson.factId(subject, predicate, object, attributes, lineage, origin, other);
    return new Fact(
        newId,
        null,
        subject,
        predicate,
        object,
        attributes,
        lineage,
        origin,
        other,
        confidence,
        validity);
  }

  /** This fact as stored: with its claim ID and the given validity. */
  public Fact stored(String claim, Validity range) {
    return new Fact(
        id,
        claim,
        subject,
        predicate,
        object,
        attributes,
        lineage,
        origin,
        evidence,
        confidence,
        range);
  }

  private static List<String> lineage(List<String> claims) {
    Objects.requireNonNull(claims, "lineage");
    for (String claim : claims) {
      if (claim == null || !ID.matcher(claim).matches()) {
        throw new IllegalArgumentException("Invalid claim ID in lineage: '" + claim + "'");
      }
    }
    return List.copyOf(new LinkedHashSet<>(claims));
  }

  /** A copy of the attributes, so the fact stays immutable. */
  @Override
  public ObjectNode attributes() {
    return attributes.deepCopy();
  }
}
