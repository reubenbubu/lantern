package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.derive.ScopeFlows;
import io.lantern.summarize.describe.WordingPrompt.Question;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * How a flow's trace is written as a question, whose user text keys the flow's wording (ADR 0058,
 * ADR 0108), and what counts as Javadoc.
 */
class WordingPromptTest {

  private final TestFacts facts = TestFacts.runtime();

  private Question flow() {
    return WordingPrompt.flow(
        EntityId.parse("method:com.example.web.Controller#list()"),
        "GET /orders on shop-app",
        "shop-app receives GET /orders (Controller.list)",
        facts.find("method:com.example.web.Controller#list()", "endpoint"),
        List.of(
            new ScopeFlows.StepLine(
                0,
                "shop-app to orders (a topic), async: Sends a message [Kafka]",
                List.of(facts.find("service:com.example:app", "PUBLISHES"))),
            new ScopeFlows.StepLine(
                1, "As Other:", List.of(facts.find("service:com.example:app", "PUBLISHES")))));
  }

  @Test
  void theQuestionAboutAFlowShowsItsTriggerAndStepsWithTheirFacts() {
    Question flow = flow();

    assertThat(flow.user())
        .isEqualTo(
            """
            The flow "GET /orders on shop-app".
            It starts when shop-app receives GET /orders (Controller.list).
            [F1] method:com.example.web.Controller#list() endpoint "GET /orders" (shop:pom.xml)

            Its steps, in order:
            - shop-app to orders (a topic), async: Sends a message [Kafka]
              [F2] service:com.example:app PUBLISHES topic:kafka:orders\
             {"protocol":"Kafka","source":"method:com.example.domain.JpaOrders#all()"} (shop:pom.xml)
              - As Other:
                [F2] service:com.example:app PUBLISHES topic:kafka:orders\
             {"protocol":"Kafka","source":"method:com.example.domain.JpaOrders#all()"} (shop:pom.xml)
            """);
    assertThat(flow.facts()).containsOnlyKeys("F1", "F2");
  }

  @Test
  void onlyALineOpeningWithTwoStarsIsJavadoc() {
    assertThat(WordingPrompt.javadoc("  /** Takes orders. */\nclass A {}")).isTrue();
    assertThat(WordingPrompt.javadoc("/**\n * Takes orders.\n */")).isTrue();
    assertThat(WordingPrompt.javadoc("@RequestMapping(\"/orders/**\")\nclass A {}")).isFalse();
    assertThat(WordingPrompt.javadoc("/*********\n * Banner\n *********/")).isFalse();
    assertThat(WordingPrompt.javadoc("/**/ class A {}")).isFalse();
    assertThat(WordingPrompt.javadoc("/* Not Javadoc. */")).isFalse();
  }

  @Test
  void aFlowsQuestionDoesNotChangeWhenTheIdsItsTriggerHoldsDo() {
    assertThat(triggered("a".repeat(64)).user()).isEqualTo(triggered("b".repeat(64)).user());
    assertThat(triggered("a".repeat(64)).user()).doesNotContain("a".repeat(64));
  }

  /** A flow whose trigger fact names the fact it starts from by its ID, which an edit changes. */
  private static Question triggered(String triggerId) {
    Fact trigger =
        Fact.create(
            EntityId.parse("flow:service:com.example:app/x"),
            "TRIGGERED_BY",
            FactObject.string("browser"),
            (ObjectNode)
                FactJson.parse("{\"trigger\": \"" + triggerId + "\", \"person\": \"person:user\"}"),
            new Origin(Origin.Kind.STATIC, "test", "1"),
            Evidence.of("shop", "src/A.java", null, null),
            1.0,
            Validity.from(1));
    return WordingPrompt.flow(
        EntityId.parse("method:com.example.web.Controller#list()"),
        "GET /orders on shop-app",
        "shop-app receives GET /orders (Controller.list)",
        trigger,
        List.of());
  }
}
