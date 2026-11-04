package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.verify.CheckedText;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Keeping the wording of texts whose evidence did not change (#722, ADR 0126). */
class CarryForwardTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static CheckedText text(String field, String text, String subject, String... cites) {
    return new CheckedText(
        field,
        CheckedText.Kind.RESPONSIBILITY,
        text,
        List.of(cites),
        Contract.Range.ANY,
        subject,
        Set.of());
  }

  /** A stored answer as a role stores it: each text at its field, citing as it cited. */
  private static String stored(List<CheckedText> texts) {
    ObjectNode root = JSON.createObjectNode();
    for (CheckedText t : texts) {
      ObjectNode value = root.putObject(t.field());
      value.put("text", t.text());
      var cites = value.putArray("cites");
      t.cites().forEach(cites::add);
    }
    return root.toString();
  }

  private static final Map<String, String> BEFORE =
      Map.of(
          "F1", "shop CALLS_HTTP pay.example.com",
          "F2", "shop PUBLISHES orders",
          "E1", "class Controller { list() }");

  /** The first answer, marked with what each text rests on. */
  private static Map<String, CarryForward.Earlier> earlier() {
    List<CheckedText> first =
        List.of(
            text("unit", "Takes the shop's orders.", "shop", "F1", "F2"),
            text("C1", "Lists the orders taken so far.", "Controller", "F1", "E1"),
            text("R1", "Announces new orders", "shop to orders", "F2"));
    return CarryForward.earlier(CarryForward.mark(stored(first), first, BEFORE));
  }

  @Test
  void aTextWhoseCitedLinesAreAllStillShownKeepsItsWordingAndTheOthersTakeTheNewOne() {
    // The code changed; a fact was added before the others, so every number moved.
    Map<String, String> now =
        Map.of(
            "F1", "shop USES_DATABASE db",
            "F2", "shop CALLS_HTTP pay.example.com",
            "F3", "shop PUBLISHES orders",
            "E1", "class Controller { orders() }");
    List<CheckedText> reworded =
        List.of(
            text("unit", "Accepts the orders of the shop.", "shop", "F2", "F3"),
            text("C1", "Shows the orders taken so far.", "Controller", "F2", "E1"),
            text("R1", "Publishes each new order", "shop to orders", "F3"));

    List<CheckedText> merged = CarryForward.merge(reworded, earlier(), now, t -> true);

    assertThat(merged)
        .extracting(CheckedText::field, CheckedText::text, CheckedText::cites)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "unit", "Takes the shop's orders.", List.of("F2", "F3")),
            org.assertj.core.groups.Tuple.tuple(
                "C1", "Shows the orders taken so far.", List.of("F2", "E1")),
            org.assertj.core.groups.Tuple.tuple("R1", "Announces new orders", List.of("F3")));
  }

  @Test
  void aTextIsNotKeptWhenItsRoleSaysNoItsSubjectChangedOrItCitesNothingShown() {
    List<CheckedText> reworded =
        List.of(
            text("unit", "Accepts the orders of the shop.", "shop", "F1", "F2"),
            // C1 is now another component.
            text("C1", "Pays for the orders.", "Payments", "F1"),
            text("R1", "Publishes each new order", "shop to orders", "F2"));

    List<CheckedText> merged =
        CarryForward.merge(reworded, earlier(), BEFORE, t -> !t.field().equals("unit"));

    assertThat(merged)
        .extracting(CheckedText::text)
        .containsExactly(
            "Accepts the orders of the shop.", "Pays for the orders.", "Announces new orders");
    // A text citing what the item does not show, such as a file range, records nothing.
    List<CheckedText> ranged =
        List.of(text("unit", "Takes the shop's orders.", "shop", "shop:pom.xml:1-3"));
    assertThat(CarryForward.mark(stored(ranged), ranged, BEFORE)).doesNotContain("carry");
    assertThat(CarryForward.earlier(stored(ranged))).isEmpty();
  }

  @Test
  void aMarkedAnswerStillReadsAsTextsAndCites() {
    List<CheckedText> first = List.of(text("C1", "Lists the orders.", "Controller", "F1", "E1"));
    JsonNode marked = JSON.readTree(CarryForward.mark(stored(first), first, BEFORE));

    assertThat(marked.get("C1").get("text").asString()).isEqualTo("Lists the orders.");
    assertThat(marked.get("C1").get("cites").toString()).isEqualTo("[\"F1\",\"E1\"]");
    assertThat(marked.get("C1").get(CarryForward.KEY).get("field").asString()).isEqualTo("C1");
  }

  private static CheckedText sentence(int n, String text, String... cites) {
    return new CheckedText(
        "sentences." + n,
        n == 1 ? CheckedText.Kind.LEAD : CheckedText.Kind.PROSE,
        text,
        List.of(cites),
        Contract.Range.ANY,
        "type Controller",
        Set.of());
  }

  @Test
  void aSummaryKeepsItsUnchangedSentencesInOrderAndAddsOnlyThoseCitingSomethingNew() {
    List<CheckedText> first =
        List.of(
            sentence(1, "Serves the shop's web pages.", "F1"),
            sentence(2, "Lists the orders of each customer.", "E1"),
            sentence(3, "Announces new orders.", "F2"));
    Map<String, CarryForward.Earlier> earlier =
        CarryForward.earlier("{\"sentences\": " + CarryForward.record(first, BEFORE) + "}");
    Map<String, String> now =
        Map.of(
            "F1", "shop CALLS_HTTP pay.example.com",
            "F2", "shop PUBLISHES orders",
            "E1", "class Controller { orders() }");

    List<CheckedText> merged =
        CarryForward.sentences(
            List.of(
                sentence(1, "Serves the web pages of the shop.", "F1"),
                sentence(2, "Shows each customer's orders.", "E1"),
                // Repeats a kept sentence: not added again.
                sentence(3, "Announces new orders.", "F2")),
            earlier,
            now,
            "sentences");

    assertThat(merged)
        .extracting(CheckedText::field, CheckedText::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("sentences.1", "Serves the shop's web pages."),
            org.assertj.core.groups.Tuple.tuple("sentences.2", "Shows each customer's orders."),
            org.assertj.core.groups.Tuple.tuple("sentences.3", "Announces new orders."));
  }

  @Test
  void aSummaryWhoseEvidenceIsUnchangedIsKeptWholeWhateverTheModelWritesNow() {
    List<CheckedText> first =
        List.of(sentence(1, "Serves the shop's web pages.", "F1"), sentence(2, "Pays.", "F2"));
    Map<String, CarryForward.Earlier> earlier =
        CarryForward.earlier("{\"sentences\": " + CarryForward.record(first, BEFORE) + "}");

    List<CheckedText> merged =
        CarryForward.sentences(
            List.of(sentence(1, "Something else entirely.", "F1", "F2")),
            earlier,
            BEFORE,
            "sentences");

    assertThat(merged)
        .extracting(CheckedText::text)
        .containsExactly("Serves the shop's web pages.", "Pays.");
  }
}
