package io.lantern.llm.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A server on the loopback interface that speaks OpenAI's Chat Completions, Files and Batch APIs
 * and answers with another provider, such as a fake: it reads each chat request back into the
 * {@link LlmRequest} the roles sent, asks the provider, and writes its answer in OpenAI's form. The
 * role is read from the request's {@code prompt_cache_key}, which {@link OpenAiProvider} sets for a
 * model with prompt caching. A batch ends at once: its first read says in progress, the next says
 * completed. Never reaches the network.
 */
public final class OpenAiStub implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Function<String, ModelConfig> models;
  private final LlmProvider answers;
  private final List<JsonNode> chats = new CopyOnWriteArrayList<>();
  private final Map<String, String> files = new ConcurrentHashMap<>();
  private final Map<String, ObjectNode> batches = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger batchReads = new AtomicInteger();
  private final List<String> deleted = new CopyOnWriteArrayList<>();
  private volatile int failReads;

  /** Fails the next reads of a batch's status with a 503, as a passing outage would. */
  public void failingNextBatchReads(int reads) {
    this.failReads = reads;
  }

  /** The files deleted. */
  public List<String> deleted() {
    return List.copyOf(deleted);
  }

  /**
   * @param answers the provider that answers
   * @param models the model config to ask the provider with, by the model id requested
   */
  public OpenAiStub(LlmProvider answers, Function<String, ModelConfig> models) throws IOException {
    this.answers = answers;
    this.models = models;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /** The base URL to give the provider. */
  public String baseUrl() {
    return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
  }

  /** The chat request bodies received, interactively or in batches, oldest first. */
  public List<JsonNode> chats() {
    return List.copyOf(chats);
  }

  /** How many batches were created. */
  public int batches() {
    return batches.size();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      byte[] body = exchange.getRequestBody().readAllBytes();
      if (method.equals("POST") && path.endsWith("/chat/completions")) {
        JsonNode request = JSON.readTree(body);
        chats.add(request);
        reply(exchange, 200, completion(request));
      } else if (method.equals("POST") && path.endsWith("/files")) {
        String id = "file-" + ids.incrementAndGet();
        files.put(id, upload(exchange, body));
        ObjectNode file = JSON.createObjectNode();
        file.put("id", id).put("object", "file").put("bytes", body.length);
        file.put("created_at", 0).put("filename", "batch.jsonl").put("purpose", "batch");
        file.put("status", "processed");
        reply(exchange, 200, file);
      } else if (method.equals("POST") && path.endsWith("/batches")) {
        JsonNode request = JSON.readTree(body);
        String id = "batch-" + ids.incrementAndGet();
        String output = "file-" + ids.incrementAndGet();
        StringBuilder lines = new StringBuilder();
        for (String line : files.get(request.path("input_file_id").asString()).lines().toList()) {
          if (line.isBlank()) {
            continue;
          }
          JsonNode entry = JSON.readTree(line);
          chats.add(entry.path("body"));
          ObjectNode result = JSON.createObjectNode();
          result.put("id", "req-" + ids.incrementAndGet());
          result.put("custom_id", entry.path("custom_id").asString());
          ObjectNode response = result.putObject("response");
          response.put("status_code", 200).put("request_id", "r");
          response.set("body", completion(entry.path("body")));
          result.putNull("error");
          lines.append(result).append('\n');
        }
        files.put(output, lines.toString());
        ObjectNode batch = JSON.createObjectNode();
        batch.put("id", id).put("object", "batch").put("endpoint", "/v1/chat/completions");
        batch.put("input_file_id", request.path("input_file_id").asString());
        batch.put("completion_window", "24h").put("created_at", 0).put("status", "in_progress");
        batches.put(id, batch.deepCopy().put("status", "completed").put("output_file_id", output));
        reply(exchange, 200, batch);
      } else if (method.equals("DELETE") && path.contains("/files/")) {
        String id = path.substring(path.lastIndexOf('/') + 1);
        deleted.add(id);
        files.remove(id);
        reply(
            exchange,
            200,
            JSON.createObjectNode().put("id", id).put("object", "file").put("deleted", true));
      } else if (method.equals("GET") && path.contains("/batches/")) {
        batchReads.incrementAndGet();
        if (failReads > 0) {
          failReads--;
          reply(exchange, 503, JSON.createObjectNode().put("error", "unavailable"));
          return;
        }
        reply(exchange, 200, batches.get(path.substring(path.lastIndexOf('/') + 1)));
      } else if (method.equals("GET") && path.endsWith("/content")) {
        String[] parts = path.split("/");
        byte[] content = files.get(parts[parts.length - 2]).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
      } else {
        reply(exchange, 404, JSON.createObjectNode().put("error", path));
      }
    } catch (RuntimeException e) {
      byte[] message = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, message.length);
      exchange.getResponseBody().write(message);
    }
  }

  /** The file part of a multipart upload. */
  private static String upload(HttpExchange exchange, byte[] body) {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    String boundary = "--" + type.substring(type.indexOf("boundary=") + 9).replace("\"", "");
    String text = new String(body, StandardCharsets.UTF_8);
    for (String part : text.split(java.util.regex.Pattern.quote(boundary))) {
      if (part.contains("name=\"file\"")) {
        String content = part.substring(part.indexOf("\r\n\r\n") + 4);
        return content.endsWith("\r\n") ? content.substring(0, content.length() - 2) : content;
      }
    }
    throw new IllegalArgumentException("No file in the upload");
  }

  private static void reply(HttpExchange exchange, int status, JsonNode body) throws IOException {
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  /** The provider's answer to a chat request, as a Chat Completions response body. */
  private ObjectNode completion(JsonNode chat) {
    String model = chat.path("model").asString();
    LlmResponse answer = answers.complete(models.apply(model), request(chat));
    ObjectNode completion = JSON.createObjectNode();
    completion.put("id", "chatcmpl-" + ids.incrementAndGet());
    completion.put("object", "chat.completion").put("created", 0).put("model", model);
    ObjectNode choice = completion.putArray("choices").addObject();
    choice.put("index", 0).putNull("logprobs");
    choice.put(
        "finish_reason",
        switch (answer.stopReason() == null ? "" : answer.stopReason()) {
          case LlmResponse.MAX_TOKENS -> "length";
          case LlmResponse.TOOL_USE -> "tool_calls";
          default -> "stop";
        });
    ObjectNode message = choice.putObject("message");
    message.put("role", "assistant").put("content", answer.text()).putNull("refusal");
    if (!answer.toolCalls().isEmpty()) {
      ArrayNode calls = message.putArray("tool_calls");
      for (LlmToolCall call : answer.toolCalls()) {
        ObjectNode entry = calls.addObject().put("id", call.id()).put("type", "function");
        entry
            .putObject("function")
            .put("name", call.name())
            .put("arguments", call.input().toString());
      }
    }
    ObjectNode usage = completion.putObject("usage");
    long prompt = answer.usage().input() + answer.usage().cacheWrite() + answer.usage().cacheRead();
    usage.put("prompt_tokens", prompt).put("completion_tokens", answer.usage().output());
    usage.put("total_tokens", prompt + answer.usage().output());
    usage.putObject("prompt_tokens_details").put("cached_tokens", answer.usage().cacheRead());
    return completion;
  }

  /** The request a chat request body was made from. */
  static LlmRequest request(JsonNode chat) {
    String key = chat.path("prompt_cache_key").asString("");
    Role role = Role.SUMMARIZE;
    if (key.startsWith("lantern-")) {
      role = Role.valueOf(key.substring(8, key.lastIndexOf('-')).toUpperCase(Locale.ROOT));
    }
    String system = null;
    List<LlmMessage> messages = new ArrayList<>();
    List<LlmToolResult> results = new ArrayList<>();
    for (JsonNode message : chat.path("messages")) {
      switch (message.path("role").asString()) {
        case "system" -> system = message.path("content").asString();
        case "tool" -> {
          String content = message.path("content").asString();
          boolean error = content.startsWith("Error: ");
          results.add(
              new LlmToolResult(
                  message.path("tool_call_id").asString(),
                  error ? content.substring(7) : content,
                  error));
        }
        case "user" -> {
          messages.add(
              results.isEmpty()
                  ? LlmMessage.user(message.path("content").asString())
                  : LlmMessage.toolResults(results, message.path("content").asString()));
          results = new ArrayList<>();
        }
        case "assistant" -> {
          if (!results.isEmpty()) {
            messages.add(LlmMessage.toolResults(results, ""));
            results = new ArrayList<>();
          }
          List<LlmToolCall> calls = new ArrayList<>();
          for (JsonNode call : message.path("tool_calls")) {
            calls.add(
                new LlmToolCall(
                    call.path("id").asString(),
                    call.path("function").path("name").asString(),
                    JSON.readTree(call.path("function").path("arguments").asString())));
          }
          String text =
              message.path("content").isString() ? message.path("content").asString() : "";
          messages.add(
              calls.isEmpty() ? LlmMessage.assistant(text) : LlmMessage.assistant(text, calls));
        }
        default -> throw new IllegalArgumentException("Unknown role " + message);
      }
    }
    if (!results.isEmpty()) {
      messages.add(LlmMessage.toolResults(results, ""));
    }
    List<LlmTool> tools = new ArrayList<>();
    for (JsonNode tool : chat.path("tools")) {
      JsonNode function = tool.path("function");
      tools.add(
          new LlmTool(
              function.path("name").asString(),
              function.path("description").asString(),
              function.path("parameters")));
    }
    return new LlmRequest(
        role,
        "openai stub",
        system,
        messages,
        chat.path("max_completion_tokens").asInt(),
        false,
        tools,
        List.of());
  }
}
