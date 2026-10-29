package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.document.Block;
import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentEntity;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.document.Tree;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Validity;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.summarize.Summary;
import io.lantern.summarize.SummaryLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposerTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("architecture-explorer", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final EntityId APP = EntityId.parse("service:com.example:app");

  private final TestFacts facts = TestFacts.shop();

  private Document compose(List<Fact> facts, Map<EntityId, Summary> summaries) {
    return new Composer("0.2.0")
        .compose(ViewDefinition.perRepo("shop-doc", "shop", TEMPLATE), SNAPSHOT, facts, summaries);
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> T block(Section section, Class<T> kind) {
    return section.blocks().stream()
        .filter(kind::isInstance)
        .map(kind::cast)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> labels(List<Tree.Node> nodes, String indent) {
    List<String> lines = new ArrayList<>();
    for (Tree.Node node : nodes) {
      lines.add(indent + node.label());
      lines.addAll(labels(node.children(), indent + "  "));
    }
    return lines;
  }

  @Test
  void recordsTheViewAndSource() {
    Document document = compose(facts.list(), Map.of());

    assertThat(document.id()).isEqualTo("shop-doc");
    assertThat(document.title()).isEqualTo("shop");
    assertThat(document.template()).isEqualTo(TEMPLATE);
    assertThat(document.source().snapshot()).isEqualTo(2);
    assertThat(document.source().vocabularyRevision()).isEqualTo(5);
    assertThat(document.source().toolVersion()).isEqualTo("0.2.0");
    // It pins the commit of the repository it covers, not of the others in the snapshot.
    assertThat(document.source().commits()).containsExactly(Map.entry("shop", TestFacts.COMMIT));
    assertThat(document.source().date()).isEqualTo(java.time.LocalDate.of(1970, 1, 1));
    assertThat(document.audience()).isEqualTo("architects and senior engineers");
    assertThat(document.sections())
        .extracting(Section::id)
        .containsExactly(
            "overview",
            "start_here",
            "containers",
            "context",
            "components",
            "sequences",
            "catalog",
            "change_guidance",
            "dependencies",
            "structure",
            "glossary",
            "quality");
  }

  @Test
  void composesOnlyTheSectionsOfTheViewInItsOrder() {
    ViewDefinition view =
        new ViewDefinition(
            "v",
            "Shop",
            Scope.repo("shop"),
            "architect",
            TEMPLATE,
            List.of(SectionKind.STRUCTURE, SectionKind.CONTEXT));

    Document document = new Composer("1").compose(view, SNAPSHOT, facts.list(), Map.of());

    assertThat(document.audience()).isEqualTo("architect");
    assertThat(document.sections())
        .extracting(Section::id)
        .containsExactly("structure", "context", "quality");
  }

  @Test
  void dependencyTreeExpandsInternalModulesWithWhatMavenPassesOn() {
    Tree tree = block(section(compose(facts.list(), Map.of()), "dependencies"), Tree.class);

    assertThat(labels(tree.roots(), ""))
        .containsExactly(
            "com.example:app",
            "  com.example:common:1.0",
            "    org.slf4j:slf4j-api:1.0",
            "  com.fasterxml:jackson:1.0 (optional)",
            "  org.junit:junit:1.0 (test)");
  }

  @Test
  void structureTreeShowsModulesAndPackages() {
    Tree tree = block(section(compose(facts.list(), Map.of()), "structure"), Tree.class);

    assertThat(labels(tree.roots(), ""))
        .containsExactly("app", "  com.example.domain", "  com.example.web");
  }

  @Test
  void includesTheRepositorysEntitiesWithParentsAndFacts() {
    Document document = compose(facts.list(), Map.of());

    assertThat(document.entities())
        .extracting(e -> e.id() + " < " + e.parent())
        .contains(
            "repo:shop < null",
            "module:com.example:app < repo:shop",
            "service:com.example:app < repo:shop",
            "package:com.example:app:com.example.web < module:com.example:app",
            "type:com.example.web.Controller < package:com.example:app:com.example.web");
    DocumentEntity app =
        document.entities().stream().filter(e -> e.id().equals(APP)).findFirst().orElseThrow();
    assertThat(app.facts()).containsExactly(facts.find("service:com.example:app", "CONTAINS").id());
  }

  @Test
  void summariesBecomeNarrativeWithCitationsAndCrossReferences() {
    Fact packaging = facts.find("module:com.example:app", "packaging");
    Fact contains = facts.find("service:com.example:app", "CONTAINS");
    EntityId module = EntityId.parse("repo:shop");
    EntityId web = EntityId.parse("package:com.example:app:com.example.web");
    Summary summary =
        summary(
            module,
            "The app module. [fact:"
                + packaging.id()
                + "] [fact:"
                + contains.id()
                + "] It holds the web layer.[summary:"
                + web
                + "]",
            List.of(packaging.id(), contains.id()));

    Document document = compose(facts.list(), Map.of(module, summary));

    Paragraph paragraph = block(section(document, "overview"), Paragraph.class);
    assertThat(paragraph.content())
        .containsExactly(
            TextRun.of("The app module."),
            new Citation(List.of(packaging.id(), contains.id())),
            TextRun.of(" It holds the web layer."),
            new CrossReference("com.example.web", CrossReference.Target.entity(web), true));
    assertThat(paragraph.facts()).containsExactlyInAnyOrder(packaging.id(), contains.id());
    DocumentEntity entity =
        document.entities().stream().filter(e -> e.id().equals(module)).findFirst().orElseThrow();
    assertThat(entity.description()).isEqualTo(paragraph.content());
  }

  @Test
  void aSummaryWhosePurposeIsInferredSaysSo() {
    Fact packaging = facts.find("module:com.example:app", "packaging");
    EntityId module = EntityId.parse("repo:shop");
    Summary summary =
        summary(
            module,
            "Sells books. [fact:" + packaging.id() + "]",
            List.of(packaging.id()),
            Summary.Purpose.INFERRED);

    Document document = compose(facts.list(), Map.of(module, summary));

    Paragraph paragraph = block(section(document, "overview"), Paragraph.class);
    assertThat(paragraph.content())
        .containsExactly(
            new TextRun("Purpose inferred from the code:", TextRun.Style.EMPHASIS),
            TextRun.of(" "),
            TextRun.of("Sells books."),
            new Citation(List.of(packaging.id())));
    assertThat(entity(document, "repo:shop").description()).isEqualTo(paragraph.content());
  }

  @Test
  void summaryCitationsOpeningASummaryOrInARowAreMarkersWithoutSpaces() {
    Fact packaging = facts.find("module:com.example:app", "packaging");
    EntityId module = EntityId.parse("repo:shop");
    EntityId web = EntityId.parse("package:com.example:app:com.example.web");
    EntityId domain = EntityId.parse("package:com.example:app:com.example.domain");
    Summary summary =
        summary(
            module,
            "[summary:" + web + "] [summary:" + domain + "] It holds the web layer.",
            List.of(packaging.id()));

    Paragraph paragraph =
        block(section(compose(facts.list(), Map.of(module, summary)), "overview"), Paragraph.class);

    assertThat(paragraph.content())
        .containsExactly(
            new CrossReference("com.example.web", CrossReference.Target.entity(web), true),
            new CrossReference("com.example.domain", CrossReference.Target.entity(domain), true),
            TextRun.of("It holds the web layer."));
  }

  @Test
  void aSummaryCitationBetweenFactCitationsSplitsThem() {
    Fact packaging = facts.find("module:com.example:app", "packaging");
    Fact contains = facts.find("service:com.example:app", "CONTAINS");
    EntityId module = EntityId.parse("repo:shop");
    EntityId web = EntityId.parse("package:com.example:app:com.example.web");
    Summary summary =
        summary(
            module,
            "The app module. [fact:"
                + packaging.id()
                + "] [summary:"
                + web
                + "] [fact:"
                + contains.id()
                + "] It runs.",
            List.of(packaging.id(), contains.id()));

    Paragraph paragraph =
        block(section(compose(facts.list(), Map.of(module, summary)), "overview"), Paragraph.class);

    assertThat(paragraph.content())
        .containsExactly(
            TextRun.of("The app module."),
            new Citation(List.of(packaging.id())),
            new CrossReference("com.example.web", CrossReference.Target.entity(web), true),
            new Citation(List.of(contains.id())),
            TextRun.of(" It runs."));
  }

  @Test
  void refusesASummaryCitingAFactNotInTheSnapshot() {
    EntityId module = EntityId.parse("module:com.example:app");
    String missing = "f".repeat(64);
    Summary summary = summary(module, "Text. [fact:" + missing + "]", List.of(missing));

    assertThatThrownBy(() -> compose(facts.list(), Map.of(module, summary)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing);
  }

  @Test
  void ignoresFactsThatDoNotHoldInTheSnapshot() {
    List<Fact> all = new ArrayList<>(facts.list());
    all.add(
        TestFacts.fact(
            "shop",
            "module:com.example:app",
            "CONTAINS",
            FactObject.entity(EntityId.parse("package:com.example:app:com.example.old")),
            FactJson.newObject(),
            new Validity(1, 2L)));

    Tree tree = block(section(compose(all, Map.of()), "structure"), Tree.class);

    assertThat(labels(tree.roots(), ""))
        .containsExactly("app", "  com.example.domain", "  com.example.web");
  }

  @Test
  void refusesARepositoryNotInTheSnapshot() {
    ViewDefinition view = ViewDefinition.perRepo("v", "other", TEMPLATE);

    assertThatThrownBy(() -> new Composer("1").compose(view, SNAPSHOT, facts.list(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("other");
  }

  @Test
  void prefersTheDocumentsRepositoryForAModuleObservedInSeveral() {
    List<Fact> all = new ArrayList<>(facts.list());
    all.add(
        TestFacts.fact(
            "base",
            "module:com.example:app",
            "packaging",
            FactObject.string("jar"),
            FactJson.newObject(),
            Validity.from(1)));

    Document document = compose(all, Map.of());

    assertThat(entity(document, "module:com.example:app").parent())
        .isEqualTo(EntityId.parse("repo:shop"));
    assertThat(entity(document, "service:com.example:app").parent())
        .isEqualTo(EntityId.parse("repo:shop"));
  }

  @Test
  void namesAndPlacesNestedTypesAndTypesInTheUnnamedPackage() {
    List<Fact> all = new ArrayList<>(facts.list());
    TestFacts more =
        new TestFacts()
            .relate("shop", "module:com.example:app", "CONTAINS", "type:Main")
            .relate(
                "shop",
                "type:com.example.web.Controller",
                "CONTAINS",
                "type:com.example.web.Controller$Page");
    all.addAll(more.list());

    Document document = compose(all, Map.of());

    DocumentEntity main = entity(document, "type:Main");
    assertThat(main.name()).isEqualTo("Main");
    assertThat(main.parent()).isEqualTo(EntityId.parse("module:com.example:app"));
    DocumentEntity page = entity(document, "type:com.example.web.Controller$Page");
    assertThat(page.name()).isEqualTo("Controller.Page");
    assertThat(page.parent()).isEqualTo(EntityId.parse("type:com.example.web.Controller"));
    assertThat(page.facts())
        .containsExactly(more.find("type:com.example.web.Controller", "CONTAINS").id());
  }

  @Test
  void dependencyLabelsShowClassifierAndArtifactType() {
    tools.jackson.databind.node.ObjectNode attributes = FactJson.newObject();
    attributes.put("scope", "test");
    attributes.put("version", "1.0");
    attributes.put("classifier", "tests");
    attributes.put("artifactType", "test-jar");
    List<Fact> all = new ArrayList<>(facts.list());
    all.add(
        TestFacts.fact(
            "shop",
            "module:com.example:app",
            "BUILD_DEPENDS_ON",
            FactObject.entity(EntityId.parse("module:com.example:common")),
            attributes,
            Validity.from(1)));

    Tree tree = block(section(compose(all, Map.of()), "dependencies"), Tree.class);

    assertThat(labels(tree.roots(), ""))
        .contains("  com.example:common:1.0:tests (test-jar) (test)");
  }

  @Test
  void composesARepositoryWithoutFacts() {
    Snapshot snapshot =
        new Snapshot(2, Map.of("empty", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);

    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.perRepo("e", "empty", TEMPLATE), snapshot, facts.list(), Map.of());

    assertThat(document.entities())
        .extracting(e -> e.id().toString())
        .containsExactly("repo:empty", "system:snapshot");
    assertThat(document.sections()).hasSize(12);
  }

  @Test
  void refusesContainsFactsThatFormACycle() {
    List<Fact> all = new ArrayList<>(facts.list());
    all.addAll(
        new TestFacts()
            .relate("shop", "module:com.example:app", "CONTAINS", "module:com.example:other")
            .relate("shop", "module:com.example:other", "CONTAINS", "module:com.example:app")
            .list());

    assertThatThrownBy(() -> compose(all, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
  }

  private static DocumentEntity entity(Document document, String id) {
    return document.entities().stream()
        .filter(e -> e.id().toString().equals(id))
        .findFirst()
        .orElseThrow();
  }

  /** A summary whose purpose is evidenced, so its text is shown as written. */
  static Summary summary(EntityId entity, String text, List<String> cited) {
    return summary(entity, text, cited, Summary.Purpose.EVIDENCED);
  }

  static Summary summary(
      EntityId entity, String text, List<String> cited, Summary.Purpose purpose) {
    return new Summary(
        "0".repeat(64),
        entity,
        SummaryLevel.of(entity),
        text,
        purpose,
        purpose == Summary.Purpose.EVIDENCED ? 0.5 : 0.35,
        cited,
        List.of(),
        cited,
        List.of(),
        "1",
        "fake",
        "fake-model");
  }
}
