package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
import java.util.List;

/** Facts shaped like the Maven and Java extractors' output, for tests. */
final class TestFacts {

  static final String COMMIT = "a".repeat(40);

  private final List<Fact> facts = new ArrayList<>();

  /** Adds {@code subject predicate object-entity}, observed in the given repository. */
  TestFacts relate(String repo, String subject, String predicate, String object) {
    facts.add(fact(repo, subject, predicate, FactObject.entity(EntityId.parse(object)), "pom.xml"));
    return this;
  }

  /** Adds {@code subject predicate "value"}, observed in the given repository. */
  TestFacts attribute(String repo, String subject, String predicate, String value) {
    facts.add(fact(repo, subject, predicate, FactObject.string(value), "pom.xml"));
    return this;
  }

  List<Fact> list() {
    return List.copyOf(facts);
  }

  static Fact fact(String repo, String subject, String predicate, FactObject object, String path) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        new Evidence(repo, COMMIT, path, null, null),
        1.0,
        Validity.from(1));
  }

  /**
   * A small repository {@code shop}: aggregator {@code module:com.example:shop} containing {@code
   * module:com.example:shop-core}, built as {@code library:com.example:shop-core}, with package
   * {@code com.example.shop} holding {@code Order} (with a member type {@code Order$Line} and a
   * method calling {@code PriceService}) and {@code PriceService}.
   */
  static TestFacts shop() {
    String repo = "shop";
    String core = "module:com.example:shop-core";
    String pkg = "package:com.example:shop-core:com.example.shop";
    return new TestFacts()
        .attribute(repo, "module:com.example:shop", "packaging", "pom")
        .relate(repo, "module:com.example:shop", "CONTAINS", core)
        .attribute(repo, core, "packaging", "jar")
        .relate(repo, "library:com.example:shop-core", "CONTAINS", core)
        .relate(repo, core, "BUILD_DEPENDS_ON", "artifact:org.slf4j:slf4j-api")
        .relate(repo, core, "CONTAINS", pkg)
        .relate(repo, pkg, "CONTAINS", "type:com.example.shop.Order")
        .relate(repo, pkg, "CONTAINS", "type:com.example.shop.PriceService")
        .attribute(repo, "type:com.example.shop.Order", "kind", "class")
        .relate(repo, "type:com.example.shop.Order", "CONTAINS", "type:com.example.shop.Order$Line")
        .relate(
            repo,
            "type:com.example.shop.Order",
            "CONTAINS",
            "method:com.example.shop.Order#total()")
        .relate(
            repo,
            "method:com.example.shop.Order#total()",
            "CALLS",
            "method:com.example.shop.PriceService#price()")
        .attribute(repo, "type:com.example.shop.Order$Line", "kind", "record")
        .attribute(repo, "type:com.example.shop.PriceService", "kind", "interface")
        .relate(
            repo,
            "type:com.example.shop.PriceService",
            "CONTAINS",
            "method:com.example.shop.PriceService#price()");
  }
}
