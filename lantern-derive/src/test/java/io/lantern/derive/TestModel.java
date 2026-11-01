package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/** Stored facts for the derive tests, and the model derived from them. */
final class TestModel {

  final List<Fact> facts = new ArrayList<>();

  Fact add(EntityId subject, String predicate, EntityId object) {
    return add(subject, predicate, FactObject.entity(object), FactJson.newObject());
  }

  Fact add(EntityId subject, String predicate, FactObject object) {
    return add(subject, predicate, object, FactJson.newObject());
  }

  Fact add(EntityId subject, String predicate, FactObject object, ObjectNode attributes) {
    return add(subject, predicate, object, attributes, "shop");
  }

  Fact add(
      EntityId subject, String predicate, FactObject object, ObjectNode attributes, String repo) {
    Fact fact =
        Fact.create(
                subject,
                predicate,
                object,
                attributes,
                new Origin(Origin.Kind.STATIC, "java", "1"),
                Evidence.of(repo, "src/Shop.java", new Evidence.LineRange(1, 1), null),
                1.0,
                Validity.from(1))
            .stored(claim(repo + ":" + predicate + ":" + subject + ":" + object), Validity.from(1));
    facts.add(fact);
    return fact;
  }

  /** The facts the named rule derives from the stored ones. */
  List<Fact> derived(String rule) {
    return Derivation.withModel(facts, 1).stream()
        .filter(f -> f.origin().name().equals(rule))
        .toList();
  }

  static List<Fact> about(List<Fact> derived, EntityId subject, String predicate) {
    return derived.stream()
        .filter(f -> f.subject().equals(subject) && f.predicate().equals(predicate))
        .sorted(Comparator.comparing(Fact::toString))
        .toList();
  }

  /** A claim ID standing for the claim a text names. */
  static String claim(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
