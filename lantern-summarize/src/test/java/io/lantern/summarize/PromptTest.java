package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.FactObject;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private static final String VERSION = "18";

  private static final String FINGERPRINT =
      "e04e97b76418b823e6266b837c3a3a09c573a6b816ca0b72d3930c8f3edb8b6f";

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
  void theSummarizePromptIsTheEpics() throws Exception {
    String epic = Files.readString(Path.of("../docs/epics/001-knowledge-build.md"));

    assertThat(epic).contains(Prompt.TASK).contains(Prompt.SCHEMA).contains(Prompt.CLOSING);
    assertThat(Prompt.TASK).startsWith("Task: summarise code units.");
    assertThat(Prompt.SYSTEM)
        .isEqualTo(
            CommonPrompt.SYSTEM
                + "\n\n"
                + Prompt.TASK
                + "\n\nAnswer schema:\n"
                + Prompt.SCHEMA.stripTrailing()
                + "\n"
                + Prompt.CLOSING);
  }

  @Test
  void aRequestSendsBothBlocksAsTheSystemPromptAndTheUnitAsItsOneItem() {
    LlmRequest request = Prompt.request(Role.SUMMARIZE_TYPES, "summarize type x", "Unit: x\n", 400);

    assertThat(request.role()).isEqualTo(Role.SUMMARIZE_TYPES);
    assertThat(request.system()).isEqualTo(Prompt.SYSTEM);
    assertThat(request.maxOutputTokens()).isEqualTo(400);
    assertThat(request.messages().getFirst().text())
        .isEqualTo("<items>\n<item id=\"I1\">\nUnit: x\n</item>\n</items>");
  }

  @Test
  void anItemListsItsFactsWithReferences() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode priceService = priceService(repo);

    assertThat(Prompt.user(priceService, List.of(), List.of()))
        .isEqualTo(
            """
            Unit: type PriceService in package com.example.shop
            Write 2 to 4 sentences of 10 to 30 words each.
            Its facts:
            <facts>
            [F1] %s
            [F2] %s
            </facts>
            """
                .formatted(
                    Prompt.fact(priceService.facts().get(0)),
                    Prompt.fact(priceService.facts().get(1))));
  }

  @Test
  void aTypeShowsItsSourceWithLineNumbersAndItsPartsSummaries() {
    SummaryNode repo = SummaryTree.fromFacts(TestFacts.shop().list()).getFirst();
    SummaryNode order = order(repo);
    Summary line = summary(order.children().getFirst().entity(), SummaryLevel.TYPE);

    String user = Prompt.user(order, excerpts(), List.of(line));

    assertThat(user)
        .contains("Its source code:\n<code ref=\"E1\" repo=\"")
        .contains(" 1  ")
        .contains("Summaries of the units it contains:\n<summaries>\n[S1] type ")
        .contains(": A line. Second line.\n</summaries>\n");
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
            Unit: type PriceService in package com.example.shop
            What is known about this unit is too long for one request, so it comes in 2 parts. \
            This is part 2. Write 1 to 3 sentences of 10 to 30 words each on what this part \
            shows about it; the summaries of the parts are merged later.
            Its facts:
            <facts>
            [F2] %s
            </facts>
            """
                .formatted(Prompt.fact(priceService.facts().get(1))));
    assertThat(Prompt.merge(priceService, List.of("It is an interface. [F1]", "It prices. [F2]\n")))
        .isEqualTo(
            """
            Unit: type PriceService in package com.example.shop
            What is known about this unit was too long for one request, so it was summarised in \
            parts. Merge the summaries of the parts below into one summary of 2 to 4 sentences of \
            10 to 30 words each. Cite the references the parts cite for what you take from them.
            <summaries>
            Part 1: It is an interface. [F1]
            Part 2: It prices. [F2]
            </summaries>
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
            "Unit: Maven module shop-core\nWrite 1 to 3 sentences of 10 to 30 words each. It"
                + " contains only one unit [S1]: say what it adds to that unit, such as its"
                + " dependencies, and do not restate that unit's summary.\n");
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
      assertThat(Prompt.length(level))
          .isEqualTo(
              "%d to %d sentences of 10 to 30 words each"
                  .formatted(level.minSentences(), level.maxSentences()));
    }
  }

  @Test
  void aFactSheetIsShownGroupByGroupAfterTheUnitsOwnFacts() {
    SummaryNode service = SummaryTree.fromFacts(FactSheetTest.service(17).list()).getFirst();

    String user = Prompt.user(service, List.of(), List.of());

    assertThat(user).contains("Of the units it contains, entry points (15 of 17):\n<facts>\n");
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
