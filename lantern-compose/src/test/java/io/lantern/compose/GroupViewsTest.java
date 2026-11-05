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
  void aUnitWithTooManyComponentsShowsItsFeatureAreasThenAPageEach() {
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
    assertThat(app.sections()).hasSize(features.size());
    assertThat(app.sections())
        .allSatisfy(
            area ->
                assertThat(C4Views.fits(block(area, Diagram.class), DiagramLimits.DEFAULT))
                    .isTrue());
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
    TestFacts facts = pair();
    facts
        .relate(
            "shop",
            APP,
            "CALLS_HTTP",
            "external-system:idp.example.com",
            "{\"protocol\": \"HTTPS\", \"httpMethod\": \"GET\", \"path\": \"/me\"}")
        .relate(
            "base",
            WORKER,
            "USES_IDENTITY_PROVIDER",
            "external-system:idp.example.com",
            "{\"purpose\": \"gets-tokens\", \"protocol\": \"OAuth2 over HTTP\"}");
    String pair = "group:system/domain/pair";

    Diagram overview =
        block(section(system(facts.list(), limits(1, 2)), "containers"), Diagram.class);

    assertThat(overview.title()).endsWith(", by domain");
    assertThat(overview.edges())
        .filteredOn(
            e ->
                e.from().toString().equals(pair)
                    && e.to().toString().equals("external-system:idp.example.com"))
        .singleElement()
        .satisfies(e -> assertThat(e.technology()).contains("HTTPS", "OAuth2 over HTTP"));
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
