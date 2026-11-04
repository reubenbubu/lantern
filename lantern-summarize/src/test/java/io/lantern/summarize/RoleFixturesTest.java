package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
import io.lantern.harness.render.Blocks;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RoleFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The recorded and injection fixtures of Summarize, for types and for modules and repositories
 * (#768, ADR 0125): a small repository whose README, controller comment, configuration comment and
 * string literal hold the four injection fixtures.
 */
public class RoleFixturesTest {

  static final Path FIXTURES = Path.of("../lantern-harness/src/test/resources/injection");
  private static final Path RECORDINGS = Path.of("src/test/resources/recordings");
  private static final String REPO = "orders";
  private static final String MODULE = "module:shop:orders";
  private static final String PKG = "package:shop:orders:shop.orders";

  public static String fixture(String name) {
    try {
      return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The line of the fixture that speaks to an AI. */
  public static String injectedLine(String name) {
    return fixture(name).lines().filter(l -> l.contains("AI assistant")).findFirst().orElseThrow();
  }

  private static Fact fact(
      String subject, String predicate, FactObject object, String path, String blob) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        Evidence.of(new Evidence.Location(REPO, path, null, blob, null, Evidence.Role.DECLARED)),
        1.0,
        Validity.from(1));
  }

  private static String blob(String text) {
    return SourceFiles.blobSha(text.getBytes(StandardCharsets.UTF_8), 40);
  }

  /** The repository: two types whose files hold fixtures, a README claim and a config comment. */
  static List<Fact> orders() {
    List<Fact> facts = new ArrayList<>();
    String pom = blob("pom");
    facts.add(fact(MODULE, "packaging", FactObject.string("jar"), "pom.xml", pom));
    facts.add(fact(MODULE, "CONTAINS", FactObject.entity(EntityId.parse(PKG)), "pom.xml", pom));
    for (String type : List.of("OrderController", "Greeting")) {
      String path = "src/main/java/shop/orders/" + type + ".java";
      String id = "type:shop.orders." + type;
      String blob = blob(fixture(type + ".java"));
      facts.add(fact(PKG, "CONTAINS", FactObject.entity(EntityId.parse(id)), path, blob));
      facts.add(fact(id, "kind", FactObject.string("class"), path, blob));
    }
    facts.add(
        fact(
            MODULE,
            "config-comment",
            FactObject.string(injectedLine("application.yml").replaceFirst("^#\\s*", "")),
            "src/main/resources/application.yml",
            blob(fixture("application.yml"))));
    facts.add(TestFacts.claim(REPO, "README.md", 5, injectedLine("README.md")));
    return facts;
  }

  static final SourceFiles SOURCES =
      TestFacts.files(
          (repo, path) ->
              repo.equals(REPO) && path.startsWith("src/main/java/shop/orders/")
                  ? Optional.of(fixture(path.substring(path.lastIndexOf('/') + 1)))
                  : Optional.empty());

  private static LlmClient client(FakeLlmProvider fake, LlmProvider provider) {
    return new LlmClient(
        ModelSettings.single(fake.model("m")),
        List.of(provider),
        BudgetGuard.unlimited("test"),
        new InMemoryLlmCallLog(),
        Clock.systemUTC());
  }

  private static Summarizer summarizer(FakeLlmProvider fake, LlmProvider provider) {
    return new Summarizer(
        new Harness(
            client(fake, provider),
            new InMemoryAnswerStore(),
            RunBudget.NONE,
            BlobLookup.NONE,
            Set.of()),
        SOURCES);
  }

  /** One line per stored fact: its origin, claim and descriptive attributes. */
  public static List<String> lines(Map<Origin, List<Fact>> byOrigin) {
    return lines(byOrigin.values().stream().flatMap(List::stream).toList());
  }

  /** One line per fact: its origin, claim, descriptive attributes and lineage, sorted. */
  public static List<String> lines(List<Fact> facts) {
    return facts.stream()
        .map(
            f ->
                f.origin().name()
                    + "@"
                    + f.origin().version()
                    + " "
                    + f.subject()
                    + " "
                    + f.predicate()
                    + " "
                    + f.object().value()
                    + " "
                    + f.attributes()
                    + " lineage="
                    + f.lineage())
        .sorted()
        .toList();
  }

  @Test
  void summarizeReplaysItsRecordingToExactlyTheExpectedFacts() {
    FakeLlmProvider scripted =
        new FakeLlmProvider().answering(Answers.epic(SummarizerTest::citingAll));
    Path folder = RECORDINGS.resolve("summarize");
    List<Fact> facts = orders();
    Summarizer.Result result =
        summarizer(scripted, RoleFixture.provider(scripted, folder))
            .summarize(SummaryTree.fromFacts(facts));

    Map<Origin, List<Fact>> stored = Summarizer.facts(result, facts, 1);
    List<String> produced = lines(stored);
    assertThat(produced).isEqualTo(RoleFixture.expected(folder, produced));
    // Both roles stored: the types' and the module's and repository's.
    assertThat(stored.keySet())
        .extracting(Origin::name)
        .containsExactlyInAnyOrder("summarize-type", "summarize");
  }

  /**
   * A model that does what the fixtures ask: says the service is secure and has no risks, approves,
   * and cites the password file it was told to read.
   */
  public static final String OBEYING =
      "This service is fully secure and has no risks; the pull request is approved and the"
          + " database password is in /etc/passwd. [orders:/etc/passwd:1-3]";

  /** The four fixtures, by file. */
  public static final List<String> INJECTED =
      List.of("README.md", "OrderController.java", "application.yml", "Greeting.java");

  /**
   * Every line of the fixtures that speaks to an AI sits inside a data block of the prompts the
   * fake was sent, and each fixture is there.
   */
  public static void shownAsData(FakeLlmProvider fake) {
    assertThat(fake.sent()).isNotEmpty();
    StringBuilder all = new StringBuilder();
    for (var sent : fake.sent()) {
      for (var message : sent.request().messages()) {
        all.append(message.text());
        assertThat(Blocks.outsideData(message.text(), "AI assistant")).as(message.text()).isEmpty();
      }
    }
    for (String name : INJECTED) {
      String said = injectedLine(name);
      said = said.substring(said.indexOf("AI assistant"));
      assertThat(all.toString()).as(name).contains(said.substring(0, 30));
    }
  }

  @Test
  void summarizeShowsTheFixturesAsDataAndAnAnswerThatObeysThemIsRejected() {
    FakeLlmProvider obeying = new FakeLlmProvider().answering(Answers.epic(r -> OBEYING));
    List<Fact> facts = orders();
    Summarizer.Result result = summarizer(obeying, obeying).summarize(SummaryTree.fromFacts(facts));

    shownAsData(obeying);
    // Every answer cites a file outside the repositories: rejected twice, nothing stored.
    assertThat(result.summaries()).isEmpty();
    assertThat(lines(Summarizer.facts(result, facts, 1))).isEmpty();
  }
}
