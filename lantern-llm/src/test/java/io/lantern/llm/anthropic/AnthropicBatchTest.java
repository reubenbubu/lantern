package io.lantern.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.lantern.llm.CacheBreak;
import io.lantern.llm.Capability;
import io.lantern.llm.LlmBatchResult;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks Message Batches (ADR 0102) against a stub of the batch endpoints on the loopback
 * interface: the batch is created with each request as {@code complete} sends it, read until it has
 * ended, and its results read back by custom id. Never reaches the network.
 */
class AnthropicBatchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private volatile JsonNode created;
  private final AtomicInteger reads = new AtomicInteger();

  private static final ModelConfig MODEL =
      new ModelConfig(
          AnthropicProvider.NAME,
          "claude-test",
          EnumSet.of(Capability.PROMPT_CACHING, Capability.BATCH),
          200_000,
          Pricing.FREE);

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      paths.add(exchange.getRequestMethod() + " " + path);
      byte[] body = exchange.getRequestBody().readAllBytes();
      String reply;
      if (path.endsWith("/results")) {
        StringBuilder lines = new StringBuilder();
        // Results come in any order; the second request errored.
        for (int i = created.path("requests").size() - 1; i >= 0; i--) {
          String id = created.path("requests").path(i).path("custom_id").asString();
          String result =
              i == 1
                  ? "{\"type\": \"errored\", \"error\": {\"type\": \"error\", \"error\":"
                      + " {\"type\": \"invalid_request_error\", \"message\": \"bad\"}}}"
                  : "{\"type\": \"succeeded\", \"message\": {\"id\": \"m"
                      + i
                      + "\", \"type\": \"message\", \"role\": \"assistant\", \"model\":"
                      + " \"claude-test\", \"content\": [{\"type\": \"text\", \"text\": \"answer "
                      + i
                      + "\"}], \"stop_reason\": \"end_turn\", \"stop_sequence\": null,"
                      + " \"usage\": {\"input_tokens\": 10, \"output_tokens\": 5,"
                      + " \"cache_creation_input_tokens\": 0, \"cache_read_input_tokens\":"
                      + " 100}}}";
          lines.append("{\"custom_id\": \"").append(id).append("\", \"result\": ");
          lines.append(result).append("}\n");
        }
        reply = lines.toString();
      } else {
        if (exchange.getRequestMethod().equals("POST")) {
          created = JSON.readTree(body);
        } else {
          reads.incrementAndGet();
        }
        boolean ended = exchange.getRequestMethod().equals("GET");
        reply =
            """
            {"id": "msgbatch_1", "type": "message_batch", "processing_status": "%s",
             "request_counts": {"processing": 0, "succeeded": 2, "errored": 1, "canceled": 0,
               "expired": 0},
             "created_at": "2026-11-01T00:00:00Z", "expires_at": "2026-11-02T00:00:00Z",
             "ended_at": %s, "archived_at": null, "cancel_initiated_at": null,
             "results_url": %s}"""
                .formatted(
                    ended ? "ended" : "in_progress",
                    ended ? "\"2026-11-01T00:10:00Z\"" : "null",
                    ended ? "\"" + base() + "/v1/messages/batches/msgbatch_1/results\"" : "null");
      }
      byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static LlmRequest request(String text) {
    return new LlmRequest(
            Role.SUMMARIZE,
            "test",
            "You are Lantern.",
            List.of(LlmMessage.user(text)),
            200,
            false,
            List.of(),
            List.of(CacheBreak.inSystem(10)))
        .withLongLivedCache();
  }

  @Test
  void aBatchIsSentAsMessagesRequestsAndItsResultsAreReadBackByCustomId() {
    try (AnthropicProvider provider =
        new AnthropicProvider(
            AnthropicOkHttpClient.builder().apiKey("test").baseUrl(base()).maxRetries(0).build(),
            Duration.ofMillis(10))) {
      assertThat(provider.batches()).isTrue();

      List<LlmBatchResult> results =
          provider.completeBatch(MODEL, List.of(request("one"), request("two"), request("three")));

      assertThat(results).hasSize(3);
      assertThat(results.get(0).response().text()).isEqualTo("answer 0");
      assertThat(results.get(0).response().usage()).isEqualTo(new TokenUsage(10, 5, 0, 100));
      assertThat(results.get(1).error()).hasMessageContaining("errored");
      assertThat(results.get(2).response().text()).isEqualTo("answer 2");
      assertThat(reads.get()).isEqualTo(1);
    }
    JsonNode first = created.path("requests").path(0);
    assertThat(first.path("custom_id").asString()).isEqualTo("r0");
    JsonNode params = first.path("params");
    assertThat(params.path("model").asString()).isEqualTo("claude-test");
    assertThat(params.path("messages").path(0).path("content").asString()).isEqualTo("one");
    // The warm-up's prefix is read by the batch: its breakpoints live for an hour.
    assertThat(params.path("system").path(0).path("cache_control").path("ttl").asString())
        .isEqualTo("1h");
    assertThat(paths.getFirst()).isEqualTo("POST /v1/messages/batches");
  }
}
