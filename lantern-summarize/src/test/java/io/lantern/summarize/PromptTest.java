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
  private static final String VERSION = "3";

  private static final String FINGERPRINT =
      "00b7eb25389b9b6185c46fff1e7ec4eb96c2160cfe690b0fed559a020e8a3920";

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
          .append(level.minSentences())
          .append(level.maxSentences())
          .append(level.maxWords())
          .append(level.maxOutputTokens())
          .append(level.content());
    }
    all.append(SummaryLevel.ONE_PART_SENTENCES).append(SummaryLevel.ONE_PART_WORDS);
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
    // A repository whose only module is an aggregator, and a module with a single part.
    all.append('\u0000').append(Prompt.user(repo, List.of(), List.of(child)));
    all.append('\u0000').append(Prompt.user(repo.children().getFirst(), List.of(), List.of(child)));
    // A repository of one plain module, and a repository merged from parts.
    all.append('\u0000').append(Prompt.user(singleModule(repo), List.of(), List.of(child)));
    all.append('\u0000').append(Prompt.merge(repo, List.of("One [S1].")));
    for (String id :
        List.of(
            "type:com.example.Order$Line",
            "type:Main",
            "package:com.example:shop:com.example.shop",
            "module:com.example:shop-core",
            "repo:shop")) {
      EntityId entity = EntityId.parse(id);
      all.append('\u0000').append(Prompt.describe(SummaryLevel.of(entity), entity));
    }
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
            Summarize the type PriceService in package com.example.shop in 2 to 4 sentences and at \
            most 80 words, covering its purpose, its main responsibilities and the types it works \
            with most.

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
            What is known about the type PriceService in package com.example.shop is too long for one \
            request, so it comes in 2 parts. This is part 2. Summarize what this part shows about \
            it in at most 80 words; the summaries of the parts will be merged later.

            Facts:
            [F2] %s
            """
                .formatted(Prompt.fact(priceService.facts().get(1))));
    assertThat(Prompt.merge(priceService, List.of("It is an interface. [F1]", "It prices. [F2]\n")))
        .isEqualTo(
            """
            What is known about the type PriceService in package com.example.shop was too long for one \
            request, so it was summarized in parts. Merge the summaries of the parts below into \
            one summary in 2 to 4 sentences and at most 80 words, covering its purpose, its main \
            responsibilities and the types it works with most. Cite the references the parts \
            cite for what you take from them.

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

  @Test
  void unitsAreNamedWithoutTheirIds() {
    assertThat(Prompt.describe(SummaryLevel.TYPE, EntityId.parse("type:com.example.Order$Line")))
        .isEqualTo("type Order.Line in package com.example");
    assertThat(Prompt.describe(SummaryLevel.TYPE, EntityId.parse("type:Main")))
        .isEqualTo("type Main");
    assertThat(
            Prompt.describe(
                SummaryLevel.PACKAGE, EntityId.parse("package:com.example:shop:com.example.shop")))
        .isEqualTo("package com.example.shop");
    assertThat(Prompt.describe(SummaryLevel.MODULE, EntityId.parse("module:com.example:shop-core")))
        .isEqualTo("Maven module shop-core");
    assertThat(Prompt.describe(SummaryLevel.REPO, new EntityId("repo", "shop")))
        .isEqualTo("repository shop");
  }

  @Test
  void aRepositoryWithOneModuleIsAskedNotToRestateIt() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    Summary module =
        new Summary(
            "c".repeat(64),
            singleModule(repo).children().getFirst().entity(),
            SummaryLevel.MODULE,
            "The shop. [fact:" + "1".repeat(64) + "]",
            List.of("1".repeat(64)),
            List.of(),
            List.of("1".repeat(64)),
            List.of(),
            "3",
            "fake",
            "m");

    assertThat(Prompt.user(singleModule(repo), List.of(), List.of(module)))
        .isEqualTo(
            """
            Summarize the repository shop in 1 to 3 sentences and at most 60 words, covering \
            what the repository as a whole is for and what it adds to its only part, such as its \
            dependencies. Do not restate the summary of that part [S1].
            Write for a reader who wants to know what this repository does for the system or the \
            business: lead with its role and main capabilities, and mention frameworks and \
            libraries briefly, if at all.

            Summaries of the parts it contains:
            [S1] Maven module shop-core: The shop.
            """);
  }

  /** The shop repository as if it held only its plain module shop-core. */
  private static SummaryNode singleModule(SummaryNode repo) {
    SummaryNode core = repo.children().getFirst().children().getFirst();
    return new SummaryNode(repo.entity(), SummaryLevel.REPO, List.of(), List.of(core));
  }

  @Test
  void aRepositoryWhoseOnlyModuleHoldsModulesIsAlsoAskedNotToRestateIt() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    Summary parent =
        new Summary(
            "c".repeat(64),
            repo.children().getFirst().entity(),
            SummaryLevel.MODULE,
            "The parent. [fact:" + "1".repeat(64) + "]",
            List.of("1".repeat(64)),
            List.of(),
            List.of("1".repeat(64)),
            List.of(),
            "3",
            "fake",
            "m");

    // The repository's prompt shows nothing but its module's summary.
    assertThat(Prompt.user(repo, List.of(), List.of(parent)))
        .contains("in 1 to 3 sentences and at most 60 words")
        .contains("Write for a reader who wants to know what this repository does");
    assertThat(Prompt.merge(repo, List.of("One [S1]."))).contains("lead with its role");
  }

  @Test
  void aModuleWithOnePackageIsAskedWhatItAdds() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode core = repo.children().getFirst().children().getFirst();
    Summary pkg =
        new Summary(
            "c".repeat(64),
            core.children().getFirst().entity(),
            SummaryLevel.PACKAGE,
            "Prices. [fact:" + "1".repeat(64) + "]",
            List.of("1".repeat(64)),
            List.of(),
            List.of("1".repeat(64)),
            List.of(),
            "3",
            "fake",
            "m");

    assertThat(Prompt.user(core, List.of(), List.of(pkg)))
        .startsWith(
            "Summarize the Maven module shop-core in 1 to 3 sentences and at most 60 words,"
                + " covering what the Maven module as a whole is for and what it adds to its only"
                + " part, such as its dependencies. Do not restate the summary of that part [S1].\n")
        .doesNotContain("Write for a reader");
    SummaryNode pkgNode = core.children().getFirst();
    assertThat(Prompt.user(pkgNode, List.of(), List.of(pkg)))
        .startsWith("Summarize the package com.example.shop in 1 to 3 sentences");
  }

  @Test
  void eachLevelAsksForASentenceCountThatFitsItsWordLimit() {
    for (SummaryLevel level : SummaryLevel.values()) {
      assertThat(level.minSentences()).isPositive().isLessThanOrEqualTo(level.maxSentences());
      // About 20 words a sentence.
      assertThat(level.maxSentences() * 20).isLessThanOrEqualTo(level.maxWords());
      assertThat(Prompt.length(level))
          .isEqualTo(
              "in %d to %d sentences and at most %d words"
                  .formatted(level.minSentences(), level.maxSentences(), level.maxWords()));
    }
  }
}
