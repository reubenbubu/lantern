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
    for (int i = 0; i <= C4Views.MAX_ELEMENTS; i++) {
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
    for (int i = 0; i <= C4Views.MAX_ELEMENTS; i++) {
      String unit = "service:com.example:u" + i;
      many.attribute("shop", unit, "deployable-unit", "spring-boot", "{}")
          .attribute("shop", unit, "application-name", "u" + i, "{}")
          .attribute("shop", unit, "technology", "Spring Boot 3.5.0 / Java 21", "{}")
          .relate(
              "shop", i % 2 == 0 ? first : second, "GROUPS", unit, "{\"basis\": \"repository\"}");
    }

    Section containers = section(system(many.list()), "containers");

    Diagram overview = block(containers, Diagram.class);
    assertThat(overview.title()).endsWith(", by domain");
    assertThat(overview.nodes())
        .extracting(n -> n.id().toString())
        .contains(first, second, APP, WORKER)
        .doesNotContain("service:com.example:u0");
    assertThat(C4Views.fits(overview)).isTrue();
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
      for (int i = 0; i <= C4Views.MAX_ELEMENTS / features.size(); i++) {
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
    assertThat(C4Views.fits(overview)).isTrue();
    assertThat(app.sections()).hasSize(features.size());
    assertThat(app.sections())
        .allSatisfy(area -> assertThat(C4Views.fits(block(area, Diagram.class))).isTrue());
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
