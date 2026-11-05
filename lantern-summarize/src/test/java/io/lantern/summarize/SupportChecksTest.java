package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.Role;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The support judge over every role's stored texts (#751). */
class SupportChecksTest {

  private static final Pattern ITEM =
      Pattern.compile("<item id=\"([^\"]+)\">\n<text>([^<]*)</text>");

  /** Each request's texts, as the judge saw them. */
  private final List<List<String>> requests = new ArrayList<>();

  private final FakeLlmProvider fake =
      new FakeLlmProvider()
          .answering(
              request -> {
                Matcher items = ITEM.matcher(request.messages().getFirst().text());
                List<String> entries = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                while (items.find()) {
                  texts.add(items.group(2));
                  String verdict = items.group(2).contains("wrong") ? "unsupported" : "supported";
                  entries.add(
                      "\"%s\": {\"claims\": [{\"claim\": \"It does it.\", \"verdict\": \"%s\"}]}"
                          .formatted(items.group(1), verdict));
                }
                synchronized (requests) {
                  requests.add(texts);
                }
                return "{\"items\": {" + String.join(", ", entries) + "}}";
              });

  private final Harness harness =
      new Harness(
          new LlmClient(
              ModelSettings.single(fake.model("m")),
              List.of(fake),
              BudgetGuard.unlimited("test"),
              new InMemoryLlmCallLog(),
              Clock.systemUTC()),
          new InMemoryAnswerStore(),
          RunBudget.NONE,
          BlobLookup.NONE,
          Set.of());

  /** Files as the snapshot holds them: B.java's lines read "line 1" to "line 9". */
  private final SourceFiles files =
      (repo, commit, path) ->
          path.equals("src/B.java")
              ? Optional.of(
                  "line 1\nline 2\nline 3\nline 4\nline 5\n".getBytes(StandardCharsets.UTF_8))
              : Optional.empty();

  private final Fact code = fact("type:shop.A", "kind", "class", "src/A.java", 1, 5);
  private final Fact flaggedCode = fact("type:shop.Evil", "kind", "class", "src/Evil.java", 10, 12);

  @Test
  void theSameHeldFactsInReverseOrderMakeTheSameRequests() {
    List<Fact> held = new ArrayList<>(List.of(code, flaggedCode));
    for (String role : List.of("describe-element", "summarize", "describe-unit")) {
      for (int i = 0; i < 40; i++) {
        held.add(text(role, role + " text " + i + " does it", i % 2 == 0 ? code : flaggedCode));
      }
    }
    held.add(risk("Orders trust a wrong header", "shop:src/B.java:3-4"));
    List<Fact> reversed = new ArrayList<>(held);
    java.util.Collections.reverse(reversed);
    SupportChecksTest other = new SupportChecksTest();

    new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(), 1);
    new SupportChecks(other.harness, other.files, Map.of("shop", "c1"))
        .judge(reversed, List.of(), 1);

    // Byte for byte, whatever order the held facts come in (#769).
    java.util.function.Function<FakeLlmProvider, List<String>> sent =
        f ->
            f.sent().stream()
                .map(
                    x ->
                        x.request().purpose()
                            + "\n"
                            + x.request().system()
                            + "\n"
                            + x.request().messages())
                .toList();
    assertThat(sent.apply(other.fake)).isEqualTo(sent.apply(fake)).isNotEmpty();
  }

  @Test
  void everyRiskAndEveryTextOnAFlaggedRangeIsJudgedAndOfTheRestOnlyTheSample() {
    List<Fact> held = new ArrayList<>(List.of(code, flaggedCode));
    List<Fact> texts = new ArrayList<>();
    for (String role : List.of("describe-element", "summarize")) {
      for (int i = 0; i < 40; i++) {
        texts.add(text(role, role + " text " + i + " does it", code));
      }
    }
    Fact onFlagged =
        text("describe-unit", "describe-unit text resting on flagged code", flaggedCode);
    Fact risk = risk("Orders trust a wrong header", "shop:src/B.java:3-4");
    texts.add(onFlagged);
    texts.add(risk);
    held.addAll(texts);
    // The screen flagged line 11 of Evil.java.
    Fact finding =
        fact(
            "finding:injection-suspected/shop:src/Evil.java",
            "finding",
            "x",
            "src/Evil.java",
            11,
            11);

    SupportChecks.Result result =
        new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(finding), 1);

    Set<String> judged = new TreeSet<>();
    result.byOrigin().values().stream()
        .flatMap(List::stream)
        .filter(f -> f.attributes().hasNonNull(LlmFacts.SUPPORT))
        .forEach(f -> judged.add(SupportChecks.text(f)));
    // Always: the risk, and the text resting on what the screen flagged.
    assertThat(judged)
        .contains("Orders trust a wrong header", "describe-unit text resting on flagged code");
    // Of the 80 others, a sample, about 10%.
    long sampled =
        judged.stream().filter(t -> t.contains(" text ") && !t.contains("flagged")).count();
    assertThat(sampled).isLessThan(20);
    assertThat(result.judged()).isEqualTo(judged.size());
    // Each request holds one role's texts only.
    assertThat(requests)
        .allSatisfy(
            r ->
                assertThat(r.stream().map(t -> t.split(" ")[0]).distinct().count())
                    .as(r.toString())
                    .isEqualTo(1));
    // The verdict is stored on the text's observation, its confidence lowered for an unsupported
    // one.
    Fact judgedRisk =
        result.byOrigin().values().stream()
            .flatMap(List::stream)
            .filter(f -> f.predicate().equals(Types.INVESTIGATED_RISK))
            .findFirst()
            .orElseThrow();
    assertThat(judgedRisk.attributes().get(LlmFacts.SUPPORT).asString()).isEqualTo("unsupported");
    assertThat(judgedRisk.confidence()).isEqualTo(risk.confidence() * 0.5);
    // The judge saw the risk's cited lines from the file.
    assertThat(fake.sent())
        .anyMatch(
            s ->
                s.request()
                    .messages()
                    .getFirst()
                    .text()
                    .contains("[shop:src/B.java:3-4]\n3: line 3\n4: line 4"));
    // Verdict counts per role.
    assertThat(result.counts().get("investigate")).isEqualTo(new SupportChecks.Counts(0, 0, 1));
    assertThat(result.counts().get("describe-unit")).isEqualTo(new SupportChecks.Counts(1, 0, 0));
    // Every fact of a judged origin is handed back, so recording them ends none.
    assertThat(result.byOrigin().values().stream().mapToInt(List::size).sum())
        .isEqualTo(
            (int) texts.stream().filter(t -> result.byOrigin().containsKey(t.origin())).count());
  }

  @Test
  void aSecretOnACitedLineIsMaskedBeforeTheJudgeSeesItAndReachesTheSecretsCheck() {
    List<String> masked = new ArrayList<>();
    String config = "spring:\n  datasource:\n    password: hunter2-very-secret\n    url: x\n";
    SourceFiles withSecret =
        new SourceFiles() {
          @Override
          public Optional<byte[]> read(String repo, String commit, String path) {
            return path.equals("config/application.yml")
                ? Optional.of(config.getBytes(StandardCharsets.UTF_8))
                : Optional.empty();
          }

          @Override
          public Set<String> secretKeys() {
            return Set.of("spring.datasource.password");
          }

          @Override
          public java.util.function.Consumer<String> maskedValues() {
            return masked::add;
          }
        };
    Fact risk = risk("The database password is in plain text", "shop:config/application.yml:3-4");

    new SupportChecks(harness, withSecret, Map.of("shop", "c1")).judge(List.of(risk), List.of(), 1);

    // The judge was shown the cited lines, numbered as the file holds them, the secret masked.
    assertThat(fake.sent())
        .anyMatch(
            s ->
                s.request()
                    .messages()
                    .getFirst()
                    .text()
                    .contains(
                        "[shop:config/application.yml:3-4]\n3:     password: ******\n4:     url: x"));
    assertThat(fake.sent()).noneMatch(s -> s.request().toString().contains("hunter2-very-secret"));
    // The value masking took out reached the harness's Secrets check.
    assertThat(masked).contains("hunter2-very-secret");
  }

  @Test
  void aKnownSecretKeyNamedOtherwiseThanItsPropertyIsMaskedForTheJudgeToo() {
    // app.jwt-key is no secret by its name alone: the extractors know it as one, under the
    // normalized key the investigator's tools mask by (#751).
    List<String> masked = new ArrayList<>();
    String config = "server.port=8080\napp.jwt-key=jwt-literal-0123456789\n";
    SourceFiles withSecret =
        new SourceFiles() {
          @Override
          public Optional<byte[]> read(String repo, String commit, String path) {
            return path.equals("config/application.properties")
                ? Optional.of(config.getBytes(StandardCharsets.UTF_8))
                : Optional.empty();
          }

          @Override
          public Set<String> secretKeys() {
            return Set.of("app.jwt-key");
          }

          @Override
          public java.util.function.Consumer<String> maskedValues() {
            return masked::add;
          }
        };
    Fact risk =
        risk("The token key is written in plain text", "shop:config/application.properties:2-2");

    new SupportChecks(harness, withSecret, Map.of("shop", "c1")).judge(List.of(risk), List.of(), 1);

    assertThat(fake.sent())
        .noneMatch(s -> s.request().toString().contains("jwt-literal-0123456789"));
    assertThat(fake.sent())
        .anyMatch(s -> s.request().messages().getFirst().text().contains("2: app.jwt-key=******"));
    assertThat(masked).contains("jwt-literal-0123456789");
  }

  @Test
  void theTextsOnAFlaggedRangeAreJudgedOnlyBecauseOfTheFlag() {
    List<Fact> held = new ArrayList<>(List.of(flaggedCode));
    for (int i = 0; i < 20; i++) {
      held.add(text("describe-unit", "describe-unit text " + i + " on flagged code", flaggedCode));
    }
    Fact finding =
        fact(
            "finding:injection-suspected/shop:src/Evil.java",
            "finding",
            "x",
            "src/Evil.java",
            11,
            11);

    int withoutFlag =
        new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(), 1).judged();
    int withFlag =
        new SupportChecks(harness, files, Map.of("shop", "c1"))
            .judge(held, List.of(finding), 1)
            .judged();

    // Without the flag only the sample; with it, every one of them (#751).
    assertThat(withoutFlag).isLessThan(20);
    assertThat(withFlag).isEqualTo(20);
  }

  @Test
  void eachTextIsJudgedByTheModelOfTheRoleThatWroteIt() {
    List<Fact> held = new ArrayList<>(List.of(code, flaggedCode));
    held.add(text("describe-element", "describe-element text on flagged code", flaggedCode));
    held.add(risk("Orders trust a wrong header", "shop:src/B.java:3-4"));
    Fact finding =
        fact(
            "finding:injection-suspected/shop:src/Evil.java",
            "finding",
            "x",
            "src/Evil.java",
            11,
            11);

    new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(finding), 1);

    // The owner's choice on #751: a model checks its own texts, Haiku Haiku's and Sonnet Sonnet's.
    assertThat(fake.sent())
        .extracting(s -> s.request().role() + " " + s.request().messages().getFirst().text())
        .anyMatch(t -> t.startsWith("INVESTIGATE ") && t.contains("Orders trust a wrong header"))
        .anyMatch(
            t ->
                t.startsWith("DESCRIBE_ELEMENTS ")
                    && t.contains("describe-element text on flagged code"))
        .noneMatch(t -> t.startsWith("JUDGE "));
  }

  @Test
  void setToTheJudgeEveryTextIsJudgedByTheJudgesOwnModel() {
    List<Fact> held = new ArrayList<>(List.of(code));
    held.add(risk("Orders trust a wrong header", "shop:src/B.java:3-4"));

    new SupportChecks(harness, files, Map.of("shop", "c1"))
        .byWriter(false)
        .judge(held, List.of(), 1);

    assertThat(fake.sent()).isNotEmpty().allMatch(s -> s.request().role() == Role.JUDGE);
  }

  @Test
  void textsThatReadTheSameAndCiteTheSameAreJudgedOnceEach() {
    List<Fact> held = new ArrayList<>(List.of(flaggedCode));
    Fact one = text("describe-unit", "describe-unit text on flagged code", flaggedCode);
    Fact two =
        Fact.create(
            EntityId.parse("service:shop:other"),
            one.predicate(),
            one.object(),
            one.attributes(),
            one.origin(),
            one.evidence(),
            one.confidence(),
            Validity.from(1));
    held.add(one);
    held.add(two);
    Fact finding =
        fact(
            "finding:injection-suspected/shop:src/Evil.java",
            "finding",
            "x",
            "src/Evil.java",
            11,
            11);

    SupportChecks.Result result =
        new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(finding), 1);

    // One item asked, the verdict on both (#751).
    assertThat(requests.stream().mapToInt(List::size).sum()).isEqualTo(1);
    assertThat(result.judged()).isEqualTo(2);
  }

  @Test
  void theKindsJudgedAreThoseWhoseTypeDeclaresASupportVerdict() throws Exception {
    // A verdict on a kind whose type does not declare it would fail recording (#751).
    Set<String> declaring = new TreeSet<>();
    try (var in = Harness.class.getClassLoader().getResourceAsStream("vocabulary/llm-types.json")) {
      for (tools.jackson.databind.JsonNode type :
          FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
        if (type.path("attributeSchema").path("properties").has(LlmFacts.SUPPORT)) {
          declaring.add(type.path("name").asString());
        }
      }
    }
    // A group's name, whose type the linker's vocabulary declares, carries one too (#751).
    assertThat(
            io.lantern.derive.Vocabularies.standard()
                .find(Types.DISPLAY_NAME)
                .orElseThrow()
                .attributeSchema()
                .properties())
        .containsKey(LlmFacts.SUPPORT);
    declaring.add(Types.DISPLAY_NAME);
    assertThat(new TreeSet<>(SupportChecks.TEXTS)).isEqualTo(declaring);
  }

  @Test
  void eachPurposeKeyFactAndGroupNameIsJudgedWhateverTheSample() {
    List<Fact> held = new ArrayList<>(List.of(code));
    List<String> always = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      for (String predicate : List.of(Types.PURPOSE, Types.KEY_FACT, Types.DISPLAY_NAME)) {
        String words = predicate + " " + i + " of the shop";
        held.add(
            text(
                predicate.equals(Types.DISPLAY_NAME) ? "name-groups" : "purpose",
                predicate,
                words,
                code));
        always.add(words);
      }
    }

    SupportChecks.Result result =
        new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(), 1);

    // Each of them, though a 10% sample would take about 6 of the 60 (#751).
    Set<String> judged = new TreeSet<>();
    result.byOrigin().values().stream()
        .flatMap(List::stream)
        .filter(f -> f.attributes().hasNonNull(LlmFacts.SUPPORT))
        .forEach(f -> judged.add(SupportChecks.text(f)));
    assertThat(judged).containsExactlyInAnyOrderElementsOf(always);
  }

  @Test
  void aTextOfAnAnswerAskedAgainIsJudgedWhateverTheSampleAndARejectedOneSaysWhy() {
    List<Fact> held = new ArrayList<>(List.of(code));
    List<String> retried = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Fact text = text("describe-element", "describe-element text " + i + " is wrong", code);
      ObjectNode attributes = text.attributes().deepCopy();
      attributes.put(LlmFacts.ANSWER, "answer" + i);
      held.add(
          Fact.create(
              text.subject(),
              text.predicate(),
              text.object(),
              attributes,
              text.origin(),
              text.evidence(),
              text.confidence(),
              text.validity()));
      retried.add("answer" + i);
    }

    SupportChecks.Result result =
        new SupportChecks(harness, files, Map.of("shop", "c1"))
            .retried(Set.copyOf(retried))
            .judge(held, List.of(), 1);

    assertThat(result.judged()).isEqualTo(20);
    assertThat(result.rejected()).hasSize(20);
    assertThat(result.rejected())
        .allSatisfy(r -> assertThat(r.reason()).isEqualTo("\"It does it.\""));
    assertThat(result.rejected().getFirst().text().attributes().has(LlmFacts.SUPPORT)).isFalse();
  }

  @Test
  void aLeadVerdictIsNotJudged() {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("title", "Orders trust a wrong header");
    attributes.putArray("cites").add("shop:src/B.java:3-4");
    Fact verdict =
        Fact.create(
            EntityId.parse("lead:x/service:shop:orders"),
            Types.LEAD_VERDICT,
            FactObject.string("confirmed"),
            attributes,
            new Origin(Origin.Kind.LLM, "investigate", "1"),
            Evidence.of("shop", "src/B.java", new Evidence.LineRange(3, 4), null),
            0.8,
            Validity.from(1));

    assertThat(SupportChecks.judgeable(verdict)).isFalse();
  }

  @Test
  void aJudgedRoleIsHandedBackWholeWhateverTheVersionOfItsFacts() {
    // Summaries of a repository not analyzed again keep an older prompt version (#751).
    Fact older =
        Fact.create(
            EntityId.parse("service:shop:old"),
            Types.RESPONSIBILITY,
            FactObject.string("c4"),
            text("describe-element", "older text", code).attributes(),
            new Origin(Origin.Kind.LLM, "describe-element", "0"),
            code.evidence(),
            LlmFacts.CONFIDENCE,
            Validity.from(1));
    List<Fact> held = new ArrayList<>(List.of(flaggedCode, older));
    held.add(text("describe-element", "describe-element text on flagged code", flaggedCode));
    Fact finding =
        fact(
            "finding:injection-suspected/shop:src/Evil.java",
            "finding",
            "x",
            "src/Evil.java",
            11,
            11);

    SupportChecks.Result result =
        new SupportChecks(harness, files, Map.of("shop", "c1")).judge(held, List.of(finding), 1);

    // One recording for the name, holding both versions, so neither ends the other's facts.
    assertThat(result.byOrigin()).hasSize(1);
    assertThat(result.byOrigin().values().iterator().next())
        .extracting(f -> f.origin().version())
        .containsExactlyInAnyOrder("0", "1");
  }

  private static Fact fact(
      String subject, String predicate, String object, String path, int from, int to) {
    return Fact.create(
            EntityId.parse(subject),
            predicate,
            FactObject.string(object),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "java", "1"),
            Evidence.of("shop", path, new Evidence.LineRange(from, to), null),
            1.0,
            Validity.from(1))
        .stored(sha(subject + predicate + object), Validity.from(1));
  }

  private static Fact text(String role, String text, Fact cited) {
    return text(role, Types.RESPONSIBILITY, text, cited);
  }

  private static Fact text(String role, String predicate, String text, Fact cited) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("text", text);
    attributes.putArray("cites").add(cited.id());
    attributes.put("basis", "inferred");
    return Fact.create(
        EntityId.parse("service:shop:" + Math.abs(text.hashCode())),
        predicate,
        FactObject.string("c4"),
        attributes,
        new Origin(Origin.Kind.LLM, role, "1"),
        cited.evidence(),
        LlmFacts.CONFIDENCE,
        Validity.from(1));
  }

  private static Fact risk(String title, String range) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("title", title);
    attributes.putArray("cites").add(range);
    return Fact.create(
        EntityId.parse("service:shop:orders"),
        Types.INVESTIGATED_RISK,
        FactObject.string("identity-from-request"),
        attributes,
        new Origin(Origin.Kind.LLM, "investigate", "1"),
        Evidence.of("shop", "src/B.java", new Evidence.LineRange(3, 4), null),
        0.8,
        Validity.from(1));
  }

  private static String sha(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
