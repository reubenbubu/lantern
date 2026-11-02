package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.checks.WordingJudge;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Which texts the wording judge grades and how each verdict is written (ADR 0111). */
class WordingJudgementsTest {

  private static Fact fact(String subject, String predicate, String role, String text) {
    ObjectNode attributes = FactJson.newObject();
    if (text != null) {
      attributes.put("text", text);
    }
    attributes.put("model", "anthropic/writer");
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        FactObject.string("c4"),
        attributes,
        new Origin(Origin.Kind.LLM, role, "1"),
        Evidence.of("shop", "src/main/java/Orders.java", new Evidence.LineRange(1, 2), null),
        0.6,
        Validity.from(1));
  }

  @Test
  void theJudgeGradesResponsibilitiesIntentsTheKeyFactAndGroupNamesOnly() {
    List<Fact> facts =
        List.of(
            fact("service:orders", "responsibility", "describe-unit", "Takes orders."),
            fact("service:orders", "intent", "describe-unit", "reserves stock"),
            fact("system:shop", "key-fact", "purpose", "Every order is paid before it ships."),
            fact("system:shop", "purpose", "purpose", "Sells things."),
            fact("group:orders/a", "display-name", "name-groups", "Ordering"),
            fact("group:orders/a", "responsibility", "name-groups", "Places orders."),
            fact("lead:x/y", "lead-verdict", "investigate-leads", "Confirmed."),
            fact("service:billing", "responsibility", "describe-unit", "  "),
            fact("group:orders/a", "intent", "name-groups", "asks for payment"));

    List<WordingJudgements.Judged> texts = WordingJudgements.texts(facts);

    assertThat(texts)
        .extracting(j -> j.text().kind())
        .containsExactly(
            "responsibility of a service",
            "intent of a line drawn from a service",
            "the most important fact about the system",
            "name of a group of components",
            "responsibility of a group of components");
    assertThat(texts.get(2).text().about()).isEqualTo("the system");
    assertThat(texts.get(0).text().about()).isEqualTo("service:orders");
  }

  @Test
  void eachLineCarriesTheWriterTheJudgeAndWhatBecameOfTheText() {
    Fact fact = fact("service:orders", "responsibility", "describe-unit", "Takes orders.");
    WordingJudgements.Judged judged = WordingJudgements.texts(List.of(fact)).getFirst();
    Fact long_ =
        fact("service:orders", "responsibility", "describe-unit", "word ".repeat(61).strip());
    WordingJudgements.Judged tooLong = WordingJudgements.texts(List.of(long_)).getFirst();

    ObjectNode passed =
        WordingJudgements.line(
            judged,
            WordingJudge.Dimension.ROLE,
            new WordingJudge.Verdict("pass", "Says its job."),
            "openai/judge");
    ObjectNode missing =
        WordingJudgements.line(judged, WordingJudge.Dimension.SPECIFIC, null, "openai/judge");
    ObjectNode skipped =
        WordingJudgements.line(tooLong, WordingJudge.Dimension.ROLE, null, "openai/judge");
    ObjectNode own =
        WordingJudgements.sameModel(judged, WordingJudge.Dimension.ROLE, "anthropic/writer");

    assertThat(passed.path("state").asString()).isEqualTo("judged");
    assertThat(passed.path("verdict").asString()).isEqualTo("pass");
    assertThat(passed.path("dimension").asString()).isEqualTo("role");
    assertThat(passed.path("writer").asString()).isEqualTo("anthropic/writer");
    assertThat(passed.path("judge").asString()).isEqualTo("openai/judge");
    assertThat(passed.path("role").asString()).isEqualTo("describe-unit");
    assertThat(passed.path("words").asInt()).isEqualTo(2);
    assertThat(missing.path("state").asString()).isEqualTo("notAnswered");
    assertThat(missing.has("verdict")).isFalse();
    assertThat(skipped.path("state").asString()).isEqualTo("tooLong");
    assertThat(WordingJudgements.byJudge(judged, "anthropic/writer")).isTrue();
    assertThat(WordingJudgements.byJudge(judged, "openai/judge")).isFalse();
    assertThat(own.path("state").asString()).isEqualTo("sameModel");
  }
}
