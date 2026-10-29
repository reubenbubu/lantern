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

  /** Adds {@code subject predicate object-entity} with attributes given as JSON. */
  TestFacts relate(String repo, String subject, String predicate, String object, String json) {
    return add(
        fact(
            repo,
            subject,
            predicate,
            FactObject.entity(EntityId.parse(object)),
            (ObjectNode) FactJson.parse(json),
            Validity.from(1)));
  }

  /** Adds {@code subject predicate "value"} with attributes given as JSON. */
  TestFacts attribute(String repo, String subject, String predicate, String value, String json) {
    return add(
        fact(
            repo,
            subject,
            predicate,
            FactObject.string(value),
            (ObjectNode) FactJson.parse(json),
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

  /**
   * {@link #runtime()} with a claim of {@code shop}'s README, that the app keeps its orders in
   * MySQL, and the claim check's finding that the code contradicts it: the app uses {@code shop} on
   * {@code db}, a PostgreSQL database (ADR 0043).
   */
  static List<Fact> runtimeWithContradictedClaim() {
    TestFacts runtime = runtime();
    List<Fact> facts = new ArrayList<>(runtime.list());
    facts.addAll(
        contradictedClaim(
            "The app keeps its orders in\nMySQL.",
            3,
            "database-technology",
            "MySQL",
            List.of("PostgreSQL"),
            List.of(runtime.find("service:com.example:app", "USES_DATABASE"))));
    return facts;
  }

  /**
   * A claim of {@code shop}'s README on the line, and the {@code claim-contradiction} fact of the
   * check, as the claim checks record them (ADR 0043).
   *
   * @param stated what the claim names, or {@code null}
   */
  static List<Fact> contradictedClaim(
      String text,
      int line,
      String check,
      String stated,
      List<String> found,
      List<Fact> contradictedBy) {
    Evidence readme =
        new Evidence("shop", COMMIT, "README.md", new Evidence.LineRange(line, line), null);
    Fact claim =
        Fact.create(
            EntityId.parse("readme:shop:README.md"),
            "claim",
            FactObject.string(text),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "docs", "1"),
            readme,
            0.5,
            Validity.from(1));
    ObjectNode attributes = FactJson.newObject();
    attributes.put("claim", claim.id());
    if (stated != null) {
      attributes.put("stated", stated);
    }
    if (!found.isEmpty()) {
      var array = attributes.putArray("found");
      found.forEach(array::add);
    }
    attributes.putArray("units").add("service:com.example:app");
    var ids = attributes.putArray("facts").add(claim.id());
    var by = attributes.putArray("contradictedBy");
    for (Fact f : contradictedBy) {
      ids.add(f.id());
      ObjectNode entry = by.addObject();
      entry.put("fact", f.id());
      entry.put("subject", f.subject().toString());
      entry.put("predicate", f.predicate());
      entry.put("object", f.object().value());
      entry.put("repo", f.evidence().repo());
      entry.put("path", f.evidence().path());
    }
    Fact contradiction =
        Fact.create(
            claim.subject(),
            "claim-contradiction",
            FactObject.string(check),
            attributes,
            new Origin(Origin.Kind.STATIC, "claim-checks", "1"),
            readme,
            0.8,
            Validity.from(1));
    return List.of(claim, contradiction);
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

  /**
   * {@link #shop()} with what runs: {@code app} is a deployable unit named {@code shop-app} whose
   * database is {@code shop} on {@code db}; {@code web.Controller} serves {@code GET /orders} and,
   * only under the profile {@code prod}, calls {@code pay.example.com}; {@code web.Jobs} runs on a
   * schedule; both call {@code domain.Orders#all()}, which {@code domain.JpaOrders} implements,
   * reading the {@code orders} table and sending to the Kafka topic {@code orders}, which reaches
   * the listener of {@code worker}. The unit {@code worker} of repository {@code base} consumes
   * that topic and calls {@code GET /orders}. A call to a tax service could not be linked. The
   * repository {@code shop} has one decision record.
   */
  static TestFacts runtime() {
    String app = "service:com.example:app";
    String worker = "service:com.example:worker";
    String list = "method:com.example.web.Controller#list()";
    String all = "method:com.example.domain.JpaOrders#all()";
    String run = "method:com.example.web.Jobs#run()";
    String web = "package:com.example:app:com.example.web";
    TestFacts facts = shop();
    return facts
        .attribute("shop", app, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", app, "application-name", "shop-app", "{}")
        .attribute("shop", app, "application-name", "shop-local", "{\"profile\": \"local\"}")
        .attribute("shop", app, "technology", "Spring Boot 3.5.0 / Java 21", "{}")
        .attribute(
            "shop",
            "type:com.example.web.Controller",
            "stereotype",
            "controller",
            "{\"rest\": true}")
        .attribute("shop", list, "endpoint", "GET /orders", "{}")
        .relate("shop", web, "CONTAINS", "type:com.example.web.Jobs")
        .relate("shop", "type:com.example.web.Jobs", "CONTAINS", run)
        .attribute("shop", run, "scheduled", "0 0 * * * *", "{}")
        .relate("shop", run, "CALLS", "method:com.example.domain.Orders#all()")
        .attribute("shop", "type:com.example.domain.JpaOrders", "stereotype", "repository", "{}")
        .relate("shop", "type:com.example.domain.JpaOrders", "CONTAINS", all)
        .relate("shop", all, "READS_TABLE", "table:com.example:app:orders", "{}")
        .relate(
            "shop",
            app,
            "USES_DATABASE",
            "database:db:5432/shop",
            "{\"protocol\": \"JDBC\", \"schemas\": [\"schema:com.example:app\"]}")
        .attribute("shop", "database:db:5432/shop", "technology", "PostgreSQL", "{}")
        .relate(
            "shop",
            app,
            "CALLS_HTTP",
            "external-system:pay.example.com",
            "{\"protocol\": \"HTTPS\", \"httpMethod\": \"POST\", \"path\": \"/charges\","
                + " \"profile\": \"prod\", \"source\": \""
                + list
                + "\"}")
        .relate(
            "shop",
            app,
            "PUBLISHES",
            "topic:kafka:orders",
            "{\"protocol\": \"Kafka\", \"source\": \"" + all + "\"}")
        .attribute(
            "shop",
            app,
            "unresolved-link",
            "GET ${TAX_URL}/quotes",
            "{\"reason\": \"unresolved-placeholder\", \"unresolved\": [\"TAX_URL\"]}")
        .relate(
            "shop",
            app,
            "SENDS_MESSAGES_TO",
            worker,
            "{\"protocol\": \"Kafka\", \"destination\": \"topic:kafka:orders\", \"source\": \""
                + all
                + "\", \"target\": \"method:com.example.worker.L#on()\"}")
        .relate("base", worker, "CONTAINS", "module:com.example:worker")
        .attribute("base", "module:com.example:worker", "packaging", "jar")
        .attribute("base", worker, "deployable-unit", "spring-boot", "{}")
        .relate(
            "base",
            worker,
            "CONSUMES",
            "topic:kafka:orders",
            "{\"protocol\": \"Kafka\", \"source\": \"method:com.example.worker.L#on()\"}")
        .relate(
            "base",
            worker,
            "CALLS_HTTP",
            app,
            "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"GET /orders\", \"target\": \""
                + list
                + "\"}")
        .attribute("shop", "decision:shop:docs/adr/0001-kafka.md", "title", "Use Kafka", "{}")
        .attribute("shop", "decision:shop:docs/adr/0001-kafka.md", "decision-number", "1", "{}")
        .attribute(
            "shop", "decision:shop:docs/adr/0001-kafka.md", "decision-status", "Accepted", "{}")
        .attribute(
            "shop",
            "decision:shop:docs/adr/0001-kafka.md",
            "decision-outcome",
            "Orders go to Kafka.",
            "{}");
  }
}
