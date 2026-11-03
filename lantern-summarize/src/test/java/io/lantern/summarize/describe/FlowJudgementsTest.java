package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FlowJudgementsTest {

  @Test
  void aScopeOfManyFlowsHasRoomForAReasonForEach() {
    // project-4's system scope, 41 flows, used all of 5,420 tokens and more on DeepSeek (#730).
    assertThat(FlowJudgements.significanceOutputTokens(41)).isEqualTo(41 * 250 + 1_000);
    assertThat(FlowJudgements.significanceOutputTokens(2)).isEqualTo(1_500);
  }
}
