package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationsTest {

  private static final String F1 = "1".repeat(64);
  private static final String F2 = "2".repeat(64);
  private static final String F3 = "3".repeat(64);
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
            List.of(),
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
        Citations.check("Returns int[] values [F1].", List.of(F1), List.of(), List.of());

    assertThat(checked.text()).isEqualTo("Returns int[] values [fact:" + F1 + "].");
  }

  @Test
  void answerWithoutCitationsIsRejected() {
    assertThatThrownBy(() -> Citations.check("It does things.", List.of(F1), List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("cites no references");
  }

  @Test
  void unknownReferencesAreRejected() {
    assertThatThrownBy(
            () ->
                Citations.check("Real [F1]. Made up [F3, S1].", List.of(F1), List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F3, S1]");
  }

  @Test
  void storedFormMarkersInTheAnswerAreRejected() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Real [F1]. Forged [fact:" + "9".repeat(64) + "].",
                    List.of(F1),
                    List.of(),
                    List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("markers");
  }

  @Test
  void emptyAnswerIsRejected() {
    assertThatThrownBy(() -> Citations.check("  \n", List.of(F1), List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("empty");
  }

  @Test
  void excerptsAreCitedAsTheFactTheyWereTakenFrom() {
    Citations.Checked checked =
        Citations.check(
            "Declared here [E1]. Also [F1, E2].", List.of(F1), List.of(F2, F1), List.of());

    assertThat(checked.text())
        .isEqualTo("Declared here [fact:%s]. Also [fact:%s][fact:%s].".formatted(F2, F1, F1));
    assertThat(checked.facts()).containsExactly(F2, F1);
  }

  @Test
  void referencesThePromptDidNotShowAreRejected() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Shown [F1]. Not shown [F2].",
                    List.of(F1, F2),
                    List.of(),
                    List.of(),
                    Set.of("F1")))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F2]");
  }

  @Test
  void rangesCiteEveryReferenceInThem() {
    Citations.Checked checked =
        Citations.check(
            "Holds orders [F1-F3, S1]. Lists lines [E1\u2013E2]. Ships [F2 - F2].",
            List.of(F1, F2, F3),
            List.of(F3, F1),
            List.of(CHILD));

    assertThat(checked.text())
        .isEqualTo(
            ("Holds orders [fact:%s][fact:%s][fact:%s][summary:type:p.A]."
                    + " Lists lines [fact:%s][fact:%s]. Ships [fact:%s].")
                .formatted(F1, F2, F3, F3, F1, F2));
    assertThat(checked.facts()).containsExactly(F1, F2, F3);
    assertThat(checked.summaries()).containsExactly(CHILD.key());
  }

  @Test
  void rangesPastTheReferencesGivenAreRejected() {
    assertThatThrownBy(
            () -> Citations.check("Holds orders [F1-F4].", List.of(F1, F2), List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F3, F4]");
  }

  @Test
  void malformedRangesAreRejected() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Real [F1]. Reversed [F2-F1]. Mixed [F1 - S1].",
                    List.of(F1, F2),
                    List.of(),
                    List.of(CHILD)))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F1-S1, F2-F1]");
  }

  @Test
  void rangesMustStayWithinTheReferencesThePromptShowed() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Shown [F1-F2].", List.of(F1, F2), List.of(), List.of(), Set.of("F1")))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F2]");
  }

  @Test
  void referencesAreListedInOrderOfFirstCitation() {
    assertThat(Citations.references("A [S2, F1]. B [E1][F1]. C int[] x."))
        .containsExactly("S2", "F1", "E1");
  }

  @Test
  void referencesListEveryReferenceInARange() {
    assertThat(Citations.references("A [F2-F4, S1]. B [F3\u2014F5]."))
        .containsExactly("F2", "F3", "F4", "S1", "F5");
  }
}
