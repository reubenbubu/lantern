package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PurposeRoleTest {

  private static final Fact CALL =
      TestFacts.fact(
          "shop",
          "service:com.example:app",
          "CALLS_HTTP",
          FactObject.string("https://pay.example.com"),
          FactJson.newObject(),
          Validity.from(1));

  private static final Fact CLAIM =
      Fact.create(
          EntityId.parse("readme:shop:README.md"),
          "claim",
          FactObject.string("Shop sells books online."),
          FactJson.newObject(),
          new Origin(Origin.Kind.STATIC, "docs", "1"),
          Evidence.of("shop", "README.md", new Evidence.LineRange(1, 1), null),
          0.5,
          Validity.from(1));

  private static PurposeRole.Said said(String about, String name, Fact... cited) {
    return new PurposeRole.Said(
        EntityId.parse(about),
        name,
        new DescribeRole.Answered("Takes the orders.", List.of(cited)));
  }

  /** The rendering without the markers that delimit its references. */
  private static String plain(PurposeRole.Asked asked) {
    return asked.item().rendering().replace("\u0001", "").replace("\u0002", "");
  }

  private static String ref(String text, String line) {
    Matcher m = Pattern.compile("- " + Pattern.quote(line) + ": \\[([A-Z]\\d+)\\]").matcher(text);
    assertThat(m.find()).as(text).isTrue();
    return m.group(1);
  }

  @Test
  void twoUnitsOfOneNameHaveTwoReferences() {
    PurposeRole.Asked asked =
        PurposeRole.item(
                List.of(
                    said("service:com.example:app", "app", CALL),
                    said("service:com.example:other", "app", CLAIM)),
                List.of(),
                Map.of(),
                List.of(),
                Map.of())
            .orElseThrow();
    Matcher m = Pattern.compile("- app: \\[(A\\d+)\\]").matcher(plain(asked));
    assertThat(m.find()).isTrue();
    String first = m.group(1);
    assertThat(m.find()).isTrue();
    assertThat(m.group(1)).isNotEqualTo(first);
  }

  @Test
  void theItemShowsThePeople() {
    PurposeRole.Asked asked =
        PurposeRole.item(
                List.of(),
                List.of(said("person:customer", "Customer", CALL)),
                Map.of(),
                List.of(),
                Map.of())
            .orElseThrow();
    assertThat(plain(asked))
        .contains("The people who use it, each with what they do:")
        .contains("- Customer: [A");
  }

  @Test
  void withNoSentenceAndNoClaimNothingIsAsked() {
    assertThat(
            PurposeRole.item(
                List.of(), List.of(), Map.of("Pay", CALL), List.of("Checkout"), Map.of()))
        .isEmpty();
  }

  @Test
  void aSentenceThatCitesAClaimMakesTheTextEvidenced() {
    PurposeRole.Asked asked =
        PurposeRole.item(
                List.of(said("service:com.example:app", "app", CLAIM)),
                List.of(),
                Map.of(),
                List.of(),
                Map.of())
            .orElseThrow();
    String a = ref(plain(asked), "app");
    Map<String, PurposeRole.Worded> worded =
        PurposeRole.worded(
            asked, "{\"purpose\":{\"text\":\"Sells books.\",\"cites\":[\"" + a + "\"]}}");
    assertThat(worded.get(PurposeRole.PURPOSE).evidenced()).isTrue();
    assertThat(worded.get(PurposeRole.PURPOSE).cited()).containsExactly(CLAIM);
  }

  @Test
  void aSentenceWithoutAClaimLeavesTheTextInferred() {
    PurposeRole.Asked asked =
        PurposeRole.item(
                List.of(said("service:com.example:app", "app", CALL)),
                List.of(),
                Map.of(),
                List.of(),
                Map.of())
            .orElseThrow();
    String a = ref(plain(asked), "app");
    Map<String, PurposeRole.Worded> worded =
        PurposeRole.worded(
            asked, "{\"keyFact\":{\"text\":\"Pays by card.\",\"cites\":[\"" + a + "\"]}}");
    assertThat(worded.get(PurposeRole.KEY_FACT).evidenced()).isFalse();
  }

  @Test
  void theRolesInputsLeaveOutThePurposeAndKeyFact() {
    PurposeRole.Asked asked =
        PurposeRole.item(
                List.of(said("service:com.example:app", "app", CALL)),
                List.of(),
                Map.of(),
                List.of(),
                Map.of())
            .orElseThrow();
    String a = ref(plain(asked), "app");
    List<Fact> texts =
        PurposeRole.facts(
            PurposeRole.worded(
                asked,
                "{\"purpose\":{\"text\":\"Sells.\",\"cites\":[\""
                    + a
                    + "\"]},\"keyFact\":{\"text\":\"Pays.\",\"cites\":[\""
                    + a
                    + "\"]}}"),
            "m",
            1);
    assertThat(texts).hasSize(2);
    assertThat(
            Describer.withoutTexts(
                java.util.stream.Stream.concat(texts.stream(), java.util.stream.Stream.of(CALL))
                    .toList()))
        .containsExactly(CALL);
  }
}
