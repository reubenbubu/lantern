package io.lantern.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.CacheBreak;
import io.lantern.llm.Capability;
import io.lantern.llm.Conversation;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmBatchResult;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.ToolBox;
import io.lantern.llm.TransientLlmException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the translation to and from OpenAI's Chat Completions and Batch APIs against a stub on the
 * loopback interface that answers with a fake (ADR 0102). Never reaches the network.
 */
class OpenAiProviderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FakeLlmProvider fake = new FakeLlmProvider();
  private OpenAiStub stub;
  private OpenAiProvider provider;

  private static ModelConfig model(Set<Capability> capabilities) {
    return new ModelConfig(
        OpenAiProvider.NAME,
        "gpt-test",
        capabilities.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(capabilities),
        200_000,
        new Pricing(
            new BigDecimal("2"), new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("0.5")));
  }

  private static final ModelConfig CACHING =
      model(Set.of(Capability.TOOL_USE, Capability.PROMPT_CACHING, Capability.BATCH));

  private OpenAiProvider start() throws IOException {
    stub = new OpenAiStub(fake, id -> fake.model(id));
    provider =
        new OpenAiProvider(
            OpenAIOkHttpClient.builder()
                .apiKey("test")
                .baseUrl(stub.baseUrl())
                .maxRetries(0)
                .build(),
            Duration.ofMillis(10));
    return provider;
  }

  @AfterEach
  void stop() {
    if (provider != null) {
      provider.close();
    }
    if (stub != null) {
      stub.close();
    }
  }

  private static LlmRequest request(Role role, String text) {
    return new LlmRequest(
        role,
        "test",
        "You are Lantern.",
        List.of(LlmMessage.user(text)),
        500,
        false,
        List.of(),
        List.of(CacheBreak.inSystem(10)));
  }

  @Test
  void aRequestGoesAsChatMessagesWithAStableCacheKeyAndItsUsageIsNormalised() throws Exception {
    fake.answering("{\"ok\": true}");
    start();

    LlmResponse first = provider.complete(CACHING, request(Role.SUMMARIZE, "First"));
    LlmResponse second = provider.complete(CACHING, request(Role.SUMMARIZE, "Second"));

    assertThat(first.text()).isEqualTo("{\"ok\": true}");
    assertThat(first.stopReason()).isEqualTo("end_turn");
    JsonNode body = stub.chats().getFirst();
    assertThat(body.path("model").asString()).isEqualTo("gpt-test");
    assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(500);
    assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("system");
    assertThat(body.path("messages").path(0).path("content").asString())
        .isEqualTo("You are Lantern.");
    assertThat(body.path("messages").path(1).path("content").asString()).isEqualTo("First");
    // Breakpoints are not sent: the cache key routes requests that share the prefix.
    assertThat(body.toString()).doesNotContain("cache_control");
    String key = body.path("prompt_cache_key").asString();
    assertThat(key).startsWith("lantern-summarize-");
    assertThat(stub.chats().get(1).path("prompt_cache_key").asString()).isEqualTo(key);
    assertThat(
            OpenAiProvider.body("m", request(Role.COMPOSE, "x"), true)
                .path("prompt_cache_key")
                .asString())
        .startsWith("lantern-compose-")
        .isNotEqualTo(key);
    // A model without prompt caching gets no key.
    assertThat(
            OpenAiProvider.body("m", request(Role.SUMMARIZE, "x"), false).has("prompt_cache_key"))
        .isFalse();
  }

  @Test
  void cachedTokensAreCacheReadsAndTheRestOfThePromptIsInput() {
    JsonNode completion =
        JSON.readTree(
            """
            {"choices": [{"index": 0, "finish_reason": "length",
              "message": {"role": "assistant", "content": "cut"}}],
             "usage": {"prompt_tokens": 1200, "completion_tokens": 50,
              "prompt_tokens_details": {"cached_tokens": 1024}}}""");

    LlmResponse caching = OpenAiProvider.response(completion, true);
    LlmResponse plain = OpenAiProvider.response(completion, false);

    assertThat(caching.usage()).isEqualTo(new TokenUsage(176, 50, 0, 1024));
    assertThat(caching.stopReason()).isEqualTo(LlmResponse.MAX_TOKENS);
    assertThat(caching.cutOff()).isTrue();
    assertThat(plain.usage()).isEqualTo(new TokenUsage(1200, 50, 0, 0));
  }

  @Test
  void aRepeatedPromptStartIsReadFromTheCacheAndRecordedAsCacheReads() throws Exception {
    fake.answering("{}");
    start();
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    LlmClient client =
        new LlmClient(
            ModelSettings.single(CACHING),
            List.of(provider),
            BudgetGuard.unlimited("t"),
            log,
            Clock.systemUTC());
    // A system prompt of about 1,500 tokens, shared by both requests; a short one, under OpenAI's
    // least of 1,024 tokens, is never cached.
    String system = "Rules. ".repeat(860);
    LlmRequest first = LlmRequest.of(Role.SUMMARIZE, "first", system, "class A", 100);
    LlmRequest second = LlmRequest.of(Role.SUMMARIZE, "second", system, "class B", 100);
    LlmRequest shortOne = LlmRequest.of(Role.COMPOSE, "short", "Rules.", "class A", 100);

    LlmResponse cold = client.call(first);
    LlmResponse warm = client.call(second);
    client.call(shortOne);
    LlmResponse shortAgain = client.call(shortOne);

    assertThat(cold.usage().cacheRead()).isZero();
    // The shared start counts from 1,024 tokens in steps of 128.
    assertThat(warm.usage().cacheRead()).isGreaterThanOrEqualTo(1_024);
    assertThat((warm.usage().cacheRead() - 1_024) % 128).isZero();
    assertThat(warm.usage().cacheRead() + warm.usage().input()).isEqualTo(cold.usage().input());
    assertThat(warm.usage().cacheWrite()).isZero();
    assertThat(shortAgain.usage().cacheRead()).isZero();
    LlmCall logged = log.calls().get(1);
    assertThat(logged.usage().cacheRead()).isEqualTo(warm.usage().cacheRead());
    assertThat(logged.costUsd()).isEqualByComparingTo(CACHING.pricing().cost(warm.usage()));
    assertThat(logged.costUsd()).isLessThan(log.calls().getFirst().costUsd());
  }

  @Test
  void aConversationRunsItsToolsOverTheChatApi() throws Exception {
    LlmTool readFile =
        new LlmTool(
            "read_file",
            "Reads a file.",
            JSON.readTree(
                "{\"type\": \"object\", \"properties\": {\"path\": {\"type\": \"string\"}}}"));
    fake.callingTools(
            request ->
                request.messages().size() == 1
                    ? List.of(
                        new LlmToolCall("call_1", "read_file", JSON.readTree("{\"path\": \"A\"}")))
                    : List.of())
        .answering(
            request ->
                "Read: " + request.messages().get(request.messages().size() - 1).toolResults());
    start();
    ModelSettings settings = ModelSettings.single(CACHING);
    LlmClient client =
        new LlmClient(
            settings,
            List.of(provider),
            BudgetGuard.unlimited("t"),
            new InMemoryLlmCallLog(),
            Clock.systemUTC());
    ToolBox tools =
        new ToolBox() {
          @Override
          public List<LlmTool> tools() {
            return List.of(readFile);
          }

          @Override
          public io.lantern.llm.LlmToolResult run(LlmToolCall call) {
            return io.lantern.llm.LlmToolResult.of(
                call, "contents of " + call.input().path("path").asString());
          }
        };

    Conversation conversation =
        client.converse(
            new LlmRequest(
                Role.RESOLVE,
                "test",
                "Resolve.",
                List.of(LlmMessage.user("Which?")),
                500,
                false,
                List.of(),
                List.of()),
            tools);
    LlmResponse answer = conversation.answer();

    assertThat(answer.text()).contains("contents of A");
    JsonNode second = stub.chats().get(1);
    assertThat(second.path("tools").path(0).path("function").path("name").asString())
        .isEqualTo("read_file");
    JsonNode assistant = second.path("messages").path(2);
    assertThat(assistant.path("role").asString()).isEqualTo("assistant");
    assertThat(assistant.path("tool_calls").path(0).path("function").path("arguments").asString())
        .isEqualTo("{\"path\":\"A\"}");
    JsonNode tool = second.path("messages").path(3);
    assertThat(tool.path("role").asString()).isEqualTo("tool");
    assertThat(tool.path("tool_call_id").asString()).isEqualTo("call_1");
    assertThat(tool.path("content").asString()).isEqualTo("contents of A");
  }

  @Test
  void aBatchGoesAsAJsonlFileAndEachResultComesBackByItsId() throws Exception {
    fake.answering(request -> "answer to " + request.messages().getFirst().text());
    start();
    List<LlmRequest> requests =
        List.of(
            request(Role.SUMMARIZE, "one"),
            request(Role.SUMMARIZE, "two"),
            request(Role.SUMMARIZE, "three"));

    List<LlmBatchResult> results = provider.completeBatch(CACHING, requests);

    assertThat(stub.batches()).isEqualTo(1);
    // The input and output files are deleted once read.
    assertThat(stub.deleted()).hasSize(2);
    assertThat(results)
        .extracting(r -> r.response().text())
        .containsExactly("answer to one", "answer to two", "answer to three");
    assertThat(stub.chats()).hasSize(3);
  }

  @Test
  void theClientSendsABatchAtHalfPriceAndLogsEachRequestAsBatched() throws Exception {
    fake.answering("{}");
    start();
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    LlmClient client =
        new LlmClient(
            ModelSettings.single(CACHING),
            List.of(provider),
            BudgetGuard.unlimited("t"),
            log,
            Clock.systemUTC());

    assertThat(client.batches(Role.SUMMARIZE)).isTrue();
    List<LlmBatchResult> results =
        client.batch(List.of(request(Role.SUMMARIZE, "one"), request(Role.SUMMARIZE, "two")));

    assertThat(results).allSatisfy(r -> assertThat(r.error()).isNull());
    assertThat(log.calls()).hasSize(2).allSatisfy(call -> assertThat(call.batch()).isTrue());
    LlmCall call = log.calls().getFirst();
    assertThat(call.costUsd())
        .isEqualByComparingTo(CACHING.pricing().cost(call.usage()).multiply(new BigDecimal("0.5")));
  }

  @Test
  void aRateLimitIsTransientWithTheWaitTheServerAskedFor() throws Exception {
    HttpServer limited =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    limited.createContext(
        "/",
        exchange -> {
          byte[] body =
              "{\"error\": {\"message\": \"slow down\", \"type\": \"rate_limit\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("retry-after", "2");
          exchange.sendResponseHeaders(429, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    limited.start();
    try (OpenAiProvider limitedProvider =
        new OpenAiProvider(
            OpenAIOkHttpClient.builder()
                .apiKey("test")
                .baseUrl("http://127.0.0.1:" + limited.getAddress().getPort())
                .maxRetries(0)
                .build(),
            Duration.ofMillis(10))) {
      Throwable thrown =
          catchThrowable(() -> limitedProvider.complete(CACHING, request(Role.SUMMARIZE, "x")));

      assertThat(thrown).isInstanceOf(TransientLlmException.class);
      assertThat(((TransientLlmException) thrown).retryAfter()).contains(Duration.ofSeconds(2));
    } finally {
      limited.stop(0);
    }
  }

  @Test
  void theStubReadsBackTheRequestTheRolesSent() {
    LlmRequest sent =
        new LlmRequest(
            Role.RESOLVE,
            "test",
            "Resolve.",
            List.of(
                LlmMessage.user("Which?"),
                LlmMessage.assistant(
                    "", List.of(new LlmToolCall("c1", "read_file", JSON.readTree("{\"p\":1}")))),
                LlmMessage.toolResults(
                    List.of(new io.lantern.llm.LlmToolResult("c1", "no such file", true)), "")),
            300,
            false,
            List.of(
                new LlmTool(
                    "read_file",
                    "Reads a file.",
                    JSON.readTree("{\"type\": \"object\", \"properties\": {}}"))),
            List.of());

    LlmRequest read = OpenAiStub.request(OpenAiProvider.body("m", sent, true));

    assertThat(read.role()).isEqualTo(Role.RESOLVE);
    assertThat(read.system()).isEqualTo(sent.system());
    assertThat(read.messages()).isEqualTo(sent.messages());
    assertThat(read.maxOutputTokens()).isEqualTo(300);
    assertThat(read.tools()).isEqualTo(sent.tools());
  }

  @Test
  void aPassingOutageWhileTheBatchRunsIsRetriedWithoutSendingTheBatchAgain() throws Exception {
    fake.answering("ok");
    start();
    stub.failingNextBatchReads(3);

    List<LlmBatchResult> results =
        provider.completeBatch(CACHING, List.of(request(Role.SUMMARIZE, "one")));

    assertThat(results.getFirst().response().text()).isEqualTo("ok");
    assertThat(stub.batches()).isEqualTo(1);
  }
}
