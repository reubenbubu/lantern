package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Validity;
import io.lantern.derive.ScopeFlows;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlowJudgementsTest {

  @Test
  void aScopeOfManyFlowsHasRoomForAReasonForEach() {
    // project-4's system scope, 41 flows, ran out of its 120-a-flow answer room on DeepSeek (#730).
    assertThat(significanceRole(41).maxOutputTokens(1)).isEqualTo(41 * 250 + 1_000);
    assertThat(significanceRole(2).maxOutputTokens(1)).isEqualTo(1_500);
  }

  @Test
  void aFlowTitleIsHeldToTheBarsRule() {
    // docs/quality-bar.md, Sequence diagrams (owner's decision of 5 Nov 2026, #770; #885).
    assertThat(FlowJudgements.TITLE_WORDS.min()).isEqualTo(3);
    assertThat(FlowJudgements.TITLE_WORDS.max()).isEqualTo(8);
    assertThat(FlowJudgements.titleProblem("Customer places an order")).isNull();
    assertThat(FlowJudgements.titleProblem("Scheduler expires stale carts")).isNull();
    assertThat(FlowJudgements.titleProblem("Order flow for customers")).isNotNull();
    assertThat(FlowJudgements.titleProblem("Checkout process runs")).isNotNull();
    assertThat(FlowJudgements.titleProblem("Customer calls placeOrder")).isNotNull();
    assertThat(FlowJudgements.titleProblem("OrderController places orders")).isNotNull();
    assertThat(FlowJudgements.titleProblem("Customer posts /orders")).isNotNull();
    assertThat(FlowJudgements.titleProblem("OrderService.place runs")).isNotNull();
    assertThat(new FlowJudgements.OneItem(null, true).pipeline().checks())
        .extracting(io.lantern.harness.verify.Check::name)
        .contains("Title");
  }

  private static FlowJudgements.OneItem significanceRole(int flows) {
    Map<String, ScopeFlows.Candidate> candidates = new HashMap<>();
    for (int i = 0; i < flows; i++) {
      candidates.put("f" + i, null);
    }
    return new FlowJudgements.OneItem(
        new FlowJudgements.Asked(null, null, candidates, Set.of(), Set.of()), false);
  }

  @Test
  void aRepairedOrderFollowsTheScopesRankNotTheListedOrder() {
    // Listed a, b, c; ranked c, a, b: a significant flow the order leaves out is added by rank.
    ScopeFlows.Candidate a = candidate("a", "service:shop:orders");
    ScopeFlows.Candidate b = candidate("b", "service:shop:orders");
    ScopeFlows.Candidate c = candidate("c", "service:shop:orders");
    FlowJudgements.Asked asked =
        FlowJudgements.significance(
            "system", "the system", List.of(a, b, c), List.of(c, a, b), Map.of(), id -> "Shop");
    assertThat(asked.ranked()).containsExactly("W3", "W1", "W2");
  }

  @Test
  void aUnitsFirstPassItemRanksItsFlowsInTheScopesRank() {
    // More flows than one pass judges, given best first: each unit's item keeps that rank.
    List<ScopeFlows.Candidate> byRank = new ArrayList<>();
    for (int i = 0; i <= FlowJudgements.ONE_PASS; i++) {
      byRank.add(candidate("f" + i, i % 2 == 0 ? "service:shop:orders" : "service:shop:billing"));
    }
    List<FlowJudgements.Asked> items =
        FlowJudgements.firstPass("system", byRank, Map.of(), id -> "Shop");
    assertThat(items).hasSize(2);
    for (FlowJudgements.Asked item : items) {
      List<ScopeFlows.Candidate> ranked =
          item.ranked().stream().map(ref -> item.flows().get(ref)).toList();
      assertThat(ranked)
          .isSortedAccordingTo(java.util.Comparator.comparingInt(byRank::indexOf))
          .hasSize(item.flows().size());
    }
  }

  private static ScopeFlows.Candidate candidate(String name, String unit) {
    EntityId id = EntityId.parse(unit);
    return new ScopeFlows.Candidate(
        EntityId.parse("flow:" + unit + "/" + name),
        id,
        "GET /" + name,
        TestFacts.fact(
            "shop",
            "endpoint:shop:" + name,
            "HANDLES",
            FactObject.string("GET /" + name),
            FactJson.newObject(),
            Validity.from(1)),
        List.of("Shop"),
        List.of(),
        List.of(),
        List.of(id));
  }
}
