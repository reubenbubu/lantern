package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Section;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.derive.Derivation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Container and component views of a large system: detail folded away, then drawn at group level,
 * before a view is split (docs/design.md, Readable diagrams; ADR 0117; #699).
 */
class GroupViewsTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          1, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 3, "test", Instant.EPOCH);
  private static final String APP = "service:com.example:app";
  private static final String WORKER = "service:com.example:worker";

  @Test
  void topicsBetweenOneSenderAndOneReceiverAreFoldedIntoViaLabelsWhenThatMakesOneOverview() {
    TestFacts many = TestFacts.runtime();
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate("shop", APP, "PUBLISHES", "topic:kafka:e" + i, "{\"protocol\": \"Kafka\"}")
          .relate("base", WORKER, "CONSUMES", "topic:kafka:e" + i, "{\"protocol\": \"Kafka\"}");
    }

    Section containers = section(system(many.list()), "containers");

    Diagram overview = block(containers, Diagram.class);
    assertThat(overview.nodes()).noneMatch(n -> n.id().type().equals("topic"));
    assertThat(overview.edges())
        .filteredOn(e -> e.from().toString().equals(APP) && e.to().toString().equals(WORKER))
        .filteredOn(e -> "PUBLISHES".equals(e.relationship()))
        .singleElement()
        .satisfies(e -> assertThat(e.technology()).startsWith("Kafka · ").contains("e0", "orders"));
    assertThat(
            containers.blocks().stream()
                .filter(Callout.class::isInstance)
                .map(b -> text(((Callout) b).content())))
        .anyMatch(t -> t.startsWith("The topics and queues ") && t.endsWith("in full."));
    // The topics stay one page away, as elements.
    assertThat(containers.sections())
        .extracting(Section::title)
        .allMatch(t -> t.startsWith("Messaging"));
    assertThat(block(containers.sections().getFirst(), Diagram.class).nodes())
        .anyMatch(n -> n.id().toString().equals("topic:kafka:e0"));
  }

  @Test
  void aTopicWithOneSenderAndSeveralReceiversStaysAnElement() {
    TestFacts many = TestFacts.runtime();
    String other = "service:com.example:other";
    many.attribute("shop", other, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", other, "application-name", "other", "{}")
        .relate("shop", other, "CONSUMES", "topic:kafka:orders", "{\"protocol\": \"Kafka\"}");
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate("shop", APP, "PUBLISHES", "topic:kafka:e" + i, "{\"protocol\": \"Kafka\"}")
          .relate("base", WORKER, "CONSUMES", "topic:kafka:e" + i, "{\"protocol\": \"Kafka\"}");
    }

    Diagram overview = block(section(system(many.list()), "containers"), Diagram.class);

    // orders has two receivers, worker and other: a coupling point, still drawn.
    assertThat(overview.nodes()).anyMatch(n -> n.id().toString().equals("topic:kafka:orders"));
    assertThat(overview.nodes()).noneMatch(n -> n.id().toString().equals("topic:kafka:e0"));
  }

  @Test
  void anOverviewByDomainThatStillDoesNotFitStaysTheOverviewWithAPagePerDomain() {
    // Owner's decision (b) of 4 Nov 2026 on #764: at the coarsest level the code proves, lines
    // merge, then topics fold, then the view splits into the small overview and a page per domain.
    TestFacts many = TestFacts.runtime();
    String domain = "group:system/domain/pair";
    many.attribute(
            "shop",
            domain,
            "group-of",
            "domain",
            "{\"scope\": \"system\", \"basis\": \"repository\", \"signal\": \"pair\"}")
        .relate("shop", domain, "GROUPS", APP, "{\"basis\": \"repository\"}")
        .relate("shop", domain, "GROUPS", WORKER, "{\"basis\": \"repository\"}");
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      many.relate(
          "base",
          WORKER,
          "CALLS_HTTP",
          "external-system:x" + i + ".example.com",
          "{\"protocol\": \"HTTPS\", \"httpMethod\": \"GET\", \"path\": \"/\"}");
    }

    Section containers = section(system(many.list()), "containers");

    assertThat(containers.blocks())
        .filteredOn(Diagram.class::isInstance)
        .extracting(b -> ((Diagram) b).title())
        .containsExactly("Containers of base and shop, by domain");
    // The domain's page, split by concern as its units do not fit either.
    assertThat(containers.sections()).extracting(Section::id).anyMatch(id -> id.contains("pair"));
    // Each of its pages names the domain, so two domains' "requests" pages are told apart (#764).
    assertThat(containers.sections())
        .filteredOn(p -> p.id().contains("pair"))
        .isNotEmpty()
        .allSatisfy(p -> assertThat(p.title()).startsWith("pair (not named): "))
        .allSatisfy(
            p -> assertThat(block(p, Diagram.class).title()).contains(" in pair (not named): "));
  }

  @Test
  void aContainerNoLineReachesIsOnAPageOfItsOwnAndOnNoOtherContainerView() {
    // Owner's decision of 6 Nov 2026 on #764: it is not part of what the views show, whether the
    // whole system fits one view or not.
    TestFacts many = pair();
    String lone = "service:com.example:lone";
    many.attribute("shop", lone, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", lone, "application-name", "lone", "{}");
    List<Fact> facts = many.list();

    for (int target : List.of(100, 3)) {
      Section containers = section(system(facts, limits(target, 2)), "containers");

      assertThat(block(containers, Diagram.class).nodes())
          .noneMatch(n -> n.id().toString().equals(lone));
      assertThat(containers.sections())
          .filteredOn(p -> p.title().equals(C4Views.ALONE))
          .singleElement()
          .satisfies(
              p ->
                  assertThat(block(p, Diagram.class).nodes())
                      .extracting(n -> n.id().toString())
                      .contains(lone)
                      .doesNotContain(APP, WORKER));
      assertThat(diagrams(containers))
          .filteredOn(d -> !d.title().endsWith("with no connections found"))
          .allSatisfy(d -> assertThat(d.nodes()).noneMatch(n -> n.id().toString().equals(lone)));
    }
  }

  @Test
  void aUnitInNoDomainKeepsItsLinesOutsideOnAPageOfItsOwnWhenTheMapLeavesThemOff() {
    // No domain's page draws a unit in no domain, so its own page does (#764 review).
    String edge = "service:com.example:edge";
    String idp = "external-system:idp.example.com";
    TestFacts more = pair();
    more.relate("edge", edge, "CONTAINS", "module:com.example:edge")
        .attribute("edge", "module:com.example:edge", "packaging", "jar")
        .attribute("edge", edge, "deployable-unit", "spring-boot", "{}")
        .relate("edge", edge, "CALLS_HTTP", idp, "{\"protocol\": \"HTTPS\", \"path\": \"/token\"}")
        .relate(
            "edge",
            edge,
            "CALLS_HTTP",
            APP,
            "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"GET /orders\"}");
    List<Fact> facts = more.list();
    Diagram whole = block(section(system(facts, limits(100, 2)), "containers"), Diagram.class);
    Diagram map =
        block(
            section(system(facts, limits(C4Views.elements(whole) - 1, 2)), "containers"),
            Diagram.class);
    Section smaller = section(system(facts, limits(C4Views.elements(map) - 1, 2)), "containers");

    assertThat(block(smaller, Diagram.class).nodes()).noneMatch(n -> n.id().toString().equals(idp));
    assertThat(diagrams(smaller))
        .anyMatch(d -> d.nodes().stream().anyMatch(n -> n.id().toString().equals(idp)));
    assertThat(smaller.sections()).anyMatch(p -> p.title().startsWith(C4Views.NO_DOMAIN));
  }

  @Test
  void aMapOfDomainsStillTooLargeLeavesTheSystemsOutsideOffAndItsPagesDrawThem() {
    // Owner's decision of 6 Nov 2026 on #764.
    String pay = "external-system:pay.example.com";
    List<Fact> facts = pair().list();
    Diagram whole = block(section(system(facts, limits(100, 2)), "containers"), Diagram.class);
    assertThat(whole.nodes()).anyMatch(n -> n.id().toString().equals(pay));
    Diagram map =
        block(
            section(system(facts, limits(C4Views.elements(whole) - 1, 2)), "containers"),
            Diagram.class);
    assertThat(map.title()).endsWith(", by domain");
    // A map that fits keeps them.
    assertThat(map.nodes()).anyMatch(n -> n.id().toString().equals(pay));

    Section smaller = section(system(facts, limits(C4Views.elements(map) - 1, 2)), "containers");

    assertThat(block(smaller, Diagram.class).nodes()).noneMatch(n -> n.id().toString().equals(pay));
    assertThat(smaller.blocks())
        .filteredOn(Callout.class::isInstance)
        .anyMatch(
            c -> text(((Callout) c).content()).startsWith("The systems outside are left off"));
    assertThat(diagrams(smaller))
        .filteredOn(d -> d.title().contains(" in "))
        .anyMatch(d -> d.nodes().stream().anyMatch(n -> n.id().toString().equals(pay)));
  }

  @Test
  void aSystemAboveTheThresholdShowsItsDomainsInPlaceOfTheirUnitsThenAPageEach() {
    TestFacts many = TestFacts.runtime();
    String first = "group:system/domain/first";
    String second = "group:system/domain/second";
    for (String domain : List.of(first, second)) {
      many.attribute(
          "shop",
          domain,
          "group-of",
          "domain",
          "{\"scope\": \"system\", \"basis\": \"repository\", \"signal\": \"x\"}");
    }
    for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements(); i++) {
      String unit = "service:com.example:u" + i;
      many.attribute("shop", unit, "deployable-unit", "spring-boot", "{}")
          .attribute("shop", unit, "application-name", "u" + i, "{}")
          .attribute("shop", unit, "technology", "Spring Boot 3.5.0 / Java 21", "{}")
          .relate(
              "shop",
              unit,
              "CALLS_HTTP",
              "service:com.example:app",
              "{\"protocol\": \"JSON/HTTP\", \"endpoint\": \"GET /u\"}")
          .relate(
              "shop", i % 2 == 0 ? first : second, "GROUPS", unit, "{\"basis\": \"repository\"}");
    }

    Document document = system(many.list());
    Section containers = section(document, "containers");

    Diagram overview = block(containers, Diagram.class);
    assertThat(overview.title()).endsWith(", by domain");
    assertThat(overview.nodes())
        .extracting(n -> n.id().toString())
        .contains(first, second, APP, WORKER)
        .doesNotContain("service:com.example:u0");
    assertThat(C4Views.fits(overview, DiagramLimits.DEFAULT)).isTrue();
    // A domain stands for its units, at their level.
    assertThat(QualityBar.failures(document))
        .noneMatch(f -> f.check().equals(QualityBar.ONE_LEVEL));
    assertThat(containers.sections()).hasSize(2);
    Diagram firstPage = block(containers.sections().getFirst(), Diagram.class);
    assertThat(firstPage.nodes())
        .extracting(n -> n.id().toString())
        .contains("service:com.example:u0")
        .doesNotContain("service:com.example:u1");
  }

  @Test
  void aComponentViewStillTooLargeLeavesWhatItsComponentsReachOutsideOffAndItsAreasPagesDrawIt() {
    // Owner's decision of 6 Nov 2026 on #764, as for a map of domains.
    Section components = section(system(manyComponents().list(), limits(5, 2)), "components");
    Section app =
        components.sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    Diagram overview = block(app, Diagram.class);

    assertThat(overview.nodes())
        .extracting(n -> n.id().type())
        .isSubsetOf("service", "group", "type")
        .filteredOn("service"::equals)
        .hasSize(1);
    assertThat(app.blocks())
        .filteredOn(Callout.class::isInstance)
        .anyMatch(c -> text(((Callout) c).content()).startsWith("What these components reach"));
    assertThat(diagrams(app))
        .anyMatch(
            d ->
                d.nodes().stream()
                    .anyMatch(n -> n.id().toString().equals("external-system:pay.example.com")));
  }

  @Test
  void aComponentWithLinesOnlyOutsideIsOffTheOverviewThatLeavesTheOutsideOffAndOnAPageOfItsOwn() {
    // Owner's decision of 6 Nov 2026 on #764: with the outside left off, it is not part of what
    // the overview shows.
    TestFacts many = manyComponents();
    String ops = "package:com.example:app:com.example.ops";
    String job = "type:com.example.ops.Sampler";
    many.relate("shop", "module:com.example:app", "CONTAINS", ops)
        .relate("shop", ops, "CONTAINS", job)
        .relate("shop", job, "CONTAINS", "method:com.example.ops.Sampler#sample()")
        .attribute(
            "shop", "method:com.example.ops.Sampler#sample()", "scheduled", "0 * * * * *", "{}")
        // Billing calls orders, so both have a line; parts has none.
        .relate(
            "shop",
            "method:com.example.billing.C0#get()",
            "CALLS",
            "method:com.example.orders.C0#get()");
    Section app =
        section(system(many.list(), limits(5, 2)), "components").sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    assertThat(block(app, Diagram.class).nodes())
        .extracting(n -> n.id().toString())
        .doesNotContain(job);
    Section outside =
        app.sections().stream()
            .filter(p -> p.title().equals("Connected to no feature area"))
            .findFirst()
            .orElseThrow();
    assertThat(block(outside, Diagram.class).nodes())
        .extracting(n -> n.id().toString())
        .contains(job);
    // So is a feature area with no line on it; its own page draws it.
    String parts = "group:service:com.example:app/area/pkg-parts";
    assertThat(block(app, Diagram.class).nodes())
        .extracting(n -> n.id().toString())
        .doesNotContain(parts);
    assertThat(app.sections())
        .anyMatch(p -> p.entity() != null && p.entity().toString().equals(parts));
  }

  @Test
  void manyComponentsReachedOnlyFromOutsideAreSplitOntoPagesThatFit() {
    // Each job in a package of its own, so no feature folder holds them.
    TestFacts many = manyComponents();
    for (int i = 0; i < 8; i++) {
      String ops = "package:com.example:app:com.example.ops" + i;
      String job = "type:com.example.ops" + i + ".Sampler" + i;
      String run = "method:com.example.ops" + i + ".Sampler" + i + "#sample()";
      many.relate("shop", "module:com.example:app", "CONTAINS", ops)
          .relate("shop", ops, "CONTAINS", job)
          .relate("shop", job, "CONTAINS", run)
          .attribute("shop", run, "scheduled", "0 * * * * *", "{}");
    }
    DiagramLimits limits = limits(5, 2);
    Section app =
        section(system(many.list(), limits), "components").sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    List<Section> pages =
        app.sections().stream()
            .filter(p -> p.title().startsWith("Connected to no feature area"))
            .toList();
    assertThat(pages)
        .hasSizeGreaterThan(1)
        .allSatisfy(p -> assertThat(C4Views.fits(block(p, Diagram.class), limits)).isTrue());
    for (int i = 0; i < 8; i++) {
      String job = "type:com.example.ops" + i + ".Sampler" + i;
      assertThat(pages)
          .filteredOn(
              p ->
                  block(p, Diagram.class).nodes().stream()
                      .anyMatch(n -> n.id().toString().equals(job)))
          .hasSize(1);
    }
  }

  @Test
  void aUnitWithNoAreaStillTooLargeIsSplitWithWhatItReachesOutsideNotLeftOff() {
    // With no area there is no area page to draw the outside (#764 review).
    TestFacts many = TestFacts.runtime();
    String jobs = "package:com.example:app:com.example.jobs";
    many.relate("shop", "module:com.example:app", "CONTAINS", jobs);
    for (int i = 0; i < 8; i++) {
      String job = "type:com.example.jobs.Job" + i;
      many.relate("shop", jobs, "CONTAINS", job)
          .relate("shop", job, "CONTAINS", "method:com.example.jobs.Job" + i + "#run()")
          .attribute(
              "shop",
              "method:com.example.jobs.Job" + i + "#run()",
              "scheduled",
              "0 * * * * *",
              "{}");
    }
    DiagramLimits limits = limits(5, 2);
    Section app =
        section(system(many.list(), limits), "components").sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    assertThat(app.blocks())
        .filteredOn(Callout.class::isInstance)
        .noneMatch(c -> text(((Callout) c).content()).contains("each area's page"));
    assertThat(diagrams(app))
        .anyMatch(
            d ->
                d.nodes().stream()
                    .anyMatch(n -> n.id().toString().equals("external-system:pay.example.com")));
  }

  @Test
  void anAspectAcrossTheAreasIsOffTheOverviewThatLeavesTheOutsideOffWithANoteAndOnTheirPages() {
    // Owner's decision of 6 Nov 2026 on #764.
    TestFacts many = manyComponents();
    String aspect = "type:com.example.security.Guard";
    many.relate(
            "shop",
            "module:com.example:app",
            "CONTAINS",
            "package:com.example:app:com.example.security")
        .relate("shop", "package:com.example:app:com.example.security", "CONTAINS", aspect)
        .attribute("shop", aspect, "stereotype", "component")
        .attribute(
            "shop",
            aspect,
            "aspect",
            "aspect",
            "{\"advice\":[\"before\"],\"pointcuts\":[\"execution(* *(..))\"]}");
    for (String feature : List.of("orders", "billing")) {
      many.relate(
          "shop",
          aspect,
          "ADVISES",
          "method:com.example." + feature + ".C0#get()",
          "{\"advice\":\"method:x.A#check()\",\"kind\":\"before\",\"pointcut\":\"p\"}");
    }
    Section app =
        section(system(many.list(), limits(5, 2)), "components").sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    assertThat(block(app, Diagram.class).nodes())
        .extracting(n -> n.id().toString())
        .doesNotContain(aspect);
    assertThat(app.blocks())
        .filteredOn(Callout.class::isInstance)
        .anyMatch(
            c -> text(((Callout) c).content()).startsWith("Guard cuts across the feature areas"));
    // It is not taken for a component reached only from outside.
    assertThat(app.sections())
        .filteredOn(p -> p.title().startsWith("Connected to no feature area"))
        .allSatisfy(
            p ->
                assertThat(block(p, Diagram.class).nodes())
                    .noneMatch(n -> n.id().toString().equals(aspect)));
    assertThat(app.sections())
        .filteredOn(p -> p.entity() != null && p.entity().toString().endsWith("/area/pkg-orders"))
        .allSatisfy(
            p ->
                assertThat(diagrams(p))
                    .anyMatch(
                        d -> d.nodes().stream().anyMatch(n -> n.id().toString().equals(aspect))));
  }

  @Test
  void aUnitWithTooManyComponentsShowsItsFeatureAreasThenAPageEach() {
    TestFacts many = manyComponents();
    List<String> features = List.of("orders", "billing", "parts");
    Section components = section(system(many.list()), "components");

    Section app =
        components.sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();
    Diagram overview = block(app, Diagram.class);
    assertThat(overview.title()).isEqualTo("Components of shop-app, by feature area");
    assertThat(overview.nodes()).anyMatch(n -> n.id().type().equals("group"));
    assertThat(C4Views.fits(overview, DiagramLimits.DEFAULT)).isTrue();
    // The three feature folders, and a pocket of the components they leave that work together
    // (#764).
    assertThat(app.sections()).hasSize(features.size() + 1);
    assertThat(app.sections())
        .extracting(p -> p.entity().toString())
        .containsOnlyOnce("group:service:com.example:app/area/pocket-JpaOrders");
    assertThat(app.sections())
        .allSatisfy(
            area ->
                assertThat(C4Views.fits(block(area, Diagram.class), DiagramLimits.DEFAULT))
                    .isTrue());
  }

  @Test
  void anAreaPageStillTooLargeIsSplitByEntryPointEachWithWhatItCalls() {
    // docs/design.md, Readable diagrams: the last step splits by groups of entry points.
    TestFacts many = TestFacts.runtime();
    String pkg = "package:com.example:app:com.example.desk";
    many.relate("shop", "module:com.example:app", "CONTAINS", pkg);
    // Every desk calls Work, so Work is a component of its own and on each page.
    int count = 8;
    for (int i = 0; i < count; i++) {
      String controller = "type:com.example.desk.Desk" + i;
      String service = "type:com.example.desk.Work";
      many.relate("shop", pkg, "CONTAINS", controller)
          .attribute("shop", controller, "stereotype", "controller", "{\"rest\": true}")
          .relate("shop", controller, "CONTAINS", controller.replace("type:", "method:") + "#get()")
          .attribute(
              "shop",
              controller.replace("type:", "method:") + "#get()",
              "endpoint",
              "GET /d/" + i,
              "{}")
          .relate("shop", pkg, "CONTAINS", service)
          .attribute("shop", service, "stereotype", "service", "{}")
          .relate("shop", service, "CONTAINS", service.replace("type:", "method:") + "#run()")
          .relate(
              "shop",
              controller.replace("type:", "method:") + "#get()",
              "CALLS",
              service.replace("type:", "method:") + "#run()");
    }
    DiagramLimits limits = limits(4, 2);
    Section components = section(system(many.list(), limits), "components");
    Section app =
        components.sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    List<Diagram> pages = diagrams(app);
    // The desks' pages fit (the orders area's one entry point reaches more than this small limit).
    assertThat(pages)
        .filteredOn(d -> d.title().contains("from Desk"))
        .hasSizeGreaterThan(1)
        .allMatch(d -> C4Views.fits(d, limits));
    for (int i = 0; i < count; i++) {
      String controller = "type:com.example.desk.Desk" + i;
      String service = "type:com.example.desk.Work";
      assertThat(pages)
          .filteredOn(d -> d.nodes().stream().anyMatch(n -> n.id().toString().equals(controller)))
          .hasSize(1)
          .allMatch(d -> d.nodes().stream().anyMatch(n -> n.id().toString().equals(service)));
    }
    assertThat(app.sections())
        .filteredOn(p -> p.title().contains("from Desk"))
        .hasSizeGreaterThan(1)
        .allMatch(p -> p.title().startsWith("d (not named): from "));
    // A page draws only its own entry points, not the others calling what they share.
    assertThat(app.sections())
        .filteredOn(p -> p.title().contains("from Desk"))
        .allSatisfy(
            p ->
                assertThat(block(p, Diagram.class).nodes())
                    .filteredOn(n -> n.label().startsWith("Desk"))
                    .allMatch(n -> p.title().contains(n.label())));
  }

  @Test
  void anEntryPointThatAloneReachesTooMuchGoesOneStepDownPerPage() {
    // Owner's decision of 6 Nov 2026 on #764: what it calls directly, then each of those that
    // calls further on a page of its own; no kind of class is treated apart.
    TestFacts many = TestFacts.runtime();
    String pkg = "package:com.example:app:com.example.desk";
    many.relate("shop", "module:com.example:app", "CONTAINS", pkg);
    for (String entry : List.of("Big", "Small")) {
      String type = "type:com.example.desk." + entry;
      many.relate("shop", pkg, "CONTAINS", type)
          .attribute("shop", type, "stereotype", "controller", "{\"rest\": true}")
          .relate("shop", type, "CONTAINS", method(type, "get"))
          .attribute("shop", method(type, "get"), "endpoint", "GET /d/" + entry, "{}");
    }
    for (int i = 1; i <= 5; i++) {
      String service = bean(many, pkg, "S" + i);
      many.relate(
              "shop", method("type:com.example.desk.Big", "get"), "CALLS", method(service, "run"))
          .relate(
              "shop",
              method("type:com.example.desk.Small", "get"),
              "CALLS",
              method(service, "run"));
    }
    for (int i = 1; i <= 3; i++) {
      String deeper = bean(many, pkg, "T" + i);
      // Called by S1 and S2, so each is a component of its own, not a part of either.
      many.relate("shop", method("type:com.example.desk.S1", "run"), "CALLS", method(deeper, "run"))
          .relate(
              "shop", method("type:com.example.desk.S2", "run"), "CALLS", method(deeper, "run"));
    }
    DiagramLimits limits = limits(4, 2);
    Section app =
        section(system(many.list(), limits), "components").sections().stream()
            .filter(s -> s.entity() != null && s.entity().toString().equals(APP))
            .findFirst()
            .orElseThrow();

    List<Section> desk =
        app.sections().stream().filter(p -> p.title().startsWith("d (not named): ")).toList();
    assertThat(desk)
        .allSatisfy(p -> assertThat(C4Views.fits(block(p, Diagram.class), limits)).isTrue());
    assertThat(desk)
        .extracting(Section::title)
        .contains(
            "d (not named): from Big (1 of 2)",
            "d (not named): from Big (2 of 2)",
            "d (not named): what S1 calls",
            "d (not named): what S2 calls");
    Section deeper =
        desk.stream().filter(p -> p.title().endsWith("what S1 calls")).findFirst().orElseThrow();
    assertThat(block(deeper, Diagram.class).nodes())
        .extracting(Diagram.Node::label)
        .contains("S1", "T1", "T2", "T3")
        .doesNotContain("Big", "Small");
  }

  private static String method(String type, String name) {
    return type.replace("type:", "method:") + "#" + name + "()";
  }

  private static String bean(TestFacts facts, String pkg, String name) {
    String type = "type:com.example.desk." + name;
    facts
        .relate("shop", pkg, "CONTAINS", type)
        .attribute("shop", type, "stereotype", "service", "{}")
        .relate("shop", type, "CONTAINS", method(type, "run"));
    return type;
  }

  /** shop-app with three feature folders of controllers, more than one view holds. */
  private static TestFacts manyComponents() {
    TestFacts many = TestFacts.runtime();
    String module = "module:com.example:app";
    List<String> features = List.of("orders", "billing", "parts");
    for (String feature : features) {
      String pkg = "package:com.example:app:com.example." + feature;
      many.relate("shop", module, "CONTAINS", pkg);
      for (int i = 0; i <= DiagramLimits.DEFAULT.mostElements() / features.size(); i++) {
        String type = "type:com.example." + feature + ".C" + i;
        String method = "method:com.example." + feature + ".C" + i + "#get()";
        many.relate("shop", pkg, "CONTAINS", type)
            .attribute("shop", type, "stereotype", "controller", "{\"rest\": true}")
            .relate("shop", type, "CONTAINS", method)
            .attribute("shop", method, "endpoint", "GET /" + feature + "/" + i, "{}");
      }
    }

    return many;
  }

  /** app and worker in one domain; app publishes a topic only worker consumes. */
  private static TestFacts pair() {
    TestFacts facts = TestFacts.runtime();
    String domain = "group:system/domain/pair";
    facts
        .attribute(
            "shop",
            domain,
            "group-of",
            "domain",
            "{\"scope\": \"system\", \"basis\": \"repository\", \"signal\": \"pair\"}")
        .relate("shop", domain, "GROUPS", APP, "{\"basis\": \"repository\"}")
        .relate("shop", domain, "GROUPS", WORKER, "{\"basis\": \"repository\"}")
        .relate("shop", APP, "PUBLISHES", "topic:kafka:paid", "{\"protocol\": \"Kafka\"}")
        .relate("base", WORKER, "CONSUMES", "topic:kafka:paid", "{\"protocol\": \"Kafka\"}");
    return facts;
  }

  /** Limits of the given target, no tolerance, lines not limiting, and the given smallest group. */
  private static DiagramLimits limits(int target, int smallestGroup) {
    return new DiagramLimits(target, 0, 100, 0.8, 5, smallestGroup);
  }

  /** Every container diagram the section and its pages draw. */
  private static List<Diagram> diagrams(Section section) {
    List<Diagram> result = new java.util.ArrayList<>();
    section.blocks().stream()
        .filter(Diagram.class::isInstance)
        .map(Diagram.class::cast)
        .forEach(result::add);
    section.sections().forEach(s -> result.addAll(diagrams(s)));
    return result;
  }

  @Test
  void theSettingsDecideWhetherAViewIsDrawnByDomainBeforeAnythingIsFolded() {
    // Owner's decisions (a) and (b) of 4 Nov 2026 and of 5 Nov 2026 on #764: a view over the
    // limit is drawn at the coarser level the code proves first; only then are lines merged and
    // topics folded. The limit and the smallest group are settings (lantern.diagrams).
    List<Fact> facts = pair().list();
    Diagram whole = block(section(system(facts, limits(100, 2)), "containers"), Diagram.class);
    int n = C4Views.elements(whole);
    assertThat(whole.title()).doesNotEndWith(", by domain");
    assertThat(whole.nodes()).anyMatch(n2 -> n2.id().toString().equals("topic:kafka:paid"));

    // One element fewer: the domain stands for its two units, with the topic only they use, its
    // own detail, which its page draws; nothing is folded.
    Section byDomain = section(system(facts, limits(n - 1, 2)), "containers");
    Diagram overview = block(byDomain, Diagram.class);
    assertThat(overview.title()).endsWith(", by domain");
    assertThat(C4Views.elements(overview)).isLessThanOrEqualTo(n - 1);
    assertThat(overview.nodes()).noneMatch(n2 -> n2.id().toString().equals("topic:kafka:paid"));
    assertThat(byDomain.blocks()).noneMatch(Callout.class::isInstance);
    assertThat(diagrams(byDomain))
        .anyMatch(
            d -> d.nodes().stream().anyMatch(n2 -> n2.id().toString().equals("topic:kafka:paid")));

    // A smallest group of three: the pair stands for nothing, so the topic is folded instead.
    Section noDomain = section(system(facts, limits(n - 1, 3)), "containers");
    assertThat(diagrams(noDomain)).noneMatch(d -> d.title().endsWith(", by domain"));
    assertThat(block(noDomain, Diagram.class).nodes())
        .noneMatch(n2 -> n2.id().toString().equals("topic:kafka:paid"));
  }

  @Test
  void eachUnitsBoxNamesTheDomainItBelongsTo() {
    // No frame is drawn around a domain's units (owner's decision of 5 Nov 2026 on #764; #897).
    List<Fact> facts = pair().list();

    assertThat(entity(system(facts, limits(100, 2)), APP).tag())
        .isEqualTo("Service · pair (not named)");
    assertThat(entity(system(facts, limits(100, 3)), APP).tag()).isEqualTo("Service");
  }

  @Test
  void aViewStillTooLargeAtItsCoarsestLevelDrawsOneLinePerPair() {
    // Owner's decisions (b) and (c) of 4 Nov 2026 on #764: lines per pair and protocol, merged
    // into one per pair only as the first fallback.
    // A unit outside the domain the pair calls over two protocols; the systems outside are left
    // off a map this small, so the merged line is to a unit.
    String other = "service:com.example:other";
    TestFacts facts = pair();
    facts
        .attribute("shop", other, "deployable-unit", "spring-boot", "{}")
        .attribute("shop", other, "application-name", "other", "{}")
        .relate(
            "shop",
            APP,
            "CALLS_HTTP",
            other,
            "{\"protocol\": \"HTTPS\", \"endpoint\": \"GET /me\"}")
        .relate(
            "base",
            WORKER,
            "CALLS_HTTP",
            other,
            "{\"protocol\": \"gRPC\", \"endpoint\": \"GET /token\"}");
    String pair = "group:system/domain/pair";

    Diagram overview =
        block(section(system(facts.list(), limits(1, 2)), "containers"), Diagram.class);

    assertThat(overview.title()).endsWith(", by domain");
    assertThat(overview.edges())
        .filteredOn(e -> e.from().toString().equals(pair) && e.to().toString().equals(other))
        .singleElement()
        .satisfies(e -> assertThat(e.technology()).contains("HTTPS", "gRPC"));
  }

  @Test
  void mergingKeepsEachLabelAndTechnologyOnceAndAProfileOnlyIfEveryLineHasOne() {
    EntityId a = EntityId.parse("service:x:a");
    EntityId b = EntityId.parse("service:x:b");
    Diagram.Edge http =
        new Diagram.Edge("e1", a, b, "Calls GET /x", "HTTPS", "CALLS_HTTP", List.of(), List.of());
    Diagram.Edge kafka =
        new Diagram.Edge(
            "e2", a, b, "Sends paid", "Kafka", "PUBLISHES", List.of("prod"), List.of());
    Diagram.Edge back =
        new Diagram.Edge("e3", b, a, "Calls GET /y", "HTTPS", "CALLS_HTTP", List.of(), List.of());

    assertThat(C4Views.merged(List.of(http, kafka, back)))
        .extracting(
            e ->
                e.from()
                    + ">"
                    + e.to()
                    + " "
                    + e.label()
                    + " ["
                    + e.technology()
                    + "] "
                    + e.profiles())
        .containsExactly(
            "service:x:a>service:x:b Calls GET /x; Sends paid [HTTPS, Kafka] []",
            "service:x:b>service:x:a Calls GET /y [HTTPS] []");
  }

  private static Document system(List<Fact> facts, DiagramLimits limits) {
    return new Composer("1", io.lantern.derive.Vocabularies.standard(), limits)
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            Derivation.withModel(facts, 1));
  }

  private static io.lantern.core.document.DocumentEntity entity(Document document, String id) {
    return document.entities().stream()
        .filter(e -> e.id().toString().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static Document system(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.c4System("system:c4", "System", TEMPLATE),
            SNAPSHOT,
            Derivation.withModel(facts, 1));
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> T block(Section section, Class<T> type) {
    return section.blocks().stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow();
  }

  private static String text(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    inlines.forEach(i -> text.append(i instanceof TextRun run ? run.text() : ""));
    return text.toString();
  }
}
