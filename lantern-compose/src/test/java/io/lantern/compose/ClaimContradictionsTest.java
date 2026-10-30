package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Citation;
import io.lantern.core.document.CrossReference;
import io.lantern.core.document.Document;
import io.lantern.core.document.Inline;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.Fact;
import io.lantern.core.snapshot.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** README claims the code contradicts, shown in documents (ADR 0051). */
class ClaimContradictionsTest {

  private static final TemplateRef EXPLORER = new TemplateRef(QualityBar.EXPLORER, "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);

  private static final List<Fact> FACTS = TestFacts.runtimeWithContradictedClaim();

  private static Fact find(String predicate) {
    return FACTS.stream().filter(f -> f.predicate().equals(predicate)).findFirst().orElseThrow();
  }

  private static Document compose(ViewDefinition view) {
    return new Composer("1").compose(view, SNAPSHOT, FACTS, Map.of());
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static <T extends Block> List<T> blocks(Section section, Class<T> kind) {
    return section.blocks().stream().filter(kind::isInstance).map(kind::cast).toList();
  }

  private static List<Callout> contradictions(Document document) {
    return blocks(section(document, "decisions"), Callout.class).stream()
        .filter(c -> ClaimContradictions.TITLE.equals(c.title()))
        .toList();
  }

  private static List<QualityReport.Failure> claimFailures(Document document) {
    Section quality = document.sections().getLast();
    return blocks(quality, QualityReport.class).getFirst().failures().stream()
        .filter(f -> f.check().equals(ClaimContradictions.CHECK))
        .toList();
  }

  private static String text(List<Inline> content) {
    return content.stream()
        .map(
            i ->
                switch (i) {
                  case TextRun t when t.style() == TextRun.Style.CODE -> "`" + t.text() + "`";
                  case TextRun t -> t.text();
                  case CrossReference r -> "[" + r.text() + "]";
                  case Citation c -> "^";
                  default -> "";
                })
        .collect(Collectors.joining());
  }

  @Test
  void theExplorerQuotesTheClaimInTheDecisionsAndCitesBothSides() {
    Document document = compose(ViewDefinition.perRepo("shop", "shop", EXPLORER));
    Fact claim = find("claim");
    Fact contradiction = find("claim-contradiction");
    Fact uses = TestFacts.runtime().find("service:com.example:app", "USES_DATABASE");

    List<Callout> callouts = contradictions(document);

    assertThat(callouts).hasSize(1);
    Callout callout = callouts.getFirst();
    assertThat(callout.variant()).isEqualTo(Callout.Variant.WARNING);
    assertThat(text(callout.content()))
        .isEqualTo(
            "`README.md`, line 3, in shop says: “The app keeps its orders in MySQL.”^. It"
                + " states MySQL; the code shows PostgreSQL. The code shows otherwise: [shop-app]"
                + " uses [shop on db] (`pom.xml`, in shop)^.");
    // The claim is cited where it is quoted, each fact of the code where it is told.
    assertThat(callout.content().stream().filter(Citation.class::isInstance))
        .map(i -> ((Citation) i).facts())
        .containsExactly(List.of(claim.id()), List.of(uses.id()));
    assertThat(callout.facts())
        .containsExactlyInAnyOrder(contradiction.id(), claim.id(), uses.id());
    assertThat(document.facts()).contains(claim, contradiction);
  }

  @Test
  void theQualityReportListsTheClaimAndPointsAtItsCallout() {
    Document document = compose(ViewDefinition.perRepo("shop", "shop", EXPLORER));

    List<QualityReport.Failure> failures = claimFailures(document);

    assertThat(failures).hasSize(1);
    QualityReport.Failure failure = failures.getFirst();
    assertThat(failure.target())
        .isEqualTo(CrossReference.Target.block(contradictions(document).getFirst().id()));
    assertThat(text(failure.detail()))
        .isEqualTo(
            "`README.md`, line 3, in shop: “The app keeps its orders in MySQL.” is contradicted"
                + " by 1 fact of the code.");
    assertThat(failure.facts()).contains(find("claim-contradiction").id(), find("claim").id());
  }

  @Test
  void aDocumentWithoutContainersStillReportsTheClaimWithoutATarget() {
    Document document =
        compose(
            new ViewDefinition(
                "shop", "shop", Scope.repo("shop"), null, EXPLORER, List.of(SectionKind.OVERVIEW)));

    List<QualityReport.Failure> failures = claimFailures(document);

    assertThat(failures).hasSize(1);
    assertThat(failures.getFirst().target()).isNull();
    assertThat(document.facts()).contains(find("claim-contradiction"));
  }

  @Test
  void theSystemShowsItButARepositoryWhoseReadmeItIsNotDoesNot() {
    Document base = compose(ViewDefinition.perRepo("base", "base", EXPLORER));
    Document system =
        compose(ViewDefinition.c4System("system", "System", new TemplateRef("c4", "1")));

    assertThat(contradictions(base)).isEmpty();
    assertThat(claimFailures(base)).isEmpty();
    assertThat(contradictions(system)).hasSize(1);
    assertThat(claimFailures(system)).hasSize(1);
  }

  @Test
  void aContradictionWhoseCodeSideDoesNotHoldIsNotShown() {
    Fact uses = TestFacts.runtime().find("service:com.example:app", "USES_DATABASE");
    List<Fact> facts = FACTS.stream().filter(f -> !f.equals(uses)).toList();

    Document document =
        new Composer("1")
            .compose(ViewDefinition.perRepo("shop", "shop", EXPLORER), SNAPSHOT, facts, Map.of());

    assertThat(contradictions(document)).isEmpty();
    assertThat(claimFailures(document)).isEmpty();
  }

  @Test
  void callsUnderAProfileUnlinkedCallsAndLongClaimsReadPlainly() {
    TestFacts runtime = TestFacts.runtime();
    Fact pay = runtime.find("service:com.example:app", "CALLS_HTTP");
    Fact tax = runtime.find("service:com.example:app", "unresolved-link");
    String text =
        "The app never calls another service synchronously. "
            + "It is fully event driven. ".repeat(6);
    List<Fact> facts = new ArrayList<>(FACTS);
    facts.addAll(
        TestFacts.contradictedClaim(
            text, 9, "synchronous-calls", null, List.of(), List.of(pay, tax)));

    Document document =
        new Composer("1")
            .compose(ViewDefinition.perRepo("shop", "shop", EXPLORER), SNAPSHOT, facts, Map.of());

    List<Callout> callouts = contradictions(document);
    assertThat(callouts)
        .extracting(Callout::id)
        .containsExactly("decisions.contradicted-claim", "decisions.contradicted-claim-2");
    assertThat(text(callouts.get(1).content()))
        .isEqualTo(
            "`README.md`, line 9, in shop says: “"
                + text.strip()
                + "”^. The code shows otherwise: [shop-app] calls [pay.example.com] under the prod"
                + " profile (`pom.xml`, in shop)^; [shop-app] makes a call Lantern could not link:"
                + " GET ${TAX_URL}/quotes (`pom.xml`, in shop)^.");
    QualityReport.Failure failure = claimFailures(document).get(1);
    assertThat(failure.target()).isEqualTo(CrossReference.Target.block(callouts.get(1).id()));
    assertThat(text(failure.detail()))
        .startsWith("`README.md`, line 9, in shop: “The app never calls")
        .endsWith("…” is contradicted by 2 facts of the code.");
  }
}
