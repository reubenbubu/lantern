package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.compose.WordingPrompt.ComponentInput;
import io.lantern.compose.WordingPrompt.Question;
import io.lantern.compose.WordingPrompt.RelationshipInput;
import io.lantern.compose.WordingPrompt.UseInput;
import io.lantern.core.fact.EntityId;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.TokenUsage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The wording prompts' versions and the check of their answers (ADR 0050, ADR 0056, ADR 0058). */
class WordingPromptTest {

  private final TestFacts facts = TestFacts.runtime();

  /**
   * The fingerprint of the prompt texts and limits for {@link WordingPrompt#VERSION}. When this
   * fails, the prompt changed: raise the version, then update the fingerprint and the version here.
   * The way facts are written into the prompt is checked by {@code DescriberTest}.
   */
  @Test
  void theVersionIsRaisedWhenThePromptChanges() throws Exception {
    String texts =
        String.join(
            "\n--\n",
            WordingPrompt.SYSTEM,
            WordingPrompt.RETRY,
            String.join(",", new java.util.TreeSet<>(WordingPrompt.GENERIC)),
            Integer.toString(WordingPrompt.SENTENCE_WORDS),
            Integer.toString(WordingPrompt.PHRASE_WORDS),
            Integer.toString(Describer.MAX_OUTPUT_TOKENS),
            Integer.toString(Describer.MAX_COMPONENTS),
            Integer.toString(Describer.MAX_COMPONENT_FACTS),
            Integer.toString(Describer.MAX_ENTRY_POINTS),
            Integer.toString(Describer.MAX_CLAIMS),
            Integer.toString(Describer.MAX_RELATIONSHIPS));
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(WordingPrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise WordingPrompt.VERSION and update this fingerprint")
        .isEqualTo("1 81abfde38fb1eac342e9044851484f7cc4c058f89fad435b5020514b0a32c207");
  }

  /**
   * The fingerprint of the texts and limits of the prompt about a data store or destination for
   * {@link WordingPrompt#ELEMENT_VERSION} (ADR 0056), as above.
   */
  @Test
  void theElementVersionIsRaisedWhenItsPromptChanges() throws Exception {
    String texts =
        String.join(
            "\n--\n",
            WordingPrompt.ELEMENT_SYSTEM,
            WordingPrompt.RETRY,
            Integer.toString(WordingPrompt.SENTENCE_WORDS),
            Integer.toString(Describer.MAX_ELEMENT_OUTPUT_TOKENS),
            Integer.toString(Describer.MAX_ELEMENT_FACTS),
            Integer.toString(Describer.MAX_TABLES),
            Integer.toString(Describer.MAX_USES));
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(WordingPrompt.ELEMENT_VERSION + " " + fingerprint)
        .as("The prompt changed: raise WordingPrompt.ELEMENT_VERSION and update this fingerprint")
        .isEqualTo("1 8c8d775e08082d7fbfb8e1fd6b11d064d45fa96ae65d784e5bbaa979683b2016");
  }

  /**
   * The fingerprint of the texts and limits of the prompt about a flow for {@link
   * WordingPrompt#FLOW_VERSION} (ADR 0058), as above. The way steps are written into it is checked
   * below and by {@code DescriberTest}.
   */
  @Test
  void theFlowVersionIsRaisedWhenItsPromptChanges() throws Exception {
    String texts =
        String.join(
            "\n--\n",
            WordingPrompt.FLOW_SYSTEM,
            WordingPrompt.RETRY,
            Integer.toString(WordingPrompt.WALKTHROUGH_WORDS),
            Integer.toString(WordingPrompt.CONDITION_WORDS),
            Integer.toString(Describer.MAX_FLOW_OUTPUT_TOKENS),
            Integer.toString(SequenceFlows.MAX_MESSAGES));
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(WordingPrompt.FLOW_VERSION + " " + fingerprint)
        .as("The prompt changed: raise WordingPrompt.FLOW_VERSION and update this fingerprint")
        .isEqualTo("1 ac2d234d87f5b4c912e95c1a2414daa57a19853b88767f61b7e8adbdf7ca7bd3");
  }

  /**
   * The fingerprint of the texts and limits of the prompt about the system's purpose for {@link
   * WordingPrompt#PURPOSE_VERSION} (ADR 0059), as above. The way units and claims are written into
   * it is checked by {@code DescriberTest}.
   */
  @Test
  void thePurposeVersionIsRaisedWhenItsPromptChanges() throws Exception {
    String texts =
        String.join(
            "\n--\n",
            WordingPrompt.PURPOSE_SYSTEM,
            WordingPrompt.RETRY,
            Integer.toString(WordingPrompt.SENTENCE_WORDS),
            Integer.toString(Describer.MAX_PURPOSE_OUTPUT_TOKENS),
            Integer.toString(Describer.MAX_PURPOSE_UNITS),
            Integer.toString(Describer.MAX_PURPOSE_UNIT_FACTS),
            Integer.toString(Describer.MAX_CLAIMS));
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(WordingPrompt.PURPOSE_VERSION + " " + fingerprint)
        .as("The prompt changed: raise WordingPrompt.PURPOSE_VERSION and update this fingerprint")
        .isEqualTo("1 a50f6b9d7ff281cf681e7bf396d31fcadd83c170d4bd75fda9d2904cac8d57a3");
  }

  private Question flow() {
    return WordingPrompt.flow(
        EntityId.parse("method:com.example.web.Controller#list()"),
        "GET /orders on shop-app",
        "shop-app receives GET /orders (Controller.list)",
        facts.find("method:com.example.web.Controller#list()", "endpoint"),
        List.of(
            new WordingPrompt.FlowStep(
                0,
                "shop-app to orders (a topic), async: Sends a message [Kafka]",
                List.of(facts.find("service:com.example:app", "PUBLISHES"))),
            new WordingPrompt.FlowStep(
                1, "As Other:", List.of(facts.find("service:com.example:app", "PUBLISHES")))));
  }

  @Test
  void theQuestionAboutAFlowShowsItsTriggerAndStepsWithTheirFacts() {
    Question flow = flow();

    assertThat(flow.system()).isEqualTo(WordingPrompt.FLOW_SYSTEM);
    assertThat(flow.version()).isEqualTo(WordingPrompt.FLOW_VERSION);
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
  void theAnswerAboutAFlowHoldsAWalkthroughAndConditions() {
    assertThat(WordingPrompt.check(response("{}"), flow())).isEmpty();
    assertThat(
            WordingPrompt.check(
                response(
                    """
                    {"walkthrough": {"text": "Lists orders. It announces them.", "cites": ["F1", "F2"]},
                     "preconditions": {"text": "A request for the order list.", "cites": ["F1"]},
                     "postconditions": {"text": "Orders announced.", "cites": ["F2"]}}"""),
                flow()))
        .containsExactly(
            Map.entry(
                WordingPrompt.POSTCONDITIONS,
                new WordingAnswer.Item("Orders announced.", List.of("F2"))),
            Map.entry(
                WordingPrompt.PRECONDITIONS,
                new WordingAnswer.Item("A request for the order list.", List.of("F1"))),
            Map.entry(
                WordingPrompt.WALKTHROUGH,
                new WordingAnswer.Item("Lists orders. It announces them.", List.of("F1", "F2"))));
    String long31 = String.join(" ", java.util.Collections.nCopies(31, "word"));
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response(
                        "{\"preconditions\": {\"text\": \"" + long31 + "\", \"cites\": [\"F1\"]}}"),
                    flow()))
        .hasMessage("the pre-conditions text has more than 30 words.");
    String long101 = String.join(" ", java.util.Collections.nCopies(101, "word"));
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response(
                        "{\"walkthrough\": {\"text\": \"" + long101 + "\", \"cites\": [\"F1\"]}}"),
                    flow()))
        .hasMessage("the walkthrough has more than 100 words.");
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response("{\"postconditions\": {\"text\": \"Orders announced.\"}}"), flow()))
        .hasMessage("the post-conditions text cites nothing; cite the facts it rests on.");
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response(
                        "{\"relationships\": {\"R1\": {\"text\": \"Announces orders\","
                            + " \"cites\": [\"F2\"]}}}"),
                    flow()))
        .hasMessage("relationships names R1, which is not in the input.");
  }

  private Question element() {
    return WordingPrompt.element(
        EntityId.parse("database:db:5432/shop"),
        "database",
        "shop on db",
        "PostgreSQL",
        List.of(facts.find("database:db:5432/shop", "technology")),
        List.of(),
        List.of(
            new UseInput(
                "shop-app (a deployable unit) connects to it",
                List.of(facts.find("service:com.example:app", "USES_DATABASE")))));
  }

  @Test
  void theAnswerAboutADataStoreHoldsOneSentence() {
    assertThat(WordingPrompt.check(response("{}"), element())).isEmpty();
    assertThat(
            WordingPrompt.check(
                response("{\"element\": {\"text\": \"Keeps orders.\", \"cites\": [\"F2\"]}}"),
                element()))
        .containsExactly(
            Map.entry(
                WordingPrompt.ELEMENT, new WordingAnswer.Item("Keeps orders.", List.of("F2"))));
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response("{\"element\": {\"text\": \"Shop on db.\", \"cites\": [\"F1\"]}}"),
                    element()))
        .hasMessage(
            "the responsibility sentence of the element only restates its name; say what it does.");
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response("{\"element\": {\"text\": \"Keeps orders.\", \"cites\": [\"F3\"]}}"),
                    element()))
        .hasMessage("the element cites F3, which is not a fact in the input.");
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    response(
                        "{\"relationships\": {\"R1\": {\"text\": \"Stores orders\","
                            + " \"cites\": [\"F1\"]}}}"),
                    element()))
        .hasMessage("relationships names R1, which is not in the input.");
  }

  private Question question() {
    return WordingPrompt.question(
        EntityId.parse("service:com.example:app"),
        "shop-app",
        null,
        List.of(facts.find("service:com.example:app", "deployable-unit")),
        List.of(),
        List.of(),
        List.of(
            new ComponentInput(
                EntityId.parse("type:com.example.web.Controller"),
                "Controller",
                null,
                List.of(facts.find("type:com.example.web.Controller", "stereotype")))),
        List.of(
            new RelationshipInput(
                "sends messages to",
                "orders",
                "a topic",
                facts.find("service:com.example:app", "PUBLISHES"))));
  }

  private static LlmResponse response(String text) {
    return new LlmResponse(text, "end_turn", new TokenUsage(1, 1, 0, 0));
  }

  private Map<String, WordingAnswer.Item> check(String text) {
    return WordingPrompt.check(response(text), question());
  }

  @Test
  void theQuestionListsEachReferenceOnce() {
    Question question = question();

    assertThat(question.user())
        .isEqualTo(
            """
            The deployable unit shop-app.

            Facts about the unit:
            [F1] service:com.example:app deployable-unit "spring-boot" (shop:pom.xml)

            Components:
            [C1] Controller
              [F2] type:com.example.web.Controller stereotype "controller" {"rest":true} (shop:pom.xml)

            Relationships:
            [R1] sends messages to orders (a topic)
              [F3] service:com.example:app PUBLISHES topic:kafka:orders\
             {"protocol":"Kafka","source":"method:com.example.domain.JpaOrders#all()"} (shop:pom.xml)
            """);
    assertThat(question.facts()).containsOnlyKeys("F1", "F2", "F3");
  }

  @Test
  void everyPartIsOptional() {
    assertThat(check("Here it is: {}")).isEmpty();
    assertThat(
            check(
                """
                {"unit": {"text": "Takes orders.", "cites": ["F1"]},
                 "components": {"C1": {"text": "Serves the order pages.", "cites": ["[F2]"]}},
                 "relationships": {"r1": {"text": "Announces new orders.", "cites": ["f3"]}}}"""))
        .containsExactly(
            Map.entry("C1", new WordingAnswer.Item("Serves the order pages.", List.of("F2"))),
            Map.entry("R1", new WordingAnswer.Item("Announces new orders", List.of("F3"))),
            Map.entry("unit", new WordingAnswer.Item("Takes orders.", List.of("F1"))));
  }

  @Test
  void answersBreakingTheRulesAreRejectedWithTheReason() {
    assertThatThrownBy(() -> check("Sure.")).hasMessage("it is not one JSON object.");
    assertThatThrownBy(() -> check("{\"unit\": {\"text\": \"Takes orders.\"}}"))
        .hasMessage("the unit cites nothing; cite the facts it rests on.");
    assertThatThrownBy(
            () -> check("{\"unit\": {\"text\": \"Takes orders.\", \"cites\": [\"C1\"]}}"))
        .hasMessage("the unit cites C1, which is not a fact in the input.");
    assertThatThrownBy(
            () -> check("{\"components\": {\"C2\": {\"text\": \"A.\", \"cites\": [\"F1\"]}}}"))
        .hasMessage("components names C2, which is not in the input.");
    assertThatThrownBy(
            () -> check("{\"relationships\": {\"R1\": {\"text\": \"Uses\", \"cites\": [\"F3\"]}}}"))
        .hasMessage("the intent phrase of R1 is just \"Uses\"; say what it is for.");
    assertThatThrownBy(
            () ->
                check(
                    "{\"relationships\": {\"R1\": {\"text\": \"one two three four five six seven"
                        + " eight nine ten eleven\", \"cites\": [\"F3\"]}}}"))
        .hasMessage("the intent phrase of R1 has more than 10 words.");
    assertThatThrownBy(
            () ->
                check(
                    "{\"components\": {\"C1\": {\"text\": \"Controller.\", \"cites\": [\"F2\"]}}}"))
        .hasMessage("the responsibility sentence of C1 only restates its name; say what it does.");
    assertThatThrownBy(
            () -> check("{\"unit\": {\"text\": \"Takes orders [F1].\", \"cites\": [\"F1\"]}}"))
        .hasMessage("the text of the unit holds a reference; put references in cites.");
    assertThatThrownBy(
            () ->
                WordingPrompt.check(
                    new LlmResponse("{", LlmResponse.MAX_TOKENS, new TokenUsage(1, 1, 0, 0)),
                    question()))
        .hasMessage("it was cut off at the length limit; write shorter texts.");
  }
}
