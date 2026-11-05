package io.lantern.llm;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One request to the model with its tool loop (ADR 0081). {@link #answer()} sends the request;
 * while the model's answer asks for tool calls, it runs them with the {@link ToolBox}, sends the
 * results back as the next user turn, and calls again, until the model answers without asking for
 * tools. {@link #reply} sends the caller's next user turn, for example the reason an answer was
 * rejected, and loops the same way. Each call is a turn, logged by the client with the
 * conversation's id and turn number; {@link #end} logs the conversation itself with its turns, tool
 * calls by tool, tokens, cost and how it ended.
 *
 * <p>Only the client's budgets and a high safety cap on turns bound it: a budget that refuses a
 * turn ends it as {@link LlmConversation.Ending#BUDGET}, and an answer that still asks for tools
 * when the cap is reached ends it as {@link LlmConversation.Ending#TURN_CAP}; both throw. A failing
 * turn ends it as {@link LlmConversation.Ending#FAILED}. Not thread-safe: one conversation runs on
 * one thread.
 */
public final class Conversation {

  /** The most turns a conversation sends unless told otherwise: a guard, not a tuning lever. */
  public static final int DEFAULT_TURN_CAP = 40;

  private final LlmClient client;
  private final ToolBox tools;
  private final int turnCap;
  private final ConversationLimits limits;
  private long freshInput;
  private long lastSent;
  private boolean nudged;
  // From the nudge on, when the limits give answer turns, only the answer tools run (#892).
  private boolean answering;
  private int answerTurnsSent;
  private boolean compacted;
  private final String id = UUID.randomUUID().toString();
  private final Clock clock;
  private final Instant started;
  private final ModelConfig model;
  private final LlmRequest first;
  private final List<LlmMessage> messages;
  private final Map<String, Integer> toolCalls = new HashMap<>();
  private String purpose;
  private LlmResponse last;
  private int turns;
  private int toolTurns;
  private TokenUsage usage = TokenUsage.NONE;
  private BigDecimal cost = BigDecimal.ZERO;
  private boolean ended;

  Conversation(LlmClient client, LlmRequest request, ToolBox tools, int turnCap) {
    this(client, request, tools, turnCap, ConversationLimits.NONE);
  }

  Conversation(
      LlmClient client, LlmRequest request, ToolBox tools, int turnCap, ConversationLimits limits) {
    this.client = Objects.requireNonNull(client, "client");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.tools = Objects.requireNonNull(tools, "tools");
    if (turnCap < 1) {
      throw new IllegalArgumentException("The turn cap must be positive: " + turnCap);
    }
    // A conversation allowed more tool turns than the default safety cap gets room for them.
    this.turnCap =
        limits.toolTurns() == Integer.MAX_VALUE
            ? turnCap
            : Math.max(turnCap, limits.toolTurns() + limits.answerTurns() + 2);
    Objects.requireNonNull(request, "request");
    this.first =
        new LlmRequest(
            request.role(),
            request.purpose(),
            request.system(),
            request.messages(),
            Math.min(request.maxOutputTokens(), limits.outputTokensPerTurn()),
            request.cacheSystem(),
            tools.tools(),
            request.cacheBreaks());
    this.messages = new ArrayList<>(request.messages());
    this.purpose = request.purpose();
    this.model = client.model(request.role());
    this.clock = client.clock();
    this.started = clock.instant();
  }

  /** The conversation's id, which the call log's turns carry. */
  public String id() {
    return id;
  }

  /** How many turns it has sent so far. */
  public int turns() {
    return turns;
  }

  /** The tool calls the model asked for so far, by tool name. */
  public Map<String, Integer> toolCalls() {
    return Map.copyOf(toolCalls);
  }

  /** The turns so far, the model's answers and the tool results included. */
  public List<LlmMessage> messages() {
    return List.copyOf(messages);
  }

  /**
   * Sends the request and runs the tool loop until the model answers.
   *
   * @return the model's answer, which asks for no tools; it may be cut off
   * @throws LlmException if a turn fails or is refused, or the turn cap is reached; the
   *     conversation has then ended
   */
  public LlmResponse answer() {
    if (turns > 0 || ended) {
      throw new IllegalStateException("The conversation has already started");
    }
    return loop(false);
  }

  /**
   * Sends the next user turn after the model's last answer, and runs the tool loop until it answers
   * again.
   *
   * @param text what the user says
   * @param purpose the purpose the call log records for this turn and the ones after it
   * @throws LlmException as for {@link #answer()}
   */
  public LlmResponse reply(String text, String purpose) {
    ModelConfig.nonBlank("Reply", text);
    if (last == null || ended) {
      throw new IllegalStateException("Nothing to reply to");
    }
    this.purpose = ModelConfig.nonBlank("Purpose", purpose);
    if (last.text().isBlank()) {
      // A turn cannot be empty, so the reply goes with the last user turn instead.
      LlmMessage user = messages.removeLast();
      String joined = user.text().isEmpty() ? text : user.text() + "\n" + text;
      messages.add(
          new LlmMessage(
              user.author(), joined, user.toolCalls(), user.toolResults(), user.thinking()));
    } else {
      messages.add(LlmMessage.assistant(last.text()));
      messages.add(LlmMessage.user(text));
    }
    return loop(true);
  }

  /**
   * Ends the conversation and logs it. Only the first end counts; a conversation the loop ended
   * (budget, cap, failure) is already logged.
   */
  public void end(LlmConversation.Ending ending, String error) {
    Objects.requireNonNull(ending, "ending");
    if (ended) {
      return;
    }
    ended = true;
    client.log(
        new LlmConversation(
            id,
            started,
            Duration.between(started, clock.instant()),
            first.role(),
            first.purpose(),
            model.provider(),
            model.model(),
            turns,
            toolCalls,
            usage,
            cost,
            ending,
            error));
  }

  /** Ends the conversation as answered or rejected. */
  public void end(LlmConversation.Ending ending) {
    end(ending, null);
  }

  /** The input used so far in fresh-token equivalents ({@link ConversationLimits}). */
  public long freshInput() {
    return freshInput;
  }

  /**
   * Sends turns until the model answers. A reply's own turn is not held to the caps: it answers the
   * caller (a repair, say), not a tool result, and the caps bound tool use.
   */
  private LlmResponse loop(boolean replying) {
    boolean exempt = replying;
    while (true) {
      String capped = exempt ? null : capped();
      exempt = false;
      if (capped != null && !answering && limits.answerTurns() > 0 && turns > 0) {
        // Reached before the nudge: the model is told now, and gets its turns to answer.
        startAnswering();
        capped = null;
      }
      if (capped != null) {
        end(LlmConversation.Ending.CAPPED, capped);
        throw new ConversationCappedException(capped);
      }
      if (turns >= turnCap) {
        String reason =
            "Stopped at the safety cap of %d turns before the model answered".formatted(turns);
        end(LlmConversation.Ending.TURN_CAP, reason);
        throw new LlmException(reason);
      }
      turns++;
      if (answering) {
        answerTurnsSent++;
      }
      LlmResponse response;
      try {
        response = client.call(turnRequest(), new LlmClient.Turn(id, turns, Map.of()));
      } catch (BudgetExceededException e) {
        end(LlmConversation.Ending.BUDGET, e.getMessage());
        throw e;
      } catch (RuntimeException e) {
        end(LlmConversation.Ending.FAILED, e.getMessage());
        throw e;
      }
      usage = usage.plus(response.usage());
      cost = cost.add(model.pricing().cost(response.usage()));
      freshInput += ConversationLimits.freshEquivalent(response.usage());
      lastSent = ConversationLimits.sent(response.usage());
      last = response;
      // A turn cut off while it wrote tool calls is not an answer: its calls go back unrun, told
      // why, and the conversation goes on (#892).
      boolean cutCalls = response.cutOff() && !response.toolCalls().isEmpty();
      if (!response.wantsTools() && !cutCalls) {
        return response;
      }
      List<LlmToolResult> results = new ArrayList<>();
      for (LlmToolCall call : response.toolCalls()) {
        LlmToolResult result;
        if (!response.cutOff()) {
          // Counted when it is answered; one of a cut-off turn never ran.
          toolCalls.merge(call.name(), 1, Integer::sum);
        }
        if (response.cutOff()) {
          // A call written as the output ran out may be cut short: it is not run (#892).
          result =
              LlmToolResult.error(
                  call,
                  "Your turn reached its output limit before this call was complete, so it was not"
                      + " run. Think less and make the call again.");
        } else if (answering && !limits.answerTools().contains(call.name())) {
          result =
              LlmToolResult.error(
                  call,
                  "No exploring is left. Give your answer now with what you have found, through "
                      + String.join(" or ", new java.util.TreeSet<>(limits.answerTools()))
                      + ".");
        } else {
          try {
            result = tools.run(call);
          } catch (RuntimeException e) {
            result = LlmToolResult.error(call, "The tool failed: " + e.getMessage());
          }
        }
        if (!result.callId().equals(call.id())) {
          result = new LlmToolResult(call.id(), result.content(), result.error());
        }
        results.add(result);
      }
      messages.add(LlmMessage.assistant(response));
      toolTurns++; // the turn these results go back in is a tool turn
      String note = "";
      if (!nudged && nearCap()) {
        // Lantern's own words go after the tool results, never inside one.
        nudged = true;
        answering = limits.answerTurns() > 0;
        note = limits.nudge();
      }
      messages.add(LlmMessage.toolResults(results, note));
    }
  }

  /** The nudge, said after the last tool results when a cap was reached before it. */
  private void startAnswering() {
    answering = true;
    if (!nudged) {
      nudged = true;
      LlmMessage results = messages.removeLast();
      String text =
          results.text().isEmpty() ? limits.nudge() : results.text() + "\n" + limits.nudge();
      messages.add(
          new LlmMessage(
              results.author(),
              text,
              results.toolCalls(),
              results.toolResults(),
              results.thinking()));
    }
  }

  /** Why the next turn may not be sent, or null if it may. */
  private String capped() {
    if (answering) {
      // The answer turns are the only cap left once they began.
      return answerTurnsSent >= limits.answerTurns()
          ? "Stopped after %d turns to answer".formatted(limits.answerTurns())
          : null;
    }
    if (toolTurns > limits.toolTurns()) {
      return "Stopped at the cap of %d tool turns".formatted(limits.toolTurns());
    }
    if (freshInput >= limits.inputTokens()) {
      return "Stopped at the cap of %d input tokens (fresh-token equivalents)"
          .formatted(limits.inputTokens());
    }
    if (lastSent >= limits.contextTokens()) {
      return "Stopped at the cap of %d tokens a turn".formatted(limits.contextTokens());
    }
    return null;
  }

  /** Whether the turn about to be sent passes {@value ConversationLimits#NUDGE_SHARE} of a cap. */
  private boolean nearCap() {
    return toolTurns >= Math.ceil(limits.toolTurns() * ConversationLimits.NUDGE_SHARE)
        || freshInput >= limits.inputTokens() * ConversationLimits.NUDGE_SHARE
        || lastSent >= limits.contextTokens() * ConversationLimits.NUDGE_SHARE;
  }

  /** The share of the model's context window past which the history is compacted, once. */
  static final double COMPACT_AT = 0.6;

  /** How many of the latest tool-result turns compaction keeps whole. */
  static final int KEEP_TURNS = 4;

  /**
   * The next turn's request (knowledge-build epic, section 10, Prompt layout for caching): the
   * history as sent before, byte for byte, with a cache breakpoint after its last turn, moved
   * forward every turn, so each turn pays full price only for what is new and reads the rest from
   * the cache (#349). The first request's own breakpoints stay, within the provider's limit; the
   * latest of them gives way to the moving one when there is no room.
   */
  private LlmRequest turnRequest() {
    compactIfLong();
    LlmRequest request = first.withMessages(messages).withPurpose(purpose);
    if (!model.supports(Capability.PROMPT_CACHING)) {
      return request;
    }
    CacheBreak moving = CacheBreak.afterMessage(messages.size() - 1);
    List<CacheBreak> breaks = new ArrayList<>(first.cacheBreaks());
    breaks.remove(moving);
    int room = CacheBreak.MAX - (model.cachesSystem(first) ? 1 : 0);
    while (!breaks.isEmpty() && breaks.size() + 1 > room) {
      breaks.removeLast();
    }
    breaks.add(moving);
    return request.withCacheBreaks(breaks);
  }

  /**
   * Once per conversation, when the history passes {@value #COMPACT_AT} of the model's context
   * window, replaces each tool result older than the last {@value #KEEP_TURNS} tool-result turns by
   * one line naming the call, to make room; caching restarts from there. It does not guarantee the
   * conversation fits: if the last turns alone are too long, the provider refuses the turn. The
   * model can read again what it needs to cite.
   */
  private void compactIfLong() {
    if (compacted
        || first.withMessages(messages).estimatedInputTokens()
            <= model.contextWindow() * COMPACT_AT) {
      return;
    }
    List<Integer> resultTurns = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      if (!messages.get(i).toolResults().isEmpty()) {
        resultTurns.add(i);
      }
    }
    Map<String, LlmToolCall> calls = new HashMap<>();
    messages.forEach(message -> message.toolCalls().forEach(call -> calls.put(call.id(), call)));
    List<Integer> older = resultTurns.subList(0, Math.max(0, resultTurns.size() - KEEP_TURNS));
    // The one compaction is spent only when it elides something.
    compacted = !older.isEmpty();
    for (int i : older) {
      LlmMessage turn = messages.get(i);
      List<LlmToolResult> elided = new ArrayList<>();
      for (LlmToolResult result : turn.toolResults()) {
        LlmToolCall call = calls.get(result.callId());
        String line =
            call == null
                ? "[tool result elided]"
                : "[" + call.name() + " " + call.input() + " elided]";
        elided.add(new LlmToolResult(result.callId(), line, result.error()));
      }
      messages.set(
          i, new LlmMessage(turn.author(), turn.text(), turn.toolCalls(), elided, turn.thinking()));
    }
  }
}
