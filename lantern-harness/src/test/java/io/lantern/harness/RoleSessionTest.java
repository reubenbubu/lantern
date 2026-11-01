package io.lantern.harness;

import static io.lantern.harness.TestHarness.asking;
import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static io.lantern.harness.TestHarness.item;
import static io.lantern.harness.TestHarness.role;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Items, fingerprints, the answer cache, plans and repair (knowledge-build epic, section 10). */
class RoleSessionTest {

  private final FakeLlmProvider fake = new FakeLlmProvider().answering("An answer.");
  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  private List<String> run(List<Item> items) {
    RoleSession session = harness(client(fake), store).session(role());
    List<String> asked = new ArrayList<>();
    for (Item item : items) {
      Outcome outcome = session.ask(item, asking(item));
      if (outcome.kind() != Outcome.Kind.CACHED) {
        asked.add(item.subject());
      }
    }
    return asked;
  }

  @Test
  void aWarmRerunOfTheSameItemsMakesNoCall() {
    List<Item> items = List.of(item("a", "A.java"), item("b", "B.java"), item("c", "C.java"));
    assertThat(run(items)).containsExactly("a", "b", "c");
    int calls = fake.sent().size();

    Harness warm = harness(client(fake), store);
    RoleSession session = warm.session(role());
    for (Item item : items) {
      assertThat(session.ask(item, asking(item)).kind()).isEqualTo(Outcome.Kind.CACHED);
    }

    assertThat(fake.sent()).hasSize(calls);
    assertThat(warm.records().getFirst().calls()).isZero();
    assertThat(warm.records().getFirst().cached()).isEqualTo(3);
  }

  @Test
  void aChangedFileReAsksExactlyTheItemsWhoseInputsComeFromIt() {
    Item a = item("a", "Shared.java");
    Item b = item("b", "B.java");
    Item c = item("c", "C.java", "Shared.java");
    run(List.of(a, b, c));

    // Shared.java changes: its blob is a new one in every item that shows it.
    List<Item> changed = new ArrayList<>();
    for (Item item : List.of(a, b, c)) {
      List<FingerprintInput> inputs =
          item.inputs().stream()
              .map(
                  input ->
                      input.inFile("repo", "Shared.java")
                          ? FingerprintInput.blob("repo", "Shared.java", "f".repeat(40))
                          : input)
              .toList();
      changed.add(new Item(item.subject(), item.locality(), inputs, item.rendering()));
    }
    List<String> touched =
        changed.stream()
            .filter(i -> i.readsFile("repo", "Shared.java"))
            .map(Item::subject)
            .toList();

    assertThat(run(changed)).containsExactlyElementsOf(touched).containsExactly("a", "c");
  }

  @Test
  void theFingerprintCoversRoleVersionModelToolsInputsAndRendering() {
    Item item = item("a", "A.java");
    RoleSpec role = role();
    var model = fake.model("m");
    String fingerprint = Fingerprints.of(role, model, item);

    assertThat(Fingerprints.of(role, model, item("a", "A.java"))).isEqualTo(fingerprint);
    assertThat(Fingerprints.of(role, fake.model("n"), item)).isNotEqualTo(fingerprint);
    assertThat(
            Fingerprints.of(
                RoleSpec.single("summarize", Role.SUMMARIZE, "2", "summarize", RunMode.BULK, 100),
                model,
                item))
        .isNotEqualTo(fingerprint);
    assertThat(
            Fingerprints.of(
                role,
                model,
                new Item("a", item.locality(), item.inputs(), item.rendering() + " More.")))
        .isNotEqualTo(fingerprint);
    assertThat(Fingerprints.of(role, model, item("a", "A.java", "B.java")))
        .isNotEqualTo(fingerprint);
    // Where the item sits only decides packets, not the answer.
    assertThat(
            Fingerprints.of(
                role, model, new Item("a", "elsewhere", item.inputs(), item.rendering())))
        .isEqualTo(fingerprint);
  }

  @Test
  void anOmittedAnswerIsCachedAndADroppedOneIsNot() {
    RoleSession session = harness(client(fake), store).session(role());
    Item omitted = item("omitted");
    Item dropped = item("dropped");

    assertThat(session.ask(omitted, calls -> Answer.omitted()).kind())
        .isEqualTo(Outcome.Kind.OMITTED);
    assertThat(
            session
                .ask(
                    dropped,
                    calls -> {
                      throw new ItemDropped("rejected twice");
                    })
                .kind())
        .isEqualTo(Outcome.Kind.DROPPED);

    RoleSession again = harness(client(fake), store).session(role());
    assertThat(again.ask(omitted, asking(omitted)).kind()).isEqualTo(Outcome.Kind.CACHED);
    assertThat(again.ask(dropped, asking(dropped)).kind()).isEqualTo(Outcome.Kind.ANSWERED);
  }

  @Test
  void aCachedAnswerIsReusedOnlyWhileTheFilesItReadKeepTheirBlobs() {
    Item item = item("a");
    Map<String, String> blobs = new java.util.HashMap<>(Map.of("Read.java", "1".repeat(40)));
    BlobLookup lookup = (repo, path) -> Optional.ofNullable(blobs.get(path));
    Asker reading =
        calls ->
            new Answer("{}", List.of(new FileRead("repo", "Read.java", blobs.get("Read.java"))));
    new Harness(client(fake), store, RunBudget.NONE, lookup, Set.of())
        .session(role())
        .ask(item, reading);

    RoleSession same =
        new Harness(client(fake), store, RunBudget.NONE, lookup, Set.of()).session(role());
    assertThat(same.ask(item, reading).kind()).isEqualTo(Outcome.Kind.CACHED);

    blobs.put("Read.java", "2".repeat(40));
    RoleSession changed =
        new Harness(client(fake), store, RunBudget.NONE, lookup, Set.of()).session(role());
    assertThat(changed.ask(item, reading).kind()).isEqualTo(Outcome.Kind.ANSWERED);

    // The new answer replaces the stale one, so the next run reuses it.
    RoleSession again =
        new Harness(client(fake), store, RunBudget.NONE, lookup, Set.of()).session(role());
    assertThat(again.ask(item, reading).kind()).isEqualTo(Outcome.Kind.CACHED);
  }

  @Test
  void aRejectedAnswerGoesBackOnceWithEveryReasonThenIsDropped() {
    FakeLlmProvider rejecting = new FakeLlmProvider().answering("Still wrong.");
    Harness harness = harness(client(rejecting), store);
    RoleSession session = harness.session(role());
    Item item = item("a");
    LlmRequest request =
        new LlmRequest(
            Role.SUMMARIZE, "describe a", "System.", List.of(LlmMessage.user("Q")), 100, false);

    Outcome outcome =
        session.ask(
            item,
            calls ->
                Answer.of(
                    calls.answer(
                        request,
                        answer -> {
                          throw new Rejected(List.of("It cites F9.", "It is too long."));
                        },
                        Repair.withMessage(r -> "Fix: " + String.join(" ", r.reasons())))));

    assertThat(outcome.kind()).isEqualTo(Outcome.Kind.DROPPED);
    assertThat(rejecting.sent()).hasSize(2);
    LlmRequest repair = rejecting.sent().get(1).request();
    assertThat(repair.messages().getLast().text()).isEqualTo("Fix: It cites F9. It is too long.");
    assertThat(store.size()).isZero();
  }

  @Test
  void anAnswerAcceptedAfterTheRepairTurnIsStoredAndCountedAsRepaired() {
    FakeLlmProvider learning =
        new FakeLlmProvider()
            .answering(request -> request.messages().size() == 1 ? "Wrong." : "Right.");
    Harness harness = harness(client(learning), store);
    RoleSession session = harness.session(role());
    LlmRequest request =
        new LlmRequest(
            Role.SUMMARIZE, "describe a", "System.", List.of(LlmMessage.user("Q")), 100, false);

    Outcome outcome =
        session.ask(
            item("a"),
            calls ->
                Answer.of(
                    "\""
                        + calls.answer(
                            request,
                            answer -> {
                              if (!answer.text().equals("Right.")) {
                                throw new Rejected("Wrong.");
                              }
                              return answer.text();
                            },
                            Repair.withMessage(Rejected::getMessage))
                        + "\""));

    assertThat(outcome.kind()).isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(outcome.answer()).isEqualTo("\"Right.\"");
    assertThat(harness.records().getFirst().toJson().get("items").get("repaired").asInt())
        .isEqualTo(1);
  }

  @Test
  void anAnswerHoldingASecretValueIsRejected() {
    FakeLlmProvider leaking = new FakeLlmProvider().answering("The key is s3cr3t-value.");
    Harness harness =
        new Harness(
            client(leaking), store, RunBudget.NONE, BlobLookup.NONE, Set.of("s3cr3t-value"));
    LlmRequest request =
        new LlmRequest(
            Role.SUMMARIZE, "describe a", "System.", List.of(LlmMessage.user("Q")), 100, false);

    Outcome outcome =
        harness
            .session(role())
            .ask(
                item("a"),
                calls ->
                    Answer.of(
                        "\""
                            + calls.answer(
                                request,
                                answer -> answer.text(),
                                Repair.withMessage(Rejected::getMessage))
                            + "\""));

    assertThat(outcome.kind()).isEqualTo(Outcome.Kind.DROPPED);
    assertThat(leaking.sent().get(1).request().messages().getLast().text()).contains("secret");
  }

  @Test
  void thePlanCountsCacheHitsAndEstimatesTheMisses() {
    io.lantern.llm.LlmClient priced = client(fake, TestHarness.priced(fake));
    harness(priced, store).session(role()).ask(item("a"), asking(item("a")));
    Harness harness = harness(priced, store);
    RoleSession session = harness.session(role());

    Estimate estimate = session.plan(List.of(item("a"), item("b")), 1, 2, 4_000, 10);

    assertThat(estimate.items()).isEqualTo(5);
    // a is cached, and one of the items not yet known is expected to be.
    assertThat(estimate.cached()).isEqualTo(2);
    assertThat(estimate.toAsk()).isEqualTo(3);
    assertThat(estimate.outputTokens()).isEqualTo(300);
    assertThat(estimate.cacheWriteTokens()).isEqualTo(10);
    assertThat(estimate.cacheReadTokens()).isEqualTo(20);
    assertThat(estimate.usd()).isGreaterThan(BigDecimal.ZERO);
    assertThat(estimate.line("summarize")).startsWith("summarize: 5 items, 2 cached, 3 to ask;");
    assertThat(harness.records().getFirst().estimate()).isEqualTo(estimate);
  }
}
