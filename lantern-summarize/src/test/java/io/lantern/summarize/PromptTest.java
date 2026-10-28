package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptTest {

  /**
   * The fingerprint of the prompt at {@link Prompt#VERSION}. If this test fails, the prompt
   * changed: raise {@link Prompt#VERSION} so cached summaries made with the old prompt are not
   * reused, then update both values here.
   */
  private static final String VERSION = "1";

  private static final String FINGERPRINT =
      "34d4c7ceef5f54ce917118733de97a8ce42d2428a0c902846d063d79bd2cf5c9";

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
    all.append('\u0000').append(Prompt.user(order, List.of(child)));
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

    assertThat(Prompt.user(priceService, List.of()))
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
}
