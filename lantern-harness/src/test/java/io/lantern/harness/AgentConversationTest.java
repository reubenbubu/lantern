package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.ConversationLimits;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.Role;
import io.lantern.llm.ToolBox;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent loop (knowledge-build epic, section 10): read-only tools, answers through {@code
 * submit} verified at once, a second failure dropped, caps that keep what was submitted.
 */
class AgentConversationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** A read-only tool that shows a fact. */
  private static final ToolBox LOOK =
      new ToolBox() {
        @Override
        public List<LlmTool> tools() {
          return List.of(
              new LlmTool(
                  "look",
                  "Shows a fact.",
                  JSON.readTree("{\"type\": \"object\", \"properties\": {}}")));
        }

        @Override
        public LlmToolResult run(LlmToolCall call) {
          return LlmToolResult.of(call, "[F1] a");
        }
      };

  private static LlmToolCall submit(int turn, String items) {
    return new LlmToolCall(
        "s" + turn, SubmitTool.NAME, JSON.readTree("{\"items\": " + items + "}"));
  }

  private static String good(String text) {
    return "{\"responsibility\": {\"text\": \"" + text + "\", \"cites\": [\"F1\"]}}";
  }

  private static int turn(LlmRequest request) {
    return (request.messages().size() + 1) / 2;
  }

  private static Item item(String subject) {
    return new Item(
        subject, "repo/unit", List.of(FingerprintInput.text(subject)), "store " + subject);
  }

  private static RoleSpec role(int toolTurns) {
    return RoleSpec.single(
            "investigate", Role.PROPOSE_RULES, "1", "investigate", RunMode.INTERACTIVE, 200)
        .withTools(LOOK.tools(), new ConversationLimits(toolTurns, 100_000, 4_000));
  }

  @Test
  void theModelFixesARejectedItemThroughSubmitAndASecondFailureIsDropped() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .callingTools(
                request ->
                    switch (turn(request)) {
                      case 1 -> List.of(new LlmToolCall("l1", "look", JSON.createObjectNode()));
                      case 2 ->
                          List.of(
                              submit(
                                  2,
                                  "{\"I1\": "
                                      + good("Keeps the records")
                                      + ", \"I2\": {\"responsibility\": {\"text\": \"x\", \"cites\": [\"F7\"]}}"
                                      + ", \"I3\": {\"responsibility\": {\"text\": \"y\", \"cites\": [\"F8\"]}}}"));
                      case 3 ->
                          List.of(
                              submit(
                                  3,
                                  "{\"I2\": "
                                      + good("Bills each customer")
                                      + ", \"I3\": {\"responsibility\": {\"text\": \"z\", \"cites\": [\"F9\"]}}}"));
                      default -> List.of();
                    })
            .answering("Done.");
    List<Item> items = List.of(item("a"), item("b"), item("c"));

    Map<String, Outcome> outcomes =
        harness(client(fake), store)
            .session(role(16))
            .converse(items, PacketsTest.SENTENCES, LOOK, List::of);

    assertThat(outcomes.get("a").kind()).isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(outcomes.get("b").kind()).isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(outcomes.get("c").kind()).isEqualTo(Outcome.Kind.DROPPED);
    // The verdicts come back in the tool result of the submit call.
    LlmMessage results = fake.sent().get(2).request().messages().getLast();
    assertThat(results.toolResults().getFirst().content())
        .contains("\"I1\":\"accepted\"")
        .contains("\"I2\":\"rejected: Cites F7, which is not in your input.");
    // submit is offered as an ordinary tool next to the role's.
    assertThat(fake.sent().getFirst().request().tools())
        .extracting(LlmTool::name)
        .containsExactly("look", "submit");
    assertThat(store.size()).isEqualTo(2);
  }

  @Test
  void atTheCapWhatWasSubmittedIsKeptAndWhatWasNotCountsAsOmitted() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .callingTools(
                request ->
                    turn(request) == 1
                        ? List.of(submit(1, "{\"I1\": " + good("Keeps the records") + "}"))
                        : List.of(
                            new LlmToolCall("l" + turn(request), "look", JSON.createObjectNode())));

    Map<String, Outcome> outcomes =
        harness(client(fake), store)
            .session(role(2))
            .converse(List.of(item("a"), item("b")), PacketsTest.SENTENCES, LOOK, List::of);

    assertThat(outcomes.get("a").kind()).isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(outcomes.get("b").kind()).isEqualTo(Outcome.Kind.OMITTED);
    // The first turn and two tool turns, then the cap.
    assertThat(fake.sent()).hasSize(3);
    // At 75% of the cap the tool results came back with the nudge after them.
    assertThat(fake.sent().get(2).request().messages().getLast().text())
        .isEqualTo(ConversationLimits.DEFAULT_NUDGE);
    // A warm rerun asks nothing.
    FakeLlmProvider unused = new FakeLlmProvider();
    Map<String, Outcome> again =
        harness(client(unused), store)
            .session(role(2))
            .converse(List.of(item("a"), item("b")), PacketsTest.SENTENCES, LOOK, List::of);
    assertThat(again.values()).allSatisfy(o -> assertThat(o.kind()).isEqualTo(Outcome.Kind.CACHED));
    assertThat(unused.sent()).isEmpty();
  }
}
