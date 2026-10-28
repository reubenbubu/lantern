package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationsTest {

  private static final String F1 = "1".repeat(64);
  private static final String F2 = "2".repeat(64);
  private static final Summary CHILD =
      new Summary(
          "c".repeat(64),
          EntityId.parse("type:p.A"),
          SummaryLevel.TYPE,
          "A thing. [fact:" + F1 + "]",
          List.of(F1),
          List.of(),
          List.of(F1),
          List.of(),
          "1",
          "fake",
          "m");

  @Test
  void rewritesReferencesToStableIds() {
    Citations.Checked checked =
        Citations.check(
            "Holds orders [F2]. Uses A [ F1 , S1 ]. Again [F2][S1].",
            List.of(F1, F2),
            List.of(CHILD));

    assertThat(checked.text())
        .isEqualTo(
            "Holds orders [fact:%s]. Uses A [fact:%s][summary:type:p.A]. Again [fact:%s][summary:type:p.A]."
                .formatted(F2, F1, F2));
    assertThat(checked.facts()).containsExactly(F2, F1);
    assertThat(checked.summaries()).containsExactly(CHILD.key());
  }

  @Test
  void otherBracketsAreLeftAlone() {
    Citations.Checked checked =
        Citations.check("Returns int[] values [F1].", List.of(F1), List.of());

    assertThat(checked.text()).isEqualTo("Returns int[] values [fact:" + F1 + "].");
  }

  @Test
  void answerWithoutCitationsIsRejected() {
    assertThatThrownBy(() -> Citations.check("It does things.", List.of(F1), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("cites no references");
  }

  @Test
  void unknownReferencesAreRejected() {
    assertThatThrownBy(
            () -> Citations.check("Real [F1]. Made up [F3, S1].", List.of(F1), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F3, S1]");
  }

  @Test
  void emptyAnswerIsRejected() {
    assertThatThrownBy(() -> Citations.check("  \n", List.of(F1), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("empty");
  }
}
