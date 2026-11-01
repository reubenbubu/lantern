package io.lantern.harness;

import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.Role;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;

/** Clients, items and askers for the harness's tests. */
final class TestHarness {

  /**
   * A priced fake model: $1 per million input tokens, $5 per million output tokens, $1.25 and $2
   * per million written to the cache for five minutes and an hour.
   */
  static final Pricing PRICED =
      new Pricing(
          new BigDecimal("1"),
          new BigDecimal("5"),
          new BigDecimal("1.25"),
          new BigDecimal("0.1"),
          new BigDecimal("2"));

  private TestHarness() {}

  static LlmClient client(FakeLlmProvider fake) {
    return client(fake, fake.model("m"));
  }

  static LlmClient client(FakeLlmProvider fake, ModelConfig model) {
    return new LlmClient(
        ModelSettings.single(model),
        List.of(fake),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  static ModelConfig priced(FakeLlmProvider fake) {
    ModelConfig free = fake.model("priced");
    return new ModelConfig(
        free.provider(), free.model(), free.capabilities(), free.contextWindow(), PRICED);
  }

  static Harness harness(LlmClient client, AnswerStore store) {
    return new Harness(client, store, RunBudget.NONE, BlobLookup.NONE, Set.of());
  }

  static RoleSpec role() {
    return RoleSpec.single("summarize", Role.SUMMARIZE, "1", "summarize", RunMode.BULK, 100);
  }

  /** An item about the subject, showing the given files' contents. */
  static Item item(String subject, String... files) {
    List<FingerprintInput> inputs =
        java.util.Arrays.stream(files)
            .map(file -> FingerprintInput.blob("repo", file, Fingerprints.gitBlob(file + subject)))
            .toList();
    return new Item(subject, "repo/" + subject, inputs, "Describe " + subject + ".");
  }

  /** Asks one request for the item and accepts any answer as the stored JSON. */
  static Asker asking(Item item) {
    return calls -> {
      String text =
          calls
              .call(
                  new LlmRequest(
                      Role.SUMMARIZE,
                      "describe " + item.subject(),
                      "System.",
                      List.of(LlmMessage.user(item.rendering())),
                      100,
                      false))
              .text();
      return Answer.of(
          "{\"text\":"
              + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(text)
              + "}");
    };
  }
}
