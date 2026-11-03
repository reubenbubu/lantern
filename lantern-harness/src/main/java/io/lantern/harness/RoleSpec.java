package io.lantern.harness;

import io.lantern.llm.ConversationLimits;
import io.lantern.llm.LlmTool;
import io.lantern.llm.Role;
import java.util.List;
import java.util.Objects;

/**
 * One role as the harness runs it.
 *
 * @param name the role's name in the run record and the answer cache, such as {@code summarize}
 * @param modelRole which configured model it uses
 * @param promptVersion its prompt's version; a new version re-asks every item
 * @param budgetGroup the share of the run budget it counts against (ADR 0100)
 * @param packetSize the most items one request carries; 1 for a role that asks one item a request
 * @param runMode how its requests are sent
 * @param expectedOutputTokens the tokens an answer to one item is expected to take, for estimates
 * @param expectedThinkingTokens the tokens a model that thinks is expected to think before each of
 *     the role's requests, or each turn of its conversations, measured at the model's default
 *     effort (#694); counted only for a model that thinks
 * @param expectedTurns the turns one of its conversations is expected to take; 1 for a role that
 *     does not converse
 * @param expectedTurnTokens the tokens each turn after the first is expected to add to its
 *     conversation (the tool results and the turn before's output), which every later turn sends
 *     again
 * @param tools the tools its conversations offer, part of every fingerprint; empty for none
 * @param limits the caps of each of its conversations
 */
public record RoleSpec(
    String name,
    Role modelRole,
    String promptVersion,
    String budgetGroup,
    int packetSize,
    RunMode runMode,
    int expectedOutputTokens,
    int expectedThinkingTokens,
    double expectedTurns,
    int expectedTurnTokens,
    List<LlmTool> tools,
    ConversationLimits limits) {

  public RoleSpec {
    name = Text.nonBlank("Role name", name);
    if (!name.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("Role name must be lower-case words: " + name);
    }
    Objects.requireNonNull(modelRole, "modelRole");
    promptVersion = Text.nonBlank("Prompt version", promptVersion);
    budgetGroup = Text.nonBlank("Budget group", budgetGroup);
    if (packetSize < 1) {
      throw new IllegalArgumentException("Packet size must be positive: " + packetSize);
    }
    Objects.requireNonNull(runMode, "runMode");
    if (expectedOutputTokens < 1) {
      throw new IllegalArgumentException("Expected output must be positive");
    }
    if (expectedThinkingTokens < 0 || expectedTurnTokens < 0 || !(expectedTurns >= 1)) {
      throw new IllegalArgumentException(
          "Expected thinking and turn tokens must not be negative, nor expected turns below 1");
    }
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    Objects.requireNonNull(limits, "limits");
  }

  /** A role that asks one item a request, without tools or caps. */
  public static RoleSpec single(
      String name,
      Role modelRole,
      String promptVersion,
      String budgetGroup,
      RunMode runMode,
      int expectedOutputTokens) {
    return new RoleSpec(
        name,
        modelRole,
        promptVersion,
        budgetGroup,
        1,
        runMode,
        expectedOutputTokens,
        0,
        1,
        0,
        List.of(),
        ConversationLimits.NONE);
  }

  /** The same role with tools and conversation caps. */
  public RoleSpec withTools(List<LlmTool> tools, ConversationLimits limits) {
    return new RoleSpec(
        name,
        modelRole,
        promptVersion,
        budgetGroup,
        packetSize,
        runMode,
        expectedOutputTokens,
        expectedThinkingTokens,
        expectedTurns,
        expectedTurnTokens,
        tools,
        limits);
  }

  /**
   * The same role with what a model that thinks is expected to think before each request, as
   * measured (#694).
   */
  public RoleSpec withThinking(int expectedThinkingTokens) {
    return new RoleSpec(
        name,
        modelRole,
        promptVersion,
        budgetGroup,
        packetSize,
        runMode,
        expectedOutputTokens,
        expectedThinkingTokens,
        expectedTurns,
        expectedTurnTokens,
        tools,
        limits);
  }

  /**
   * The same role with the turns one of its conversations is expected to take and the tokens each
   * turn after the first is expected to add, as measured (#694).
   */
  public RoleSpec withTurns(double expectedTurns, int expectedTurnTokens) {
    return new RoleSpec(
        name,
        modelRole,
        promptVersion,
        budgetGroup,
        packetSize,
        runMode,
        expectedOutputTokens,
        expectedThinkingTokens,
        expectedTurns,
        expectedTurnTokens,
        tools,
        limits);
  }

  /** The same role with another packet size. */
  public RoleSpec withPacketSize(int packetSize) {
    return new RoleSpec(
        name,
        modelRole,
        promptVersion,
        budgetGroup,
        packetSize,
        runMode,
        expectedOutputTokens,
        expectedThinkingTokens,
        expectedTurns,
        expectedTurnTokens,
        tools,
        limits);
  }
}
