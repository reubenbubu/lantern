package io.lantern.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.anthropic.core.http.Headers;
import com.sun.net.httpserver.HttpServer;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.TransientLlmException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the translation to and from the Messages API against a stub server on the loopback
 * interface. Never reaches the network.
 */
class AnthropicProviderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> requestPath = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String responseBody;
  private volatile Map<String, String> responseHeaders = Map.of();
  private final AtomicInteger requests = new AtomicInteger();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          requestPath.set(exchange.getRequestURI().getPath());
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          responseHeaders.forEach(exchange.getResponseHeaders()::add);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private AnthropicProvider provider() {
    return AnthropicProvider.create(
        "stub-key", "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Test
  void sendsTheRequestAndReadsTheAnswer() {
    responseBody =
        """
        {"id": "msg_1", "type": "message", "role": "assistant", "model": "stub-model",
         "content": [{"type": "text", "text": "An order "}, {"type": "text", "text": "aggregate."}],
         "stop_reason": "end_turn", "stop_sequence": null,
         "usage": {"input_tokens": 12, "output_tokens": 5,
                   "cache_creation_input_tokens": 100, "cache_read_input_tokens": 7}}
        """;
    LlmRequest request =
        new LlmRequest(
            Role.SUMMARIZE,
            "summarize Order",
            "You summarize code.",
            List.of(
                LlmMessage.user("class Order {}"),
                LlmMessage.assistant("Need more?"),
                LlmMessage.user("No.")),
            300,
            true);

    LlmResponse response;
    try (AnthropicProvider provider = provider()) {
      response = provider.complete("stub-model", request);
    }

    assertThat(response.text()).isEqualTo("An order aggregate.");
    assertThat(response.stopReason()).isEqualTo("end_turn");
    assertThat(response.usage()).isEqualTo(new TokenUsage(12, 5, 100, 7));
    assertThat(requestPath.get()).isEqualTo("/v1/messages");
    JsonNode sent = JSON.readTree(requestBody.get());
    assertThat(sent.get("model").asString()).isEqualTo("stub-model");
    assertThat(sent.get("max_tokens").asInt()).isEqualTo(300);
    assertThat(sent.at("/system/0/text").asString()).isEqualTo("You summarize code.");
    assertThat(sent.at("/system/0/cache_control/type").asString()).isEqualTo("ephemeral");
    assertThat(sent.get("messages")).hasSize(3);
    assertThat(sent.at("/messages/0/role").asString()).isEqualTo("user");
    assertThat(sent.at("/messages/0/content").asString()).isEqualTo("class Order {}");
    assertThat(sent.at("/messages/1/role").asString()).isEqualTo("assistant");
    assertThat(sent.at("/messages/2/content").asString()).isEqualTo("No.");
  }

  @Test
  void sendsToolsToolCallsAndResultsAndReadsTheToolCallsOfTheAnswer() {
    responseBody =
        """
        {"id": "msg_3", "type": "message", "role": "assistant", "model": "stub-model",
         "content": [{"type": "text", "text": "Let me look."},
                     {"type": "tool_use", "id": "toolu_2", "name": "read_file",
                      "input": {"repo": "shop", "path": "a/B.java", "from": 3, "to": 9}}],
         "stop_reason": "tool_use", "stop_sequence": null,
         "usage": {"input_tokens": 40, "output_tokens": 12}}
        """;
    tools.jackson.databind.node.ObjectNode schema = JSON.createObjectNode().put("type", "object");
    schema.putObject("properties").putObject("entity").put("type", "string");
    schema.putArray("required").add("entity");
    LlmTool tool = new LlmTool("facts_about", "Facts about an entity.", schema);
    LlmToolCall earlier =
        new LlmToolCall("toolu_1", "facts_about", JSON.createObjectNode().put("entity", "unit:a"));
    LlmRequest request =
        new LlmRequest(
            Role.RESOLVE,
            "resolve",
            "You resolve.",
            List.of(
                LlmMessage.user("Which unit?"),
                LlmMessage.assistant("", List.of(earlier)),
                LlmMessage.toolResults(
                    List.of(new LlmToolResult("toolu_1", "No facts.", true)), "Go on.")),
            300,
            false,
            List.of(tool));

    LlmResponse response;
    try (AnthropicProvider provider = provider()) {
      response = provider.complete("stub-model", request);
    }

    assertThat(response.text()).isEqualTo("Let me look.");
    assertThat(response.stopReason()).isEqualTo(LlmResponse.TOOL_USE);
    assertThat(response.wantsTools()).isTrue();
    LlmToolCall call = response.toolCalls().getFirst();
    assertThat(call.id()).isEqualTo("toolu_2");
    assertThat(call.name()).isEqualTo("read_file");
    assertThat(call.input().get("path").asString()).isEqualTo("a/B.java");
    assertThat(call.input().get("to").asInt()).isEqualTo(9);
    JsonNode sent = JSON.readTree(requestBody.get());
    assertThat(sent.at("/tools/0/name").asString()).isEqualTo("facts_about");
    assertThat(sent.at("/tools/0/description").asString()).isEqualTo("Facts about an entity.");
    assertThat(sent.at("/tools/0/input_schema/type").asString()).isEqualTo("object");
    assertThat(sent.at("/tools/0/input_schema/properties/entity/type").asString())
        .isEqualTo("string");
    assertThat(sent.at("/tools/0/input_schema/required/0").asString()).isEqualTo("entity");
    assertThat(sent.at("/messages/1/role").asString()).isEqualTo("assistant");
    assertThat(sent.at("/messages/1/content")).hasSize(1);
    assertThat(sent.at("/messages/1/content/0/type").asString()).isEqualTo("tool_use");
    assertThat(sent.at("/messages/1/content/0/id").asString()).isEqualTo("toolu_1");
    assertThat(sent.at("/messages/1/content/0/input/entity").asString()).isEqualTo("unit:a");
    assertThat(sent.at("/messages/2/content/0/type").asString()).isEqualTo("tool_result");
    assertThat(sent.at("/messages/2/content/0/tool_use_id").asString()).isEqualTo("toolu_1");
    assertThat(sent.at("/messages/2/content/0/content").asString()).isEqualTo("No facts.");
    assertThat(sent.at("/messages/2/content/0/is_error").asBoolean()).isTrue();
    assertThat(sent.at("/messages/2/content/1/type").asString()).isEqualTo("text");
    assertThat(sent.at("/messages/2/content/1/text").asString()).isEqualTo("Go on.");
    // The schema's type is sent once, not again among its other fields.
    String body = requestBody.get().replace(" ", "");
    assertThat(body.split("\"type\":\"object\"", -1)).hasSize(2);
  }

  @Test
  void keepsTheThinkingOfAToolUseAnswerAndSendsItBackFirst() {
    responseBody =
        """
        {"id": "msg_4", "type": "message", "role": "assistant", "model": "stub-model",
         "content": [{"type": "thinking", "thinking": "I should look.", "signature": "sig-1"},
                     {"type": "redacted_thinking", "data": "opaque"},
                     {"type": "tool_use", "id": "toolu_9", "name": "facts_about",
                      "input": {"entity": "unit:a"}}],
         "stop_reason": "tool_use", "stop_sequence": null,
         "usage": {"input_tokens": 10, "output_tokens": 30}}
        """;
    tools.jackson.databind.node.ObjectNode schema = JSON.createObjectNode().put("type", "object");
    LlmTool tool = new LlmTool("facts_about", "Facts about an entity.", schema);
    LlmRequest first =
        new LlmRequest(
            Role.RESOLVE,
            "resolve",
            null,
            List.of(LlmMessage.user("Which unit?")),
            300,
            false,
            List.of(tool));

    LlmResponse answer;
    try (AnthropicProvider provider = provider()) {
      answer = provider.complete("stub-model", first);
      assertThat(answer.text()).isEmpty();
      assertThat(answer.thinking())
          .containsExactly(
              new io.lantern.llm.LlmThinking("I should look.", "sig-1", false),
              new io.lantern.llm.LlmThinking("opaque", null, true));
      provider.complete(
          "stub-model",
          first.withMessages(
              List.of(
                  LlmMessage.user("Which unit?"),
                  LlmMessage.assistant(answer),
                  LlmMessage.toolResults(
                      List.of(LlmToolResult.of(answer.toolCalls().getFirst(), "none")), ""))));
    }

    JsonNode sent = JSON.readTree(requestBody.get());
    assertThat(sent.at("/messages/1/content/0/type").asString()).isEqualTo("thinking");
    assertThat(sent.at("/messages/1/content/0/thinking").asString()).isEqualTo("I should look.");
    assertThat(sent.at("/messages/1/content/0/signature").asString()).isEqualTo("sig-1");
    assertThat(sent.at("/messages/1/content/1/type").asString()).isEqualTo("redacted_thinking");
    assertThat(sent.at("/messages/1/content/1/data").asString()).isEqualTo("opaque");
    assertThat(sent.at("/messages/1/content/2/type").asString()).isEqualTo("tool_use");
  }

  @Test
  void leavesOutAMissingSystemPromptAndCacheControl() {
    responseBody =
        """
        {"id": "msg_2", "type": "message", "role": "assistant", "model": "stub-model",
         "content": [], "stop_reason": "max_tokens", "stop_sequence": null,
         "usage": {"input_tokens": 3, "output_tokens": 0}}
        """;

    LlmResponse response;
    try (AnthropicProvider provider = provider()) {
      response =
          provider.complete("stub-model", LlmRequest.of(Role.COMPOSE, "compose", null, "Hi", 10));
    }

    assertThat(response.text()).isEmpty();
    assertThat(response.stopReason()).isEqualTo("max_tokens");
    assertThat(response.usage()).isEqualTo(new TokenUsage(3, 0, 0, 0));
    JsonNode sent = JSON.readTree(requestBody.get());
    assertThat(sent.has("system")).isFalse();
  }

  @Test
  void wrapsApiErrors() {
    status = 400;
    responseBody =
        """
        {"type": "error", "error": {"type": "invalid_request_error", "message": "bad model"}}
        """;

    try (AnthropicProvider provider = provider()) {
      assertThatThrownBy(
              () ->
                  provider.complete(
                      "stub-model", LlmRequest.of(Role.COMPOSE, "compose", null, "Hi", 10)))
          .isInstanceOf(LlmException.class)
          .hasMessageContaining("bad model");
    }
  }

  private LlmException failure() {
    try (AnthropicProvider provider = provider()) {
      LlmRequest request = LlmRequest.of(Role.COMPOSE, "compose", null, "Hi", 10);
      Throwable thrown = catchThrowable(() -> provider.complete("stub-model", request));
      assertThat(thrown).isInstanceOf(LlmException.class);
      return (LlmException) thrown;
    }
  }

  @Test
  void aRateLimitIsTransientWithTheWaitTheApiAskedFor() {
    status = 429;
    responseHeaders = Map.of("retry-after", "2");
    responseBody =
        """
        {"type": "error", "error": {"type": "rate_limit_error", "message": "slow down"}}
        """;

    LlmException failure = failure();

    assertThat(failure).isInstanceOf(TransientLlmException.class).hasMessageContaining("slow down");
    assertThat(((TransientLlmException) failure).retryAfter()).contains(Duration.ofSeconds(2));
    // The SDK does not retry on its own; the client does.
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  void overloadAndServerErrorsAreTransient() {
    responseBody =
        """
        {"type": "error", "error": {"type": "overloaded_error", "message": "overloaded"}}
        """;
    for (int code : new int[] {500, 503, 529}) {
      status = code;
      responseHeaders = Map.of("retry-after-ms", "1500");

      LlmException failure = failure();

      assertThat(failure).isInstanceOf(TransientLlmException.class);
      assertThat(((TransientLlmException) failure).retryAfter()).contains(Duration.ofMillis(1500));
    }
    assertThat(requests.get()).isEqualTo(3);
  }

  @Test
  void anUnreachableApiIsTransient() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    LlmRequest request = LlmRequest.of(Role.COMPOSE, "compose", null, "Hi", 10);

    try (AnthropicProvider provider =
        AnthropicProvider.create("stub-key", "http://127.0.0.1:" + closedPort)) {
      assertThatThrownBy(() -> provider.complete("stub-model", request))
          .isInstanceOf(TransientLlmException.class);
    }
  }

  @Test
  void clientErrorsAndNotImplementedAreNotTransient() {
    responseBody =
        """
        {"type": "error", "error": {"type": "authentication_error", "message": "bad key"}}
        """;
    for (int code : new int[] {400, 401, 501}) {
      status = code;

      assertThat(failure()).isNotInstanceOf(TransientLlmException.class);
    }
  }

  @Test
  void readsRetryAfterInEveryForm() {
    assertThat(AnthropicProvider.retryAfter(headers("retry-after", "1.5")))
        .isEqualTo(Duration.ofMillis(1500));
    assertThat(AnthropicProvider.retryAfter(headers("retry-after-ms", "250")))
        .isEqualTo(Duration.ofMillis(250));
    Duration fromDate =
        AnthropicProvider.retryAfter(
            headers(
                "retry-after",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30))));
    assertThat(fromDate).isBetween(Duration.ofSeconds(28), Duration.ofSeconds(30));
    assertThat(AnthropicProvider.retryAfter(headers("retry-after", "soon"))).isNull();
    assertThat(AnthropicProvider.retryAfter(headers("retry-after", "86400")))
        .isEqualTo(Duration.ofMinutes(10));
    assertThat(AnthropicProvider.retryAfter(Headers.builder().build())).isNull();
  }

  private static Headers headers(String name, String value) {
    return Headers.builder().put(name, value).build();
  }

  @Test
  void needsAnApiKey() {
    assertThatThrownBy(() -> AnthropicProvider.create(" ", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
