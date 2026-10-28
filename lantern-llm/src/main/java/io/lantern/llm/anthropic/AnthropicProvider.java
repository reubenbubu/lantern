package io.lantern.llm.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.TokenUsage;
import java.util.List;
import java.util.Objects;

/** Calls Anthropic's Messages API through the official Java SDK. */
public final class AnthropicProvider implements LlmProvider, AutoCloseable {

  /** The provider name model settings use for Anthropic. */
  public static final String NAME = "anthropic";

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
    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
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
    Message message;
    try {
      message = client.messages().create(params(model, request));
    } catch (AnthropicException e) {
      throw new LlmException("Anthropic call failed: " + e.getMessage(), e);
    }
    return response(message);
  }

  static MessageCreateParams params(String model, LlmRequest request) {
    MessageCreateParams.Builder params =
        MessageCreateParams.builder().model(model).maxTokens(request.maxOutputTokens());
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
