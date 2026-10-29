package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.Inline;
import io.lantern.core.document.ListBlock;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Validity;
import io.lantern.core.snapshot.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Executive Brief's own sections: headline numbers and risks (ADR 0015), blast radius and
 * technology currency (ADR 0050).
 */
class ExecutiveBriefTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("executive-brief", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final String APP = "module:com.example:app";
  private static final String TOOLS = "module:com.example:tools";

  private static Document compose(List<Fact> facts) {
    return new Composer("1")
        .compose(
            ViewDefinition.executiveBrief("shop.brief", "shop", TEMPLATE),
            SNAPSHOT,
            facts,
            Map.of());
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(
            i ->
                switch (i) {
                  case TextRun t -> t.text();
                  case CrossReference r -> r.text();
                  default -> "";
                })
        .collect(Collectors.joining());
  }

  /** {@code module BUILD_DEPENDS_ON target} in compile scope at the version. */
  private static Fact dependency(String module, String target, String version) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("scope", "compile");
    attributes.put("version", version);
    return TestFacts.fact(
        "shop",
        module,
        "BUILD_DEPENDS_ON",
        FactObject.entity(EntityId.parse(target)),
        attributes,
        Validity.from(1));
  }

  /**
   * The shop facts with every risk: a second module {@code tools} in {@code shop}; {@code app}
   * depends on {@code tools} at a snapshot version (its own repository, so no risk), on {@code
   * common} of {@code base} and on {@code snap} at snapshot versions; {@code tools} uses {@code
   * jackson} at another version than {@code app}; and {@code domain.Orders} calls back into {@code
   * web}, closing a package cycle.
   */
  private static List<Fact> risky() {
    List<Fact> facts = new ArrayList<>(TestFacts.shop().list());
    facts.addAll(
        new TestFacts()
            .attribute("shop", TOOLS, "packaging", "jar")
            .relate("shop", "library:com.example:tools", "CONTAINS", TOOLS)
            .add(dependency(APP, TOOLS, "1.0-SNAPSHOT"))
            .add(dependency(APP, "module:com.example:common", "2.0-SNAPSHOT"))
            .add(dependency(APP, "artifact:org.example:snap", "0.1-SNAPSHOT"))
            .add(dependency(TOOLS, "artifact:com.fasterxml:jackson", "2.0"))
            .relate(
                "shop",
                "method:com.example.domain.Orders#all()",
                "CALLS",
                "method:com.example.web.Controller#list()")
            .list());
    return facts;
  }

  @Test
  void hasTheBriefsSectionsInOrder() {
    Document document = compose(TestFacts.shop().list());

    assertThat(document.template()).isEqualTo(TEMPLATE);
    assertThat(document.sections())
        .extracting(Section::id)
        .containsExactly(
            "overview",
            "headlines",
            "risks",
            "blast_radius",
            "technology_currency",
            "context",
            "containers",
            "quality");
    assertThat(section(document, "headlines").title()).isEqualTo("At a glance");
  }

  @Test
  void headlinesCountWhatTheRepositoryBuildsAndHowItIsConnected() {
    TestFacts shop = TestFacts.shop();
    Metrics metrics = (Metrics) section(compose(shop.list()), "headlines").blocks().getFirst();

    assertThat(metrics.id()).isEqualTo("headlines.metrics");
    assertThat(metrics.items())
        .extracting(i -> i.label() + " " + i.value())
        .containsExactly(
            "Services 1",
            "Libraries 0",
            "Modules 1",
            "Java types 3",
            "Third-party libraries 1",
            "Repositories it uses 1",
            "Repositories using it 0");
    // Test-scoped junit is not counted; optional jackson is.
    assertThat(metrics.items().get(4).facts())
        .containsExactly(
            shop.list().stream()
                .filter(
                    f ->
                        f.predicate().equals("BUILD_DEPENDS_ON")
                            && f.object()
                                .entityId()
                                .toString()
                                .equals("artifact:com.fasterxml:jackson"))
                .findFirst()
                .orElseThrow()
                .id());
    assertThat(metrics.items().get(5).facts())
        .containsExactly(
            shop.list().stream()
                .filter(
                    f ->
                        f.subject().toString().equals(APP)
                            && f.object().kind() == FactObject.Kind.ENTITY
                            && f.object().entityId().toString().equals("module:com.example:common"))
                .findFirst()
                .orElseThrow()
                .id());
    assertThat(metrics.items().get(3).facts()).hasSize(3);
  }

  @Test
  void aRepositoryUsedByOthersCountsThem() {
    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.executiveBrief("b", "base", TEMPLATE),
                SNAPSHOT,
                TestFacts.shop().list(),
                Map.of());
    Metrics metrics = (Metrics) section(document, "headlines").blocks().getFirst();

    assertThat(metrics.items())
        .extracting(i -> i.label() + " " + i.value())
        .containsExactly(
            "Services 0",
            "Libraries 1",
            "Modules 1",
            "Java types 0",
            "Third-party libraries 1",
            "Repositories it uses 0",
            "Repositories using it 1");
  }

  @Test
  void withoutRisksSaysWhatWasChecked() {
    List<Block> blocks = section(compose(TestFacts.shop().list()), "risks").blocks();

    assertThat(blocks).hasSize(1);
    Callout note = (Callout) blocks.getFirst();
    assertThat(note.id()).isEqualTo("risks.none");
    assertThat(note.variant()).isEqualTo(Callout.Variant.NOTE);
    assertThat(note.severity()).isNull();
    assertThat(note.title()).isEqualTo("No risks found");
  }

  @Test
  void findsRisksMostSeriousFirst() {
    List<Callout> risks =
        section(compose(risky()), "risks").blocks().stream().map(Callout.class::cast).toList();

    assertThat(risks)
        .extracting(c -> c.id() + " " + c.variant() + " " + c.severity())
        .containsExactly(
            "risks.unreleased RISK MEDIUM",
            "risks.inconsistent-versions RISK MEDIUM",
            "risks.cycles.service:com.example:app RISK LOW");
    assertThat(text(risks.get(0).content()))
        .isEqualTo(
            "shop is built against 2 dependencies on unreleased (snapshot) versions:"
                + " snap (0.1-SNAPSHOT) and common (2.0-SNAPSHOT).");
    assertThat(risks.get(0).consequence())
        .isEqualTo("A release of it cannot be rebuilt exactly and may change without notice.");
    assertThat(risks.get(0).facts()).hasSize(2);
    assertThat(risks).allSatisfy(r -> assertThat(r.confidence()).isEqualByComparingTo("1"));
    assertThat(text(risks.get(1).content()))
        .isEqualTo(
            "shop uses different versions of 2 dependencies: jackson (1.0, 2.0) and common (1.0,"
                + " 2.0-SNAPSHOT).");
    assertThat(risks.get(1).consequence())
        .isEqualTo("The versions can conflict when they run together.");
    assertThat(risks.get(1).facts()).hasSize(4);
    assertThat(risks.get(2).title()).isEqualTo("Tangled packages in app");
    assertThat(text(risks.get(2).content()))
        .isEqualTo(
            "2 packages of app depend on each other in a cycle. The cycle: com.example.domain and"
                + " com.example.web.");
    assertThat(risks.get(2).consequence())
        .isEqualTo("None of them can be changed, tested or split out on its own.");
    // The calls in both directions.
    assertThat(risks.get(2).facts()).hasSize(2);
    risks.forEach(
        r ->
            r.content().stream()
                .filter(CrossReference.class::isInstance)
                .map(i -> ((CrossReference) i).target())
                .forEach(t -> assertThat(t.kind()).isEqualTo(CrossReference.Target.Kind.ENTITY)));
  }

  @Test
  void namesAtMostFiveAndCountsTheRest() {
    List<Fact> facts = new ArrayList<>(TestFacts.shop().list());
    for (int i = 1; i <= 7; i++) {
      facts.add(dependency(APP, "artifact:org.example:snap" + i, "1-SNAPSHOT"));
    }
    Callout risk = (Callout) section(compose(facts), "risks").blocks().getFirst();

    assertThat(text(risk.content()))
        .endsWith(
            "snap1 (1-SNAPSHOT), snap2 (1-SNAPSHOT), snap3 (1-SNAPSHOT), snap4 (1-SNAPSHOT),"
                + " snap5 (1-SNAPSHOT), and 2 more.");
    assertThat(risk.facts()).hasSize(7);
  }

  @Test
  void speaksOfOneUnreleasedVersionInTheSingular() {
    List<Fact> facts = new ArrayList<>(TestFacts.shop().list());
    facts.add(dependency(APP, "artifact:org.example:snap", "1-SNAPSHOT"));
    Callout risk = (Callout) section(compose(facts), "risks").blocks().getFirst();

    assertThat(text(risk.content()))
        .startsWith("shop is built against 1 dependency on an unreleased (snapshot) version:");
  }

  @Test
  void doesNotDependOnInputOrder() {
    List<Fact> shuffled = new ArrayList<>(risky());
    Collections.shuffle(shuffled, new Random(7));

    String first = DocumentJson.write(compose(risky()));
    assertThat(DocumentJson.write(compose(shuffled))).isEqualTo(first);
    assertThat(DocumentJson.read(first)).isEqualTo(compose(risky()));
  }

  @Test
  void findsEveryCycleInAGraph() {
    EntityId a = EntityId.parse("package:m:a");
    EntityId b = EntityId.parse("package:m:b");
    EntityId c = EntityId.parse("package:m:c");
    EntityId d = EntityId.parse("package:m:d");
    EntityId e = EntityId.parse("package:m:e");
    Map<EntityId, Map<EntityId, List<Fact>>> edges =
        Map.of(
            a, Map.of(b, List.of()),
            b, Map.of(c, List.of()),
            c, Map.of(a, List.of(), d, List.of()),
            d, Map.of(e, List.of()),
            e, Map.of(d, List.of()));

    assertThat(RepoRisks.stronglyConnected(edges))
        .extracting(Object::toString)
        .containsExactlyInAnyOrder(
            "[package:m:a, package:m:b, package:m:c]", "[package:m:d, package:m:e]");
  }

  // Blast radius and technology currency (ADR 0050).

  private static final Instant OCTOBER_2026 = Instant.parse("2026-10-29T10:00:00Z");

  private static Document compose(List<Fact> facts, Instant at) {
    return new Composer("1")
        .compose(
            ViewDefinition.executiveBrief("shop.brief", "shop", TEMPLATE),
            new Snapshot(2, SNAPSHOT.commits(), 5, "0.1.0", at),
            facts,
            Map.of());
  }

  private static List<String> rows(Table table) {
    return table.rows().stream()
        .map(
            r ->
                r.cells().stream().map(ExecutiveBriefTest::text).collect(Collectors.joining(" | ")))
        .toList();
  }

  /**
   * The runtime facts with the technology of {@code shop-app} replaced: Spring Boot at the version,
   * Java at the release, and a direct dependency of {@code app} on Spring Framework.
   */
  private static List<Fact> withTechnology(String boot, String java, String framework) {
    List<Fact> facts = new ArrayList<>(TestFacts.runtime().list());
    facts.removeIf(f -> f.predicate().equals("technology") && f.subject().type().equals("service"));
    TestFacts more =
        new TestFacts()
            .attribute(
                "shop",
                "service:com.example:app",
                "technology",
                "Spring Boot " + boot + " / Java " + java,
                "{\"framework\": \"Spring Boot\", \"frameworkVersion\": \""
                    + boot
                    + "\", \"java\": \""
                    + java
                    + "\"}");
    facts.addAll(more.list());
    if (framework != null) {
      facts.add(dependency(APP, "artifact:org.springframework:spring-context", framework));
    }
    return facts;
  }

  @Test
  void blastRadiusNamesWhoDependsOnTheUnitsAndWhatTheyDependOn() {
    List<Block> blocks = section(compose(TestFacts.runtime().list()), "blast_radius").blocks();

    assertThat(blocks)
        .extracting(Block::id)
        .containsExactly(
            "blast_radius.summary",
            "blast_radius.dependents",
            "blast_radius.dependencies",
            "blast_radius.unresolved");
    Paragraph summary = (Paragraph) blocks.get(0);
    assertThat(text(summary.content()))
        .isEqualTo(
            "1 container in the snapshot depends on shop-app. It depends on 1 external system"
                + " and 1 database.");
    assertThat(summary.facts()).hasSize(4);
    Table dependents = (Table) blocks.get(1);
    assertThat(dependents.title()).isEqualTo("Who depends on it");
    assertThat(rows(dependents))
        .containsExactly(
            "worker | Container in repository base | calls shop-app; receives messages from"
                + " shop-app");
    assertThat(dependents.rows().getFirst().facts()).hasSize(2);
    assertThat(rows((Table) blocks.get(2)))
        .containsExactly(
            "shop on db | PostgreSQL database | shop-app stores its data in it",
            "pay.example.com | External system | shop-app calls it (only under the Spring profile"
                + " prod)");
    assertThat(text(((Callout) blocks.get(3)).content()))
        .isEqualTo(
            "1 call, message or database connection of shop-app could not be traced to its"
                + " target, so shop may depend on more than this shows.");
  }

  @Test
  void blastRadiusOfALibraryNamesTheUnitsRunningItsCode() {
    List<Fact> facts = new ArrayList<>(TestFacts.runtime().list());
    facts.addAll(
        new TestFacts()
            .relate("shop", "service:com.example:app", "BUNDLES", "library:com.example:common")
            .list());
    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.executiveBrief("base.brief", "base", TEMPLATE),
                SNAPSHOT,
                facts,
                Map.of());
    List<Block> blocks = section(document, "blast_radius").blocks();

    assertThat(text(((ListBlock) blocks.getFirst()).items().getFirst().content()))
        .isEqualTo("shop-app, built in shop, runs its code");
    // worker is base's own unit; shop-app runs base's code, so their links are within scope.
    assertThat(text(((Paragraph) blocks.get(1)).content()))
        .isEqualTo(
            "No container in the snapshot depends on base's deployable units. They depend on 1"
                + " external system and 1 database.");
  }

  @Test
  void blastRadiusWithoutUnitsSaysSo() {
    List<Block> blocks = section(compose(TestFacts.shop().list()), "blast_radius").blocks();

    assertThat(blocks).hasSize(1);
    assertThat(((Callout) blocks.getFirst()).variant()).isEqualTo(Callout.Variant.NOTE);
  }

  @Test
  void technologyCurrencyComparesVersionsWithTheirEndOfLife() {
    Document document = compose(withTechnology("2.7.18", "1.8", "6.2.1"), OCTOBER_2026);
    List<Block> blocks = section(document, "technology_currency").blocks();

    assertThat(blocks)
        .extracting(Block::id)
        .containsExactly(
            "technology_currency.summary",
            "technology_currency.versions",
            "technology_currency.source");
    assertThat(text(((Paragraph) blocks.get(0)).content()))
        .isEqualTo(
            "As of the snapshot's date, 29 Oct 2026, 2 of the 3 versions in use are past the end"
                + " of free support.");
    assertThat(rows((Table) blocks.get(1)))
        .containsExactly(
            "Java | 1.8 | shop-app | 31 Dec 2030",
            "Spring Boot | 2.7.18 | shop-app | Ended 30 Jun 2023",
            "Spring Framework | 6.2.1 | shop-app | Ended 30 Jun 2026");
    assertThat(((Table) blocks.get(1)).rows()).allSatisfy(r -> assertThat(r.facts()).hasSize(1));

    List<Callout> risks =
        section(document, "risks").blocks().stream().map(Callout.class::cast).toList();
    Callout risk = risks.getFirst();
    assertThat(risk.id()).isEqualTo("risks.framework-ended");
    assertThat(risk.severity()).isEqualTo(Callout.Severity.HIGH);
    assertThat(text(risk.content()))
        .isEqualTo(
            "shop uses 2 versions past the end of free support: Spring Boot 2.7.18 (ended 30 Jun"
                + " 2023) in shop-app and Spring Framework 6.2.1 (ended 30 Jun 2026) in shop-app.");
    assertThat(risk.consequence()).isNotBlank();
    assertThat(risk.confidence()).isEqualByComparingTo("1");
    assertThat(risk.facts()).hasSize(2);
  }

  @Test
  void technologyCurrencyFindsSupportEndingSoonAndUnsupportedJava() {
    Document document = compose(withTechnology("4.0.1", "26", null), OCTOBER_2026);

    assertThat(rows((Table) section(document, "technology_currency").blocks().get(1)))
        .containsExactly(
            "Java | 26 | shop-app | Ended 22 Sep 2026",
            "Spring Boot | 4.0.1 | shop-app | Ends 31 Dec 2026, within six months");
    List<Callout> risks =
        section(document, "risks").blocks().stream().map(Callout.class::cast).toList();
    assertThat(risks)
        .extracting(c -> c.id() + " " + c.severity() + " " + c.confidence())
        .containsExactly("risks.framework-ending MEDIUM 1", "risks.java-ended MEDIUM 0.5");
  }

  @Test
  void technologyCurrencyKnowsWhatItDoesNotKnow() {
    Document document = compose(withTechnology("${boot.version}", "9", null), OCTOBER_2026);

    assertThat(rows((Table) section(document, "technology_currency").blocks().get(1)))
        .containsExactly(
            "Java | 9 | shop-app | Not in Lantern's table",
            "Spring Boot | ${boot.version} | shop-app | Not in Lantern's table");
    assertThat(
            compose(withTechnology("1.2.0", "21", null), OCTOBER_2026).sections().stream()
                .filter(s -> s.id().equals("technology_currency"))
                .findFirst()
                .map(s -> rows((Table) s.blocks().get(1)))
                .orElseThrow())
        .contains("Spring Boot | 1.2.0 | shop-app | Ended");
  }

  @Test
  void technologyCurrencyWithoutVersionsSaysSo() {
    List<Block> blocks = section(compose(TestFacts.shop().list()), "technology_currency").blocks();

    assertThat(blocks).hasSize(1);
    assertThat(((Callout) blocks.getFirst()).variant()).isEqualTo(Callout.Variant.NOTE);
  }
}
