package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
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
          Summary.Purpose.INFERRED,
          0.35,
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
        .hasMessage("it cites ranges that reach past the references in the input: [F1-F4].");
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
        .hasMessage(
            "it cites ranges that are not of one kind in ascending order, or are too long:"
                + " [F1-S1, F2-F1]; cite each reference on its own.");
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
  void summaryRangesShortRangesAndEmDashesAreRead() {
    Summary second =
        new Summary(
            "d".repeat(64),
            EntityId.parse("type:p.B"),
            SummaryLevel.TYPE,
            "B. [fact:" + F1 + "]",
            Summary.Purpose.INFERRED,
            0.35,
            List.of(F1),
            List.of(),
            List.of(F1),
            List.of(),
            "1",
            "fake",
            "m");
    Summary third =
        new Summary(
            "e".repeat(64),
            EntityId.parse("type:p.C"),
            SummaryLevel.TYPE,
            "C. [fact:" + F1 + "]",
            Summary.Purpose.INFERRED,
            0.35,
            List.of(F1),
            List.of(),
            List.of(F1),
            List.of(),
            "1",
            "fake",
            "m");
    Citations.Checked checked =
        Citations.check(
            "Parts [S1-S3]. Facts [F1-3]. Again [F2\u2014F3].",
            List.of(F1, F2, F3),
            List.of(),
            List.of(CHILD, second, third));

    assertThat(checked.text())
        .isEqualTo(
            ("Parts [summary:type:p.A][summary:type:p.B][summary:type:p.C]."
                    + " Facts [fact:%s][fact:%s][fact:%s]. Again [fact:%s][fact:%s].")
                .formatted(F1, F2, F3, F2, F3));
    assertThat(checked.summaries()).containsExactly(CHILD.key(), second.key(), third.key());
    assertThat(checked.facts()).containsExactly(F1, F2, F3);
  }

  @Test
  void aReferenceInARangeAndAloneIsCitedOnce() {
    Citations.Checked checked =
        Citations.check("Both [F1-F3, F2].", List.of(F1, F2, F3), List.of(), List.of());

    assertThat(checked.text())
        .isEqualTo("Both [fact:%s][fact:%s][fact:%s][fact:%s].".formatted(F1, F2, F3, F2));
    assertThat(checked.facts()).containsExactly(F1, F2, F3);
  }

  @Test
  void aRangeFromZeroReachesPastTheInput() {
    assertThatThrownBy(
            () ->
                Citations.check("Real [F1]. From [F0-F2].", List.of(F1, F2), List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessage("it cites ranges that reach past the references in the input: [F0-F2].");
  }

  @Test
  void aRangeWithAnIndexTooLargeToReadIsRejected() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Real [F1]. Huge [F1-F99999999999999999999].",
                    List.of(F1),
                    List.of(),
                    List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F1-F99999999999999999999]")
        .hasMessageContaining("cite each reference on its own");
  }

  @Test
  void aRangeNearTheLargestIndexEnds() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Real [F1]. Edge [F2147483640-F2147483647].",
                    List.of(F1),
                    List.of(),
                    List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessage(
            "it cites ranges that reach past the references in the input:"
                + " [F2147483640-F2147483647].");
    assertThat(Citations.references("[F2147483640-F2147483647]")).hasSize(8);
    assertThat(Citations.references("[F9223372036854775800-F9223372036854775807]")).hasSize(8);
  }

  @Test
  void rangesOfUpToTenThousandReferencesAreExpanded() {
    List<String> many = IntStream.range(0, 10_001).mapToObj(i -> "%064d".formatted(i)).toList();

    assertThat(Citations.check("All [F1-F10000].", many, List.of(), List.of()).facts())
        .hasSize(10_000);
    assertThatThrownBy(() -> Citations.check("All [F1-F10001].", many, List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessageContaining("[F1-F10001]")
        .hasMessageContaining("too long");
  }

  @Test
  void aLongRangePastTheInputIsReportedAsWritten() {
    assertThatThrownBy(
            () -> Citations.check("All [F1-F9999].", List.of(F1, F2), List.of(), List.of()))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessage("it cites ranges that reach past the references in the input: [F1-F9999].");
  }

  @Test
  void aRejectionListsAtMostTenReferences() {
    assertThatThrownBy(
            () ->
                Citations.check(
                    "Shown [F1-F20].",
                    IntStream.range(0, 20).mapToObj(i -> "%064d".formatted(i)).toList(),
                    List.of(),
                    List.of(),
                    Set.of("F1")))
        .isInstanceOf(Citations.Rejected.class)
        .hasMessage(
            "it cites references that are not in the input:"
                + " [F10, F11, F12, F13, F14, F15, F16, F17, F18, F19] and 9 more.");
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

  @Test
  void theFirstReferencesAreThoseOfTheFirstCitation() {
    assertThat(Citations.firstReferences("Sells books. [F2, S1-S3] Ships them. [F1]"))
        .containsExactly("F2", "S1", "S2", "S3");
    assertThat(Citations.firstReferences("No citation at all.")).isEmpty();
  }

  @Test
  void sentencesWithoutCitationsAreLeftOut() {
    Citations.Sentences sentences =
        Citations.cited(
            "Sells books [F1]. It is great! Ships them. [F2] Does it pay? Returns int[] x.");

    assertThat(sentences.text()).isEqualTo("Sells books [F1]. Ships them. [F2]");
    assertThat(sentences.uncited())
        .containsExactly("It is great!", "Does it pay?", "Returns int[] x.");
    assertThat(sentences.firstLeftOut()).isFalse();
  }

  @Test
  void aFirstSentenceWithoutCitationsIsReported() {
    Citations.Sentences sentences = Citations.cited("Sells books. Ships them [F1, S2].");

    assertThat(sentences.text()).isEqualTo("Ships them [F1, S2].");
    assertThat(sentences.uncited()).containsExactly("Sells books.");
    assertThat(sentences.firstLeftOut()).isTrue();
  }

  @Test
  void fullStopsInsideASentenceDoNotEndIt() {
    String text =
        "It uses Spring Boot 3.2 in com.example.shop, e.g. Order and i.e. lines, for the"
            + " shop. and more [F1]. "
            + "It ships \"books.\" [F2]";

    Citations.Sentences sentences = Citations.cited(text);

    assertThat(sentences.text()).isEqualTo(text);
    assertThat(sentences.uncited()).isEmpty();
  }

  @Test
  void aCitedLineOrABlankLineEndsASentence() {
    Citations.Sentences sentences =
        Citations.cited("Sells books [F1]\nShips them\n\nA heading\n\n**Note** [S1-S2]");

    assertThat(sentences.text()).isEqualTo("Sells books [F1]\n**Note** [S1-S2]");
    assertThat(sentences.uncited()).containsExactly("Ships them", "A heading");
  }

  @Test
  void citationsAfterTheFullStopStayWithTheirSentence() {
    for (String text :
        List.of(
            "Ships them. [F2].",
            "Sells books. [F1], [F2]",
            "Sells books. [F1] and more [F3].",
            "Sells books.\u201d [F1] Ships them. [F2]")) {
      Citations.Sentences sentences = Citations.cited(text);

      assertThat(sentences.text()).as(text).isEqualTo(text);
      assertThat(sentences.uncited()).as(text).isEmpty();
    }
  }

  @Test
  void windowsLineEndsEndSentencesToo() {
    Citations.Sentences sentences =
        Citations.cited("Sells books [F1]\r\nShips them\r\n\r\nA heading\r\n\r\nNote [S1]");

    assertThat(sentences.text()).isEqualTo("Sells books [F1]\r\nNote [S1]");
    assertThat(sentences.uncited()).containsExactly("Ships them", "A heading");
  }

  @Test
  void aSentenceCitingAnUnknownReferenceIsKeptForTheCheckToReject() {
    assertThat(Citations.cited("Sells books [F9]. Ships them.").text())
        .isEqualTo("Sells books [F9].");
    assertThat(Citations.cited("No citation. None at all.").text()).isEmpty();
  }
}
