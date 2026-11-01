package io.lantern.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A provider for tests that never leaves the process. It answers with a function of the request (by
 * default a fixed text), reports token usage estimated from the text lengths, and remembers every
 * request it was sent. It can also take a while to answer and fail the first attempts of each
 * request, for example with a {@link TransientLlmException} like a rate limit, and it counts how
 * many calls were in flight at once.
 *
 * <p>It can also {@linkplain #thinking think} before answering, like a model that thinks by
 * default: the thinking tokens count against the request's output limit. Any answer that does not
 * fit in the output limit, after any thinking, is cut off with the stop reason {@code max_tokens},
 * as a real model's is.
 *
 * <p>Thread-safe if the answering function is.
 */
public final class FakeLlmProvider implements LlmProvider {

  /** The provider name fakes use unless given another. */
  public static final String NAME = "fake";

  private final String name;
  private final List<Sent> sent = new ArrayList<>();
  private volatile Function<LlmRequest, String> answer = request -> "fake answer";
  private volatile Function<LlmRequest, List<LlmToolCall>> toolCalls = request -> List.of();
  private volatile Duration latency = Duration.ZERO;
  private int failuresPerRequest;
  private Supplier<? extends RuntimeException> failure;
  private final Map<LlmRequest, Integer> attempts = new HashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();
  private volatile Thinks thinks = new Thinks(0, false);
  private volatile boolean simulatesCache;
  private final Set<String> cachedPrefixes = ConcurrentHashMap.newKeySet();
  private final List<Integer> batchSizes = new ArrayList<>();

  public FakeLlmProvider() {
    this(NAME);
  }

  public FakeLlmProvider(String name) {
    this.name = ModelConfig.nonBlank("Provider name", name);
  }

  /**
   * A free model of this provider with every capability but {@link Capability#BATCH}, and a large
   * context window; {@link #batchingModel} is one that batches too.
   */
  public ModelConfig model(String model) {
    return new ModelConfig(
        name, model, EnumSet.complementOf(EnumSet.of(Capability.BATCH)), 1_000_000, Pricing.FREE);
  }

  /** A free model of this provider with every capability, the batch API included. */
  public ModelConfig batchingModel(String model) {
    return new ModelConfig(name, model, EnumSet.allOf(Capability.class), 1_000_000, Pricing.FREE);
  }

  /**
   * Reports cache tokens the way a provider with prompt caching would: the part of a request up to
   * its last cache breakpoint (or the end of a cached system prompt) is written to the cache by the
   * first answer that sends it, and read from the cache by every later request that starts with the
   * same text up to one of its breakpoints. A prefix counts as written once its answer is returned,
   * so requests sent at the same time all write it, as they do on a real provider (#357). Without
   * this, a cached system prompt counts as written on every call.
   */
  public FakeLlmProvider simulatingPromptCache() {
    this.simulatesCache = true;
    return this;
  }

  /** Answers every request with the given text. */
  public FakeLlmProvider answering(String text) {
    Objects.requireNonNull(text, "text");
    return answering(request -> text);
  }

  /**
   * Answers each request with the function's result. A function that throws makes the call fail
   * with that exception.
   */
  public FakeLlmProvider answering(Function<LlmRequest, String> answer) {
    this.answer = Objects.requireNonNull(answer, "answer");
    return this;
  }

  /**
   * Before answering, asks for the tool calls the function gives for the request, if any: an answer
   * with those calls, no text and the stop reason {@code tool_use}. When it gives none, the request
   * is answered as usual. A request without tools is always answered.
   */
  public FakeLlmProvider callingTools(Function<LlmRequest, List<LlmToolCall>> toolCalls) {
    this.toolCalls = Objects.requireNonNull(toolCalls, "toolCalls");
    return this;
  }

  /** Takes the given time to answer or fail each call. */
  public FakeLlmProvider withLatency(Duration latency) {
    Objects.requireNonNull(latency, "latency");
    if (latency.isNegative()) {
      throw new IllegalArgumentException("Latency must not be negative: " + latency);
    }
    this.latency = latency;
    return this;
  }

  /**
   * From now on, fails the first given number of attempts of each distinct request with an
   * exception from the supplier, then answers it. Which attempts fail depends only on the requests,
   * not on the order they arrive in.
   */
  public FakeLlmProvider failingFirst(int attempts, Supplier<? extends RuntimeException> failure) {
    if (attempts < 0) {
      throw new IllegalArgumentException("Attempts must not be negative: " + attempts);
    }
    Objects.requireNonNull(failure, "failure");
    synchronized (this) {
      this.failure = failure;
      this.failuresPerRequest = attempts;
      this.attempts.clear();
    }
    return this;
  }

  /**
   * Thinks the given number of tokens before every answer, unless thinking can be disabled and the
   * model's {@link Thinking#disabled()} is set. Thinking tokens count against the output limit and
   * are reported as output tokens, but are never part of the answer's text.
   *
   * @param tokens the thinking tokens per call, zero for none
   * @param canBeDisabled whether this fake honors a request to turn thinking off; some providers
   *     accept the request and think anyway
   */
  public FakeLlmProvider thinking(int tokens, boolean canBeDisabled) {
    if (tokens < 0) {
      throw new IllegalArgumentException("Thinking tokens must not be negative: " + tokens);
    }
    this.thinks = new Thinks(tokens, canBeDisabled);
    return this;
  }

  /** The most calls that were in flight at the same time so far. */
  public int maxConcurrentCalls() {
    return maxInFlight.get();
  }

  /** The size of each batch sent so far, oldest first. */
  public synchronized List<Integer> batchSizes() {
    return List.copyOf(batchSizes);
  }

  /** The requests sent so far, oldest first. */
  public synchronized List<Sent> sent() {
    return List.copyOf(sent);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    return complete(model, request, thinks.tokens(), request.cacheSystem());
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    Thinks current = thinks;
    boolean disabled = current.canBeDisabled() && model.thinking().disabled();
    return complete(
        model.model(), request, disabled ? 0 : current.tokens(), model.cachesSystem(request));
  }

  /** The fake has a batch API; a model uses it only when its settings say {@code batch}. */
  @Override
  public boolean batches() {
    return true;
  }

  /**
   * Answers each request of the batch as {@link #complete(ModelConfig, LlmRequest)} would, one
   * after the other; a request that fails gets an error result.
   */
  @Override
  public List<LlmBatchResult> completeBatch(ModelConfig model, List<LlmRequest> requests) {
    synchronized (this) {
      batchSizes.add(requests.size());
    }
    List<LlmBatchResult> results = new ArrayList<>();
    for (LlmRequest request : requests) {
      try {
        results.add(LlmBatchResult.answered(complete(model, request)));
      } catch (LlmException e) {
        results.add(LlmBatchResult.failed(e));
      } catch (RuntimeException e) {
        results.add(LlmBatchResult.failed(new LlmException("Fake batch request failed: " + e, e)));
      }
    }
    return results;
  }

  private LlmResponse complete(
      String model, LlmRequest request, int thinking, boolean cachesSystem) {
    RuntimeException failed = null;
    synchronized (this) {
      sent.add(new Sent(model, request));
      if (attempts.merge(request, 1, Integer::sum) <= failuresPerRequest) {
        failed = failure.get();
      }
    }
    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
    try {
      return answer(request, failed, thinking, cachesSystem);
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private LlmResponse answer(
      LlmRequest request, RuntimeException failed, int thinking, boolean cachesSystem) {
    if (!latency.isZero()) {
      try {
        Thread.sleep(latency);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LlmException("Interrupted", e);
      }
    }
    if (failed != null) {
      throw failed;
    }
    List<LlmToolCall> calls = request.tools().isEmpty() ? List.of() : toolCalls.apply(request);
    if (!calls.isEmpty()) {
      long chars = 0;
      for (LlmToolCall call : calls) {
        chars += call.name().length() + call.input().toString().length();
      }
      long output = thinking + Tokens.estimate(chars);
      return new LlmResponse(
          "",
          LlmResponse.TOOL_USE,
          simulatesCache
              ? simulated(request, cachesSystem, output)
              : new TokenUsage(request.estimatedInputTokens(), output, 0, 0),
          calls);
    }
    String text = answer.apply(request);
    long limit = request.maxOutputTokens();
    long output = thinking + Tokens.estimate(text);
    String stopReason = "end_turn";
    if (output > limit) {
      // Thinking comes first, so the answer gets what is left of the limit, about four characters
      // a token.
      long left = Math.max(0, limit - thinking);
      text = text.substring(0, (int) Math.min(text.length(), left * 4));
      output = limit;
      stopReason = LlmResponse.MAX_TOKENS;
    }
    long input = request.estimatedInputTokens();
    if (simulatesCache) {
      return new LlmResponse(text, stopReason, simulated(request, cachesSystem, output));
    }
    TokenUsage usage =
        cachesSystem
            ? new TokenUsage(
                input - Tokens.estimate(request.system()),
                output,
                Tokens.estimate(request.system()),
                0)
            : new TokenUsage(input, output, 0, 0);
    return new LlmResponse(text, stopReason, usage);
  }

  /** The usage of a request with the prompt cache simulated, and its prefixes then cached. */
  private TokenUsage simulated(LlmRequest request, boolean cachesSystem, long output) {
    List<String> prefixes = prefixes(request, cachesSystem);
    long input = request.estimatedInputTokens();
    long read = 0;
    // Like a provider, it looks back from the breakpoints for the longest prefix cached before,
    // ending at an earlier turn, as a conversation's previous breakpoint did.
    List<String> candidates = new ArrayList<>(prefixes);
    candidates.addAll(turnEnds(request, cachesSystem));
    for (String prefix : candidates) {
      if (cachedPrefixes.contains(digest(prefix))) {
        read = Math.max(read, Tokens.estimate(prefix));
      }
    }
    long written = prefixes.isEmpty() ? 0 : Math.max(0, Tokens.estimate(prefixes.getLast()) - read);
    read = Math.min(read, input);
    written = Math.min(written, input - read);
    prefixes.forEach(prefix -> cachedPrefixes.add(digest(prefix)));
    // Like Anthropic, a write through a long-lived breakpoint is counted apart.
    if (!prefixes.isEmpty() && request.cacheBreaks().stream().anyMatch(CacheBreak::longLived)) {
      return new TokenUsage(input - read - written, output, 0, read, written);
    }
    return new TokenUsage(input - read - written, output, written, read);
  }

  /**
   * The request's text up to the end of each turn before its last breakpoint's turn, within the
   * {@value #LOOKBACK_BLOCKS} blocks a provider looks back from a breakpoint.
   */
  private static List<String> turnEnds(LlmRequest request, boolean cachesSystem) {
    List<String> ends = new ArrayList<>();
    collect(request, cachesSystem, ends);
    return ends;
  }

  /** The text a request sends up to each of its breakpoints, tools and system prompt first. */
  private static List<String> prefixes(LlmRequest request, boolean cachesSystem) {
    return collect(request, cachesSystem, null);
  }

  /**
   * The request's text up to each of its breakpoints, shortest first; with {@code ends}, also adds
   * the text up to the end of each turn before the last breakpoint's turn to it.
   */
  private static List<String> collect(LlmRequest request, boolean cachesSystem, List<String> ends) {
    int lastBreak =
        request.cacheBreaks().stream()
            .mapToInt(CacheBreak::message)
            .max()
            .orElse(CacheBreak.SYSTEM);
    StringBuilder text = new StringBuilder();
    for (LlmTool tool : request.tools()) {
      text.append(tool.name()).append(tool.description()).append(tool.inputSchema());
    }
    List<String> prefixes = new ArrayList<>();
    String system = request.system() == null ? "" : request.system();
    for (CacheBreak at : request.cacheBreaks()) {
      if (at.message() == CacheBreak.SYSTEM) {
        prefixes.add(text + system.substring(0, Math.min(at.offset(), system.length())));
      }
    }
    text.append(system);
    if (cachesSystem && request.system() != null) {
      prefixes.add(text.toString());
    }
    for (int i = 0; i < request.messages().size(); i++) {
      LlmMessage message = request.messages().get(i);
      text.append('\u0000').append(message.author());
      for (LlmToolResult result : message.toolResults()) {
        text.append(result.callId()).append(result.content());
      }
      int textStart = text.length();
      text.append(message.text());
      for (LlmToolCall call : message.toolCalls()) {
        text.append(call.id()).append(call.name()).append(call.input());
      }
      if (ends != null && i < lastBreak && blocksAfter(request, i, lastBreak) <= LOOKBACK_BLOCKS) {
        ends.add(text.toString());
      }
      for (CacheBreak at : request.cacheBreaks()) {
        if (at.message() == i) {
          prefixes.add(
              at.offset() == CacheBreak.END
                  ? text.toString()
                  : text.substring(0, textStart + Math.min(at.offset(), message.text().length())));
        }
      }
    }
    prefixes.sort(java.util.Comparator.comparingInt(String::length));
    return prefixes;
  }

  /** How many blocks back from a breakpoint a provider looks for a cached prefix (Anthropic). */
  static final int LOOKBACK_BLOCKS = 20;

  /**
   * The blocks from after turn {@code from} to the end of turn {@code to}: a turn's text is one,
   * its tool calls together one, its tool results together one.
   */
  private static int blocksAfter(LlmRequest request, int from, int to) {
    int blocks = 0;
    for (int i = from + 1; i <= to; i++) {
      LlmMessage message = request.messages().get(i);
      blocks +=
          (message.text().isEmpty() ? 0 : 1)
              + (message.toolCalls().isEmpty() ? 0 : 1)
              + (message.toolResults().isEmpty() ? 0 : 1);
    }
    return blocks;
  }

  private static String digest(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** How the fake thinks, in one value so that calls never see half a change. */
  private record Thinks(int tokens, boolean canBeDisabled) {}

  /**
   * A request the fake was sent.
   *
   * @param model the model it was sent to
   * @param request the request
   */
  public record Sent(String model, LlmRequest request) {}
}
