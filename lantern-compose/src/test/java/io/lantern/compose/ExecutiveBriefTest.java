package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Metrics;
import io.lantern.core.document.Section;
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

/** The Executive Brief's own sections: headline numbers and risks (ADR 0015). */
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
        .containsExactly("overview", "headlines", "risks", "context", "containers", "quality");
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
            "shop is built against 2 dependencies on unreleased (snapshot) versions, so a release"
                + " of it cannot be rebuilt exactly and may change without notice:"
                + " snap (0.1-SNAPSHOT) and common (2.0-SNAPSHOT).");
    assertThat(risks.get(0).facts()).hasSize(2);
    assertThat(text(risks.get(1).content()))
        .isEqualTo(
            "shop uses different versions of 2 dependencies, which can lead to"
                + " conflicts when they run together: jackson (1.0, 2.0) and common (1.0,"
                + " 2.0-SNAPSHOT).");
    assertThat(risks.get(1).facts()).hasSize(4);
    assertThat(risks.get(2).title()).isEqualTo("Tangled packages in app");
    assertThat(text(risks.get(2).content()))
        .isEqualTo(
            "2 packages of app depend on each other in a cycle, so none of them can be changed,"
                + " tested or split out on its own. The cycle: com.example.domain and"
                + " com.example.web.");
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
        .startsWith("shop is built against 1 dependency on an unreleased (snapshot) version, so");
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
}
