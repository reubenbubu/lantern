package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptTest {

  /**
   * The fingerprint of the prompt at {@link Prompt#VERSION}. If this test fails, the prompt
   * changed: raise {@link Prompt#VERSION} so cached summaries made with the old prompt are not
   * reused, then update both values here.
   */
  private static final String VERSION = "2";

  private static final String FINGERPRINT =
      "fb256f819bac965c74e036bc7ab21b956758948a15c62eafaf09858cd6b162b1";

  @Test
  void promptVersionChangesWithThePrompt() throws Exception {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode order =
        repo.children()
            .getFirst()
            .children()
            .getFirst()
            .children()
            .getFirst()
            .children()
            .getFirst();
    StringBuilder all = new StringBuilder();
    all.append(Prompt.SYSTEM).append('\u0000').append(Prompt.RETRY).append('\u0000');
    for (SummaryLevel level : SummaryLevel.values()) {
      all.append(level)
          .append(level.noun())
          .append(level.maxWords())
          .append(level.maxOutputTokens());
    }
    Summary child =
        new Summary(
            "c".repeat(64),
            order.children().getFirst().entity(),
            SummaryLevel.TYPE,
            "A line. [fact:" + "1".repeat(64) + "]\nSecond line.",
            List.of("1".repeat(64)),
            List.of(),
            List.of("1".repeat(64)),
            List.of(),
            "1",
            "fake",
            "m");
    // How a type's source file is shown (with a fence longer than the backticks in it).
    List<Excerpts.Excerpt> excerpts =
        Excerpts.of(
            new SummaryNode(
                EntityId.parse("type:p.Order"),
                SummaryLevel.TYPE,
                List.of(
                    ExcerptsTest.fact("kind", FactObject.string("class")),
                    ExcerptsTest.member("method:p.Order#total()")),
                List.of()),
            e -> Optional.of(ExcerptsTest.ORDER + "// ```\n"));
    all.append('\u0000').append(Prompt.user(order, excerpts, List.of(child)));
    all.append('\u0000')
        .append(
            Prompt.user(
                order,
                Prompt.facts(order.facts()),
                Prompt.excerpts(excerpts),
                List.of(),
                new Prompt.Part(2, 3)));
    all.append('\u0000').append(Prompt.merge(order, List.of("One [F1].", "Two [E1, S1].")));
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(all.toString().getBytes(StandardCharsets.UTF_8)));

    assertThat(Prompt.VERSION + " " + fingerprint).isEqualTo(VERSION + " " + FINGERPRINT);
  }

  @Test
  void userPromptListsFactsAndPartsWithReferences() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode priceService =
        repo.children().getFirst().children().getFirst().children().getFirst().children().get(1);

    assertThat(Prompt.user(priceService, List.of(), List.of()))
        .isEqualTo(
            """
            Summarize the type type:com.example.shop.PriceService in at most 80 words.

            Facts:
            [F1] %s
            [F2] %s
            """
                .formatted(
                    Prompt.fact(priceService.facts().get(0)),
                    Prompt.fact(priceService.facts().get(1))));
    assertThat(priceService.facts())
        .extracting(Prompt::fact)
        .containsExactlyInAnyOrder(
            "type:com.example.shop.PriceService kind \"interface\"",
            "type:com.example.shop.PriceService CONTAINS method:com.example.shop.PriceService#price()");
  }

  @Test
  void partAndMergePromptsSayWhatTheyAre() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode priceService =
        repo.children().getFirst().children().getFirst().children().getFirst().children().get(1);
    List<Prompt.Item> facts = Prompt.facts(priceService.facts());

    assertThat(
            Prompt.user(
                priceService, facts.subList(1, 2), List.of(), List.of(), new Prompt.Part(2, 2)))
        .isEqualTo(
            """
            What is known about the type type:com.example.shop.PriceService is too long for one \
            request, so it comes in 2 parts. This is part 2. Summarize what this part shows about \
            it in at most 80 words; the summaries of the parts will be merged later.

            Facts:
            [F2] %s
            """
                .formatted(Prompt.fact(priceService.facts().get(1))));
    assertThat(Prompt.merge(priceService, List.of("It is an interface. [F1]", "It prices. [F2]\n")))
        .isEqualTo(
            """
            What is known about the type type:com.example.shop.PriceService was too long for one \
            request, so it was summarized in parts. Merge the summaries of the parts below into \
            one summary of at most 80 words. Cite the references the parts cite for what you \
            take from them.

            Summaries of the parts:
            Part 1: It is an interface. [F1]
            Part 2: It prices. [F2]
            """);
  }

  @Test
  void codeFencesAreLongerThanAnyRunOfBackticksInTheFile() {
    assertThat(Prompt.fence("int x;")).isEqualTo("```");
    assertThat(Prompt.fence("/// ```java\n/// ````\n")).isEqualTo("`````");
  }
}
