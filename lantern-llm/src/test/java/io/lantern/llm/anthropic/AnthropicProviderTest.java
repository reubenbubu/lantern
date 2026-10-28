package io.lantern.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestPath.set(exchange.getRequestURI().getPath());
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
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

  @Test
  void needsAnApiKey() {
    assertThatThrownBy(() -> AnthropicProvider.create(" ", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
