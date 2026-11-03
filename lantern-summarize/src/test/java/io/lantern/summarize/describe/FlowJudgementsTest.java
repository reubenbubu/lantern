package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.derive.ScopeFlows;
import java.util.HashMap;
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

  private static FlowJudgements.OneItem significanceRole(int flows) {
    Map<String, ScopeFlows.Candidate> candidates = new HashMap<>();
    for (int i = 0; i < flows; i++) {
      candidates.put("f" + i, null);
    }
    return new FlowJudgements.OneItem(
        new FlowJudgements.Asked(null, null, candidates, Set.of(), Set.of()), false);
  }
}
