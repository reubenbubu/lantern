package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/** Builds facts for composer tests. */
final class TestFacts {

  static final String COMMIT = "a".repeat(40);

  private final List<Fact> facts = new ArrayList<>();

  /** Adds {@code subject predicate object-entity}, observed in the repository. */
  TestFacts relate(String repo, String subject, String predicate, String object) {
    return add(
        fact(
            repo,
            subject,
            predicate,
            FactObject.entity(EntityId.parse(object)),
            FactJson.newObject(),
            Validity.from(1)));
  }

  /** Adds {@code module BUILD_DEPENDS_ON target} with the scope, and optional if asked. */
  TestFacts dependency(String repo, String module, String target, String scope, boolean optional) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("scope", scope);
    attributes.put("version", "1.0");
    if (optional) {
      attributes.put("optional", true);
    }
    return add(
        fact(
            repo,
            module,
            "BUILD_DEPENDS_ON",
            FactObject.entity(EntityId.parse(target)),
            attributes,
            Validity.from(1)));
  }

  /** Adds {@code subject predicate "value"}, observed in the repository. */
  TestFacts attribute(String repo, String subject, String predicate, String value) {
    return add(
        fact(
            repo,
            subject,
            predicate,
            FactObject.string(value),
            FactJson.newObject(),
            Validity.from(1)));
  }

  TestFacts add(Fact fact) {
    facts.add(fact);
    return this;
  }

  List<Fact> list() {
    return List.copyOf(facts);
  }

  /** The first fact with the subject and predicate. */
  Fact find(String subject, String predicate) {
    return facts.stream()
        .filter(f -> f.subject().toString().equals(subject) && f.predicate().equals(predicate))
        .findFirst()
        .orElseThrow();
  }

  static Fact fact(
      String repo,
      String subject,
      String predicate,
      FactObject object,
      ObjectNode attributes,
      Validity validity) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        attributes,
        new Origin(Origin.Kind.STATIC, "test", "1"),
        new Evidence(repo, COMMIT, "pom.xml", null, null),
        1.0,
        validity);
  }

  /**
   * Two repositories. {@code shop} builds the service {@code shop-app} from module {@code app},
   * with packages {@code web} and {@code domain}: {@code web.Controller} calls {@code
   * domain.Orders#all()} and {@code domain.JpaOrders} implements {@code domain.Orders}. {@code app}
   * depends on {@code common} from repository {@code base} (compile), on {@code junit} (test) and
   * on {@code jackson} (optional); {@code common} depends on {@code slf4j} (compile) and on {@code
   * assertj} (test).
   */
  static TestFacts shop() {
    String app = "module:com.example:app";
    String common = "module:com.example:common";
    String web = "package:com.example:app:com.example.web";
    String domain = "package:com.example:app:com.example.domain";
    return new TestFacts()
        .attribute("shop", app, "packaging", "jar")
        .relate("shop", "service:com.example:app", "CONTAINS", app)
        .relate("shop", app, "CONTAINS", web)
        .relate("shop", app, "CONTAINS", domain)
        .relate("shop", web, "CONTAINS", "type:com.example.web.Controller")
        .relate("shop", domain, "CONTAINS", "type:com.example.domain.Orders")
        .relate("shop", domain, "CONTAINS", "type:com.example.domain.JpaOrders")
        .relate(
            "shop",
            "type:com.example.web.Controller",
            "CONTAINS",
            "method:com.example.web.Controller#list()")
        .relate(
            "shop",
            "type:com.example.domain.Orders",
            "CONTAINS",
            "method:com.example.domain.Orders#all()")
        .relate(
            "shop",
            "method:com.example.web.Controller#list()",
            "CALLS",
            "method:com.example.domain.Orders#all()")
        .relate(
            "shop",
            "type:com.example.domain.JpaOrders",
            "IMPLEMENTS",
            "type:com.example.domain.Orders")
        .dependency("shop", app, common, "compile", false)
        .dependency("shop", app, "artifact:org.junit:junit", "test", false)
        .dependency("shop", app, "artifact:com.fasterxml:jackson", "compile", true)
        .attribute("base", common, "packaging", "jar")
        .relate("base", "library:com.example:common", "CONTAINS", common)
        .dependency("base", common, "artifact:org.slf4j:slf4j-api", "compile", false)
        .dependency("base", common, "artifact:org.assertj:assertj", "test", false);
  }
}
