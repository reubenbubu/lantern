package io.lantern.llm;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One call to the LLM.
 *
 * @param role what the call is for, which decides the model
 * @param purpose a short description of this particular call, for the call log (for example
 *     "summarize class io.example.Order"); not sent to the model
 * @param system the system prompt, or {@code null} for none
 * @param messages the conversation, starting and ending with a user turn
 * @param maxOutputTokens the most tokens the model may write
 * @param cacheSystem whether to cache the system prompt; needs {@link Capability#PROMPT_CACHING}. A
 *     model with that capability caches it whether set or not ({@link ModelConfig#cachesSystem})
 * @param tools the tools the model may call, each with a distinct name; any needs {@link
 *     Capability#TOOL_USE}
 * @param cacheBreaks where the provider may cache the prompt (knowledge-build epic, section 10), in
 *     the order they come in the request, at most {@value CacheBreak#MAX} with {@code cacheSystem}
 *     counted as one; any needs {@link Capability#PROMPT_CACHING}
 */
public record LlmRequest(
    Role role,
    String purpose,
    String system,
    List<LlmMessage> messages,
    int maxOutputTokens,
    boolean cacheSystem,
    List<LlmTool> tools,
    List<CacheBreak> cacheBreaks) {

  public LlmRequest {
    Objects.requireNonNull(role, "role");
    purpose = ModelConfig.nonBlank("Purpose", purpose);
    if (system != null && system.isBlank()) {
      throw new IllegalArgumentException("System prompt must be null or not blank");
    }
    messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    if (messages.isEmpty()
        || messages.getFirst().author() != LlmMessage.Author.USER
        || messages.getLast().author() != LlmMessage.Author.USER) {
      throw new IllegalArgumentException("Messages must start and end with a user turn");
    }
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("Max output tokens must be positive: " + maxOutputTokens);
    }
    if (cacheSystem && system == null) {
      throw new IllegalArgumentException("Cannot cache a missing system prompt");
    }
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    Set<String> names = new HashSet<>();
    for (LlmTool tool : tools) {
      if (!names.add(tool.name())) {
        throw new IllegalArgumentException("Two tools are named " + tool.name());
      }
    }
    boolean usesTools =
        messages.stream().anyMatch(m -> !m.toolCalls().isEmpty() || !m.toolResults().isEmpty());
    if (usesTools && tools.isEmpty()) {
      throw new IllegalArgumentException("A conversation with tool calls must offer the tools");
    }
    cacheBreaks = List.copyOf(Objects.requireNonNull(cacheBreaks, "cacheBreaks"));
    if (cacheBreaks.size() + (cacheSystem ? 1 : 0) > CacheBreak.MAX) {
      throw new IllegalArgumentException(
          "At most " + CacheBreak.MAX + " cache breakpoints: " + cacheBreaks);
    }
    CacheBreak previous = null;
    for (CacheBreak at : cacheBreaks) {
      String text = at.message() == CacheBreak.SYSTEM ? system : null;
      if (at.message() == CacheBreak.SYSTEM && system == null) {
        throw new IllegalArgumentException("A cache breakpoint in a missing system prompt");
      }
      if (at.message() >= messages.size()) {
        throw new IllegalArgumentException("A cache breakpoint after the last turn: " + at);
      }
      if (at.message() >= 0) {
        text = messages.get(at.message()).text();
      }
      if (at.offset() != CacheBreak.END && at.offset() > text.length()) {
        throw new IllegalArgumentException("A cache breakpoint past its text: " + at);
      }
      if (previous != null
          && (at.message() < previous.message()
              || (at.message() == previous.message() && at.offset() <= previous.offset()))) {
        throw new IllegalArgumentException("Cache breakpoints out of order: " + cacheBreaks);
      }
      previous = at;
    }
  }

  /** A request without cache breakpoints. */
  public LlmRequest(
      Role role,
      String purpose,
      String system,
      List<LlmMessage> messages,
      int maxOutputTokens,
      boolean cacheSystem,
      List<LlmTool> tools) {
    this(role, purpose, system, messages, maxOutputTokens, cacheSystem, tools, List.of());
  }

  /** A request without tools. */
  public LlmRequest(
      Role role,
      String purpose,
      String system,
      List<LlmMessage> messages,
      int maxOutputTokens,
      boolean cacheSystem) {
    this(role, purpose, system, messages, maxOutputTokens, cacheSystem, List.of());
  }

  /** A single-turn request without prompt caching. */
  public static LlmRequest of(
      Role role, String purpose, String system, String user, int maxOutputTokens) {
    return new LlmRequest(
        role, purpose, system, List.of(LlmMessage.user(user)), maxOutputTokens, false);
  }

  /** The same request with another output limit. */
  public LlmRequest withMaxOutputTokens(int maxOutputTokens) {
    return new LlmRequest(
        role, purpose, system, messages, maxOutputTokens, cacheSystem, tools, cacheBreaks);
  }

  /** The same request with other turns. */
  public LlmRequest withMessages(List<LlmMessage> messages) {
    return new LlmRequest(
        role, purpose, system, messages, maxOutputTokens, cacheSystem, tools, cacheBreaks);
  }

  /** The same request with another purpose. */
  public LlmRequest withPurpose(String purpose) {
    return new LlmRequest(
        role, purpose, system, messages, maxOutputTokens, cacheSystem, tools, cacheBreaks);
  }

  /** The same request with other cache breakpoints. */
  public LlmRequest withCacheBreaks(List<CacheBreak> cacheBreaks) {
    return new LlmRequest(
        role, purpose, system, messages, maxOutputTokens, cacheSystem, tools, cacheBreaks);
  }

  /** The same request with other tools. */
  public LlmRequest withTools(List<LlmTool> tools) {
    return new LlmRequest(
        role, purpose, system, messages, maxOutputTokens, cacheSystem, tools, cacheBreaks);
  }

  /**
   * A rough count of the input tokens, about four characters per token: the system prompt, the
   * turns with their tool calls and results, and the tools' definitions.
   */
  public long estimatedInputTokens() {
    long chars = system == null ? 0 : system.length();
    for (LlmMessage message : messages) {
      chars += message.chars();
    }
    for (LlmTool tool : tools) {
      chars +=
          tool.name().length()
              + tool.description().length()
              + tool.inputSchema().toString().length();
    }
    return Tokens.estimate(chars);
  }
}
