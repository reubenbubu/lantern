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
  private TokenUsage usage = TokenUsage.NONE;
  private BigDecimal cost = BigDecimal.ZERO;
  private boolean ended;

  Conversation(LlmClient client, LlmRequest request, ToolBox tools, int turnCap) {
    this.client = Objects.requireNonNull(client, "client");
    this.tools = Objects.requireNonNull(tools, "tools");
    if (turnCap < 1) {
      throw new IllegalArgumentException("The turn cap must be positive: " + turnCap);
    }
    this.turnCap = turnCap;
    Objects.requireNonNull(request, "request");
    this.first =
        new LlmRequest(
            request.role(),
            request.purpose(),
            request.system(),
            request.messages(),
            request.maxOutputTokens(),
            request.cacheSystem(),
            tools.tools());
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
    return loop();
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
      messages.add(new LlmMessage(user.author(), joined, user.toolCalls(), user.toolResults()));
    } else {
      messages.add(LlmMessage.assistant(last.text()));
      messages.add(LlmMessage.user(text));
    }
    return loop();
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

  private LlmResponse loop() {
    while (true) {
      if (turns >= turnCap) {
        String reason =
            "Stopped after %d turns, the safety cap, with the model still asking for tools"
                .formatted(turns);
        end(LlmConversation.Ending.TURN_CAP, reason);
        throw new LlmException(reason);
      }
      turns++;
      LlmResponse response;
      try {
        response =
            client.call(
                first.withMessages(messages).withPurpose(purpose),
                new LlmClient.Turn(id, turns, Map.of()));
      } catch (BudgetExceededException e) {
        end(LlmConversation.Ending.BUDGET, e.getMessage());
        throw e;
      } catch (RuntimeException e) {
        end(LlmConversation.Ending.FAILED, e.getMessage());
        throw e;
      }
      usage =
          new TokenUsage(
              usage.input() + response.usage().input(),
              usage.output() + response.usage().output(),
              usage.cacheWrite() + response.usage().cacheWrite(),
              usage.cacheRead() + response.usage().cacheRead());
      cost = cost.add(model.pricing().cost(response.usage()));
      last = response;
      if (!response.wantsTools()) {
        return response;
      }
      List<LlmToolResult> results = new ArrayList<>();
      for (LlmToolCall call : response.toolCalls()) {
        toolCalls.merge(call.name(), 1, Integer::sum);
        LlmToolResult result;
        try {
          result = tools.run(call);
        } catch (RuntimeException e) {
          result = LlmToolResult.error(call, "The tool failed: " + e.getMessage());
        }
        if (!result.callId().equals(call.id())) {
          result = new LlmToolResult(call.id(), result.content(), result.error());
        }
        results.add(result);
      }
      messages.add(LlmMessage.assistant(response.text(), response.toolCalls()));
      messages.add(LlmMessage.toolResults(results, ""));
    }
  }
}
