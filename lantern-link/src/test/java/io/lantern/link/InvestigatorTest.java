package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.Capability;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmConversation;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Pricing;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.Role;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Investigate role (ADR 0084), with a fake model and recorded answers; never a real one. Two
 * units, orders and billing, and the files a model reads and cites: orders publishes inside a
 * transaction, billing trusts a header, and orders calls a rates API no fact links it to.
 */
class InvestigatorTest {

  private static final String COMMIT = "a".repeat(40);

  private static final String ORDER_SERVICE =
      """
      package o;

      import org.springframework.transaction.annotation.Transactional;

      public class OrderService {

        @Transactional
        public void placeOrder(Order order) {
          orders.save(order);
          publisher.sendOrderCreated(order);
        }
      }
      """;

  private static final String PERMISSION_FILTER =
      """
      package b;

      public class PermissionFilter {
        void filter(Request request) {
          String user = request.getHeader("User-Id");
          chain.proceed(user);
        }
      }
      """;

  private static final String APPLICATION_YML =
      """
      spring:
        application:
          name: orders
      rates:
        url: https://api.rates.example.com/v1
        password: hunter2
      """;

  /** The risk a model finds in OrderService, citing lines 7 to 11. */
  private static final String BEFORE_COMMIT =
      "{\"kind\": \"risk\", \"title\": \"Event published before commit\", \"finding\":"
          + " \"placeOrder sends the event inside its transaction.\", \"consequence\":"
          + " \"Consumers can see an order that is rolled back.\", \"impact\": \"medium\","
          + " \"confidence\": \"high\", \"units\": [\"service:g:orders\"], \"names\":"
          + " [\"Transactional\", \"sendOrderCreated\"], \"cites\":"
          + " [\"a:src/main/java/o/OrderService.java:7-11\"]}";

  /** A connection to the rates API, which no fact links orders to. */
  private static final String RATES =
      "{\"kind\": \"connection\", \"from\": \"service:g:orders\", \"to\":"
          + " \"external-system:api.rates.example.com\", \"interaction\": \"http\","
          + " \"description\": \"Fetches rates from\", \"technology\": \"HTTPS\","
          + " \"confidence\": \"medium\", \"names\": [\"api.rates.example.com\"], \"cites\":"
          + " [\"a:src/main/resources/application.yml:4-5\"]}";

  private final List<Fact> facts = new ArrayList<>();
  private final Map<String, String> files = new HashMap<>();
  private final InMemoryInvestigationStore store = new InMemoryInvestigationStore();
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();

  private final GraphTools.RepoFiles repoFiles =
      (repo, path) ->
          Optional.ofNullable(files.get(repo + ":" + path))
              .map(s -> s.getBytes(StandardCharsets.UTF_8));

  InvestigatorTest() {
    files.put("a:src/main/java/o/OrderService.java", ORDER_SERVICE);
    files.put("a:src/main/resources/application.yml", APPLICATION_YML);
    files.put("b:src/main/java/b/PermissionFilter.java", PERMISSION_FILTER);
    unit("a", "orders");
    type("a", "orders", "o.OrderService");
    unit("b", "billing");
    type("b", "billing", "b.PermissionFilter");
    // orders calls billing, a link static matching found.
    add(
        CrossRepoLinks.NAME,
        "service:g:orders",
        CrossRepoLinks.CALLS_HTTP,
        FactObject.entity(EntityId.parse("service:g:billing")),
        "{\"protocol\":\"HTTP\"}",
        "a:src/main/java/o/OrderService.java#10");
  }

  // ---------------------------------------------------------------------------------------------
  // Facts and the model

  private void add(
      String origin, String subject, String predicate, FactObject object, String json, String at) {
    ObjectNode attributes = json == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(json);
    String repo = at.substring(0, at.indexOf(':'));
    String rest = at.substring(at.indexOf(':') + 1);
    Evidence.LineRange lines = null;
    if (rest.contains("#")) {
      int line = Integer.parseInt(rest.substring(rest.indexOf('#') + 1));
      lines = new Evidence.LineRange(line, line);
      rest = rest.substring(0, rest.indexOf('#'));
    }
    facts.add(
        Fact.create(
            EntityId.parse(subject),
            predicate,
            object,
            attributes,
            new Origin(Origin.Kind.STATIC, origin, "1"),
            new Evidence(repo, COMMIT, rest, lines, null),
            1.0,
            Validity.from(1)));
  }

  private void unit(String repo, String artifact) {
    add(
        MavenExtractor.NAME,
        "service:g:" + artifact,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo + ":pom.xml");
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo + ":pom.xml");
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.APPLICATION_NAME,
        FactObject.string(artifact),
        "{\"module\":\"module:g:" + artifact + "\"}",
        repo + ":src/main/resources/application.yml#3");
  }

  private void type(String repo, String module, String type) {
    String pkg = type.substring(0, type.lastIndexOf('.'));
    add(
        JavaExtractor.NAME,
        "module:g:" + module,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("package:g:" + module + ":" + pkg)),
        null,
        repo + ":src/main/java");
    add(
        JavaExtractor.NAME,
        "package:g:" + module + ":" + pkg,
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("type:" + type)),
        null,
        repo + ":src/main/java/" + type.replace('.', '/') + ".java#1");
  }

  private LlmClient client(LlmProvider provider, ModelConfig model) {
    return new LlmClient(
        ModelSettings.single(model),
        List.of(provider),
        BudgetGuard.unlimited("test"),
        log,
        Clock.systemUTC());
  }

  private Investigator.Result investigate(LlmProvider provider) {
    return investigate(provider, new FakeLlmProvider(provider.name()).model("m"));
  }

  private Investigator.Result investigate(LlmProvider provider, ModelConfig model) {
    Investigator.Result result =
        new Investigator(
                client(provider, model), store, repoFiles, Map.of("a", COMMIT, "b", COMMIT))
            .investigate(facts, 1);
    result.facts().forEach(vocabulary()::validate);
    return result;
  }

  static Vocabulary vocabulary() {
    Vocabulary base = DeployableUnitsTest.vocabulary();
    Map<String, VocabularyType> types = new TreeMap<>();
    base.types().forEach(t -> types.put(t.name(), t));
    base.changes(Investigator.vocabulary()).forEach(t -> types.put(t.name(), t));
    return new Vocabulary(base.revision() + 1, types.values());
  }

  /**
   * A model that answers the system's prompt with the given answers in turn (the first, then the
   * one after a reply) and proposes nothing for a unit.
   */
  private static FakeLlmProvider proposing(String... answers) {
    return new FakeLlmProvider()
        .answering(
            request -> {
              if (!isSystem(request)) {
                return "{\"proposals\": []}";
              }
              long replies =
                  request.messages().stream()
                      .filter(m -> m.author() == io.lantern.llm.LlmMessage.Author.ASSISTANT)
                      .count();
              return answers[(int) Math.min(replies, answers.length - 1)];
            });
  }

  private static boolean isSystem(LlmRequest request) {
    return request.purpose().startsWith("investigate the system");
  }

  private static String proposals(String... proposals) {
    return "{\"proposals\": [" + String.join(", ", proposals) + "]}";
  }

  private static Fact only(Investigator.Result result, String predicate) {
    List<Fact> found =
        result.facts().stream().filter(f -> f.predicate().equals(predicate)).toList();
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  // ---------------------------------------------------------------------------------------------
  // Cases

  @Test
  void eachUnitAndTheSystemAreInvestigatedWithTheirFactsAndTheTools() {
    FakeLlmProvider fake = proposing(proposals());

    Investigator.Result result = investigate(fake);

    assertThat(result.asked()).isEqualTo(3);
    assertThat(result.facts()).isEmpty();
    assertThat(fake.sent())
        .extracting(s -> s.request().purpose())
        .containsExactlyInAnyOrder(
            "investigate service:g:billing",
            "investigate service:g:orders",
            "investigate the system");
    LlmRequest orders =
        fake.sent().stream()
            .map(s -> s.request())
            .filter(r -> r.purpose().equals("investigate service:g:orders"))
            .findFirst()
            .orElseThrow();
    assertThat(orders.role()).isEqualTo(Role.INVESTIGATE);
    assertThat(orders.system()).isEqualTo(InvestigatePrompt.SYSTEM);
    assertThat(orders.tools())
        .extracting(io.lantern.llm.LlmTool::name)
        .containsExactly("facts_about", "search_facts", "read_file");
    assertThat(orders.tools().get(2).description()).contains("Cite the lines it shows");
    String user = orders.messages().getFirst().text();
    assertThat(user)
        .startsWith("Investigate the deployable unit service:g:orders.")
        .contains("- service:g:orders, named orders, in the repository a")
        .contains("The repositories, as the facts and the tools name them: a, b.")
        .contains(
            "service:g:orders CALLS_HTTP service:g:billing {\"protocol\":\"HTTP\"}"
                + " (a:src/main/java/o/OrderService.java)")
        // No fact IDs or lines, which change with every commit.
        .doesNotContain(facts.getFirst().id())
        .doesNotContain("#10");
  }

  @Test
  void aVerifiedRiskBecomesAFactOfTheLlmWithItsCitationsAsEvidence() {
    Investigator.Result result = investigate(proposing(proposals(BEFORE_COMMIT)));

    Fact risk = only(result, Investigator.RISK);
    assertThat(risk.subject()).isEqualTo(EntityId.parse("service:g:orders"));
    assertThat(risk.object().value()).isEqualTo("Event published before commit");
    assertThat(risk.origin())
        .isEqualTo(new Origin(Origin.Kind.LLM, Investigator.NAME, InvestigatePrompt.VERSION));
    assertThat(risk.confidence()).isEqualTo(0.6);
    assertThat(risk.evidence().repo()).isEqualTo("a");
    assertThat(risk.evidence().commit()).isEqualTo(COMMIT);
    assertThat(risk.evidence().path()).isEqualTo("src/main/java/o/OrderService.java");
    assertThat(risk.evidence().lines()).isEqualTo(new Evidence.LineRange(7, 11));
    assertThat(risk.evidence().contentHash()).hasSize(64);
    assertThat(risk.attributes().get("cites").get(0).asString())
        .isEqualTo("a:src/main/java/o/OrderService.java:7-11");
    assertThat(risk.attributes().get("impact").asString()).isEqualTo("medium");
    assertThat(risk.attributes().get("scope").asString()).isEqualTo("system");
    assertThat(risk.attributes().get("model").asString()).isEqualTo("fake/m");
    assertThat(result.rejected()).isZero();
    assertThat(log.conversations())
        .filteredOn(c -> c.purpose().equals("investigate the system"))
        .singleElement()
        .satisfies(c -> assertThat(c.ending()).isEqualTo(LlmConversation.Ending.ANSWERED));
  }

  @Test
  void aNewConnectionIsAcceptedAndOneTheFactsAlreadyLinkIsDropped() {
    String billing =
        "{\"kind\": \"connection\", \"from\": \"service:g:orders\", \"to\":"
            + " \"service:g:billing\", \"interaction\": \"http\", \"description\": \"Calls\","
            + " \"confidence\": \"high\", \"names\": [\"sendOrderCreated\"], \"cites\":"
            + " [\"a:src/main/java/o/OrderService.java:10-10\"]}";

    Investigator.Result result = investigate(proposing(proposals(RATES, billing)));

    Fact connection = only(result, Investigator.CONNECTION);
    assertThat(connection.subject()).isEqualTo(EntityId.parse("service:g:orders"));
    assertThat(connection.object().value()).isEqualTo("external-system:api.rates.example.com");
    assertThat(connection.attributes().get("description").asString())
        .isEqualTo("Fetches rates from");
    assertThat(connection.attributes().get("technology").asString()).isEqualTo("HTTPS");
    assertThat(connection.attributes().get("link").asString()).hasSize(16);
    assertThat(connection.confidence()).isEqualTo(0.45);
    // Static extraction wins: the call to billing is linked already.
    assertThat(result.dropped()).isEqualTo(1);
  }

  @Test
  void anUncitedOrMisCitedProposalIsSentBackOnceAndDroppedIfItFailsAgain() {
    String uncited =
        "{\"kind\": \"risk\", \"title\": \"Header trusted\", \"finding\": \"It trusts User-Id.\","
            + " \"consequence\": \"Anyone can act as anyone.\", \"impact\": \"high\","
            + " \"confidence\": \"high\", \"units\": [\"service:g:billing\"], \"names\":"
            + " [\"User-Id\"], \"cites\": []}";
    String misCited =
        "{\"kind\": \"risk\", \"title\": \"Header trusted twice\", \"finding\": \"It trusts"
            + " User-Id.\", \"consequence\": \"Anyone can act as anyone.\", \"impact\": \"high\","
            + " \"confidence\": \"high\", \"units\": [\"service:g:billing\"], \"names\":"
            + " [\"User-Id\"], \"cites\": [\"b:src/main/java/b/PermissionFilter.java:1-3\"]}";
    String pastTheEnd = misCited.replace("1-3", "7-12").replace("twice", "thrice");
    String noSuchFile =
        misCited.replace("PermissionFilter.java", "Missing.java").replace("twice", "again");
    String secret =
        RATES
            .replace("api.rates.example.com\"]", "hunter2\"]")
            .replace("4-5", "6-6")
            .replace("Fetches rates from", "Logs in with");
    String unknownUnit =
        BEFORE_COMMIT
            .replace("service:g:orders", "service:g:nowhere")
            .replace("Event published before commit", "Event lost");
    String corrected = misCited.replace("1-3", "5-5");

    FakeLlmProvider fake =
        proposing(
            proposals(
                BEFORE_COMMIT, uncited, misCited, pastTheEnd, noSuchFile, secret, unknownUnit),
            proposals(corrected, uncited));
    Investigator.Result result = investigate(fake);

    String retry =
        fake.sent().stream()
            .map(s -> s.request())
            .filter(r -> r.purpose().equals("investigate the system (retry)"))
            .findFirst()
            .orElseThrow()
            .messages()
            .getLast()
            .text();
    assertThat(retry)
        .startsWith("Some proposals were rejected:")
        .contains("- risk \"Header trusted\": it cites no lines of the code.")
        .contains(
            "- risk \"Header trusted twice\": the lines b:src/main/java/b/PermissionFilter.java:1-3"
                + " hold none of its names (User-Id); cite the lines that show them.")
        .contains(
            "- risk \"Header trusted thrice\": it cites"
                + " b:src/main/java/b/PermissionFilter.java:7-12, but"
                + " src/main/java/b/PermissionFilter.java has lines 1 to 8.")
        .contains(
            "- risk \"Header trusted again\": it cites b:src/main/java/b/Missing.java:1-3, but"
                + " there is no file src/main/java/b/Missing.java in b that can be read.")
        // A masked secret never holds: the model never saw it either.
        .contains("hold none of its names (hunter2)")
        .contains("service:g:nowhere is not a deployable unit; the units are listed in the prompt.")
        .doesNotContain("Event published before commit");
    assertThat(result.facts())
        .extracting(f -> f.object().value())
        .containsExactlyInAnyOrder("Event published before commit", "Header trusted twice");
    Fact header =
        result.facts().stream()
            .filter(f -> f.object().value().equals("Header trusted twice"))
            .findFirst()
            .orElseThrow();
    assertThat(header.evidence().lines()).isEqualTo(new Evidence.LineRange(5, 5));
    // Six were sent back and one corrected: the uncited one again, the four not answered again.
    assertThat(result.rejected()).isEqualTo(5);
    // Each conversation asked once and replied at most once.
    assertThat(fake.sent()).hasSize(4);
  }

  @Test
  void aConnectionToWhatNoDiagramDrawsOrAKeywordNameIsSentBack() {
    String toAType =
        RATES
            .replace("external-system:api.rates.example.com", "type:o.OrderService")
            .replace("Fetches rates from", "Uses");
    String keyword =
        BEFORE_COMMIT
            .replace("[\"Transactional\", \"sendOrderCreated\"]", "[\"public\"]")
            .replace("Event published before commit", "Vague");
    FakeLlmProvider fake = proposing(proposals(toAType, keyword), proposals());

    Investigator.Result result = investigate(fake);

    String retry =
        fake.sent().stream()
            .map(s -> s.request())
            .filter(r -> r.purpose().equals("investigate the system (retry)"))
            .findFirst()
            .orElseThrow()
            .messages()
            .getLast()
            .text();
    assertThat(retry)
        .contains(
            "type:o.OrderService is not a deployable unit, data store, topic, queue, exchange or"
                + " external system the facts hold")
        .contains("the name \"public\" is a keyword or word that holds on almost any line");
    assertThat(result.facts()).isEmpty();
    assertThat(result.rejected()).isEqualTo(2);
  }

  @Test
  void theSameRiskTitleForTwoUnitsIsTwoRisks() {
    String billing =
        BEFORE_COMMIT
            .replace("service:g:orders", "service:g:billing")
            .replace("[\"Transactional\", \"sendOrderCreated\"]", "[\"User-Id\"]")
            .replace(
                "a:src/main/java/o/OrderService.java:7-11",
                "b:src/main/java/b/PermissionFilter.java:5-5");

    Investigator.Result result = investigate(proposing(proposals(BEFORE_COMMIT, billing)));

    assertThat(result.facts())
        .extracting(f -> f.subject().toString())
        .containsExactlyInAnyOrder("service:g:orders", "service:g:billing");
    assertThat(result.dropped()).isZero();
  }

  @Test
  void anAnswerThatIsNotJsonIsSentBackOnceThenTheConversationIsRejected() {
    FakeLlmProvider fake = proposing("I found nothing worth saying.", "Still nothing.");

    Investigator.Result result = investigate(fake);

    assertThat(result.facts()).isEmpty();
    assertThat(
            fake.sent().stream()
                .map(s -> s.request())
                .filter(r -> r.purpose().equals("investigate the system (retry)"))
                .findFirst()
                .orElseThrow()
                .messages()
                .getLast()
                .text())
        .isEqualTo(
            "Your answer was rejected: it is not one JSON object.\n"
                + "Answer again with one JSON object in the form asked for.");
    assertThat(log.conversations())
        .filteredOn(c -> c.purpose().equals("investigate the system"))
        .singleElement()
        .satisfies(c -> assertThat(c.ending()).isEqualTo(LlmConversation.Ending.REJECTED));
  }

  @Test
  void verifiedProposalsAreReusedWhileTheirCitationsHoldAndAskedAgainWhenAFileChanges() {
    Investigator.Result first = investigate(proposing(proposals(BEFORE_COMMIT)));
    FakeLlmProvider unused = proposing(proposals(BEFORE_COMMIT));

    Investigator.Result second = investigate(unused);

    assertThat(unused.sent()).isEmpty();
    assertThat(second.reused()).isEqualTo(3);
    assertThat(second.facts()).isEqualTo(first.facts());

    // The cited lines no longer hold sendOrderCreated, though no fact changed.
    files.put(
        "a:src/main/java/o/OrderService.java", ORDER_SERVICE.replace("sendOrderCreated", "send"));
    FakeLlmProvider again = proposing(proposals());

    Investigator.Result third = investigate(again);

    assertThat(again.sent())
        .extracting(s -> s.request().purpose())
        .containsExactly("investigate the system");
    assertThat(third.facts()).isEmpty();
  }

  @Test
  void aModelThatCannotUseToolsIsNotAsked() {
    FakeLlmProvider fake = proposing(proposals(BEFORE_COMMIT));
    ModelConfig noTools =
        new ModelConfig("fake", "m", EnumSet.noneOf(Capability.class), 100_000, Pricing.FREE);

    Investigator.Result result = investigate(fake, noTools);

    assertThat(fake.sent()).isEmpty();
    assertThat(result.facts()).isEmpty();
  }

  @Test
  void aBudgetThatRefusesTheCallSkipsTheInvestigationAndCachesNothing() {
    FakeLlmProvider fake = proposing(proposals(BEFORE_COMMIT));
    ModelConfig priced =
        new ModelConfig(
            "fake",
            "m",
            EnumSet.allOf(Capability.class),
            1_000_000,
            new Pricing(
                new java.math.BigDecimal("3"),
                new java.math.BigDecimal("15"),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO));
    LlmClient client =
        new LlmClient(
            ModelSettings.single(priced),
            List.of(fake),
            new BudgetGuard("test", null, java.math.BigDecimal.ZERO),
            log,
            Clock.systemUTC());

    Investigator.Result result =
        new Investigator(client, store, repoFiles, Map.of("a", COMMIT, "b", COMMIT))
            .investigate(facts, 1);

    assertThat(result.skipped())
        .containsExactlyInAnyOrder("service:g:billing", "service:g:orders", "system");
    assertThat(store.size()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // A recorded conversation

  /**
   * The recordings of {@link #readingTheCode()}'s conversation about the system, kept in the
   * repository in the record and replay format (ADR 0008): it looks up the facts about orders,
   * reads OrderService, then proposes the risk and the connection; the units' conversations propose
   * nothing.
   */
  private static final Path RECORDINGS = Path.of("src/test/resources/recordings/investigate-tools");

  private static LlmToolCall toolCall(String id, String name, String input) {
    return new LlmToolCall(id, name, FactJson.parse(input));
  }

  /** A model that looks up and reads before it proposes, as {@link #RECORDINGS} hold it. */
  private static FakeLlmProvider readingTheCode() {
    return proposing(proposals(BEFORE_COMMIT, RATES))
        .callingTools(
            r -> {
              if (!isSystem(r)) {
                return List.of();
              }
              return switch (r.messages().size()) {
                case 1 ->
                    List.of(
                        toolCall("toolu_01", "facts_about", "{\"entity\":\"service:g:orders\"}"));
                case 3 ->
                    List.of(
                        toolCall(
                            "toolu_02",
                            "read_file",
                            "{\"repo\":\"a\",\"path\":\"src/main/java/o/OrderService.java\"}"),
                        toolCall(
                            "toolu_03",
                            "read_file",
                            "{\"repo\":\"a\",\"path\":\"src/main/resources/application.yml\"}"));
                default -> List.of();
              };
            });
  }

  @Test
  void aRecordedMultiTurnInvestigationReplaysTurnByTurn() {
    if (Boolean.getBoolean("lantern.test.rerecord")) {
      // Rewrites the recordings from the scripted model above; no real model is called.
      FakeLlmProvider scripted = readingTheCode();
      new Investigator(
              new LlmClient(
                  ModelSettings.single(scripted.model("m")),
                  List.of(RecordReplayLlmProvider.recording(scripted, RECORDINGS)),
                  BudgetGuard.unlimited("test"),
                  new InMemoryLlmCallLog(),
                  Clock.systemUTC()),
              new InMemoryInvestigationStore(),
              repoFiles,
              Map.of("a", COMMIT, "b", COMMIT))
          .investigate(facts, 1);
    }

    Investigator.Result replayed =
        investigate(RecordReplayLlmProvider.replaying(FakeLlmProvider.NAME, RECORDINGS));

    assertThat(replayed.asked()).isEqualTo(3);
    assertThat(replayed.facts())
        .extracting(Fact::predicate, f -> f.object().value())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(Investigator.RISK, "Event published before commit"),
            org.assertj.core.groups.Tuple.tuple(
                Investigator.CONNECTION, "external-system:api.rates.example.com"));
    assertThat(log.conversations())
        .filteredOn(c -> c.purpose().equals("investigate the system"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.turns()).isEqualTo(3);
              assertThat(c.toolCalls())
                  .containsEntry("facts_about", 1)
                  .containsEntry("read_file", 2);
              assertThat(c.ending()).isEqualTo(LlmConversation.Ending.ANSWERED);
            });
  }
}
