package io.lantern.app;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks claim identity on the facts of a real snapshot (ADR 0090), for the end-to-end tests of the
 * test sets:
 *
 * <ul>
 *   <li>Every fact seen again, at another commit, lines and file, by another version of its origin,
 *       with another confidence and its descriptive attributes left out, is the same claim.
 *   <li>No key attribute holds a fact ID, which changes with the evidence: a reference to the facts
 *       a claim comes from is lineage, so descriptive.
 *   <li>Two facts of one origin that are one claim are one thing: they differ in nothing but their
 *       evidence, or they are a runtime link ({@code INTERACTS_WITH}) seen from several places,
 *       whose call sites and routes are descriptive.
 * </ul>
 */
final class ClaimIdentityCheck {

  /** A fact ID: 64 lowercase hex digits. */
  private static final Pattern FACT_ID = Pattern.compile("\\b[0-9a-f]{64}\\b");

  private ClaimIdentityCheck() {}

  /** The problems found, empty when every fact passes. */
  static List<String> problems(Vocabulary vocabulary, Collection<Fact> facts) {
    List<String> problems = new ArrayList<>();
    Map<String, List<Fact>> byClaim = new TreeMap<>();
    for (Fact fact : facts) {
      String claim = vocabulary.claimId(fact);
      if (!claim.equals(vocabulary.claimId(seenAgain(vocabulary, fact)))) {
        problems.add("a sighting of " + fact.id() + " (" + fact.predicate() + ") is another claim");
      }
      for (String key : vocabulary.keyAttributes(fact.predicate())) {
        JsonNode value = fact.attributes().get(key);
        if (value != null && FACT_ID.matcher(value.toString()).find()) {
          problems.add(
              "key attribute " + key + " of a " + fact.predicate() + " fact holds a fact ID");
        }
      }
      byClaim.computeIfAbsent(claim + " " + fact.origin().name(), k -> new ArrayList<>()).add(fact);
    }
    for (List<Fact> sightings : byClaim.values()) {
      Fact first = sightings.getFirst();
      if (vocabulary.isA(first.predicate(), BuiltInTypes.INTERACTS_WITH)) {
        continue;
      }
      for (Fact other : sightings.subList(1, sightings.size())) {
        if (!other.attributes().equals(first.attributes())) {
          problems.add(
              "two things are one "
                  + first.predicate()
                  + " claim: "
                  + first.subject()
                  + " "
                  + first.attributes()
                  + " and "
                  + other.attributes());
        }
      }
    }
    return problems;
  }

  private static Fact seenAgain(Vocabulary vocabulary, Fact fact) {
    ObjectNode keys = fact.attributes().deepCopy();
    keys.retain(vocabulary.keyAttributes(fact.predicate()));
    Evidence evidence = fact.evidence();
    return Fact.create(
        fact.subject(),
        fact.predicate(),
        fact.object(),
        keys,
        new Origin(fact.origin().kind(), fact.origin().name(), "another-version"),
        Evidence.of(
            evidence.repo(),
            "moved/" + (evidence.path() == null ? "file" : evidence.path()),
            new Evidence.LineRange(1000, 1001),
            null),
        fact.confidence() / 2,
        Validity.from(fact.validity().from() + 1));
  }
}
