package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.llm.ConversationLimits;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.Role;
import io.lantern.llm.ToolBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A role whose submit schema is a list and whose entries are kept in part (knowledge-build epic,
 * section 11.2, Modes B and C): each proposal is accepted or rejected on its own, and a later
 * submission adds to what was accepted.
 */
class PartialSubmitTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final ToolBox NONE =
      new ToolBox() {
        @Override
        public List<LlmTool> tools() {
          return List.of();
        }

        @Override
        public LlmToolResult run(LlmToolCall call) {
          return LlmToolResult.error(call, "no tools");
        }
      };

  /** Proposals submitted as a list, numbered P1, P2, ... across submissions, of one item. */
  private static final class Proposals implements PacketRole {
    private int count;

    @Override
    public PromptLayout layout(List<Item> packet, List<String> ids) {
      return new PromptLayout(
          "Common rules.", "Task: propose.", "<facts>\n[F1] a\n</facts>", "", false, "<items/>");
    }

    @Override
    public int maxOutputTokens(int items) {
      return 100;
    }

    @Override
    public CheckInputs inputs(List<Item> packet, List<String> ids) {
      return new CheckInputs(
          JSON.readTree("{\"type\": \"object\"}"),
          Map.of("F1", "a"),
          Set.of('F'),
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of());
    }

    @Override
    public boolean partial() {
      return true;
    }

    @Override
    public synchronized JsonNode envelope(JsonNode submitted) {
      ObjectNode envelope = JSON.createObjectNode();
      ObjectNode parts = envelope.putObject("items").putObject("I1").putObject("parts");
      for (JsonNode entry : submitted.path("items")) {
        parts.set("P" + (++count), entry);
      }
      return envelope;
    }

    @Override
    public ItemAnswer read(String id, Item item, JsonNode entry) {
      List<CheckedText> texts = new ArrayList<>();
      for (var part : entry.path("parts").properties()) {
        List<String> cites = new ArrayList<>();
        part.getValue().path("cites").forEach(c -> cites.add(c.asString()));
        texts.add(
            new CheckedText(
                "parts." + part.getKey(),
                CheckedText.Kind.PROSE,
                part.getValue().path("text").asString(""),
                cites,
                20,
                "",
                Set.of()));
      }
      return new ItemAnswer(id, entry, texts, List.of());
    }

    @Override
    public String store(Item item, ItemAnswer accepted) {
      return accepted.raw().toString();
    }
  }

  private static int turn(LlmRequest request) {
    return (request.messages().size() + 1) / 2;
  }

  private static LlmToolCall submit(int turn, String items) {
    return new LlmToolCall(
        "s" + turn, SubmitTool.NAME, JSON.readTree("{\"items\": [" + items + "]}"));
  }

  @Test
  void eachProposalIsKeptOrRejectedOnItsOwnAndALaterSubmissionAdds() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .callingTools(
                r ->
                    switch (turn(r)) {
                      case 1 ->
                          List.of(
                              submit(
                                  1,
                                  "{\"text\": \"Keeps records\", \"cites\": [\"F1\"]},"
                                      + " {\"text\": \"Bills\", \"cites\": [\"F7\"]}"));
                      case 2 ->
                          List.of(submit(2, "{\"text\": \"Sends mail\", \"cites\": [\"F1\"]}"));
                      default -> List.of();
                    })
            .answering("Done.");
    Item item = new Item("unit", "unit", List.of(FingerprintInput.text("unit")), "the unit");
    RoleSpec role =
        RoleSpec.single("explore", Role.PROPOSE_RULES, "1", "investigate", RunMode.INTERACTIVE, 200)
            .withTools(List.of(), new ConversationLimits(16, 100_000, 4_000));

    Map<String, Outcome> outcomes =
        harness(client(fake), new InMemoryAnswerStore())
            .session(role)
            .converse(List.of(item), new Proposals(), NONE, List::of);

    assertThat(fake.sent().get(1).request().messages().getLast().toolResults().getFirst().content())
        .contains("accepted in part")
        .contains("parts.P2: Cites F7, which is not in your input.");
    Outcome outcome = outcomes.get("unit");
    assertThat(outcome.kind()).isEqualTo(Outcome.Kind.ANSWERED);
    JsonNode stored = JSON.readTree(outcome.answer()).path("parts");
    assertThat(stored.has("P1")).isTrue();
    assertThat(stored.has("P2")).isFalse();
    assertThat(stored.path("P3").path("text").asString()).isEqualTo("Sends mail");
  }
}
