package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Diagram;
import io.lantern.core.document.Document;
import io.lantern.core.document.QualityReport;
import io.lantern.core.document.Section;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.snapshot.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A link left open that the LLM resolver linked is drawn, marked as the LLM's with its confidence,
 * and is not also listed as not linked (ADR 0044, ADR 0045).
 */
class ResolvedLinksTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("c4-model-sequence-diagrams", "1");
  private static final TemplateRef BRIEF = new TemplateRef("executive-brief", "1");
  private static final Snapshot SNAPSHOT =
      new Snapshot(
          2, Map.of("shop", TestFacts.COMMIT, "base", TestFacts.COMMIT), 5, "0.1.0", Instant.EPOCH);
  private static final String APP = "service:com.example:app";

  private static final TestFacts RUNTIME = TestFacts.runtime();
  private static final Fact UNRESOLVED = RUNTIME.find(APP, "unresolved-link");
  private static final Fact CALL = resolverLink(UNRESOLVED);

  private static Fact resolverLink(Fact unresolved) {
    var attributes =
        (tools.jackson.databind.node.ObjectNode)
            FactJson.parse(
                "{\"protocol\": \"HTTPS\", \"httpMethod\": \"GET\", \"path\": \"/quotes\","
                    + " \"resolves\": \""
                    + unresolved.id()
                    + "\"}");
    return Fact.create(
        EntityId.parse(APP),
        "CALLS_HTTP",
        FactObject.entity(EntityId.parse("external-system:tax.example.com")),
        attributes,
        new Origin(Origin.Kind.LLM, "link-resolver", "1"),
        Evidence.of("shop", "Code.java", null, null),
        0.28,
        Validity.from(1));
  }

  private static Fact resolverSend() {
    var attributes =
        (tools.jackson.databind.node.ObjectNode)
            FactJson.parse("{\"protocol\": \"Kafka\", \"resolves\": \"" + UNRESOLVED.id() + "\"}");
    return Fact.create(
        EntityId.parse(APP),
        "PUBLISHES",
        FactObject.entity(EntityId.parse("topic:kafka:audit")),
        attributes,
        new Origin(Origin.Kind.LLM, "link-resolver", "1"),
        Evidence.of("shop", "Code.java", null, null),
        0.18,
        Validity.from(1));
  }

  @Test
  void aResolvedMessageLinkIsDrawnMarkedAsTheLlmsToo() {
    List<Fact> facts = open();
    facts.add(resolverSend());
    Document document = c4(facts);
    assertThat(notLinked(document)).isEmpty();
    Diagram diagram =
        section(document, "containers").blocks().stream()
            .filter(Diagram.class::isInstance)
            .map(Diagram.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(diagram.edges())
        .filteredOn(e -> e.to().toString().equals("topic:kafka:audit"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.technology())
                    .isEqualTo("Kafka · resolved by the LLM, confidence 0.18"));
  }

  private static List<Fact> open() {
    return new ArrayList<>(RUNTIME.list());
  }

  private static List<Fact> resolved() {
    List<Fact> facts = open();
    facts.add(CALL);
    return facts;
  }

  private static Document c4(List<Fact> facts) {
    return new Composer("1")
        .compose(ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE), SNAPSHOT, facts, Map.of());
  }

  private static Section section(Document document, String id) {
    return document.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  private static List<Callout> notLinked(Document document) {
    return section(document, "decisions").blocks().stream()
        .filter(Callout.class::isInstance)
        .map(Callout.class::cast)
        .filter(c -> "Not linked".equals(c.title()))
        .toList();
  }

  @Test
  void theNotLinkedBoxListsItsItemsByWhatIsCalledWhateverTheFactIds() {
    // Two more open links of app, whose IDs sort either way round: the box lists them by the call
    // as written, the same on every run (#605).
    java.util.Set<Boolean> seen = new java.util.HashSet<>();
    for (int version = 1; seen.size() < 2; version++) {
      Fact alpha = open("GET http://alpha/a", 1);
      Fact zeta = open("GET http://zeta/z", version);
      if (!seen.add(zeta.id().compareTo(alpha.id()) < 0)) {
        continue;
      }
      List<Fact> facts = open();
      facts.add(zeta);
      facts.add(alpha);
      String text =
          notLinked(c4(facts)).getFirst().content().stream()
              .map(i -> ((io.lantern.core.document.TextRun) i).text())
              .reduce("", String::concat);
      assertThat(text.indexOf("http://alpha/a"))
          .as(text)
          .isNotNegative()
          .isLessThan(text.indexOf("http://zeta/z"));
    }
  }

  private static Fact open(String call, int version) {
    return Fact.create(
        EntityId.parse(APP),
        "unresolved-link",
        FactObject.string(call),
        (tools.jackson.databind.node.ObjectNode)
            FactJson.parse("{\"kind\": \"http\", \"reason\": \"unknown-host\"}"),
        new Origin(Origin.Kind.STATIC, "linker", Integer.toString(version)),
        Evidence.of("shop", "Code.java", null, null),
        1.0,
        Validity.from(1));
  }

  @Test
  void aResolvedLinkIsNotListedAsNotLinked() {
    assertThat(notLinked(c4(open()))).hasSize(1);
    assertThat(notLinked(c4(resolved()))).isEmpty();
  }

  @Test
  void aResolvedLinkIsDrawnMarkedAsTheLlmsWithItsConfidence() {
    Section containers = section(c4(resolved()), "containers");
    Diagram diagram =
        containers.blocks().stream()
            .filter(Diagram.class::isInstance)
            .map(Diagram.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(diagram.edges())
        .filteredOn(e -> e.to().toString().equals("external-system:tax.example.com"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.technology())
                    .isEqualTo("HTTPS · resolved by the LLM, confidence 0.28"));
  }

  @Test
  void aResolvedLinkIsNotCountedAsOpenInTheBrief() {
    ViewDefinition view = ViewDefinition.executiveBrief("shop.brief", "shop", BRIEF);
    assertThat(brief(view, open())).contains("could not be traced");
    assertThat(brief(view, resolved())).doesNotContain("could not be traced");
  }

  private static String brief(ViewDefinition view, List<Fact> facts) {
    return new Composer("1").compose(view, SNAPSHOT, facts, Map.of()).toString();
  }

  @Test
  void theQualityReportShowsTheResolvedLinkAsTheCallThatContradictsAClaim() {
    List<Fact> facts = resolved();
    facts.addAll(
        TestFacts.contradictedClaim(
            "The app never calls another service.",
            3,
            "synchronous-calls",
            null,
            List.of(),
            List.of(UNRESOLVED)));
    Document document =
        new Composer("1")
            .compose(
                ViewDefinition.perRepo("shop", "shop", EXPLORER_REF), SNAPSHOT, facts, Map.of());
    String callouts = document.toString();
    assertThat(callouts).contains(CALL.id()).doesNotContain("could not link");
    Section quality = document.sections().getLast();
    assertThat(
            quality.blocks().stream()
                .filter(QualityReport.class::isInstance)
                .map(QualityReport.class::cast)
                .findFirst()
                .orElseThrow()
                .failures())
        .anyMatch(f -> f.check().equals(ClaimContradictions.CHECK));
  }

  private static final TemplateRef EXPLORER_REF = new TemplateRef(QualityBar.EXPLORER, "1");
}
