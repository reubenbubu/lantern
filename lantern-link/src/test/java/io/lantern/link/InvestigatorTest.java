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
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
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

  private static final Map<String, String> TEXTS =
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
          "fs:" + STORAGE,
          """
          package f;
          class StorageClient {
            String bucket = restClient.post().uri("https://s3.example.com/bucket");
          }
          """);

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
        BuiltInTypes.CONTAINS,
        FactObject.entity(EntityId.parse("module:g:" + artifact)),
        null,
        repo,
        "pom.xml",
        0);
    add(
        DeployableUnits.NAME,
        "service:g:" + artifact,
        DeployableUnits.DEPLOYABLE_UNIT,
        FactObject.string("spring-boot"),
        null,
        repo,
        "pom.xml",
        0);
  }

  /** The files unit reads the caller's user from a header, and derivation raised a lead on it. */
  private Fact headerLead() {
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
        "lead:identity-from-request/" + FILES_UNIT + "/header:X-User-Id",
        Investigator.LEAD_TRIGGER,
        FactObject.string("identity-from-request"),
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

  /** A model that submits, turn by turn, what the function gives, and ends on null. */
  private static FakeLlmProvider submitting(Function<LlmRequest, String> items) {
    return new FakeLlmProvider()
        .callingTools(
            r -> {
              String answer = items.apply(r);
              return answer == null ? List.of() : List.of(submit(r, answer));
            })
        .answering("Done.");
  }

  private static final String CONFIRMED =
      """
      {"lead": "I1", "verdict": "confirmed", "kind": "risk",
       "title": "Files trust the caller's user header",
       "finding": "upload takes the user from the X-User-Id header as the caller sent it.",
       "consequence": "Any caller can store files as any user.",
       "impact": "high", "confidence": "high", "units": ["service:g:files"],
       "names": ["X-User-Id", "upload"], "cites": ["fs:src/main/java/f/FileController.java:5-7"]}""";

  private static final String MISCITED =
      """
      {"lead": "I1", "verdict": "confirmed", "kind": "risk",
       "title": "Files trust the caller's user header",
       "finding": "upload takes the user from a header.",
       "consequence": "Any caller can store files as any user.",
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

  private Investigator.Result investigate(LlmClient client) {
    harness =
        new Harness(
            client,
            store,
            RunBudget.NONE,
            (repo, path) -> FILES.read(repo, path).map(io.lantern.harness.Fingerprints::gitBlob),
            Set.of());
    return new Investigator(
            harness, FILES, Map.of("identity-from-request", "Whether anything verifies it."))
        .investigate(facts, 1);
  }

  private Investigator.Result investigate(FakeLlmProvider fake) {
    return investigate(client(fake));
  }

  private static List<Fact> of(Investigator.Result result, String predicate) {
    return result.facts().stream().filter(f -> f.predicate().equals(predicate)).toList();
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
  void aConfirmedLeadIsAVerdictAndARiskCitedToTheVerifiedLines() {
    Fact lead = headerLead();
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    Investigator.Result result = investigate(fake);

    Fact verdict = of(result, Investigator.LEAD_VERDICT).getFirst();
    assertThat(verdict.subject()).isEqualTo(lead.subject());
    assertThat(verdict.object().value()).isEqualTo("confirmed");
    assertThat(verdict.lineage())
        .containsExactly(lead.claimId() != null ? lead.claimId() : lead.id());
    Fact risk = of(result, Investigator.RISK).getFirst();
    assertThat(risk.subject().toString()).isEqualTo(FILES_UNIT);
    assertThat(risk.origin()).isEqualTo(new Origin(Origin.Kind.LLM, "investigate", "1"));
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
    assertThat(second.messages().getLast().toolResults().getFirst().content())
        .contains("rejected")
        .contains("X-User-Id");
    assertThat(of(result, Investigator.RISK)).hasSize(1);
  }

  @Test
  void aVerdictMiscitedTwiceIsDroppedAndTheLeadUnclear() {
    headerLead();
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) <= 2 ? MISCITED : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Investigator.RISK)).isEmpty();
    Fact verdict = of(result, Investigator.LEAD_VERDICT).getFirst();
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
    assertThat(of(result, Investigator.LEAD_VERDICT).getFirst().object().value())
        .isEqualTo("unclear");
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
  void aRefutedLeadIsStoredWithoutARisk() {
    headerLead();
    String refuted = CONFIRMED.replace("\"confirmed\"", "\"refuted\"");
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? refuted : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Investigator.LEAD_VERDICT).getFirst().object().value())
        .isEqualTo("refuted");
    assertThat(of(result, Investigator.RISK)).isEmpty();
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
         "finding": "upload accepts any file.", "consequence": "A caller can fill the disk.",
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
    assertThat(second.messages().getLast().toolResults().getFirst().content())
        .contains("accepted in part")
        .contains("proposals.P2")
        .contains("not a deployable unit");
    Fact found = of(result, Investigator.CONNECTION).getFirst();
    assertThat(found.subject().toString()).isEqualTo(FILES_UNIT);
    assertThat(found.object().value()).isEqualTo("external-system:s3");
    assertThat(found.attributes().path("mode").asString()).isEqualTo("unit");
    assertThat(of(result, Investigator.RISK)).isEmpty();
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

    assertThat(of(result, Investigator.CONNECTION)).isEmpty();
  }

  @Test
  void aRiskThatRestatesAStaticFindingIsItsWording() {
    headerLead();
    ObjectNode raised = FactJson.newObject();
    raised.put("about", FILES_UNIT);
    Fact finding =
        add(
            "findings",
            "finding:shared-jwt-secret/x",
            Investigator.FINDING_RAISED,
            FactObject.string("shared-jwt-secret"),
            raised.toString(),
            "fs",
            CONTROLLER,
            6);
    FakeLlmProvider fake = submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null);

    Investigator.Result result = investigate(fake);

    assertThat(of(result, Investigator.RISK)).isEmpty();
    Fact wording = of(result, Investigator.FINDING_WORDING).getFirst();
    assertThat(wording.subject()).isEqualTo(finding.subject());
    assertThat(wording.attributes().path("title").asString())
        .isEqualTo("Files trust the caller's user header");
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
  void aRerunOverUnchangedFactsAndCodeMakesNoCall() {
    headerLead();
    investigate(submitting(r -> leads(r) && turn(r) == 1 ? CONFIRMED : null));
    // What Describe stored after it in the first run is not among what it reads.
    facts.add(
        Fact.create(
            EntityId.parse(FILES_UNIT),
            LlmFacts.RESPONSIBILITY,
            FactObject.string("Stores uploaded files."),
            FactJson.newObject(),
            new Origin(Origin.Kind.LLM, "describe-unit", "1"),
            Evidence.of("fs", "pom.xml", null, null),
            0.6,
            Validity.from(1)));
    FakeLlmProvider again = submitting(r -> null);

    Investigator.Result result = investigate(again);

    assertThat(again.sent()).isEmpty();
    assertThat(of(result, Investigator.RISK)).hasSize(1);
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
    assertThat(of(result, Investigator.NOT_INVESTIGATED).stream().map(f -> f.subject().toString()))
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
  }
}
