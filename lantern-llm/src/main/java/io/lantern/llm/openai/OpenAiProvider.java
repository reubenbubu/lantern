package io.lantern.llm.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.batches.Batch;
import com.openai.models.batches.BatchCreateParams;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FilePurpose;
import io.lantern.llm.Capability;
import io.lantern.llm.LlmBatchResult;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.TransientLlmException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls OpenAI's Chat Completions API, or a server that speaks it, through the official Java SDK
 * (knowledge-build epic, section 10, Portability; ADR 0102). The roles send the same requests as to
 * Anthropic; this provider only translates:
 *
 * <ul>
 *   <li>The system prompt goes first as a {@code system} message, then the turns in order. An
 *       assistant turn's tool calls become {@code tool_calls}; a user turn's tool results become
 *       {@code tool} messages before its text. Tools are sent as functions with their input
 *       schemas, and {@code tool_calls} in an answer become its tool calls.
 *   <li>Cache breakpoints are hints this API does not take: it caches the longest stable prefix of
 *       a prompt on its own, which the harness's layout keeps first. For a model with {@linkplain
 *       Capability#PROMPT_CACHING prompt caching}, requests that share their tools and system
 *       prompt carry the same {@code prompt_cache_key}, so they are routed to the same cache.
 *   <li>Usage is recorded in the one normalised form: {@code cached_tokens} are cache reads, the
 *       rest of the prompt is uncached input, and there are no cache writes, which this API does
 *       not charge for.
 *   <li>The finish reasons {@code length}, {@code tool_calls} and {@code stop} become {@link
 *       LlmResponse#MAX_TOKENS}, {@link LlmResponse#TOOL_USE} and {@code end_turn}.
 * </ul>
 *
 * <p>Batches go through the Batch API: the requests are uploaded as a JSONL file, the batch is read
 * every poll interval until it ends, and each request's result is read back from the output and
 * error files by its custom id.
 *
 * <p>Rate limits (429), timeouts (408), server errors (5xx other than 501) and connection failures
 * become a {@link TransientLlmException}; a provider made by {@link #create} leaves retrying to
 * {@link io.lantern.llm.LlmClient}. Thinking settings are not sent: models that reason on this API
 * take no switch Lantern sets.
 */
public final class OpenAiProvider implements LlmProvider, AutoCloseable {

  /** The provider name model settings use for OpenAI. */
  public static final String NAME = "openai";

  /** How often a batch's status is read while it runs, unless given another. */
  public static final Duration BATCH_POLL = Duration.ofSeconds(30);

  /** The finish reason a normal answer gets, in Anthropic's words, which the harness uses. */
  static final String END_TURN = "end_turn";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<Batch.Status> ENDED =
      Set.of(
          Batch.Status.COMPLETED,
          Batch.Status.FAILED,
          Batch.Status.EXPIRED,
          Batch.Status.CANCELLED);

  private final OpenAIClient client;
  private final boolean ownsClient;
  private final Duration batchPoll;

  /** Uses the given SDK client, reading a running batch's status at the given interval. */
  public OpenAiProvider(OpenAIClient client, Duration batchPoll) {
    this(client, false, batchPoll);
  }

  private OpenAiProvider(OpenAIClient client, boolean ownsClient, Duration batchPoll) {
    this.client = Objects.requireNonNull(client, "client");
    this.ownsClient = ownsClient;
    this.batchPoll = Objects.requireNonNull(batchPoll, "batchPoll");
  }

  /**
   * A provider with its own SDK client, which {@link #close()} closes.
   *
   * @param apiKey the API key
   * @param baseUrl the API's base URL, such as a gateway's, or {@code null} for OpenAI's
   */
  public static OpenAiProvider create(String apiKey, String baseUrl) {
    return create(apiKey, baseUrl, BATCH_POLL);
  }

  /**
   * A provider with its own SDK client, which {@link #close()} closes, reading a running batch's
   * status at the given interval.
   */
  public static OpenAiProvider create(String apiKey, String baseUrl, Duration batchPoll) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("OpenAI API key must not be blank");
    }
    OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
    if (baseUrl != null) {
      builder.baseUrl(baseUrl);
    }
    return new OpenAiProvider(builder.build(), true, batchPoll);
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
    return send(body(model, request, request.cacheSystem()), true);
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    boolean caches = model.supports(Capability.PROMPT_CACHING);
    return send(body(model.model(), request, caches), caches);
  }

  private LlmResponse send(ObjectNode body, boolean caches) {
    com.fasterxml.jackson.databind.json.JsonMapper sdk = ObjectMappers.jsonMapper();
    ChatCompletion completion;
    try {
      ChatCompletionCreateParams params =
          ChatCompletionCreateParams.builder()
              .body(sdk.readValue(body.toString(), ChatCompletionCreateParams.Body.class))
              .build();
      completion = client.chat().completions().create(params);
    } catch (OpenAIException e) {
      throw error(e);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new LlmException("Could not write an OpenAI request: " + e.getMessage(), e);
    }
    try {
      return response(JSON.readTree(sdk.writeValueAsString(completion)), caches);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new LlmException("Could not read an OpenAI answer: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean batches() {
    return true;
  }

  /**
   * Sends the requests as one batch of the Batch API, each as {@link #complete(ModelConfig,
   * LlmRequest)} would send it on its own, and waits for it to end.
   */
  @Override
  public List<LlmBatchResult> completeBatch(ModelConfig model, List<LlmRequest> requests) {
    boolean caches = model.supports(Capability.PROMPT_CACHING);
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < requests.size(); i++) {
      ObjectNode line = JSON.createObjectNode();
      line.put("custom_id", id(i));
      line.put("method", "POST");
      line.put("url", "/v1/chat/completions");
      line.set("body", body(model.model(), requests.get(i), caches));
      lines.append(line).append('\n');
    }
    Map<String, LlmBatchResult> byId = new LinkedHashMap<>();
    try {
      String file =
          client
              .files()
              .create(
                  FileCreateParams.builder()
                      .file(lines.toString().getBytes(StandardCharsets.UTF_8))
                      .purpose(FilePurpose.BATCH)
                      .build())
              .id();
      Batch batch =
          client
              .batches()
              .create(
                  BatchCreateParams.builder()
                      .inputFileId(file)
                      .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                      .completionWindow(BatchCreateParams.CompletionWindow._24H)
                      .build());
      while (!ENDED.contains(batch.status())) {
        try {
          Thread.sleep(batchPoll);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new LlmException("Interrupted while waiting for batch " + batch.id(), e);
        }
        batch = client.batches().retrieve(batch.id());
      }
      if (batch.status().equals(Batch.Status.FAILED)) {
        throw new LlmException("OpenAI batch " + batch.id() + " failed: " + batch.errors());
      }
      for (String output :
          List.of(batch.outputFileId().orElse(""), batch.errorFileId().orElse(""))) {
        if (!output.isEmpty()) {
          read(output, caches, byId);
        }
      }
    } catch (OpenAIException e) {
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

  /** Reads the results in a batch's output or error file into the map, by custom id. */
  private void read(String file, boolean caches, Map<String, LlmBatchResult> byId) {
    String text;
    try (HttpResponse content = client.files().content(file);
        InputStream body = content.body()) {
      text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TransientLlmException("Could not read batch file " + file, null, e);
    }
    for (String line : text.lines().toList()) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode entry = JSON.readTree(line);
      String id = entry.path("custom_id").asString("");
      JsonNode response = entry.path("response");
      int status = response.path("status_code").asInt(0);
      if (status == 200 && response.path("body").isObject()) {
        byId.put(id, LlmBatchResult.answered(response(response.path("body"), caches)));
      } else {
        JsonNode error =
            entry.path("error").isObject() ? entry.path("error") : response.path("body");
        byId.put(
            id,
            LlmBatchResult.failed(
                new LlmException("OpenAI batch request failed (" + status + "): " + error)));
      }
    }
  }

  /** A request's custom id in a batch, by its place. */
  private static String id(int index) {
    return "r" + index;
  }

  /** The Chat Completions body of a request. */
  static ObjectNode body(String model, LlmRequest request, boolean caches) {
    ObjectNode body = JSON.createObjectNode();
    body.put("model", model);
    body.put("max_completion_tokens", request.maxOutputTokens());
    ArrayNode messages = body.putArray("messages");
    if (request.system() != null) {
      messages.addObject().put("role", "system").put("content", request.system());
    }
    for (LlmMessage turn : request.messages()) {
      if (turn.author() == LlmMessage.Author.USER) {
        for (LlmToolResult result : turn.toolResults()) {
          messages
              .addObject()
              .put("role", "tool")
              .put("tool_call_id", result.callId())
              .put("content", result.error() ? "Error: " + result.content() : result.content());
        }
        if (!turn.text().isBlank() || turn.toolResults().isEmpty()) {
          messages.addObject().put("role", "user").put("content", turn.text());
        }
      } else {
        ObjectNode message = messages.addObject().put("role", "assistant");
        if (turn.text().isBlank() && !turn.toolCalls().isEmpty()) {
          message.putNull("content");
        } else {
          message.put("content", turn.text());
        }
        if (!turn.toolCalls().isEmpty()) {
          ArrayNode calls = message.putArray("tool_calls");
          for (LlmToolCall call : turn.toolCalls()) {
            ObjectNode function =
                calls
                    .addObject()
                    .put("id", call.id())
                    .put("type", "function")
                    .putObject("function");
            function.put("name", call.name()).put("arguments", call.input().toString());
          }
        }
      }
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      for (LlmTool tool : request.tools()) {
        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", tool.name()).put("description", tool.description());
        function.set("parameters", tool.inputSchema());
      }
    }
    if (caches) {
      body.put("prompt_cache_key", cacheKey(request));
    }
    return body;
  }

  /**
   * The key that routes requests sharing a prefix to the same cache: the role and a digest of the
   * tools and system prompt, which every request of a role starts with.
   */
  static String cacheKey(LlmRequest request) {
    StringBuilder prefix = new StringBuilder();
    for (LlmTool tool : request.tools()) {
      prefix.append(tool.name()).append('\u0000').append(tool.inputSchema()).append('\u0000');
    }
    prefix.append(request.system() == null ? "" : request.system());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(prefix.toString().getBytes(StandardCharsets.UTF_8));
      return "lantern-"
          + request.role().name().toLowerCase(Locale.ROOT)
          + "-"
          + HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The answer in a Chat Completions response body. */
  static LlmResponse response(JsonNode completion, boolean caches) {
    JsonNode choice = completion.path("choices").path(0);
    JsonNode message = choice.path("message");
    String text = message.path("content").isString() ? message.path("content").asString() : "";
    List<LlmToolCall> calls = new ArrayList<>();
    for (JsonNode call : message.path("tool_calls")) {
      JsonNode function = call.path("function");
      JsonNode input;
      try {
        input = JSON.readTree(function.path("arguments").asString("{}"));
      } catch (RuntimeException e) {
        input = null;
      }
      calls.add(
          new LlmToolCall(
              call.path("id").asString(""),
              function.path("name").asString(""),
              input != null && input.isObject() ? input : JSON.createObjectNode()));
    }
    String finish = choice.path("finish_reason").asString(null);
    String stopReason =
        finish == null
            ? null
            : switch (finish) {
              case "length" -> LlmResponse.MAX_TOKENS;
              case "tool_calls" -> LlmResponse.TOOL_USE;
              case "stop" -> END_TURN;
              default -> finish;
            };
    JsonNode usage = completion.path("usage");
    long prompt = usage.path("prompt_tokens").asLong(0);
    long cached =
        Math.min(prompt, usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
    TokenUsage tokens =
        new TokenUsage(prompt - cached, usage.path("completion_tokens").asLong(0), 0, cached);
    return new LlmResponse(
        text, stopReason, caches ? tokens : tokens.cacheAsInput(), calls, List.of());
  }

  static LlmException error(OpenAIException e) {
    String message = "OpenAI call failed: " + e.getMessage();
    if (e instanceof OpenAIServiceException service) {
      int status = service.statusCode();
      if (status == 408 || status == 429 || (status >= 500 && status != 501)) {
        return new TransientLlmException(message, retryAfter(service.headers()), e);
      }
      return new LlmException(message, e);
    }
    if (e instanceof OpenAIIoException || e instanceof OpenAIRetryableException) {
      return new TransientLlmException(message, null, e);
    }
    return new LlmException(message, e);
  }

  /** The wait a {@code retry-after-ms} or {@code retry-after} header asked for, if readable. */
  static Duration retryAfter(Headers headers) {
    for (String name : List.of("retry-after-ms", "retry-after")) {
      for (String value : headers.values(name)) {
        try {
          double amount = Double.parseDouble(value.trim());
          if (amount >= 0 && Double.isFinite(amount)) {
            double millis = name.endsWith("-ms") ? amount : amount * 1000;
            return Duration.ofMillis((long) Math.min(millis, 10 * 60 * 1000));
          }
        } catch (NumberFormatException ignored) {
          // Try the next value.
        }
      }
    }
    return null;
  }
}
