package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfidenceTest {

  @Test
  void anEvidencedTextIsTrustedMoreThanAnInferredOneAndBothLessThanAStaticFact() {
    assertThat(Confidence.of(true, 3, 0)).isEqualTo(0.5);
    assertThat(Confidence.of(false, 3, 0)).isEqualTo(0.35);
  }

  @Test
  void sentencesLeftOutLowerItByTheirShare() {
    assertThat(Confidence.of(true, 1, 1)).isEqualTo(0.25);
    assertThat(Confidence.of(false, 2, 1)).isEqualTo(0.23);
    assertThat(Confidence.of(false, 1, 100)).isEqualTo(0.01);
  }

  @Test
  void anAnswerKeepsAtLeastOneSentence() {
    assertThatThrownBy(() -> Confidence.of(true, 0, 2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
