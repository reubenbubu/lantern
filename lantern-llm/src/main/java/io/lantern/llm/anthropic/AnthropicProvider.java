package io.lantern.llm.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigBetweenTools;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import io.lantern.llm.BatchPolling;
import io.lantern.llm.CacheBreak;
import io.lantern.llm.Capability;
import io.lantern.llm.LlmBatchResult;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmThinking;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Thinking;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.TransientLlmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls Anthropic's Messages API through the official Java SDK. Rate limits (HTTP 429), timeouts
 * (408), overload and server errors (5xx other than 501) and connection failures become a {@link
 * TransientLlmException} with the wait the API asked for, so {@link io.lantern.llm.LlmClient}
 * retries them; a provider made by {@link #create} leaves retrying to it and does not retry itself.
 *
 * <p>For a model whose {@link Thinking#disabled()} is set, it turns thinking off the way the
 * model's {@linkplain Thinking#offSwitch() off switch} says: {@code thinking: {type: "disabled"}}
 * or {@code thinking: {type: "between_tools"}} (ADR 0022). Thinking blocks in an answer are left
 * out of its text.
 *
 * <p>For a model with {@linkplain Capability#PROMPT_CACHING prompt caching}, the system prompt is
 * marked for caching ({@link ModelConfig#cachesSystem}). For a model without it, any cache write
 * and cache read tokens the server reports are counted as input tokens (ADR 0008).
 *
 * <p>A request's tools are sent as custom tools with their input schemas; an assistant turn's tool
 * calls as {@code tool_use} blocks after its text, and a user turn's tool results as {@code
 * tool_result} blocks before its text. An answer's {@code tool_use} blocks become its tool calls
 * (ADR 0081). The thinking blocks of an answer that asks for tools are kept, with their signatures,
 * and sent back first on that assistant turn, as the API requires when thinking is on.
 */
public final class AnthropicProvider implements LlmProvider, AutoCloseable {

  /** The provider name model settings use for Anthropic. */
  public static final String NAME = "anthropic";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The longest wait taken from a retry-after header, so a bad value cannot stall a run. */
  private static final double MAX_RETRY_AFTER_MS = 10 * 60 * 1000;

  /** How often a batch's status is read while it runs, unless given another. */
  public static final Duration BATCH_POLL = Duration.ofSeconds(30);

  private final AnthropicClient client;
  private final boolean ownsClient;
  private final Duration batchPoll;

  /** Uses the given SDK client; the caller closes it. */
  public AnthropicProvider(AnthropicClient client) {
    this(client, false, BATCH_POLL);
  }

  /** Uses the given SDK client, reading a running batch's status at the given interval. */
  public AnthropicProvider(AnthropicClient client, Duration batchPoll) {
    this(client, false, batchPoll);
  }

  private AnthropicProvider(AnthropicClient client, boolean ownsClient, Duration batchPoll) {
    this.client = Objects.requireNonNull(client, "client");
    this.ownsClient = ownsClient;
    this.batchPoll = Objects.requireNonNull(batchPoll, "batchPoll");
  }

  /**
   * A provider with its own SDK client, which {@link #close()} closes.
   *
   * @param apiKey the API key
   * @param baseUrl the API's base URL, or {@code null} for Anthropic's
   */
  public static AnthropicProvider create(String apiKey, String baseUrl) {
    return create(apiKey, baseUrl, BATCH_POLL);
  }

  /**
   * A provider with its own SDK client, which {@link #close()} closes, reading a running batch's
   * status at the given interval.
   */
  public static AnthropicProvider create(String apiKey, String baseUrl, Duration batchPoll) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("Anthropic API key must not be blank");
    }
    AnthropicOkHttpClient.Builder builder =
        AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
    if (baseUrl != null) {
      builder.baseUrl(baseUrl);
    }
    return new AnthropicProvider(builder.build(), true, batchPoll);
  }

  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    return send(params(model, request, Thinking.DEFAULT, request.cacheSystem()));
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    LlmResponse response =
        send(params(model.model(), request, model.thinking(), model.cachesSystem(request)));
    if (model.supports(Capability.PROMPT_CACHING)) {
      return response;
    }
    // Lantern asks such a model for no caching, so cache tokens are a server's own split of the
    // prompt, such as a local server that reports it all as cache writes (#298); they are input,
    // at the input price, since the model has no cache prices.
    return new LlmResponse(
        response.text(),
        response.stopReason(),
        response.usage().cacheAsInput(),
        response.toolCalls(),
        response.thinking());
  }

  @Override
  public boolean batches() {
    return true;
  }

  /**
   * Sends the requests as one Message Batch, each as {@link #complete(ModelConfig, LlmRequest)}
   * would send it on its own, reads the batch's status every poll interval until it has ended, and
   * reads back each request's result by its custom id.
   */
  @Override
  public List<LlmBatchResult> completeBatch(ModelConfig model, List<LlmRequest> requests) {
    com.fasterxml.jackson.databind.json.JsonMapper sdk = ObjectMappers.jsonMapper();
    BatchCreateParams.Builder batch = BatchCreateParams.builder();
    for (int i = 0; i < requests.size(); i++) {
      LlmRequest request = requests.get(i);
      MessageCreateParams params =
          params(model.model(), request, model.thinking(), model.cachesSystem(request));
      BatchCreateParams.Request.Params body;
      try {
        // The batch's request params are the Messages API's body, in a type of their own.
        body =
            sdk.readValue(
                sdk.writeValueAsString(params._body()), BatchCreateParams.Request.Params.class);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new LlmException("Could not write a batch request: " + e.getMessage(), e);
      }
      batch.addRequest(BatchCreateParams.Request.builder().customId(id(i)).params(body).build());
    }
    Map<String, LlmBatchResult> byId = new LinkedHashMap<>();
    try {
      MessageBatch created = client.messages().batches().create(batch.build());
      MessageBatch status = created;
      while (!status.processingStatus().equals(MessageBatch.ProcessingStatus.ENDED)) {
        try {
          Thread.sleep(batchPoll);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new LlmException("Interrupted while waiting for batch " + created.id(), e);
        }
        status =
            BatchPolling.step(
                "reading batch " + created.id(),
                batchPoll,
                () -> client.messages().batches().retrieve(created.id()),
                AnthropicProvider::anyError);
      }
      BatchPolling.step(
          "reading the results of batch " + created.id(),
          batchPoll,
          () -> {
            byId.clear();
            try (StreamResponse<MessageBatchIndividualResponse> results =
                client.messages().batches().resultsStreaming(created.id())) {
              results.stream()
                  .forEach(result -> byId.put(result.customId(), result(model, result)));
            }
            return byId;
          },
          AnthropicProvider::anyError);
    } catch (AnthropicException e) {
      // Only creating the batch gets here: nothing was created, so it may be sent again.
      throw error(e);
    }
    List<LlmBatchResult> results = new ArrayList<>();
    for (int i = 0; i < requests.size(); i++) {
      results.add(
          byId.getOrDefault(
              id(i), LlmBatchResult.failed(new LlmException("The batch returned no result"))));
    }
    return results;
  }

  /** The error of a failed step, transient when retrying it may help. */
  private static LlmException anyError(RuntimeException e) {
    return e instanceof AnthropicException anthropic
        ? error(anthropic)
        : new LlmException("Anthropic batch step failed: " + e, e);
  }

  /** A request's custom id in a batch, by its place. */
  private static String id(int index) {
    return "r" + index;
  }

  private static LlmBatchResult result(ModelConfig model, MessageBatchIndividualResponse result) {
    MessageBatchResult outcome = result.result();
    if (outcome.isSucceeded()) {
      LlmResponse response = response(outcome.asSucceeded().message());
      return LlmBatchResult.answered(
          model.supports(Capability.PROMPT_CACHING)
              ? response
              : new LlmResponse(
                  response.text(),
                  response.stopReason(),
                  response.usage().cacheAsInput(),
                  response.toolCalls(),
                  response.thinking()));
    }
    String why =
        outcome.isErrored()
            ? "errored: " + outcome.asErrored().error()
            : outcome.isExpired() ? "expired" : outcome.isCanceled() ? "canceled" : "no result";
    return LlmBatchResult.failed(new LlmException("Anthropic batch request " + why));
  }

  private LlmResponse send(MessageCreateParams params) {
    Message message;
    try {
      message = client.messages().create(params);
    } catch (AnthropicException e) {
      throw error(e);
    }
    return response(message);
  }

  static LlmException error(AnthropicException e) {
    String message = "Anthropic call failed: " + e.getMessage();
    if (e instanceof AnthropicServiceException service) {
      int status = service.statusCode();
      if (status == 408 || status == 429 || (status >= 500 && status != 501)) {
        return new TransientLlmException(message, retryAfter(service.headers()), e);
      }
      return new LlmException(message, e);
    }
    if (e instanceof AnthropicIoException || e instanceof AnthropicRetryableException) {
      return new TransientLlmException(message, null, e);
    }
    return new LlmException(message, e);
  }

  /**
   * The wait the API asked for: {@code retry-after-ms} in milliseconds, or {@code retry-after} in
   * seconds or as an HTTP date; {@code null} if neither is there or readable.
   */
  static Duration retryAfter(Headers headers) {
    for (String value : headers.values("retry-after-ms")) {
      try {
        double millis = Double.parseDouble(value.trim());
        if (millis >= 0 && Double.isFinite(millis)) {
          return Duration.ofNanos((long) (Math.min(millis, MAX_RETRY_AFTER_MS) * 1_000_000));
        }
      } catch (NumberFormatException ignored) {
        // Try the next header.
      }
    }
    for (String value : headers.values("retry-after")) {
      try {
        double seconds = Double.parseDouble(value.trim());
        if (seconds >= 0 && Double.isFinite(seconds)) {
          return Duration.ofNanos(
              (long) (Math.min(seconds * 1000, MAX_RETRY_AFTER_MS) * 1_000_000));
        }
      } catch (NumberFormatException notSeconds) {
        try {
          Instant at =
              ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
          Duration wait = Duration.between(Instant.now(), at);
          return wait.isNegative()
              ? Duration.ZERO
              : min(wait, Duration.ofMillis((long) MAX_RETRY_AFTER_MS));
        } catch (DateTimeParseException ignored) {
          // Try the next header.
        }
      }
    }
    return null;
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  static MessageCreateParams params(
      String model, LlmRequest request, Thinking thinking, boolean cacheSystem) {
    MessageCreateParams.Builder params =
        MessageCreateParams.builder().model(model).maxTokens(request.maxOutputTokens());
    if (thinking.disabled()) {
      switch (thinking.offSwitch()) {
        case DISABLED -> params.thinking(ThinkingConfigDisabled.builder().build());
        case BETWEEN_TOOLS -> params.thinking(ThinkingConfigBetweenTools.builder().build());
        // Thinking refuses to be disabled without an off switch, so this cannot happen.
        case NONE -> throw new IllegalStateException("Model " + model + " has no off switch");
      }
    }
    if (request.system() != null) {
      List<CacheBreak> breaks = breaks(request, CacheBreak.SYSTEM);
      if (breaks.isEmpty()) {
        TextBlockParam.Builder system = TextBlockParam.builder().text(request.system());
        // The one breakpoint, at the end of the system prompt: tools and system prompt render
        // first and are the same for every call of a role, while the turns after it differ (#303).
        if (cacheSystem) {
          system.cacheControl(CacheControlEphemeral.builder().build());
        }
        params.systemOfTextBlockParams(List.of(system.build()));
      } else {
        params.systemOfTextBlockParams(textBlocks(request.system(), breaks, cacheSystem));
      }
    }
    for (LlmTool tool : request.tools()) {
      params.addTool(
          Tool.builder()
              .name(tool.name())
              .description(tool.description())
              .inputSchema(
                  Tool.InputSchema.builder()
                      .additionalProperties(withoutType(fields(tool.inputSchema())))
                      .build())
              .build());
    }
    for (int index = 0; index < request.messages().size(); index++) {
      LlmMessage turn = request.messages().get(index);
      List<CacheBreak> breaks = breaks(request, index);
      boolean cacheEnd = breaks.stream().anyMatch(at -> at.offset() == CacheBreak.END);
      CacheBreak end =
          breaks.stream().filter(at -> at.offset() == CacheBreak.END).findFirst().orElse(null);
      List<CacheBreak> inText =
          breaks.stream().filter(at -> at.offset() != CacheBreak.END).toList();
      if (breaks.isEmpty()
          && turn.toolCalls().isEmpty()
          && turn.toolResults().isEmpty()
          && turn.thinking().isEmpty()) {
        switch (turn.author()) {
          case USER -> params.addUserMessage(turn.text());
          case ASSISTANT -> params.addAssistantMessage(turn.text());
        }
        continue;
      }
      List<ContentBlockParam> blocks = new ArrayList<>();
      // Thinking goes back first, unchanged, on the turn that asked for tools, as the API asks.
      for (LlmThinking thought : turn.thinking()) {
        blocks.add(
            thought.redacted()
                ? ContentBlockParam.ofRedactedThinking(
                    RedactedThinkingBlockParam.builder().data(thought.text()).build())
                : ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder()
                        .thinking(thought.text())
                        .signature(thought.signature() == null ? "" : thought.signature())
                        .build()));
      }
      // Tool results come first in a user turn, as the API asks.
      for (LlmToolResult result : turn.toolResults()) {
        blocks.add(
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(result.callId())
                    .content(result.content())
                    .isError(result.error())
                    .build()));
      }
      if (!turn.text().isBlank()) {
        if (inText.isEmpty()) {
          blocks.add(ContentBlockParam.ofText(turn.text()));
        } else {
          textBlocks(turn.text(), inText, false)
              .forEach(b -> blocks.add(ContentBlockParam.ofText(b)));
        }
      }
      for (LlmToolCall call : turn.toolCalls()) {
        blocks.add(
            ContentBlockParam.ofToolUse(
                ToolUseBlockParam.builder()
                    .id(call.id())
                    .name(call.name())
                    .input(
                        ToolUseBlockParam.Input.builder()
                            .additionalProperties(fields(call.input()))
                            .build())
                    .build()));
      }
      if (cacheEnd && !blocks.isEmpty()) {
        blocks.set(blocks.size() - 1, cached(blocks.getLast(), end.longLived()));
      }
      params.addMessage(
          turn.author() == LlmMessage.Author.USER
              ? MessageParam.ofUserOfContentBlockParams(blocks)
              : MessageParam.ofAssistantOfContentBlockParams(blocks));
    }
    return params.build();
  }

  /** The request's cache breakpoints in the given turn, or the system prompt for -1, in order. */
  private static List<CacheBreak> breaks(LlmRequest request, int message) {
    return request.cacheBreaks().stream().filter(at -> at.message() == message).toList();
  }

  /**
   * The text split into blocks at the breakpoints, each block that ends at one marked for caching;
   * a breakpoint at the end, or {@code cacheEnd}, marks the last block.
   */
  static List<TextBlockParam> textBlocks(String text, List<CacheBreak> breaks, boolean cacheEnd) {
    List<TextBlockParam> blocks = new ArrayList<>();
    int from = 0;
    for (CacheBreak at : breaks) {
      int to = at.offset() == CacheBreak.END ? text.length() : at.offset();
      if (to > from) {
        blocks.add(
            TextBlockParam.builder()
                .text(text.substring(from, to))
                .cacheControl(control(at.longLived()))
                .build());
        from = to;
      }
    }
    if (from < text.length()) {
      TextBlockParam.Builder rest = TextBlockParam.builder().text(text.substring(from));
      if (cacheEnd) {
        rest.cacheControl(control(false));
      }
      blocks.add(rest.build());
    }
    return blocks;
  }

  private static CacheControlEphemeral control(boolean longLived) {
    CacheControlEphemeral.Builder control = CacheControlEphemeral.builder();
    if (longLived) {
      control.ttl(CacheControlEphemeral.Ttl.TTL_1H);
    }
    return control.build();
  }

  /** The block marked for caching, for a breakpoint after a turn's last block. */
  private static ContentBlockParam cached(ContentBlockParam block, boolean longLived) {
    CacheControlEphemeral control = control(longLived);
    if (block.isText()) {
      return ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(control).build());
    }
    if (block.isToolResult()) {
      return ContentBlockParam.ofToolResult(
          block.asToolResult().toBuilder().cacheControl(control).build());
    }
    if (block.isToolUse()) {
      return ContentBlockParam.ofToolUse(
          block.asToolUse().toBuilder().cacheControl(control).build());
    }
    // Thinking blocks cannot be marked; a turn with tool calls always ends in one of those above.
    return block;
  }

  /** The schema's fields without its type, which the SDK always sends as {@code object}. */
  private static Map<String, JsonValue> withoutType(Map<String, JsonValue> fields) {
    fields.remove("type");
    return fields;
  }

  /** The fields of a JSON object as the SDK's values. */
  private static Map<String, JsonValue> fields(JsonNode object) {
    Map<String, JsonValue> fields = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> field : object.properties()) {
      fields.put(field.getKey(), JsonValue.from(JSON.treeToValue(field.getValue(), Object.class)));
    }
    return fields;
  }

  static LlmResponse response(Message message) {
    StringBuilder text = new StringBuilder();
    List<LlmToolCall> calls = new ArrayList<>();
    List<LlmThinking> thought = new ArrayList<>();
    // Thinking and redacted thinking blocks are not part of the answer's text; they are kept only
    // for an answer that asks for tools, to be sent back with it.
    for (ContentBlock block : message.content()) {
      block.text().map(TextBlock::text).ifPresent(text::append);
      block
          .thinking()
          .ifPresent(t -> thought.add(new LlmThinking(t.thinking(), t.signature(), false)));
      block.redactedThinking().ifPresent(r -> thought.add(new LlmThinking(r.data(), null, true)));
      block
          .toolUse()
          .ifPresent(
              (ToolUseBlock use) -> {
                Object input = use._input().convert(Object.class);
                calls.add(
                    new LlmToolCall(
                        use.id(),
                        use.name(),
                        input == null ? JSON.createObjectNode() : JSON.valueToTree(input)));
              });
    }
    Usage usage = message.usage();
    return new LlmResponse(
        text.toString(),
        message.stopReason().map(StopReason::asString).orElse(null),
        new TokenUsage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheCreationInputTokens().orElse(0L),
            usage.cacheReadInputTokens().orElse(0L)),
        calls,
        calls.isEmpty() ? List.of() : thought);
  }
}
