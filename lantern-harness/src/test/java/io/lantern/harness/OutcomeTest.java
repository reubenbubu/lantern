package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutcomeTest {

  private static final String FP = "a".repeat(64);

  @Test
  void anOutcomeNamesItsAnswerOnlyWhenTheCacheHoldsARowForIt() {
    assertThat(Outcome.of(Outcome.Kind.ANSWERED, FP, "{}", null).answerKey()).isEqualTo(FP);
    assertThat(Outcome.of(Outcome.Kind.CACHED, FP, "{}", null).answerKey()).isEqualTo(FP);
    assertThat(Outcome.of(Outcome.Kind.OMITTED, FP, null, "omitted").answerKey()).isEqualTo(FP);
    assertThat(Outcome.of(Outcome.Kind.DROPPED, FP, null, "twice").answerKey()).isEqualTo(FP);
    // Nothing is stored for these, so a fact made from them names no answer (ADR 0124).
    assertThat(Outcome.unstoredDrop(FP, "cut off").answerKey()).isNull();
    assertThat(Outcome.of(Outcome.Kind.NOT_ASKED, FP, null, "budget").answerKey()).isNull();
    assertThat(Outcome.of(Outcome.Kind.FAILED, FP, null, "error").answerKey()).isNull();
  }
}
