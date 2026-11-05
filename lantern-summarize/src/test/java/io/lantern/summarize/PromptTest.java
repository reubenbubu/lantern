package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactObject;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
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
  private static final String VERSION = "20";

  private static final String FINGERPRINT =
      "ba2f8b7260e56c66f7b22afbb8e876822d5aeccd167f52a88c6ec1bb6a842c1f";

  @Test
  void promptVersionChangesWithThePrompt() throws Exception {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode order = order(repo);
    StringBuilder all = new StringBuilder();
    all.append(Prompt.SYSTEM).append('\u0000');
    for (SummaryLevel level : SummaryLevel.values()) {
      all.append(level)
          .append(level.noun())
          .append(level.minSentences())
          .append(level.maxSentences())
          .append(level.maxOutputTokens());
    }
    all.append(SummaryLevel.ONE_PART_SENTENCES).append(SummaryLevel.ONE_PART_SENTENCE_COUNT);
    // The size the request asks for; the size the checks accept is the contract's (ADR 0118).
    all.append(Prompt.ASKED_WORDS);
    Summary child = summary(order.children().getFirst().entity(), SummaryLevel.TYPE);
    // How a type's source file is shown.
    List<Excerpts.Excerpt> excerpts = excerpts();
    all.append('\u0000').append(Prompt.user(order, excerpts, List.of(child)));
    all.append('\u0000')
        .append(
            Prompt.user(
                order,
                Prompt.facts(order.facts()),
                List.of(),
                Prompt.excerpts(excerpts),
                List.of(),
                new Prompt.Part(2, 3)));
    all.append('\u0000').append(Prompt.merge(order, List.of("One [F1].", "Two [E1, S1].")));
    // A repository whose only module is an aggregator, and a module with a single part.
    all.append('\u0000').append(Prompt.user(repo, List.of(), List.of(child)));
    all.append('\u0000').append(Prompt.user(repo.children().getFirst(), List.of(), List.of(child)));
    all.append('\u0000').append(Prompt.merge(repo, List.of("One [S1].")));
    // A repository and a module with fact sheets, one group of them cut short.
    SummaryNode service = SummaryTree.fromFacts(FactSheetTest.service(17).list()).getFirst();
    all.append('\u0000').append(Prompt.user(service, List.of(), List.of(child)));
    all.append('\u0000')
        .append(Prompt.user(service.children().getFirst(), List.of(), List.of(child)));
    // The request around the item.
    all.append('\u0000').append(Prompt.request(Role.SUMMARIZE, "p", "item", 10).messages());
    // How many README claims a repository shows.
    all.append(SummaryTree.README_CLAIMS);
    all.append(FactSheet.MAX_PER_GROUP);
    for (FactSheet.Group group : FactSheet.Group.values()) {
      all.append(group.title()).append(group.predicates());
    }
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

  /**
   * Block 2 and the answer schema are the epic's, character for character (knowledge-build epic,
   * section 11.3), after the common system prompt of section 11.0.
   */
  @Test
  void theSummarizePromptAsksForTomlInEveryRuleThatNamesTheFormat() {
    // The closed knowledge-build epic keeps the JSON prompt as its record; since #872 the model is
    // shown the TOML format and an example instead of the JSON schema, which the checks still use.
    assertThat(Prompt.TASK).startsWith("Task: summarise code units.");
    assertThat(Prompt.SYSTEM)
        .isEqualTo(
            CommonPrompt.system(io.lantern.harness.AnswerFormat.TOML)
                + "\n\n"
                + Prompt.TASK
                + "\n\n"
                + Prompt.CLOSING);
    assertThat(Prompt.SYSTEM)
        .contains("Answer with exactly one TOML document in the format of your task")
        .contains("answer a table with omitted = true")
        .doesNotContain("JSON")
        .doesNotContain("${");
    // The example in the closing is an answer the role reads.
    String example =
        Prompt.CLOSING.substring(
            Prompt.CLOSING.indexOf("[I1]"), Prompt.CLOSING.indexOf("\nWrite every text"));
    assertThat(io.lantern.harness.Toml.read(example).root().path("I1").path("sentences"))
        .hasSize(2);
  }

  @Test
  void aRequestSendsBothBlocksAsTheSystemPromptAndTheUnitAsItsOneItem() {
    LlmRequest request =
        Prompt.request(Role.SUMMARIZE_TYPES, "summarize type x", "[item.I1]\nunit = \"x\"\n", 400);

    assertThat(request.role()).isEqualTo(Role.SUMMARIZE_TYPES);
    assertThat(request.system()).isEqualTo(Prompt.SYSTEM);
    assertThat(request.maxOutputTokens()).isEqualTo(400);
    assertThat(request.messages().getFirst().text())
        .isEqualTo("<items>\n[item.I1]\nunit = \"x\"\n</items>");
  }

  @Test
  void anItemListsItsFactsWithReferences() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode priceService = priceService(repo);

    assertThat(Prompt.user(priceService, List.of(), List.of()))
        .isEqualTo(
            """
            [item.I1]
            unit = "type PriceService in package com.example.shop"
            ask = "Write 3 to 4 sentences of 10 to 30 words each (2 when its inputs give too little for more)."
            facts = '''
            @ shop:pom.xml
            PriceService
              [F1] CONTAINS method price()
              [F2] kind "interface"
            '''
            names = '''
            PriceService = com.example.shop.PriceService
            '''
            """);
  }

  @Test
  void aTypeShowsItsSourceWithLineNumbersAndItsPartsSummaries() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode order = order(repo);
    Summary line = summary(order.children().getFirst().entity(), SummaryLevel.TYPE);

    String user = Prompt.user(order, excerpts(), List.of(line));

    assertThat(user)
        .contains("[[item.I1.code]]\nref = \"E1\"\nrepo = \"")
        .contains(" 1  ")
        .contains("summaries = \'\'\'\n[S1] type ")
        .contains(": A line. Second line.\n\'\'\'\n");
    // The code is the item's: a table after its keys, read back as written.
    tools.jackson.databind.JsonNode item =
        io.lantern.harness.Toml.read(user).root().path("item").path("I1");
    assertThat(item.path("code").get(0).path("ref").asString()).isEqualTo("E1");
    assertThat(item.path("summaries").asString()).startsWith("[S1] type ");
  }

  @Test
  void partAndMergeItemsSayWhatTheyAre() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode priceService = priceService(repo);
    List<Prompt.Item> facts = Prompt.facts(priceService.facts());

    assertThat(
            Prompt.user(
                priceService,
                facts.subList(1, 2),
                List.of(),
                List.of(),
                List.of(),
                new Prompt.Part(2, 2)))
        .isEqualTo(
            """
            [item.I1]
            unit = "type PriceService in package com.example.shop"
            ask = "What is known about this unit is too long for one request, so it comes in 2 \
            parts. This is part 2. Write 1 to 3 sentences of 10 to 30 words each on what this part \
            shows about it; the summaries of the parts are merged later."
            facts = '''
            @ shop:pom.xml
            PriceService
              [F2] kind "interface"
            '''
            names = '''
            PriceService = com.example.shop.PriceService
            '''
            """);
    assertThat(Prompt.merge(priceService, List.of("It is an interface. [F1]", "It prices. [F2]\n")))
        .isEqualTo(
            """
            [item.I1]
            unit = "type PriceService in package com.example.shop"
            ask = "What is known about this unit was too long for one request, so it was \
            summarised in parts. Merge the summaries of the parts into one summary of 3 to 4 \
            sentences of 10 to 30 words each (2 when its inputs give too little for more). Cite the \
            references the parts cite for what you take from them."
            parts = '''
            Part 1: It is an interface. [F1]
            Part 2: It prices. [F2]
            '''
            """);
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
  void aUnitWithOnePartIsAskedWhatItAdds() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode core = repo.children().getFirst().children().getFirst();
    Summary pkg = summary(core.children().getFirst().entity(), SummaryLevel.PACKAGE);

    assertThat(Prompt.user(core, List.of(), List.of(pkg)))
        .startsWith(
            "[item.I1]\nunit = \"Maven module shop-core\"\nask = \"Write 1 to 3 sentences of 10"
                + " to 30 words each. It contains only one unit [S1]: say what it adds to that unit,"
                + " such as its dependencies, and do not restate that unit's summary.\"\n");
    assertThat(Summarizer.maxSentences(core, 1)).isEqualTo(3);
    assertThat(Summarizer.maxSentences(core, 2)).isEqualTo(6);
    // A type with one member type is asked as any type.
    assertThat(Summarizer.maxSentences(order(repo), 1)).isEqualTo(4);
  }

  @Test
  void eachLevelAsksForItsSentenceCount() {
    for (SummaryLevel level : SummaryLevel.values()) {
      assertThat(level.minSentences()).isPositive().isLessThanOrEqualTo(level.maxSentences());
      // The schema allows six.
      assertThat(level.maxSentences()).isLessThanOrEqualTo(6);
      // A type is asked one more than the fewest it may have (#874).
      assertThat(Prompt.length(level))
          .isEqualTo(
              level == SummaryLevel.TYPE
                  ? "3 to 4 sentences of 10 to 30 words each (2 when its inputs give too little"
                      + " for more)"
                  : "%d to %d sentences of 10 to 30 words each"
                      .formatted(level.minSentences(), level.maxSentences()));
    }
  }

  @Test
  void aFactSheetIsShownGroupByGroupAfterTheUnitsOwnFacts() {
    SummaryNode service = SummaryTree.fromFacts(FactSheetTest.service(17).list()).getFirst();

    String user = Prompt.user(service, List.of(), List.of());

    assertThat(user)
        .contains(
            "[[item.I1.contained]]\nabout = \"Of the units it contains, entry points (15 of"
                + " 17)\"\nfacts = \'\'\'\n");
    assertThat(io.lantern.harness.Toml.read(user).root().path("item").path("I1").path("contained"))
        .isNotEmpty();
  }

  private static SummaryNode order(SummaryNode repo) {
    return repo.children()
        .getFirst()
        .children()
        .getFirst()
        .children()
        .getFirst()
        .children()
        .getFirst();
  }

  private static SummaryNode priceService(SummaryNode repo) {
    return repo.children().getFirst().children().getFirst().children().getFirst().children().get(1);
  }

  private static List<Excerpts.Excerpt> excerpts() {
    return Excerpts.of(
        new SummaryNode(
            EntityId.parse("type:p.Order"),
            SummaryLevel.TYPE,
            List.of(
                ExcerptsTest.fact("kind", FactObject.string("class")),
                ExcerptsTest.member("method:p.Order#total()")),
            List.of()),
        e -> Optional.of(ExcerptsTest.ORDER));
  }

  private static Summary summary(EntityId entity, SummaryLevel level) {
    return new Summary(
        "c".repeat(64),
        entity,
        level,
        "A line. [fact:" + "1".repeat(64) + "]\nSecond line.",
        Summary.Purpose.INFERRED,
        0.35,
        List.of("1".repeat(64)),
        List.of(),
        List.of("1".repeat(64)),
        List.of(),
        "1",
        "fake",
        "m");
  }

  @Test
  void theSentenceLimitTheRequestStatesIsItsContracts() {
    // Summarize's prompt files state no limit: each request states its contract's (ADR 0118).
    io.lantern.harness.Contract contract = io.lantern.harness.Contract.of("summarize");
    String stated = contract.field("sentence").stated();
    org.assertj.core.api.Assertions.assertThat(Prompt.length(SummaryLevel.TYPE)).contains(stated);
    org.assertj.core.api.Assertions.assertThat(stated)
        .contains(String.valueOf(contract.requestedWords("sentence")));
    org.assertj.core.api.Assertions.assertThat(Prompt.SENTENCE_WORDS)
        .isEqualTo(contract.acceptedWords("sentence"));
  }
}
