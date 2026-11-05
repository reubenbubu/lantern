package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Items' first requests joined into packed requests and their answers split back (#874). */
class PacketCollectorTest {

  /** Joins turns one per line; splits an answer by lines, a line {@code -} for an item left out. */
  private static final Packing LINES =
      new Packing() {
        @Override
        public String join(List<String> turns) {
          return String.join("\n", turns);
        }

        @Override
        public List<String> split(String answer, List<String> turns) {
          List<String> parts = new ArrayList<>();
          List<String> lines = answer.lines().toList();
          for (int i = 0; i < turns.size(); i++) {
            String line = i < lines.size() ? lines.get(i) : "-";
            parts.add(line.equals("-") ? null : line);
          }
          return parts;
        }

        @Override
        public String purpose(int items) {
          return "packet of " + items;
        }
      };

  private static LlmRequest request(String turn) {
    return LlmRequest.of(Role.SUMMARIZE_TYPES, "ask " + turn, "System.", turn, 100);
  }

  @Test
  void waitingRequestsShareOneRequestAndAnItemLeftOutIsAskedAlone() throws Exception {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                r -> {
                  String text = r.messages().getFirst().text();
                  // The packed answer leaves the second item out; alone, it is answered.
                  return text.contains("\n") ? "answer a\n-\nanswer c" : "alone " + text;
                });
    PacketCollector packets =
        new PacketCollector(
            TestHarness.client(fake),
            LINES,
            new Packing.Limits(10_000, 10, 50),
            Duration.ofMillis(200));

    List<LlmResponse> answers = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<LlmResponse>> calls = new ArrayList<>();
      for (String turn : List.of("a", "b", "c")) {
        calls.add(pool.submit(() -> packets.call(request(turn))));
        Thread.sleep(20);
      }
      for (Future<LlmResponse> call : calls) {
        answers.add(call.get());
      }
    }

    assertThat(answers)
        .extracting(LlmResponse::text)
        .containsExactly("answer a", "alone b", "answer c");
    assertThat(fake.sent()).hasSize(2);
    LlmRequest packed = fake.sent().getFirst().request();
    assertThat(packed.purpose()).isEqualTo("packet of 3");
    assertThat(packed.messages().getFirst().text()).isEqualTo("a\nb\nc");
    assertThat(packed.maxOutputTokens()).isEqualTo(3 * (100 + 50));
    assertThat(fake.sent().get(1).request().purpose()).isEqualTo("ask b");
  }

  @Test
  void aPacketIsCutAtItsItemLimitAndARepairTurnGoesAlone() throws Exception {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(r -> r.messages().getFirst().text().replace("\n", "!\n") + "!");
    PacketCollector packets =
        new PacketCollector(
            TestHarness.client(fake),
            LINES,
            new Packing.Limits(10_000, 2, 0),
            Duration.ofMillis(200));

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<LlmResponse>> calls = new ArrayList<>();
      for (String turn : List.of("a", "b", "c")) {
        calls.add(pool.submit(() -> packets.call(request(turn))));
      }
      for (Future<LlmResponse> call : calls) {
        assertThat(call.get().text()).endsWith("!");
      }
    }
    assertThat(fake.sent()).extracting(s -> s.request().purpose()).contains("packet of 2");

    LlmRequest repair =
        request("a")
            .withMessages(
                List.of(LlmMessage.user("a"), LlmMessage.assistant("x"), LlmMessage.user("again")));
    packets.call(repair);
    assertThat(fake.sent().getLast().request().messages()).hasSize(3);
  }

  @Test
  void theSharesOfAPacketsTokensAddUpToItsUsage() {
    TokenUsage usage = new TokenUsage(1001, 503, 7, 99, 0);
    List<TokenUsage> shares =
        PacketCollector.shares(
            usage, List.of("aaa", "b", "cc"), java.util.Arrays.asList("xx", null, "x"));

    assertThat(shares.stream().reduce(TokenUsage.NONE, TokenUsage::plus)).isEqualTo(usage);
    assertThat(shares.get(1).output()).isZero();
    assertThat(shares.get(0).input()).isGreaterThan(shares.get(1).input());
  }
}
