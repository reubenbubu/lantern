package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.ModelSettings;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Investigate role (knowledge-build epic, section 11.2), with a fake model that submits through
 * the {@code submit} tool; never a real one.
 */
class InvestigatorTest {

  private static final String FILES_UNIT = "service:g:files";
  private static final String GATEWAY_UNIT = "service:g:gateway";
  private static final String CONTROLLER = "src/main/java/f/FileController.java";
  private static final String STORAGE = "src/main/java/f/StorageClient.java";
  private static final String GATEWAY_CONFIG = "src/main/resources/application.properties";

  /** The test's files; a test that edits one puts it back. */
  private static final Map<String, String> TEXTS =
      new java.util.concurrent.ConcurrentHashMap<>(
          Map.of(
              "fs:" + CONTROLLER,
              """
          package f;

          @RestController
          class FileController {
            @PostMapping("/files")
            String upload(@RequestHeader("X-User-Id") String userId, MultipartFile file) {
              return storage.save(userId, file);
            }
          }
          """,
              "gw:" + GATEWAY_CONFIG,
              """
          spring.cloud.gateway.routes[0].uri=lb://files
          spring.cloud.gateway.routes[0].predicates[0]=Path=/files/**
          """,
              "fs:" + STORAGE,
              """
          package f;
          class StorageClient {
            String bucket = restClient.post().uri("https://s3.example.com/bucket");
          }
          """));

  private static final GraphTools.RepoFiles FILES =
      new GraphTools.RepoFiles() {
        @Override
        public Optional<byte[]> read(String repo, String path) {
          return Optional.ofNullable(TEXTS.get(repo + ":" + path))
              .map(text -> text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public List<GraphTools.Hit> find(String repo, String text, int max) {
          return List.of();
        }
      };

  private final List<Fact> facts = new ArrayList<>();
  private final InMemoryAnswerStore store = new InMemoryAnswerStore();
  private final InMemoryLlmCallLog log = new InMemoryLlmCallLog();

  // ---------------------------------------------------------------------------------------------
  // Facts

  private Fact add(
      String origin,
      String subject,
      String predicate,
      FactObject object,
      String attributes,
      String repo,
      String path,
      int line) {
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    Fact fact =
        Fact.create(
            EntityId.parse(subject),
            predicate,
            object,
            json,
            new Origin(Origin.Kind.STATIC, origin, "1"),
            Evidence.of(repo, path, line > 0 ? new Evidence.LineRange(line, line) : null, null),
            1.0,
            Validity.from(1));
    facts.add(fact);
    return fact;
  }

  private void unit(String repo, String artifact) {
    add(
        MavenExtractor.NAME,
        "service:g:" + artifact,
        Types.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo,
        "pom.xml",
        0);
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        Types.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo,
        "pom.xml",
        0);
  }

  /** The files unit reads the caller's user from a header, and derivation raised a lead on it. */
  private Fact headerLead() {
    return headerLead("identity-from-request");
  }

  /** As {@link #headerLead()}, the lead of the given kind. */
  private Fact headerLead(String kind) {
    unit("gw", "gateway");
    unit("fs", "files");
    Fact read =
        add(
            "spring",
            "method:f.FileController#upload()",
            "reads-request-value",
            FactObject.string("X-User-Id"),
            "{\"source\":\"header\"}",
            "fs",
            CONTROLLER,
            6);
    ObjectNode attributes = FactJson.newObject();
    attributes.put("trigger", "The header X-User-Id is read by the code of files");
    attributes.putArray("units").add(FILES_UNIT);
    attributes.putArray("code").add("method:f.FileController#upload()");
    attributes.put("content", "sha256:abc");
    attributes.putArray("facts").add(read.id());
    return add(
        "lead-identity-from-request",
        "lead:" + kind + "/" + FILES_UNIT + "/header:X-User-Id",
        Types.LEAD_TRIGGER,
        FactObject.string(kind),
        attributes.toString(),
        "fs",
        CONTROLLER,
        6);
  }

  // ---------------------------------------------------------------------------------------------
  // A model that answers through submit

  private static boolean leads(LlmRequest r) {
    return r.system().contains("Task: check leads.");
  }

  private static boolean unit(LlmRequest r, String unit) {
    return !leads(r)
        && r.messages().getFirst().text().contains("The deployable unit " + unit + ",");
  }

  private static int turn(LlmRequest request) {
    return (request.messages().size() + 1) / 2;
  }

  private static LlmToolCall submit(LlmRequest request, String items) {
    return new LlmToolCall(
        "s" + turn(request), "submit", FactJson.parse("{\"items\": [" + items + "]}"));
  }

  /**
   * A model that submits, turn by turn, what the function gives, and ends on null; in the same
   * turn, before it submits, it reads each range it cites, as a model must (#715).
   */
  private static FakeLlmProvider submitting(Function<LlmRequest, String> items) {
    return new FakeLlmProvider()
        .callingTools(
            r -> {
              String answer = items.apply(r);
              if (answer == null) {
                return List.of();
              }
              List<LlmToolCall> calls = new ArrayList<>(reads(answer));
              calls.add(submit(r, answer));
              return calls;
            })
        .answering("Done.");
  }

  /** A model that submits what the function gives without reading what it cites. */
  private static FakeLlmProvider submittingUnread(Function<LlmRequest, String> items) {
    return new FakeLlmProvider()
        .callingTools(
            r -> {
              String answer = items.apply(r);
              return answer == null ? List.of() : List.of(submit(r, answer));
            })
        .answering("Done.");
  }

  /** The file ranges an answer cites, as quoted strings: {@code "repo:path:from-to"}. */
  private static final java.util.regex.Pattern CITED_RANGE =
      java.util.regex.Pattern.compile("\"([^\":\\s]+):([^\":\\s]+):(\\d+)(?:-(\\d+))?\"");

  /** A read_file call for each range the answer cites, so the conversation was shown them. */
  private static List<LlmToolCall> reads(String answer) {
    List<LlmToolCall> reads = new ArrayList<>();
    java.util.regex.Matcher range = CITED_RANGE.matcher(answer);
    while (range.find()) {
      String to = range.group(4) == null ? range.group(3) : range.group(4);
      reads.add(
          new LlmToolCall(
              "r" + reads.size(),
              "read_file",
              FactJson.parse(
                  "{\"repo\":\""
                      + range.group(1)
                      + "\",\"path\":\""
                      + range.group(2)
                      + "\",\"from\":"
                      + range.group(3)
                      + ",\"to\":"
                      + to
                      + "}")));
    }
    return reads;
  }

  private static final String CONFIRMED =
      """
      {"lead": "I1", "verdict": "confirmed", "kind": "risk",
       "title": "Files trust the caller's user header",
       "finding": "upload takes the user from the X-User-Id header as the caller sent it, and stores the file under that user without checking it.",
       "consequence": "Any caller can store files as any user, so one user's files can be overwritten or read by another who sets the header.",
       "impact": "high", "confidence": "high", "units": ["service:g:files"],
       "names": ["X-User-Id", "upload"], "cites": ["fs:src/main/java/f/FileController.java:5-7"]}""";

  private static final String REFUTED_FINDING =
      "upload reads X-User-Id only after the gateway has removed the caller's own header and set"
          + " it from the verified token.";

  private static final String MISCITED =
      """
      {"lead": "I1", "verdict": "confirmed", "kind": "risk",
       "title": "Files trust the caller's user header",
       "finding": "upload takes the user from a header that the caller sends, and stores the file as that user without checking.",
       "consequence": "Any caller can store files as any user, so one user's files can be overwritten or read by another who sets the header.",
       "impact": "high", "confidence": "high", "units": ["service:g:files"],
       "names": ["X-User-Id"], "cites": ["fs:src/main/java/f/FileController.java:1-2"]}""";

  private LlmClient client(LlmProvider provider) {
    return new LlmClient(
        ModelSettings.single(new FakeLlmProvider(provider.name()).model("m")),
        List.of(provider),
        BudgetGuard.unlimited("test"),
        log,
        Clock.systemUTC());
  }

  private Harness harness;
  private io.lantern.harness.Harness.Calibration harnessCalibration;

  private Investigator.Result investigate(LlmClient client) {
    harness =
        new Harness(
            client,
            store,
            RunBudget.NONE,
            (repo, path) -> FILES.read(repo, path).map(io.lantern.harness.Fingerprints::gitBlob),
            Set.of());
    if (harnessCalibration != null) {
      harness.calibrating("investigate-leads", harnessCalibration);
    }
    return new Investigator(
            harness,
            FILES,
            Map.of(
                "identity-from-request", "Whether anything verifies it.",
                "plaintext-secret", "Whether the secret is kept in the code."))
        .investigate(facts, 1);
  }

  private Investigator.Result investigate(FakeLlmProvider fake) {
    return investigate(client(fake));
  }

  private static List<Fact> of(Investigator.Result result, String predicate) {
    return result.facts().stream().filter(f -> f.predicate().equals(predicate)).toList();
  }

  private double riskConfidence(String confidence) {
    headerLead();
    String answer =
        CONFIRMED.replace("\"confidence\": \"high\"", "\"confidence\": \"" + confidence + "\"");
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? answer : null);
    return of(investigate(fake), Types.INVESTIGATED_RISK).getFirst().confidence();
  }

  // ---------------------------------------------------------------------------------------------
  // Cases

  @Test
  void aLeadIsShownWithWhatToJudgeItsFactsAndTheCodeItPointsAt() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> null);

    investigate(fake);

    LlmRequest request =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(InvestigatorTest::leads)
            .findFirst()
            .orElseThrow();
    String user = request.messages().getFirst().text();
    assertThat(request.system()).contains(InvestigatePrompt.LEADS_TASK, InvestigatePrompt.SCHEMA);
    assertThat(user)
        .contains("Lead lead:identity-from-request/service:g:files/header:X-User-Id")
        .contains("What to judge: Whether anything verifies it.")
        .contains("<code repo=\"fs\" path=\"" + CONTROLLER + "\" lines=\"3-9\">")
        .contains("6    String upload(@RequestHeader(\"X-User-Id\")")
        .contains("- " + FILES_UNIT);
    assertThat(request.tools().stream().map(t -> t.name()).toList())
        .containsExactlyInAnyOrder("facts_about", "read_file", "find_in_code", "submit");
  }

  @Test
  void anExhaustedBudgetStopsInvestigateWithoutFailingAndALaterRunAsks() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);
    LlmClient spent =
        new LlmClient(
            ModelSettings.single(new FakeLlmProvider(fake.name()).model("m")),
            List.of(fake),
            new BudgetGuard("run", 100L, null),
            log,
            Clock.systemUTC());

    Investigator.Result stopped = investigate(spent);

    // Nothing is sent or stored; the lead is unclear, not asked, and the run goes on (#769).
    assertThat(fake.sent()).isEmpty();
    assertThat(of(stopped, Types.INVESTIGATED_RISK)).isEmpty();
    assertThat(of(stopped, Types.LEAD_VERDICT))
        .singleElement()
        .satisfies(
            v -> {
              assertThat(v.object().value()).isEqualTo("unclear");
              assertThat(Claims.json(v).path("reason").asString())
                  .startsWith("not asked: Budget 'run'");
            });
    assertThat(store.size()).isZero();
    assertThat(log.calls())
        .isNotEmpty()
        .allSatisfy(c -> assertThat(c.outcome()).isEqualTo(LlmCall.Outcome.REFUSED));

    // With budget left, a later run asks and confirms the lead.
    assertThat(of(investigate(fake), Types.INVESTIGATED_RISK)).hasSize(1);
  }

  @Test
  void theSameFactsMakeTheSameRequestsEveryTime() {
    headerLead();
    List<Fact> reversed = new ArrayList<>(facts);
    java.util.Collections.reverse(reversed);
    FakeLlmProvider one = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);
    FakeLlmProvider two = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);
    for (var run : List.of(Map.entry(one, List.copyOf(facts)), Map.entry(two, reversed))) {
      new Investigator(
              new Harness(
                  client(run.getKey()),
                  new InMemoryAnswerStore(),
                  RunBudget.NONE,
                  (repo, path) ->
                      FILES.read(repo, path).map(io.lantern.harness.Fingerprints::gitBlob),
                  Set.of()),
              FILES,
              Map.of(
                  "identity-from-request", "Whether anything verifies it.",
                  "plaintext-secret", "Whether the secret is kept in the code."))
          .investigate(run.getValue(), 1);
    }

    // Byte for byte, whatever order the facts come in (#769).
    Function<FakeLlmProvider, List<String>> requests =
        fake ->
            fake.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .map(r -> r.purpose() + "\n" + r.system() + "\n" + r.messages())
                .sorted()
                .toList();
    assertThat(requests.apply(two)).isEqualTo(requests.apply(one)).isNotEmpty();
  }

  @Test
  void aRiskOfMediumConfidenceHoldsItsFactor() {
    assertThat(riskConfidence("medium")).isEqualTo(0.45);
  }

  @Test
  void aRiskOfLowConfidenceHoldsItsFactor() {
    assertThat(riskConfidence("low")).isEqualTo(0.3);
  }

  @Test
  void aRiskKeyIsItsNamesSortedAndAConnectionKeyItsEndsAndInteraction() {
    tools.jackson.databind.JsonNode oneOrder =
        FactJson.parse(
            "{\"kind\": \"risk\", \"title\": \"A\", \"names\": [\"upload\", \" X-User-Id\"]}");
    tools.jackson.databind.JsonNode otherOrder =
        FactJson.parse(
            "{\"kind\": \"risk\", \"title\": \"B\", \"names\": [\"X-User-Id\", \"upload\"]}");
    tools.jackson.databind.JsonNode connection =
        FactJson.parse(
            "{\"kind\": \"connection\", \"from\": \"service:g:files\", \"to\": \"external-system:s3\","
                + " \"interaction\": \"http\", \"names\": [\"upload\"]}");
    tools.jackson.databind.JsonNode otherInteraction =
        FactJson.parse(
            "{\"kind\": \"connection\", \"from\": \"service:g:files\", \"to\": \"external-system:s3\","
                + " \"interaction\": \"messaging\", \"names\": [\"upload\"]}");

    // Two risks naming the same things are one, whatever their order or title (#769).
    assertThat(Investigator.proposalKey(oneOrder))
        .isEqualTo(Investigator.proposalKey(otherOrder))
        .isEqualTo("risk X-User-Id,upload");
    // A connection is keyed by its ends and interaction, not its names.
    assertThat(Investigator.proposalKey(connection))
        .isEqualTo("connection service:g:files external-system:s3 http")
        .isNotEqualTo(Investigator.proposalKey(otherInteraction));
  }

  @Test
  void aConfirmedLeadIsAVerdictAndARiskCitedToTheVerifiedLines() {
    Fact lead = headerLead();
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    Investigator.Result result = investigate(fake);

    Fact verdict = of(result, Types.LEAD_VERDICT).getFirst();
    assertThat(verdict.subject()).isEqualTo(lead.subject());
    assertThat(verdict.object().value()).isEqualTo("confirmed");
    assertThat(verdict.lineage())
        .containsExactly(lead.claimId() != null ? lead.claimId() : lead.id());
    Fact risk = of(result, Types.INVESTIGATED_RISK).getFirst();
    assertThat(risk.subject().toString()).isEqualTo(FILES_UNIT);
    assertThat(risk.origin())
        .isEqualTo(new Origin(Origin.Kind.LLM, "investigate", InvestigatePrompt.VERSION));
    assertThat(risk.attributes().path("title").asString())
        .isEqualTo("Files trust the caller's user header");
    assertThat(risk.attributes().path("impact").asString()).isEqualTo("high");
    assertThat(risk.attributes().path("mode").asString()).isEqualTo("leads");
    assertThat(risk.confidence()).isEqualTo(0.6);
    Evidence.Location cited = risk.evidence().locations().getFirst();
    assertThat(
            cited.repo()
                + ":"
                + cited.path()
                + ":"
                + cited.lines().start()
                + "-"
                + cited.lines().end())
        .isEqualTo("fs:" + CONTROLLER + ":5-7");
    assertThat(cited.blob()).isNotNull();
    assertThat(result.confirmed()).isEqualTo(1);
  }

  @Test
  void aVerdictCitingLinesOfTheLeadsExcerptIsKeptUnread() {
    // The request's own excerpt (lines 3-9) counts as shown: no read_file is needed (#715).
    headerLead();
    FakeLlmProvider fake = submittingUnread(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.LEAD_VERDICT))
        .singleElement()
        .satisfies(v -> assertThat(v.object().value()).isEqualTo("confirmed"));
    assertThat(result.confirmed()).isEqualTo(1);
  }

  @Test
  void aMiscitedVerdictIsSentBackAndACorrectedOneAccepted() {
    headerLead();
    FakeLlmProvider fake =
        submitting(
            r -> !leads(r) ? null : turn(r) == 1 ? MISCITED : turn(r) == 2 ? CONFIRMED : null);

    Investigator.Result result = investigate(fake);

    LlmRequest second =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(r -> leads(r) && turn(r) == 2)
            .findFirst()
            .orElseThrow();
    assertThat(second.messages().getLast().toolResults().getLast().content())
        .contains("rejected")
        .contains("X-User-Id");
    assertThat(of(result, Types.INVESTIGATED_RISK)).hasSize(1);
  }

  @Test
  void aVerdictMiscitedTwiceIsDroppedAndTheLeadUnclear() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) <= 2 ? MISCITED : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
    Fact verdict = of(result, Types.LEAD_VERDICT).getFirst();
    assertThat(verdict.object().value()).isEqualTo("unclear");
    assertThat(verdict.attributes().path("reason").asString()).contains("rejected twice");
  }

  @Test
  void anUnansweredLeadIsAskedAgainAloneOnceThenUnclear() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> null);

    Investigator.Result result = investigate(fake);

    long leadConversations =
        fake.sent().stream().map(c -> c.request()).filter(r -> leads(r) && turn(r) == 1).count();
    assertThat(leadConversations).isEqualTo(2);
    assertThat(of(result, Types.LEAD_VERDICT).getFirst().object().value()).isEqualTo("unclear");
    // The lead asked again is an item planned of its own, so the record counts each once.
    assertThat(harness.records())
        .filteredOn(r -> r.role().equals("investigate-leads"))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.planned()).isEqualTo(2);
              assertThat(r.settled()).isEqualTo(2);
            });
  }

  @Test
  void aLeadNamedByItsIdRatherThanItsItemIdIsStillAnswered() {
    Fact lead = headerLead();
    FakeLlmProvider fake =
        submitting(
            r ->
                leads(r) && turn(r) == 1
                    ? CONFIRMED.replace("\"I1\"", "\"" + lead.subject() + "\"")
                    : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.LEAD_VERDICT).getFirst().object().value()).isEqualTo("confirmed");
  }

  @Test
  void everyVerdictKeepsItsJustification() {
    headerLead();
    String refuted =
        CONFIRMED
            .replace("\"confirmed\"", "\"refuted\"")
            .replace(
                "upload takes the user from the X-User-Id header as the caller sent it, and stores the file"
                    + " under that user without checking it.",
                REFUTED_FINDING);
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? refuted : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.LEAD_VERDICT).getFirst().attributes().path("finding").asString())
        .isEqualTo(REFUTED_FINDING);
  }

  @Test
  void anUncitedProposalIsSentBack() {
    headerLead();
    String uncited =
        """
        {"kind": "risk", "title": "Uploads have no size limit",
         "finding": "upload accepts any file of any size and keeps it whole in memory before writing it to the disk.", "consequence": "A caller can fill the disk or the memory of the service with one large upload and stop it serving others.",
         "impact": "medium", "confidence": "low", "names": ["MultipartFile"], "cites": []}""";
    FakeLlmProvider fake = submitting(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? uncited : null);

    Investigator.Result result = investigate(fake);

    LlmRequest second =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(r -> unit(r, FILES_UNIT) && turn(r) == 2)
            .findFirst()
            .orElseThrow();
    assertThat(second.messages().getLast().toolResults().getLast().content()).contains("cites");
    assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
  }

  /** A risk of the files unit citing the given ranges. */
  private static String uploadRisk(String... cites) {
    return """
        {"kind": "risk", "title": "Uploads have no size limit",
         "finding": "upload accepts any MultipartFile of any size and keeps it whole in memory before writing it to the disk.", "consequence": "A caller can fill the disk or the memory of the service with one large upload and stop it serving others.",
         "impact": "medium", "confidence": "low", "names": ["MultipartFile"],
         "cites": [%s]}"""
        .formatted(
            String.join(", ", java.util.Arrays.stream(cites).map(c -> "\"" + c + "\"").toList()));
  }

  @Test
  void aRiskCitingLinesItReadIsKept() {
    headerLead();
    String risk = uploadRisk("fs:" + CONTROLLER + ":6-6");

    Investigator.Result result =
        investigate(submitting(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? risk : null));

    assertThat(of(result, Types.INVESTIGATED_RISK)).hasSize(1);
  }

  @Test
  void aRiskCitingLinesNoToolShowedIsSentBack() {
    // A cited range must be one the tools showed or an excerpt of the request gave (#715).
    headerLead();
    String risk = uploadRisk("fs:" + CONTROLLER + ":6-6");
    FakeLlmProvider fake = submittingUnread(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? risk : null);

    Investigator.Result result = investigate(fake);

    assertThat(secondTurn(fake))
        .contains("Cites fs:" + CONTROLLER + ":6-6, lines this conversation was not shown");
    assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
  }

  @Test
  void aRangeInAThirdRepositoryCountsOnlyIfTheConversationReadIt() {
    // The gateway's repository is neither the files unit's nor shown in its request: a range
    // there holds only once read_file showed it (#715).
    headerLead();
    String risk =
        uploadRisk("fs:" + CONTROLLER + ":6-6", "gw:" + GATEWAY_CONFIG + ":1-2")
            .replace(
                "\"names\": [\"MultipartFile\"]", "\"names\": [\"MultipartFile\", \"lb://files\"]");
    FakeLlmProvider readingOnlyItsOwn =
        new FakeLlmProvider()
            .callingTools(
                r ->
                    unit(r, FILES_UNIT) && turn(r) == 1
                        ? List.of(
                            new LlmToolCall(
                                "r1",
                                "read_file",
                                FactJson.parse(
                                    "{\"repo\":\"fs\",\"path\":\""
                                        + CONTROLLER
                                        + "\",\"from\":6,\"to\":6}")),
                            submit(r, risk))
                        : List.of())
            .answering("Done.");

    Investigator.Result unread = investigate(readingOnlyItsOwn);

    assertThat(secondTurn(readingOnlyItsOwn))
        .contains("Cites gw:" + GATEWAY_CONFIG + ":1-2, lines this conversation was not shown")
        .doesNotContain("Cites fs:");
    assertThat(of(unread, Types.INVESTIGATED_RISK)).isEmpty();
  }

  @Test
  void aRangeInAThirdRepositoryTheConversationReadIsKept() {
    headerLead();
    String risk =
        uploadRisk("fs:" + CONTROLLER + ":6-6", "gw:" + GATEWAY_CONFIG + ":1-2")
            .replace(
                "\"names\": [\"MultipartFile\"]", "\"names\": [\"MultipartFile\", \"lb://files\"]");

    Investigator.Result result =
        investigate(submitting(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? risk : null));

    assertThat(of(result, Types.INVESTIGATED_RISK)).hasSize(1);
  }

  /** What the files unit's conversation was told after its first submission. */
  private static String secondTurn(FakeLlmProvider fake) {
    return fake.sent().stream()
        .map(c -> c.request())
        .filter(r -> unit(r, FILES_UNIT) && turn(r) == 2)
        .findFirst()
        .orElseThrow()
        .messages()
        .getLast()
        .toolResults()
        .getLast()
        .content();
  }

  @Test
  void aUnitsRiskNamingNoUnitIsTheUnitsAndASystemRiskMustNameItsUnits() {
    headerLead();
    String unnamed =
        """
        {"kind": "risk", "title": "Uploads have no size limit",
         "finding": "upload accepts any MultipartFile of any size and keeps it whole in memory before writing it to the disk.", "consequence": "A caller can fill the disk or the memory of the service with one large upload and stop it serving others.",
         "impact": "medium", "confidence": "low", "names": ["MultipartFile"],
         "cites": ["fs:src/main/java/f/FileController.java:6-6"]}""";
    FakeLlmProvider fake =
        submitting(
            r ->
                turn(r) == 1
                        && (unit(r, FILES_UNIT)
                            || r.messages().getFirst().text().contains("The whole system"))
                    ? unnamed
                    : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_RISK))
        .singleElement()
        .satisfies(
            risk -> {
              assertThat(risk.subject().toString()).isEqualTo(FILES_UNIT);
              assertThat(risk.attributes().path("mode").asString()).isEqualTo("unit");
            });
    LlmRequest system =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(
                r -> r.messages().getFirst().text().contains("The whole system") && turn(r) == 2)
            .findFirst()
            .orElseThrow();
    assertThat(system.messages().getLast().toolResults().getLast().content())
        .contains("names the units it concerns");
  }

  @Test
  void aLeadOfAFindingsKindIsItsWordingOnlyIfTheFindingRestsOnWhatTheLeadShows() {
    headerLead("plaintext-secret");
    ObjectNode raised = FactJson.newObject();
    raised.put("about", FILES_UNIT);
    // A finding of the lead's kind in another file of the unit: not what the lead shows.
    add(
        "findings",
        "finding:shared-jwt-secret/elsewhere",
        Types.FINDING_RAISED,
        FactObject.string("shared-jwt-secret"),
        raised.toString(),
        "fs",
        STORAGE,
        3);
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    Investigator.Result separate = investigate(fake);

    assertThat(of(separate, Types.FINDING_WORDING)).isEmpty();
    assertThat(of(separate, Types.INVESTIGATED_RISK)).hasSize(1);

    // One in the file the lead's fact names, though not on the cited lines: its wording.
    Fact finding =
        add(
            "findings",
            "finding:shared-jwt-secret/here",
            Types.FINDING_RAISED,
            FactObject.string("shared-jwt-secret"),
            raised.toString(),
            "fs",
            CONTROLLER,
            12);
    Investigator.Result worded =
        investigate(submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null));

    assertThat(of(worded, Types.FINDING_WORDING))
        .singleElement()
        .satisfies(w -> assertThat(w.subject()).isEqualTo(finding.subject()));
  }

  @Test
  void aRefutedLeadIsStoredWithoutARisk() {
    headerLead();
    String refuted = CONFIRMED.replace("\"confirmed\"", "\"refuted\"");
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? refuted : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.LEAD_VERDICT).getFirst().object().value()).isEqualTo("refuted");
    assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
  }

  /** A second lead of the header kind in the files unit, on another header, at line 7. */
  private Fact orgLead(String... alsoUnits) {
    Fact read =
        add(
            "spring",
            "method:f.FileController#upload()",
            "reads-request-value",
            FactObject.string("X-Org-Id"),
            "{\"source\":\"header\"}",
            "fs",
            CONTROLLER,
            7);
    ObjectNode attributes = FactJson.newObject();
    attributes.put("trigger", "The header X-Org-Id is read by the code of files");
    ArrayNode orgUnits = attributes.putArray("units").add(FILES_UNIT);
    for (String unit : alsoUnits) {
      orgUnits.add(unit);
    }
    attributes.putArray("code").add("method:f.FileController#upload()");
    attributes.put("content", "sha256:def");
    attributes.putArray("facts").add(read.id());
    return add(
        "lead-identity-from-request",
        "lead:identity-from-request/" + FILES_UNIT + "/header:X-Org-Id",
        Types.LEAD_TRIGGER,
        FactObject.string("identity-from-request"),
        attributes.toString(),
        "fs",
        CONTROLLER,
        7);
  }

  private static final String CONFIRMED_ORG =
      """
      {"lead": "I1", "verdict": "confirmed", "kind": "risk",
       "title": "Files trust the caller's organization header",
       "finding": "upload passes the file to storage under the organization the caller names, and nothing checks that the caller belongs to it.",
       "consequence": "Any caller can store files in any organization, so one organization's files can be overwritten by a caller from another one.",
       "impact": "high", "confidence": "high", "units": ["service:g:files"],
       "names": ["storage"], "cites": ["fs:src/main/java/f/FileController.java:7-7"]}""";

  @Test
  void theConfirmedLeadsOfOneKindInAUnitAreOneRiskCitingEveryPlace() {
    Fact header = headerLead();
    Fact org = orgLead();
    FakeLlmProvider fake =
        submitting(
            r ->
                !leads(r) || turn(r) != 1
                    ? null
                    : r.messages().getFirst().text().contains("header:X-Org-Id")
                        ? CONFIRMED_ORG
                        : CONFIRMED);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.LEAD_VERDICT)).hasSize(2);
    assertThat(result.confirmed()).isEqualTo(2);
    Fact risk = of(result, Types.INVESTIGATED_RISK).stream().collect(onlyOne());
    assertThat(risk.object().value()).isEqualTo("identity-from-request " + FILES_UNIT);
    // The first lead's wording, every lead's places.
    assertThat(risk.attributes().path("title").asString())
        .isIn(
            "Files trust the caller's user header", "Files trust the caller's organization header");
    assertThat(texts(risk.attributes().path("cites")))
        .containsExactlyInAnyOrder("fs:" + CONTROLLER + ":5-7", "fs:" + CONTROLLER + ":7-7");
    assertThat(risk.evidence().locations()).hasSize(2);
    // Every lead it gathers is in its lineage; the highest impact and confidence of them.
    assertThat(risk.lineage()).containsExactlyInAnyOrder(claimOf(header), claimOf(org));
    assertThat(risk.attributes().path("impact").asString()).isEqualTo("high");
    assertValidAttributes(result);
  }

  @Test
  void twoLeadsOfOneKindWithDifferentUnitsAreTwoRisks() {
    // A risk is one problem: its kind and all its units (owner's decision on #747). The header
    // lead is about files alone; the organization lead about files and the gateway.
    headerLead();
    orgLead(GATEWAY_UNIT);
    FakeLlmProvider fake =
        submitting(
            r ->
                !leads(r) || turn(r) != 1
                    ? null
                    : r.messages().getFirst().text().contains("header:X-Org-Id")
                        ? CONFIRMED_ORG
                        : CONFIRMED);

    Investigator.Result result = investigate(fake);

    List<Fact> risks = of(result, Types.INVESTIGATED_RISK);
    assertThat(risks).hasSize(2);
    // Each risk's value is its kind and its leads' units, so the two are two claims.
    assertThat(risks)
        .extracting(r -> r.object().value())
        .containsExactlyInAnyOrder(
            "identity-from-request " + FILES_UNIT,
            "identity-from-request " + FILES_UNIT + "," + GATEWAY_UNIT);
    assertValidAttributes(result);
  }

  @Test
  void aRiskFoundExploringThatCitesTheLinesOfARiskNamingItsUnitIsNotASecondRisk() {
    // The lead is the gateway's first and files' second; its risk names both units.
    Fact lead = headerLead();
    ObjectNode attributes = (ObjectNode) lead.attributes().deepCopy();
    attributes.putArray("units").add(GATEWAY_UNIT).add(FILES_UNIT);
    add(
        "lead-identity-from-request",
        "lead:identity-from-request/" + GATEWAY_UNIT + "/header:X-User-Id",
        Types.LEAD_TRIGGER,
        FactObject.string("identity-from-request"),
        attributes.toString(),
        "fs",
        CONTROLLER,
        6);
    String confirmed =
        CONFIRMED.replace(
            "\"units\": [\"service:g:files\"]",
            "\"units\": [\"" + GATEWAY_UNIT + "\", \"" + FILES_UNIT + "\"]");
    String overlapping = uploadRisk("fs:" + CONTROLLER + ":6-6");
    FakeLlmProvider fake =
        submitting(
            r ->
                turn(r) != 1
                    ? null
                    : leads(r)
                        ? r.messages().getFirst().text().contains(GATEWAY_UNIT + "/header")
                            ? confirmed
                            : null
                        : unit(r, FILES_UNIT) ? overlapping : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_RISK))
        .singleElement()
        .satisfies(r -> assertThat(r.subject().toString()).isEqualTo(GATEWAY_UNIT));
  }

  @Test
  void aRiskFoundExploringThatCitesTheLinesOfARiskWhoseLeadNamesItsUnitIsNotASecondRisk() {
    // The lead names the gateway and files, but its answer names the gateway alone.
    Fact lead = headerLead();
    ObjectNode attributes = (ObjectNode) lead.attributes().deepCopy();
    attributes.putArray("units").add(GATEWAY_UNIT).add(FILES_UNIT);
    add(
        "lead-identity-from-request",
        "lead:identity-from-request/" + GATEWAY_UNIT + "/header:X-User-Id",
        Types.LEAD_TRIGGER,
        FactObject.string("identity-from-request"),
        attributes.toString(),
        "fs",
        CONTROLLER,
        6);
    String confirmed =
        CONFIRMED.replace(
            "\"units\": [\"service:g:files\"]", "\"units\": [\"" + GATEWAY_UNIT + "\"]");
    String overlapping = uploadRisk("fs:" + CONTROLLER + ":6-6");
    FakeLlmProvider fake =
        submitting(
            r ->
                turn(r) != 1
                    ? null
                    : leads(r)
                        ? r.messages().getFirst().text().contains(GATEWAY_UNIT + "/header")
                            ? confirmed
                            : null
                        : unit(r, FILES_UNIT) ? overlapping : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_RISK)).hasSize(1);
  }

  @Test
  void aJoinedRiskTakesTheHighestImpactOfItsLeads() {
    headerLead();
    orgLead();
    // The header lead, asked first, is low; the organization lead high.
    String low =
        CONFIRMED.replace(
            "\"impact\": \"high\", \"confidence\": \"high\"",
            "\"impact\": \"low\", \"confidence\": \"low\"");
    FakeLlmProvider fake =
        submitting(
            r ->
                !leads(r) || turn(r) != 1
                    ? null
                    : r.messages().getFirst().text().contains("header:X-Org-Id")
                        ? CONFIRMED_ORG
                        : low);

    Fact risk = of(investigate(fake), Types.INVESTIGATED_RISK).stream().collect(onlyOne());

    assertThat(risk.attributes().path("impact").asString()).isEqualTo("high");
    assertThat(risk.attributes().path("confidence").asString()).isEqualTo("high");
    assertThat(risk.confidence()).isEqualTo(0.6);
  }

  @Test
  void aLeadThatRestatesNoFindingNeverJoinsAFixedChecksFindingAskedBeforeIt() {
    // Two plaintext-secret leads in files: one rests on a fixed check's finding, one does not.
    headerLead("plaintext-secret");
    ObjectNode raised = FactJson.newObject();
    raised.put("about", FILES_UNIT);
    add(
        "findings",
        "finding:shared-jwt-secret/here",
        Types.FINDING_RAISED,
        FactObject.string("shared-jwt-secret"),
        raised.toString(),
        "fs",
        CONTROLLER,
        12);
    Fact read =
        add(
            "spring",
            "method:f.StorageClient#bucket()",
            "reads-request-value",
            FactObject.string("bucket"),
            "{\"source\":\"header\"}",
            "fs",
            STORAGE,
            3);
    ObjectNode attributes = FactJson.newObject();
    attributes.put("trigger", "A secret in the storage client");
    attributes.putArray("units").add(FILES_UNIT);
    attributes.putArray("code").add("method:f.StorageClient#bucket()");
    attributes.put("content", "sha256:ghi");
    attributes.putArray("facts").add(read.id());
    add(
        "lead-plaintext-secret",
        "lead:plaintext-secret/" + FILES_UNIT + "/storage",
        Types.LEAD_TRIGGER,
        FactObject.string("plaintext-secret"),
        attributes.toString(),
        "fs",
        STORAGE,
        3);
    String storage =
        CONFIRMED
            .replace("fs:src/main/java/f/FileController.java:5-7", "fs:" + STORAGE + ":3-3")
            .replace("[\"X-User-Id\", \"upload\"]", "[\"bucket\"]");
    FakeLlmProvider fake =
        submitting(
            r ->
                !leads(r) || turn(r) != 1
                    ? null
                    : r.messages().getFirst().text().contains("/storage") ? storage : CONFIRMED);

    Investigator.Result result = investigate(fake);

    // The finding is worded by the lead resting on it, and the other lead is a risk of its own.
    assertThat(of(result, Types.FINDING_WORDING)).hasSize(1);
    assertThat(of(result, Types.INVESTIGATED_RISK))
        .singleElement()
        .satisfies(
            r ->
                assertThat(texts(r.attributes().path("cites")))
                    .containsExactly("fs:" + STORAGE + ":3-3"));
    assertValidAttributes(result);
  }

  @Test
  void aRiskFoundExploringThatCitesTheLinesOfARiskOfItsUnitIsNotASecondRisk() {
    headerLead();
    String overlapping = uploadRisk("fs:" + CONTROLLER + ":6-6");
    FakeLlmProvider fake =
        submitting(
            r ->
                turn(r) != 1
                    ? null
                    : leads(r) ? CONFIRMED : unit(r, FILES_UNIT) ? overlapping : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_RISK))
        .singleElement()
        .satisfies(r -> assertThat(r.attributes().path("mode").asString()).isEqualTo("leads"));
  }

  @Test
  void aRiskFoundExploringThatAlsoCitesLinesOfItsOwnIsARiskOfItsOwn() {
    headerLead();
    String partly =
        uploadRisk("fs:" + CONTROLLER + ":6-6", "fs:" + STORAGE + ":3-3")
            .replace("[\"MultipartFile\"]", "[\"MultipartFile\", \"bucket\"]");
    Investigator.Result result =
        investigate(
            submitting(
                r ->
                    turn(r) != 1
                        ? null
                        : leads(r) ? CONFIRMED : unit(r, FILES_UNIT) ? partly : null));

    // Only a risk citing nothing but the stored risk's lines restates it (#747).
    assertThat(of(result, Types.INVESTIGATED_RISK)).hasSize(2);
  }

  @Test
  void aRiskFoundExploringThatCitesOtherLinesIsARiskOfItsOwn() {
    headerLead();
    String elsewhere = uploadRisk("fs:" + STORAGE + ":3-3").replace("MultipartFile", "bucket");
    Investigator.Result both =
        investigate(
            submitting(
                r ->
                    turn(r) != 1
                        ? null
                        : leads(r) ? CONFIRMED : unit(r, FILES_UNIT) ? elsewhere : null));
    assertThat(of(both, Types.INVESTIGATED_RISK)).hasSize(2);
  }

  private static List<String> texts(tools.jackson.databind.JsonNode array) {
    List<String> texts = new ArrayList<>();
    array.forEach(v -> texts.add(v.asString()));
    return texts;
  }

  private static String claimOf(Fact fact) {
    return fact.claimId() != null ? fact.claimId() : fact.id();
  }

  private static <T> java.util.stream.Collector<T, ?, T> onlyOne() {
    return java.util.stream.Collectors.collectingAndThen(
        java.util.stream.Collectors.toList(),
        list -> {
          assertThat(list).hasSize(1);
          return list.getFirst();
        });
  }

  @Test
  void exploringAUnitKeepsTheProposalsThatHoldAndSendsBackTheRest() {
    headerLead();
    String connection =
        """
        {"kind": "connection", "from": "service:g:files", "to": "external-system:s3",
         "interaction": "http", "description": "Stores uploaded files",
         "names": ["s3.example.com"], "cites": ["fs:src/main/java/f/StorageClient.java:3-3"]}""";
    String wrongUnit =
        """
        {"kind": "risk", "title": "Uploads have no size limit",
         "finding": "upload accepts any file of any size and keeps it whole in memory before writing it to the disk.", "consequence": "A caller can fill the disk or the memory of the service with one large upload and stop it serving others.",
         "impact": "medium", "confidence": "low", "units": ["service:g:nowhere"],
         "names": ["MultipartFile"], "cites": ["fs:src/main/java/f/FileController.java:6-6"]}""";
    FakeLlmProvider fake =
        submitting(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? connection + "," + wrongUnit : null);

    Investigator.Result result = investigate(fake);

    LlmRequest second =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(r -> unit(r, FILES_UNIT) && turn(r) == 2)
            .findFirst()
            .orElseThrow();
    assertThat(second.messages().getLast().toolResults().getLast().content())
        .contains("accepted in part")
        .contains("proposals.P2")
        .contains("not a deployable unit");
    Fact found = of(result, Types.INVESTIGATED_CONNECTION).getFirst();
    assertThat(found.subject().toString()).isEqualTo(FILES_UNIT);
    assertThat(found.object().value()).isEqualTo("external-system:s3");
    assertThat(found.attributes().path("mode").asString()).isEqualTo("unit");
    assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
  }

  @Test
  void aConnectionALinkAlreadyJoinsIsSentBack() {
    headerLead();
    add(
        "cross-repo-links",
        GATEWAY_UNIT,
        "CALLS_HTTP",
        FactObject.entity(EntityId.parse(FILES_UNIT)),
        null,
        "gw",
        "pom.xml",
        0);
    String repeat =
        """
        {"kind": "connection", "from": "service:g:files", "to": "service:g:gateway",
         "interaction": "http", "description": "Answers the gateway",
         "names": ["upload"], "cites": ["fs:src/main/java/f/FileController.java:6-6"]}""";
    FakeLlmProvider fake = submitting(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? repeat : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_CONNECTION)).isEmpty();
  }

  @Test
  void aConnectionToTracingLoggingOrConfigInfrastructureIsRejected() {
    headerLead();
    String tracing =
        """
        {"kind": "connection", "from": "service:g:files", "to": "external-system:zipkin",
         "interaction": "http", "description": "Exports distributed tracing spans",
         "names": ["s3.example.com"], "cites": ["fs:src/main/java/f/StorageClient.java:3-3"]}""";
    FakeLlmProvider fake = submitting(r -> unit(r, FILES_UNIT) && turn(r) == 1 ? tracing : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_CONNECTION)).isEmpty();
    assertThat(
            fake.sent().stream()
                .map(c -> c.request())
                .filter(r -> unit(r, FILES_UNIT) && turn(r) == 2)
                .findFirst()
                .orElseThrow()
                .messages()
                .getLast()
                .toolResults()
                .getLast()
                .content())
        .contains("Connects to infrastructure (tracing)");
    // The named list is the contract's: logging, metrics, registries and config servers too.
    assertThat(InvestigateRole.Proposals.infrastructure("external-system:loki Pushes logs"))
        .isEqualTo("loki");
    assertThat(InvestigateRole.Proposals.infrastructure("service:g:config-server Reads settings"))
        .isEqualTo("config-server");
    // Business connections that share a word with infrastructure pass.
    assertThat(InvestigateRole.Proposals.infrastructure("external-system:s3 Stores uploads"))
        .isNull();
    assertThat(
            InvestigateRole.Proposals.infrastructure(
                "database:audit Stores audit logs of each order"))
        .isNull();
    assertThat(
            InvestigateRole.Proposals.infrastructure(
                "external-system:schema-registry Checks each event's schema kafka"))
        .isNull();
    assertThat(
            InvestigateRole.Proposals.infrastructure(
                "service:g:metrics Reports sales metrics to managers"))
        .isNull();
  }

  @Test
  void aRiskThatRestatesAStaticFindingIsItsWording() {
    headerLead("plaintext-secret");
    ObjectNode raised = FactJson.newObject();
    raised.put("about", FILES_UNIT);
    Fact finding =
        add(
            "findings",
            "finding:shared-jwt-secret/x",
            Types.FINDING_RAISED,
            FactObject.string("shared-jwt-secret"),
            raised.toString(),
            "fs",
            CONTROLLER,
            6);
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
    Fact wording = of(result, Types.FINDING_WORDING).getFirst();
    assertThat(wording.subject()).isEqualTo(finding.subject());
    assertThat(wording.attributes().path("title").asString())
        .isEqualTo("Files trust the caller's user header");
  }

  @Test
  void exploringAUnitIsShownEveryLeadThatTouchesIt() {
    Fact lead = headerLead();
    ObjectNode attributes = (ObjectNode) lead.attributes().deepCopy();
    attributes.withArray("units").add(GATEWAY_UNIT);
    add(
        "lead-identity-from-request",
        "lead:identity-from-request/" + GATEWAY_UNIT + "/header:X-User-Id",
        Types.LEAD_TRIGGER,
        FactObject.string("identity-from-request"),
        attributes.toString(),
        "fs",
        CONTROLLER,
        6);
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    investigate(fake);

    // The gateway's exploration sees the lead whose conversation was about files, since it
    // touches the gateway too (#747).
    String gateway =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(r -> !leads(r))
            .map(r -> r.system() + "\n" + r.messages().getFirst().text())
            .filter(t -> t.contains("The deployable unit " + GATEWAY_UNIT))
            .findFirst()
            .orElseThrow();
    assertThat(gateway)
        .contains("<answers>")
        .contains("lead:identity-from-request/" + GATEWAY_UNIT);
    // The system conversation sees the lead once, under its first unit.
    String system =
        fake.sent().stream()
            .map(c -> c.request().messages().getFirst().text())
            .filter(t -> t.contains("The whole system"))
            .findFirst()
            .orElseThrow();
    assertThat(system).doesNotContain(GATEWAY_UNIT + ": lead:identity-from-request");
  }

  @Test
  void theSystemIsShownTheRisksItsUnitsHave() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    investigate(fake);

    LlmRequest system =
        fake.sent().stream()
            .map(c -> c.request())
            .filter(r -> r.messages().getFirst().text().contains("The whole system"))
            .findFirst()
            .orElseThrow();
    assertThat(system.messages().getFirst().text())
        .contains("<answers>")
        .contains(FILES_UNIT + ": lead:identity-from-request")
        .contains("Files trust the caller's user header");
  }

  @Test
  void aCalibrationAsksTheLeadsAtItsSize() {
    headerLead();
    headerLead("plaintext-secret");
    FakeLlmProvider usual = submitting(r -> null);
    investigate(usual);

    FakeLlmProvider fake = submitting(r -> null);
    harnessCalibration = new io.lantern.harness.Harness.Calibration(2, "1");
    investigate(fake);
    harnessCalibration = null;

    // One lead in the first request at the usual size; both under a calibration at 2 (ADR 0112).
    java.util.function.Function<FakeLlmProvider, String> first =
        f ->
            f.sent().stream()
                .map(s -> s.request())
                .filter(r -> leads(r) && turn(r) == 1)
                .findFirst()
                .orElseThrow()
                .messages()
                .getFirst()
                .text();
    assertThat(first.apply(usual)).contains("<item id=\"I1\">").doesNotContain("<item id=\"I2\">");
    assertThat(first.apply(fake)).contains("<item id=\"I1\">").contains("<item id=\"I2\">");
    assertThat(harness.records())
        .filteredOn(r -> r.toJson().path("role").asString().equals("investigate-leads"))
        .singleElement()
        .satisfies(r -> assertThat(r.toJson().path("packetSize").asInt()).isEqualTo(2));
  }

  @Test
  void aCachedAnswerIsAskedAgainOnceAFileItCitesChanges() {
    headerLead();
    // It also cites the storage client, a file it read with a tool, not shown in its prompt.
    String citingStorage =
        CONFIRMED.replace(
            "\"cites\": [\"fs:src/main/java/f/FileController.java:5-7\"]",
            "\"cites\": [\"fs:src/main/java/f/FileController.java:5-7\", \"fs:"
                + STORAGE
                + ":3-3\"]");
    investigate(submitting(r -> leads(r) && turn(r) == 1 ? citingStorage : null));
    FakeLlmProvider unchanged = submitting(r -> null);
    investigate(unchanged);
    assertThat(unchanged.sent()).isEmpty();

    String key = "fs:" + STORAGE;
    String before = TEXTS.get(key);
    try {
      TEXTS.put(key, before + "// edited\n");
      FakeLlmProvider again = submitting(r -> null);
      investigate(again);
      // Its cached answer is checked against the file as it is now (#769).
      assertThat(again.sent()).anyMatch(c -> leads(c.request()));
    } finally {
      TEXTS.put(key, before);
    }
  }

  @Test
  void aRerunOverUnchangedFactsAndCodeMakesNoCall() {
    headerLead();
    investigate(submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null));
    // What Describe stored after it in the first run is not among what it reads.
    facts.add(
        Fact.create(
            EntityId.parse(FILES_UNIT),
            Types.RESPONSIBILITY,
            FactObject.string("Stores uploaded files."),
            FactJson.newObject(),
            new Origin(Origin.Kind.LLM, "describe-unit", "1"),
            Evidence.of("fs", "pom.xml", null, null),
            0.6,
            Validity.from(1)));
    FakeLlmProvider again = submitting(r -> null);

    Investigator.Result result = investigate(again);

    assertThat(again.sent()).isEmpty();
    assertThat(of(result, Types.INVESTIGATED_RISK)).hasSize(1);
    assertThat(result.asked()).isZero();
  }

  @Test
  void aModelThatCannotUseToolsIsNotAskedAndEveryUnitIsNotInvestigated() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> CONFIRMED);
    LlmClient client =
        new LlmClient(
            ModelSettings.single(
                new io.lantern.llm.ModelConfig(
                    FakeLlmProvider.NAME, "m", Set.of(), 1_000_000, io.lantern.llm.Pricing.FREE)),
            List.of(fake),
            BudgetGuard.unlimited("t"),
            log,
            Clock.systemUTC());

    Investigator.Result result = investigate(client);

    assertThat(fake.sent()).isEmpty();
    assertThat(of(result, Types.NOT_INVESTIGATED).stream().map(f -> f.subject().toString()))
        .containsExactlyInAnyOrder(FILES_UNIT, GATEWAY_UNIT, Investigator.SYSTEM.toString());
  }

  @Test
  void itsFactsAreOfTheLlmVocabulary() {
    headerLead();
    Investigator.Result result =
        investigate(submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null));
    Set<String> types = new java.util.HashSet<>();
    LlmFacts.vocabulary().forEach(t -> types.add(t.name()));

    assertThat(result.facts()).isNotEmpty();
    assertThat(result.facts()).allMatch(f -> types.contains(f.predicate()));
    assertValidAttributes(result);
  }

  /**
   * Every fact's attributes hold to its type's schema, as the store checks them when it saves the
   * run's facts (#747: an undeclared attribute fails the run there).
   */
  private static void assertValidAttributes(Investigator.Result result) {
    Map<String, io.lantern.core.vocabulary.VocabularyType> types = new java.util.HashMap<>();
    LlmFacts.vocabulary().forEach(t -> types.put(t.name(), t));
    for (Fact fact : result.facts()) {
      List<io.lantern.core.vocabulary.AttributeSchema> chain = new ArrayList<>();
      for (var type = types.get(fact.predicate()); type != null; type = types.get(type.parent())) {
        chain.addFirst(type.attributeSchema());
        if (type.parent() == null) {
          break;
        }
      }
      io.lantern.core.vocabulary.AttributeSchema.merge(chain).validate(fact.attributes());
    }
  }

  // The recorded and injection fixtures of Investigate's three roles (#768).

  private static final java.nio.file.Path INVESTIGATE_RECORDINGS =
      java.nio.file.Path.of("src/test/resources/recordings/investigate");

  private static boolean system(LlmRequest r) {
    return r.messages().getFirst().text().contains("The whole system");
  }

  /**
   * A model that confirms the lead, finds the files unit's upload risk and names it for the system.
   */
  private static String investigating(LlmRequest r) {
    if (turn(r) != 1) {
      return null;
    }
    if (leads(r)) {
      return CONFIRMED;
    }
    if (unit(r, FILES_UNIT)) {
      return """
          {"kind": "risk", "title": "Every file goes to one fixed bucket",
           "finding": "The storage client posts every uploaded file to the one bucket written in its code, https://s3.example.com/bucket, whatever user the file belongs to.",
           "consequence": "Files of every user share one bucket, so a mistake in one user's access rules exposes the others' files.",
           "impact": "medium", "confidence": "low", "names": ["restClient", "bucket"],
           "cites": ["fs:%s:3-3"]}"""
          .formatted(STORAGE);
    }
    if (system(r)) {
      return """
          {"kind": "risk", "title": "Gateway sends all file traffic to one service",
           "finding": "The gateway routes every path under /files to lb://files, the one files service, with no other route for them.",
           "consequence": "When the files service is down, no user can upload or read a file, as nothing else serves those paths.",
           "impact": "medium", "confidence": "low", "units": ["%s", "%s"], "names": ["lb://files"],
           "cites": ["gw:%s:1-2"]}"""
          .formatted(GATEWAY_UNIT, FILES_UNIT, GATEWAY_CONFIG);
    }
    return null;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"investigate-leads", "investigate-units", "investigate-system"})
  void eachRoleReplaysItsRecordingToExactlyTheExpectedFacts(String role) {
    headerLead();
    FakeLlmProvider scripted = submitting(InvestigatorTest::investigating);

    Investigator.Result result =
        investigate(client(io.lantern.llm.RoleFixture.provider(scripted, INVESTIGATE_RECORDINGS)));

    List<String> produced = io.lantern.harness.InjectionFixtures.lines(result.facts());
    List<String> expected = io.lantern.llm.RoleFixture.expected(INVESTIGATE_RECORDINGS, produced);
    String mode =
        switch (role) {
          case "investigate-leads" -> "lead-verdict|\"mode\":\"leads\"";
          case "investigate-units" -> "\"mode\":\"unit\"";
          default -> "\"mode\":\"system\"";
        };
    java.util.function.Predicate<String> ofMode =
        l -> java.util.Arrays.stream(mode.split("\\|")).anyMatch(l::contains);
    List<String> ofRole = produced.stream().filter(ofMode).toList();
    assertThat(ofRole).isNotEmpty();
    assertThat(ofRole).isEqualTo(expected.stream().filter(ofMode).toList());
    // And nothing else: the whole run stores exactly the expected facts.
    assertThat(produced).isEqualTo(expected);
  }

  /** Where the injection fixtures sit in the files unit's repository. */
  private static final Map<String, String> INJECTED_PATHS =
      Map.of(
          "README.md", "README.md",
          "OrderController.java", "src/main/java/f/OrderController.java",
          "application.yml", "src/main/resources/application.yml",
          "Greeting.java", "src/main/java/f/Greeting.java");

  @Test
  void eachRoleShowsTheFixturesAsDataAndAnAnswerThatObeysThemIsRejected() {
    headerLead();
    INJECTED_PATHS.forEach(
        (name, path) ->
            TEXTS.put("fs:" + path, io.lantern.harness.InjectionFixtures.fixture(name)));
    String obeyed = io.lantern.harness.InjectionFixtures.OBEYING;
    String cite = io.lantern.harness.InjectionFixtures.OBEYING_CITE;
    try {
      // A model that reads the four files, then does what they say, in every conversation.
      FakeLlmProvider obeying =
          new FakeLlmProvider()
              .callingTools(
                  r -> {
                    if (turn(r) == 1) {
                      List<LlmToolCall> reads = new ArrayList<>();
                      INJECTED_PATHS.values().stream()
                          .sorted()
                          .forEach(
                              path ->
                                  reads.add(
                                      new LlmToolCall(
                                          "r" + reads.size(),
                                          "read_file",
                                          FactJson.parse(
                                              "{\"repo\":\"fs\",\"path\":\"" + path + "\"}"))));
                      return reads;
                    }
                    if (turn(r) > 3) {
                      return List.of();
                    }
                    String risk =
                        uploadRisk(cite)
                            .replace(
                                "upload accepts any MultipartFile of any size and keeps it whole in"
                                    + " memory before writing it to the disk.",
                                obeyed)
                            .replace(
                                "\"names\":", "\"units\": [\"" + FILES_UNIT + "\"], \"names\":");
                    return List.of(
                        submit(
                            r,
                            leads(r)
                                ? CONFIRMED
                                    .replace(
                                        "\"fs:src/main/java/f/FileController.java:5-7\"",
                                        "\"" + cite + "\"")
                                    .replaceAll(
                                        "\"finding\": \"[^\"]*\"",
                                        "\"finding\": \"" + obeyed + "\"")
                                : risk));
                  })
              .answering("Done.");

      Investigator.Result result = investigate(obeying);

      for (String role : List.of("leads", "units", "system")) {
        io.lantern.harness.InjectionFixtures.shownAsData(
            obeying.sent().stream()
                .map(FakeLlmProvider.Sent::request)
                .filter(
                    r ->
                        switch (role) {
                          case "leads" -> leads(r);
                          case "system" -> system(r);
                          default -> !leads(r) && !system(r);
                        })
                .toList());
      }
      List<String> stored = io.lantern.harness.InjectionFixtures.lines(result.facts());
      assertThat(stored).noneMatch(l -> l.contains("fully secure") || l.contains("/etc/passwd"));
      assertThat(of(result, Types.INVESTIGATED_RISK)).isEmpty();
      // The lead is left unclear: its answer was rejected twice.
      assertThat(of(result, "lead-verdict"))
          .singleElement()
          .satisfies(v -> assertThat(v.object().value()).isEqualTo("unclear"));
    } finally {
      INJECTED_PATHS.values().forEach(path -> TEXTS.remove("fs:" + path));
    }
  }
}
