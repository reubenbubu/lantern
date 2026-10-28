package io.lantern.llm.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Usage;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.TransientLlmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/**
 * Calls Anthropic's Messages API through the official Java SDK. Rate limits (HTTP 429), timeouts
 * (408), overload and server errors (5xx other than 501) and connection failures become a {@link
 * TransientLlmException} with the wait the API asked for, so {@link io.lantern.llm.LlmClient}
 * retries them; a provider made by {@link #create} leaves retrying to it and does not retry itself.
 *
 * <p>For a model whose {@link io.lantern.llm.Thinking#disabled()} is set, it sends {@code thinking:
 * {type: "disabled"}}. Thinking blocks in an answer are left out of its text.
 */
public final class AnthropicProvider implements LlmProvider, AutoCloseable {

  /** The provider name model settings use for Anthropic. */
  public static final String NAME = "anthropic";

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
    return send(params(model, request, false));
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    return send(params(model.model(), request, model.thinking().disabled()));
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

  static MessageCreateParams params(String model, LlmRequest request, boolean thinkingDisabled) {
    MessageCreateParams.Builder params =
        MessageCreateParams.builder().model(model).maxTokens(request.maxOutputTokens());
    if (thinkingDisabled) {
      params.thinking(ThinkingConfigDisabled.builder().build());
    }
    if (request.system() != null) {
      TextBlockParam.Builder system = TextBlockParam.builder().text(request.system());
      if (request.cacheSystem()) {
        system.cacheControl(CacheControlEphemeral.builder().build());
      }
      params.systemOfTextBlockParams(List.of(system.build()));
    }
    for (LlmMessage turn : request.messages()) {
      switch (turn.author()) {
        case USER -> params.addUserMessage(turn.text());
        case ASSISTANT -> params.addAssistantMessage(turn.text());
      }
    }
    return params.build();
  }

  static LlmResponse response(Message message) {
    StringBuilder text = new StringBuilder();
    // Only text blocks: thinking and redacted thinking blocks are not part of the answer.
    for (ContentBlock block : message.content()) {
      block.text().map(TextBlock::text).ifPresent(text::append);
    }
    Usage usage = message.usage();
    return new LlmResponse(
        text.toString(),
        message.stopReason().map(StopReason::asString).orElse(null),
        new TokenUsage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheCreationInputTokens().orElse(0L),
            usage.cacheReadInputTokens().orElse(0L)));
  }
}
