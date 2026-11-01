package io.lantern.harness;

import static io.lantern.harness.TestHarness.harness;
import static io.lantern.harness.TestHarness.item;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.ModelConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When a bulk role batches, and what its plan then estimates (ADR 0102). */
class BatchPlanTest {

  private static List<Item> items(int count) {
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      items.add(item("unit-" + i, "File" + i + ".java"));
    }
    return items;
  }

  private static ModelConfig batching(FakeLlmProvider fake) {
    ModelConfig priced = TestHarness.priced(fake);
    return new ModelConfig(
        priced.provider(),
        priced.model(),
        fake.batchingModel("x").capabilities(),
        priced.contextWindow(),
        priced.pricing());
  }

  @Test
  void aBulkRoleBatchesPastFiftyMissesAndItsPlanIsAtTheBatchPrice() {
    FakeLlmProvider fake = new FakeLlmProvider();
    RoleSession fifty =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .session(TestHarness.role());
    Estimate atFifty = fifty.plan(items(50), 100);
    assertThat(fifty.batching()).isFalse();

    RoleSession more =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .session(TestHarness.role());
    Estimate past = more.plan(items(51), 100);
    assertThat(more.batching()).isTrue();

    RoleSession interactive =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .batchingAbove(1000)
            .session(TestHarness.role());
    Estimate same = interactive.plan(items(51), 100);
    assertThat(interactive.batching()).isFalse();
    // Everything at half price but the warm-up's cache write of the 100-token prompt start, which
    // goes at the full one-hour write price ($2 a million) instead of the five-minute one ($1.25).
    BigDecimal write = new BigDecimal("0.000125");
    BigDecimal writeLong = new BigDecimal("0.0002");
    assertThat(past.usd())
        .isEqualByComparingTo(
            same.usd().subtract(write).multiply(new BigDecimal("0.5")).add(writeLong));
    assertThat(atFifty.usd()).isPositive();
  }

  @Test
  void aModelThatDoesNotBatchOrAnInteractiveRoleNeverBatches() {
    FakeLlmProvider fake = new FakeLlmProvider();
    RoleSession plain =
        harness(TestHarness.client(fake), new InMemoryAnswerStore()).session(TestHarness.role());
    plain.plan(items(80), 100);
    assertThat(plain.batching()).isFalse();

    RoleSession agentic =
        harness(TestHarness.client(fake, batching(fake)), new InMemoryAnswerStore())
            .session(
                RoleSpec.single(
                    "resolve",
                    io.lantern.llm.Role.RESOLVE,
                    "1",
                    "resolve",
                    RunMode.INTERACTIVE,
                    100));
    agentic.plan(items(80), 100);
    assertThat(agentic.batching()).isFalse();
  }
}
