package io.lantern.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import io.lantern.llm.Thinking;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs {@link LlmClient} and {@link AnthropicProvider} against a stub of a model that thinks by
 * default, on the loopback interface. The stub writes a thinking block before its answer and counts
 * the thinking tokens against {@code max_tokens}, like the real APIs; it can honor {@code thinking:
 * {type: "disabled"}} (as DeepSeek does) or accept it and think anyway (as GLM through OpenRouter
 * does). Never reaches the network.
 */
class ThinkingModelTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int THINKING_TOKENS = 300;
  private static final String ANSWER = "An order aggregate with its lines.";
  private static final int ANSWER_TOKENS = 9;

  private HttpServer server;
  private volatile boolean honorsDisabled;
  private final AtomicReference<JsonNode> sent = new AtomicReference<>();
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          JsonNode request =
              JSON.readTree(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          sent.set(request);
          byte[] body = JSON.writeValueAsBytes(answer(request));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  /** Thinks first, then answers with what is left of max_tokens. */
  private ObjectNode answer(JsonNode request) {
    int maxTokens = request.get("max_tokens").asInt();
    JsonNode type = request.at("/thinking/type");
    boolean disabled = type.isString() && type.asString().equals("disabled");
    int thinking = disabled && honorsDisabled ? 0 : THINKING_TOKENS;
    ObjectNode message = JSON.createObjectNode();
    message.put("id", "msg_1").put("type", "message").put("role", "assistant");
    message.put("model", request.get("model").asString());
    ArrayNode content = message.putArray("content");
    int output;
    String stopReason;
    if (thinking > 0) {
      content
          .addObject()
          .put("type", "thinking")
          .put("thinking", "Let me think about the order.")
          .put("signature", "stub-signature");
      content.addObject().put("type", "redacted_thinking").put("data", "stub-redacted");
    }
    if (thinking + ANSWER_TOKENS <= maxTokens) {
      content.addObject().put("type", "text").put("text", ANSWER);
      output = thinking + ANSWER_TOKENS;
      stopReason = "end_turn";
    } else {
      int left = Math.max(0, maxTokens - thinking);
      if (left > 0) {
        content.addObject().put("type", "text").put("text", ANSWER.substring(0, left * 4));
      }
      output = maxTokens;
      stopReason = "max_tokens";
    }
    message.put("stop_reason", stopReason).putNull("stop_sequence");
    message.putObject("usage").put("input_tokens", 20).put("output_tokens", output);
    return message;
  }

  private LlmResponse call(Thinking thinking) {
    ModelConfig model =
        new ModelConfig(AnthropicProvider.NAME, "stub-model", Set.of(), 100_000, Pricing.FREE)
            .withThinking(thinking);
    try (AnthropicProvider provider =
        AnthropicProvider.create("stub-key", "http://127.0.0.1:" + server.getAddress().getPort())) {
      LlmClient client =
          new LlmClient(
              ModelSettings.single(model),
              List.of(provider),
              BudgetGuard.unlimited("test"),
              log,
              Clock.systemUTC());
      return client.call(
          LlmRequest.of(Role.SUMMARIZE, "summarize Order", "You summarize.", "class Order", 50));
    }
  }

  @Test
  void byDefaultThinkingUsesUpTheOutputLimit() {
    LlmResponse response = call(Thinking.DEFAULT);

    assertThat(sent.get().has("thinking")).isFalse();
    assertThat(sent.get().get("max_tokens").asInt()).isEqualTo(50);
    assertThat(response.cutOff()).isTrue();
    assertThat(response.text()).isEmpty();
    assertThat(log.calls().getLast().outcome()).isEqualTo(LlmCall.Outcome.TRUNCATED);
  }

  @Test
  void aProviderThatHonorsDisabledThinkingAnswersInFull() {
    honorsDisabled = true;

    LlmResponse response = call(new Thinking(true, 0));

    assertThat(sent.get().at("/thinking/type").asString()).isEqualTo("disabled");
    assertThat(sent.get().get("max_tokens").asInt()).isEqualTo(50);
    assertThat(response.cutOff()).isFalse();
    assertThat(response.text()).isEqualTo(ANSWER);
    assertThat(response.usage().output()).isEqualTo(ANSWER_TOKENS);
    assertThat(log.calls().getLast().outcome()).isEqualTo(LlmCall.Outcome.SUCCEEDED);
  }

  @Test
  void aProviderThatThinksAnywayNeedsHeadroom() {
    honorsDisabled = false;

    LlmResponse cut = call(new Thinking(true, 0));

    assertThat(sent.get().at("/thinking/type").asString()).isEqualTo("disabled");
    assertThat(cut.cutOff()).isTrue();
    assertThat(log.calls().getLast().outcome()).isEqualTo(LlmCall.Outcome.TRUNCATED);

    LlmResponse whole = call(new Thinking(true, 400));

    assertThat(sent.get().get("max_tokens").asInt()).isEqualTo(450);
    assertThat(whole.cutOff()).isFalse();
    // The thinking blocks are not part of the answer.
    assertThat(whole.text()).isEqualTo(ANSWER);
    assertThat(whole.usage().output()).isEqualTo(THINKING_TOKENS + ANSWER_TOKENS);
    assertThat(log.calls().getLast().outcome()).isEqualTo(LlmCall.Outcome.SUCCEEDED);
  }
}
