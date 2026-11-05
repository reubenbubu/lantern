package io.lantern.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ConversationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FakeLlmProvider fake = new FakeLlmProvider();
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);

  /** Looks a word up: "lookup" answers with the word upper-cased, "fail" with an error. */
  private static final class Words implements ToolBox {

    final List<LlmToolCall> ran = new ArrayList<>();

    @Override
    public List<LlmTool> tools() {
      ObjectNode schema = JSON.createObjectNode().put("type", "object");
      schema.putObject("properties").putObject("word").put("type", "string");
      schema.putArray("required").add("word");
      return List.of(
          new LlmTool("lookup", "Looks a word up.", schema),
          new LlmTool("fail", "Always fails.", JSON.createObjectNode().put("type", "object")));
    }

    @Override
    public LlmToolResult run(LlmToolCall call) {
      ran.add(call);
      if (call.name().equals("fail")) {
        throw new IllegalStateException("broken");
      }
      return LlmToolResult.of(call, call.input().path("word").asString().toUpperCase());
    }
  }

  private static LlmToolCall lookup(String id, String word) {
    return new LlmToolCall(id, "lookup", JSON.createObjectNode().put("word", word));
  }

  private LlmClient client(ModelConfig model, BudgetGuard budget) {
    return new LlmClient(ModelSettings.single(model), List.of(fake), budget, log, clock);
  }

  private static LlmRequest request() {
    return LlmRequest.of(Role.RESOLVE, "resolve a link", "You resolve.", "Which unit?", 200);
  }

  /** Two lookups in the first answer, one in the second, then the answer. */
  private void twoToolTurnsThenAnswer() {
    fake.callingTools(
            r ->
                switch (r.messages().size()) {
                  case 1 -> List.of(lookup("t1", "orders"), lookup("t2", "payments"));
                  case 3 -> List.of(lookup("t3", "shipping"));
                  default -> List.of();
                })
        .answering("{\"target\": \"C1\"}");
  }

  @Test
  void runsTheToolsTheModelAsksForUntilItAnswers() {
    twoToolTurnsThenAnswer();
    Words words = new Words();
    Conversation conversation =
        client(fake.model("m"), BudgetGuard.unlimited("run")).converse(request(), words);

    LlmResponse answer = conversation.answer();
    conversation.end(LlmConversation.Ending.ANSWERED);

    assertThat(answer.text()).isEqualTo("{\"target\": \"C1\"}");
    assertThat(words.ran).extracting(LlmToolCall::id).containsExactly("t1", "t2", "t3");
    assertThat(fake.sent()).hasSize(3);
    LlmRequest third = fake.sent().get(2).request();
    assertThat(third.tools()).extracting(LlmTool::name).containsExactly("lookup", "fail");
    assertThat(third.messages()).hasSize(5);
    assertThat(third.messages().get(1).toolCalls()).extracting(LlmToolCall::id).contains("t2");
    assertThat(third.messages().get(2).toolResults())
        .extracting(LlmToolResult::callId, LlmToolResult::content)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("t1", "ORDERS"),
            org.assertj.core.groups.Tuple.tuple("t2", "PAYMENTS"));
    assertThat(third.messages().get(4).toolResults().getFirst().content()).isEqualTo("SHIPPING");

    // Each turn is a call with the conversation's id, its number and the tools it asked for.
    assertThat(log.calls())
        .extracting(LlmCall::conversation, LlmCall::turn, LlmCall::toolCalls)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(conversation.id(), 1, Map.of("lookup", 2)),
            org.assertj.core.groups.Tuple.tuple(conversation.id(), 2, Map.of("lookup", 1)),
            org.assertj.core.groups.Tuple.tuple(conversation.id(), 3, Map.of()));
    LlmConversation logged = log.conversations().getFirst();
    assertThat(logged.id()).isEqualTo(conversation.id());
    assertThat(logged.turns()).isEqualTo(3);
    assertThat(logged.toolCalls()).isEqualTo(Map.of("lookup", 3));
    assertThat(logged.toolCallCount()).isEqualTo(3);
    assertThat(logged.ending()).isEqualTo(LlmConversation.Ending.ANSWERED);
    assertThat(logged.purpose()).isEqualTo("resolve a link");
    long tokens = log.calls().stream().mapToLong(c -> c.usage().total()).sum();
    assertThat(logged.usage().total()).isEqualTo(tokens);
  }

  @Test
  void aToolThatFailsAnswersWithAnError() {
    fake.callingTools(
            r ->
                r.messages().size() == 1
                    ? List.of(new LlmToolCall("t1", "fail", JSON.createObjectNode()))
                    : List.of())
        .answering("done");
    Conversation conversation =
        client(fake.model("m"), BudgetGuard.unlimited("run")).converse(request(), new Words());

    conversation.answer();

    LlmToolResult result = fake.sent().get(1).request().messages().get(2).toolResults().getFirst();
    assertThat(result.error()).isTrue();
    assertThat(result.content()).contains("broken");
  }

  @Test
  void aReplyGoesOnAfterTheAnswerAndKeepsCountingTurns() {
    twoToolTurnsThenAnswer();
    Conversation conversation =
        client(fake.model("m"), BudgetGuard.unlimited("run")).converse(request(), new Words());
    conversation.answer();

    conversation.reply("Rejected: cite a candidate.", "resolve a link (retry)");
    conversation.end(LlmConversation.Ending.REJECTED);

    LlmRequest retry = fake.sent().getLast().request();
    assertThat(retry.purpose()).isEqualTo("resolve a link (retry)");
    assertThat(retry.messages().get(5).text()).isEqualTo("{\"target\": \"C1\"}");
    assertThat(retry.messages().getLast().text()).isEqualTo("Rejected: cite a candidate.");
    LlmConversation logged = log.conversations().getFirst();
    assertThat(logged.turns()).isEqualTo(4);
    assertThat(logged.ending()).isEqualTo(LlmConversation.Ending.REJECTED);
  }

  @Test
  void aReplyToAnEmptyAnswerJoinsTheLastUserTurn() {
    fake.callingTools(r -> r.messages().size() == 1 ? List.of(lookup("t1", "a")) : List.of())
        .answering(r -> r.messages().getLast().text().isEmpty() ? " " : "ok");
    Conversation conversation =
        client(fake.model("m"), BudgetGuard.unlimited("run")).converse(request(), new Words());
    assertThat(conversation.answer().text()).isBlank();

    assertThat(conversation.reply("Answer with JSON.", "again").text()).isEqualTo("ok");

    LlmMessage last = fake.sent().getLast().request().messages().getLast();
    assertThat(last.toolResults()).hasSize(1);
    assertThat(last.text()).isEqualTo("Answer with JSON.");
  }

  @Test
  void aBudgetEndsTheConversation() {
    fake.callingTools(r -> List.of(lookup("t" + r.messages().size(), "x")));
    // Each turn reserves its worst case: about 60 tokens of input and 200 of output.
    BudgetGuard budget = new BudgetGuard("run", 600L, null);
    Conversation conversation = client(fake.model("m"), budget).converse(request(), new Words());

    assertThatThrownBy(conversation::answer).isInstanceOf(BudgetExceededException.class);

    LlmConversation logged = log.conversations().getFirst();
    assertThat(logged.ending()).isEqualTo(LlmConversation.Ending.BUDGET);
    assertThat(logged.turns()).isGreaterThan(1);
    assertThat(logged.error()).isNotBlank();
    assertThat(log.calls().getLast().outcome()).isEqualTo(LlmCall.Outcome.REFUSED);
  }

  @Test
  void theSafetyCapEndsAConversationThatNeverAnswers() {
    fake.callingTools(r -> List.of(lookup("t" + r.messages().size(), "x")));
    LlmClient client = client(fake.model("m"), BudgetGuard.unlimited("run"));
    Conversation conversation = new Conversation(client, request(), new Words(), 3);

    assertThatThrownBy(conversation::answer)
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("3 turns");

    assertThat(fake.sent()).hasSize(3);
    assertThat(log.conversations().getFirst().ending()).isEqualTo(LlmConversation.Ending.TURN_CAP);
    assertThat(log.conversations().getFirst().toolCalls()).isEqualTo(Map.of("lookup", 3));
  }

  @Test
  void aModelWithoutToolUseIsRefusedBeforeSending() {
    ModelConfig plain =
        new ModelConfig(
            "fake", "plain", EnumSet.of(Capability.PROMPT_CACHING), 100_000, Pricing.FREE);
    Conversation conversation =
        client(plain, BudgetGuard.unlimited("run")).converse(request(), new Words());

    assertThatThrownBy(conversation::answer)
        .isInstanceOf(UnsupportedCapabilityException.class)
        .hasMessageContaining("tool use");

    assertThat(fake.sent()).isEmpty();
    assertThat(log.calls().getFirst().outcome()).isEqualTo(LlmCall.Outcome.REFUSED);
    assertThat(log.conversations().getFirst().ending()).isEqualTo(LlmConversation.Ending.FAILED);
  }

  @Test
  void aToolBoxWithoutToolsSendsAPlainRequest() {
    ModelConfig plain = new ModelConfig("fake", "plain", Set.of(), 100_000, Pricing.FREE);
    ToolBox none =
        new ToolBox() {
          @Override
          public List<LlmTool> tools() {
            return List.of();
          }

          @Override
          public LlmToolResult run(LlmToolCall call) {
            throw new AssertionError("no tools");
          }
        };
    fake.answering("plain");

    Conversation conversation =
        client(plain, BudgetGuard.unlimited("run")).converse(request(), none);

    assertThat(conversation.answer().text()).isEqualTo("plain");
    assertThat(fake.sent().getFirst().request().tools()).isEmpty();
  }

  @Test
  void costsAddUpOverTheTurns() {
    Pricing priced =
        new Pricing(
            BigDecimal.valueOf(3), BigDecimal.valueOf(15), BigDecimal.ZERO, BigDecimal.ZERO);
    twoToolTurnsThenAnswer();
    ModelConfig model =
        new ModelConfig("fake", "priced", EnumSet.of(Capability.TOOL_USE), 100_000, priced);
    Conversation conversation =
        client(model, BudgetGuard.unlimited("run")).converse(request(), new Words());

    conversation.answer();
    conversation.end(LlmConversation.Ending.ANSWERED);

    BigDecimal perTurn =
        log.calls().stream().map(LlmCall::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(perTurn.signum()).isPositive();
    assertThat(log.conversations().getFirst().costUsd()).isEqualByComparingTo(perTurn);
  }

  @Test
  void aToolLoopReplaysFromItsRecordings(@TempDir Path recordings) {
    twoToolTurnsThenAnswer();
    ModelConfig model = fake.model("m");
    LlmClient recording =
        new LlmClient(
            ModelSettings.single(model),
            List.of(RecordReplayLlmProvider.recording(fake, recordings)),
            BudgetGuard.unlimited("run"),
            log,
            clock);
    recording.converse(request(), new Words()).answer();
    assertThat(fake.sent()).hasSize(3);

    LlmClient replaying =
        new LlmClient(
            ModelSettings.single(model),
            List.of(RecordReplayLlmProvider.replaying("fake", recordings)),
            BudgetGuard.unlimited("run"),
            log,
            clock);
    Words words = new Words();
    Conversation replayed = replaying.converse(request(), words);

    assertThat(replayed.answer().text()).isEqualTo("{\"target\": \"C1\"}");
    assertThat(replayed.turns()).isEqualTo(3);
    assertThat(words.ran).extracting(LlmToolCall::id).containsExactly("t1", "t2", "t3");
    assertThat(fake.sent()).hasSize(3);
  }

  @Test
  void eachTurnResendsTheHistoryUnchangedWithABreakpointAfterItsLastTurn() {
    fake.simulatingPromptCache();
    twoToolTurnsThenAnswer();
    Conversation conversation =
        client(fake.model("m"), BudgetGuard.unlimited("run")).converse(request(), new Words());

    conversation.answer();
    conversation.end(LlmConversation.Ending.ANSWERED);

    List<LlmRequest> sent = fake.sent().stream().map(FakeLlmProvider.Sent::request).toList();
    assertThat(sent).hasSize(3);
    for (int n = 0; n < sent.size(); n++) {
      LlmRequest turn = sent.get(n);
      assertThat(turn.cacheBreaks())
          .as("turn %d ends with a breakpoint on its own last block", n + 1)
          .endsWith(CacheBreak.afterMessage(turn.messages().size() - 1));
      if (n > 0) {
        LlmRequest before = sent.get(n - 1);
        assertThat(turn.messages().subList(0, before.messages().size()))
            .as("turn %d carries turn %d's history unchanged", n + 1, n)
            .isEqualTo(before.messages());
        assertThat(turn.system()).isEqualTo(before.system());
        assertThat(turn.tools()).isEqualTo(before.tools());
      }
    }
    // Every turn after the first reads the history before it from the cache.
    List<LlmCall> turns = log.calls();
    assertThat(turns).hasSize(3);
    assertThat(turns.getFirst().usage().cacheRead()).isZero();
    assertThat(turns.subList(1, 3))
        .allSatisfy(call -> assertThat(call.usage().cacheRead()).isPositive());
  }

  @Test
  void aLongHistoryIsCompactedOnceKeepingTheLastFourToolTurnsWhole() {
    // Seven tool turns, each with a long result, on a model whose window the history outgrows.
    fake.callingTools(
            r ->
                r.messages().size() < 15
                    ? List.of(lookup("t" + r.messages().size(), "w".repeat(800)))
                    : List.of())
        .answering("{\"target\": \"C1\"}");
    ModelConfig small = fake.model("m");
    small =
        new ModelConfig(
            small.provider(), small.model(), small.capabilities(), 4_000, small.pricing());
    Conversation conversation =
        client(small, BudgetGuard.unlimited("run")).converse(request(), new Words());

    conversation.answer();
    conversation.end(LlmConversation.Ending.ANSWERED);

    List<LlmRequest> sent = fake.sent().stream().map(FakeLlmProvider.Sent::request).toList();
    LlmRequest last = sent.getLast();
    List<LlmMessage> results =
        last.messages().stream().filter(m -> !m.toolResults().isEmpty()).toList();
    assertThat(results).hasSize(7);
    long elided =
        results.stream()
            .filter(m -> m.toolResults().getFirst().content().endsWith(" elided]"))
            .count();
    assertThat(elided).isPositive();
    assertThat(results.subList(0, (int) elided))
        .allSatisfy(m -> assertThat(m.toolResults().getFirst().content()).startsWith("[lookup {"));
    assertThat(results.subList((int) elided, 7))
        .hasSizeGreaterThanOrEqualTo(Conversation.KEEP_TURNS)
        .allSatisfy(m -> assertThat(m.toolResults().getFirst().content()).startsWith("WWW"));
    // Compacted once: the turn that compacted and every later one keep the same elided history.
    int compactedAt = -1;
    for (int n = 0; n < sent.size(); n++) {
      if (sent.get(n).messages().stream()
          .anyMatch(
              m -> m.toolResults().stream().anyMatch(r -> r.content().endsWith(" elided]")))) {
        compactedAt = n;
        break;
      }
    }
    assertThat(compactedAt).isPositive();
    for (int n = compactedAt + 1; n < sent.size(); n++) {
      LlmRequest before = sent.get(n - 1);
      assertThat(sent.get(n).messages().subList(0, before.messages().size()))
          .isEqualTo(before.messages());
    }
  }

  @Test
  void theFakeCacheLooksBackAtMostTwentyBlocksForAnEarlierPrefix() {
    fake.simulatingPromptCache().answering("ok");
    LlmClient client = client(fake.model("m"), BudgetGuard.unlimited("run"));
    LlmRequest start = request().withCacheBreaks(List.of(CacheBreak.afterMessage(0)));
    client.call(start);

    // Ten blocks back, the first turn is found; twenty-two back, only the system prompt is.
    long near = client.call(grown(start, 5)).usage().cacheRead();
    long far = client.call(grown(start, 11)).usage().cacheRead();
    assertThat(far).isEqualTo(Tokens.estimate("You resolve."));
    assertThat(near).isGreaterThan(far);
  }

  /** The request with {@code pairs} more answer and question turns, a breakpoint after the last. */
  private static LlmRequest grown(LlmRequest start, int pairs) {
    List<LlmMessage> messages = new ArrayList<>(start.messages());
    for (int i = 0; i < pairs; i++) {
      messages.add(LlmMessage.assistant("Answer " + i + " of " + pairs + "."));
      messages.add(LlmMessage.user("Question " + i + " of " + pairs + "?"));
    }
    return start
        .withCacheBreaks(List.of())
        .withMessages(messages)
        .withCacheBreaks(List.of(CacheBreak.afterMessage(messages.size() - 1)));
  }
}
