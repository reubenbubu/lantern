package io.lantern.llm.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
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

  private final AnthropicClient client;
  private final boolean ownsClient;

  /** Uses the given SDK client; the caller closes it. */
  public AnthropicProvider(AnthropicClient client) {
    this(client, false);
  }

  private AnthropicProvider(AnthropicClient client, boolean ownsClient) {
    this.client = Objects.requireNonNull(client, "client");
    this.ownsClient = ownsClient;
  }

  /**
   * A provider with its own SDK client, which {@link #close()} closes.
   *
   * @param apiKey the API key
   * @param baseUrl the API's base URL, or {@code null} for Anthropic's
   */
  public static AnthropicProvider create(String apiKey, String baseUrl) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("Anthropic API key must not be blank");
    }
    AnthropicOkHttpClient.Builder builder =
        AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
    if (baseUrl != null) {
      builder.baseUrl(baseUrl);
    }
    return new AnthropicProvider(builder.build(), true);
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
    return send(params(model, request, Thinking.DEFAULT));
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    return send(params(model.model(), request, model.thinking()));
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

  static MessageCreateParams params(String model, LlmRequest request, Thinking thinking) {
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
      TextBlockParam.Builder system = TextBlockParam.builder().text(request.system());
      if (request.cacheSystem()) {
        system.cacheControl(CacheControlEphemeral.builder().build());
      }
      params.systemOfTextBlockParams(List.of(system.build()));
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
    for (LlmMessage turn : request.messages()) {
      if (turn.toolCalls().isEmpty() && turn.toolResults().isEmpty() && turn.thinking().isEmpty()) {
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
        blocks.add(ContentBlockParam.ofText(turn.text()));
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
      params.addMessage(
          turn.author() == LlmMessage.Author.USER
              ? MessageParam.ofUserOfContentBlockParams(blocks)
              : MessageParam.ofAssistantOfContentBlockParams(blocks));
    }
    return params.build();
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
