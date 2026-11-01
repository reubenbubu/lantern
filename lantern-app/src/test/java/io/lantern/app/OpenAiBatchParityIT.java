package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.document.Document;
import io.lantern.core.document.DocumentJson;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.SystemRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.harness.AnswerStore;
import io.lantern.harness.RoleRecord;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.openai.OpenAiStub;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.MavenMirror;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Criterion 11 and the batch path (ADR 0102): a set is analyzed with the fake model sent
 * interactively, then again with every role on the OpenAI provider, through a stub of OpenAI's Chat
 * Completions, Files and Batch APIs that answers with the same fake, every bulk role batching all
 * its misses. Each role's stored answers and every document must come out the same.
 *
 * <p>The set is project-0 where it is set up, as {@link Project0EndToEndIT} finds it; with {@code
 * lantern.test.set} naming a set's folder as an absolute path, that set instead, as {@link
 * TestSetScoreIT} analyzes it. Writes each role's calls, batched and interactive, of both runs to
 * {@code target/openai-parity/<set>.md}.
 */
@SpringBootTest(
    properties = {
      "lantern.llm.models.fake.provider=fake",
      "lantern.llm.models.fake.id=fake-model",
      "lantern.llm.models.fake.context-window=1000000",
      "lantern.llm.models.fake.prices.input=0",
      "lantern.llm.models.fake.prices.output=0",
      "lantern.llm.tiers.high-volume=fake",
      "lantern.llm.tiers.reasoning=fake",
      "lantern.llm.anthropic.api-key="
    })
@Import({TestcontainersConfiguration.class, Project0EndToEndIT.Fake.class})
@EnabledIf(
    value = "aSetIsPresent",
    disabledReason = "neither project-0 nor lantern.test.set is set up (docs/adr/0029)")
class OpenAiBatchParityIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired AnalysisRun run;
  @Autowired ApplicationContext context;
  @Autowired JdbcLlmCallLog callLog;
  @Autowired FakeLlmProvider fake;
  @Autowired JdbcClient jdbc;

  static boolean aSetIsPresent() {
    return System.getProperty("lantern.test.set", "").isBlank()
        ? Project0EndToEndIT.project0IsPresent()
        : Files.isDirectory(Path.of(System.getProperty("lantern.test.set")));
  }

  @DynamicPropertySource
  static void workspace(DynamicPropertyRegistry registry) throws IOException {
    Path workspace = Files.createTempDirectory("lantern-openai-parity");
    registry.add("lantern.workspace", workspace::toString);
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    MavenMirror mirror = file == null || file.isBlank() ? null : MavenMirror.from(Path.of(file));
    if (mirror != null) {
      registry.add(MavenMirror.PROPERTY + ".id", mirror::id);
      registry.add(MavenMirror.PROPERTY + ".url", () -> mirror.url().toString());
    }
  }

  @Test
  void everyRoleOnTheOpenAiProviderWithBatchingStoresTheSameAnswersAndDocuments() throws Exception {
    String set;
    Map<String, Path> repos;
    String given = System.getProperty("lantern.test.set", "");
    if (given.isBlank()) {
      set = "project-0";
      repos = Project0EndToEndIT.project0();
    } else {
      Path folder = Path.of(given).toAbsolutePath().normalize();
      set = folder.getFileName().toString();
      Path pins = Path.of("..", "testsets", set, "pins.txt");
      assertThat(TestSetScoreIT.heldOut(pins)).as(set + " is held out").isFalse();
      repos = TestSetScoreIT.repos(pins, folder);
    }

    AnalysisRun.Result interactive = run.run(set, null, repos);
    Project0EndToEndIT.assertEveryPlannedItemCountedOnce(interactive);

    try (OpenAiStub stub = new OpenAiStub(fake, fake::model)) {
      Map<String, String> values = new LinkedHashMap<>();
      values.put("lantern.llm.models.oa.provider", "openai");
      values.put("lantern.llm.models.oa.id", "fake-model");
      values.put("lantern.llm.models.oa.context-window", "1000000");
      values.put("lantern.llm.models.oa.capabilities", "tool-use,prompt-caching,batch");
      values.put("lantern.llm.models.oa.prices.input", "2");
      values.put("lantern.llm.models.oa.prices.output", "8");
      values.put("lantern.llm.models.oa.prices.cache-read", "0.5");
      // OpenAI does not charge for writing its cache.
      values.put("lantern.llm.models.oa.prices.cache-write", "0");
      values.put("lantern.llm.tiers.high-volume", "oa");
      values.put("lantern.llm.tiers.reasoning", "oa");
      values.put("lantern.llm.openai.api-key", "test");
      values.put("lantern.llm.openai.base-url", stub.baseUrl());
      values.put("lantern.llm.batch-above", "0");
      values.put("lantern.llm.batch-poll", "10ms");
      LlmProperties openai =
          new Binder(new MapConfigurationPropertySource(values))
              .bindOrCreate("lantern.llm", LlmProperties.class);
      int sentBefore = fake.sent().size();
      AnalysisRun.Result batched;
      try (LlmAccess access =
          LlmConfiguration.create(openai, List.of(), callLog, Clock.systemUTC())) {
        batched = withLlm(access, openai).run(set, null, repos);
      }

      Project0EndToEndIT.assertEveryPlannedItemCountedOnce(batched);
      // Every request of the second run went through the stub; none reached the fake directly.
      assertThat(stub.chats()).hasSize(fake.sent().size() - sentBefore);
      assertThat(stub.batches()).isPositive();
      Map<String, RoleRecord> roles = new TreeMap<>();
      batched.roles().forEach(r -> roles.put(r.role(), r));
      assertThat(roles.values())
          .allSatisfy(
              r -> {
                assertThat(r.toJson().path("provider").asString()).isEqualTo("openai");
                assertThat(r.toJson().path("items").path("failed").asInt()).as(r.role()).isZero();
              });
      // Each bulk role that asked anything batched all but its warm-up request.
      for (String bulk : List.of("summarize", "injection-screen")) {
        RoleRecord record = roles.get(bulk);
        assertThat(record).as(bulk).isNotNull();
        assertThat(record.batchCalls()).as(bulk).isEqualTo(record.calls() - 1);
      }

      // The stub reports cached tokens by OpenAI's rule (a shared prompt start of 1,024 tokens or
      // more), and every one is recorded as a cache read. Project-0's prompts share starts of 600
      // to 750 tokens, under that least, so OpenAI caches none of them (#655).
      assertThat(
              batched.roles().stream()
                  .mapToLong(r -> r.toJson().path("tokens").path("cacheRead").asLong())
                  .sum())
          .as("cache reads on OpenAI")
          .isEqualTo(stub.cachedTokens());
      // The same stored answer for every item of every role, on both providers.
      Map<String, String> fakeAnswers = answers("fake");
      Map<String, String> openaiAnswers = answers("openai");
      Map<String, String> differ = new TreeMap<>();
      for (String key : fakeAnswers.keySet()) {
        if (!fakeAnswers.get(key).equals(openaiAnswers.get(key))) {
          differ.put(key, fakeAnswers.get(key) + "\n  became " + openaiAnswers.get(key));
        }
      }
      openaiAnswers.keySet().stream()
          .filter(key -> !fakeAnswers.containsKey(key))
          .forEach(key -> differ.put(key, "only on openai: " + openaiAnswers.get(key)));
      assertThat(differ).as("answers that differ").isEmpty();
      // And the same documents, but for the snapshot they are of.
      assertThat(batched.documents()).hasSameSizeAs(interactive.documents());
      for (int i = 0; i < interactive.documents().size(); i++) {
        Document before = interactive.documents().get(i).document();
        Document after = batched.documents().get(i).document();
        assertThat(DocumentJson.write(after))
            .as(after.id())
            .isEqualTo(
                DocumentJson.write(
                    new Document(
                        before.id(),
                        before.title(),
                        before.audience(),
                        before.template(),
                        after.source(),
                        before.entities(),
                        before.facts(),
                        before.sections())));
      }
      write(set, interactive, batched, stub.cachedTokens());
    }
  }

  /**
   * The stored answers made with the provider, by role and subject, without what names the provider
   * that made them: the provider, and the keys of summaries, which hash it.
   */
  private Map<String, String> answers(String provider) {
    Map<String, String> answers = new TreeMap<>();
    jdbc.sql(
            "SELECT role, subject, coalesce(answer::text, 'omitted') AS answer FROM llm_answers"
                + " WHERE provider = :provider")
        .param("provider", provider)
        .query(
            rs -> {
              String answer = rs.getString("answer");
              if (answer.startsWith("{")) {
                tools.jackson.databind.node.ObjectNode node =
                    (tools.jackson.databind.node.ObjectNode) JSON.readTree(answer);
                node.remove(List.of("provider", "key", "citedSummaries", "inputSummaries"));
                answer = node.toString();
              }
              answers.put(rs.getString("role") + " " + rs.getString("subject"), answer);
            });
    return answers;
  }

  private static void write(
      String set, AnalysisRun.Result interactive, AnalysisRun.Result batched, long cached)
      throws IOException {
    StringBuilder md = new StringBuilder("# " + set + ": interactive on the fake, then batched");
    md.append(" on the OpenAI provider\n\n");
    md.append(
        "| Role | Interactive calls | OpenAI calls | Batched | OpenAI cache reads (tokens) |\n");
    md.append("| --- | --- | --- | --- | --- |\n");
    Map<String, long[]> rows = new TreeMap<>();
    interactive
        .roles()
        .forEach(r -> rows.computeIfAbsent(r.role(), k -> new long[4])[0] = r.calls());
    batched
        .roles()
        .forEach(
            r -> {
              long[] row = rows.computeIfAbsent(r.role(), k -> new long[4]);
              row[1] = r.calls();
              row[2] = r.batchCalls();
              row[3] = r.toJson().path("tokens").path("cacheRead").asLong();
            });
    rows.forEach(
        (role, row) ->
            md.append(
                "| %s | %d | %d | %d | %d |\n".formatted(role, row[0], row[1], row[2], row[3])));
    md.append(
        "\nThe stub reported %d cached tokens in all, by OpenAI's rule: a prompt start shared with an"
                .formatted(cached)
            + " earlier request of 1,024 tokens or more, counted in steps of 128.\n");
    Path out = Path.of("target", "openai-parity");
    Files.createDirectories(out);
    Files.writeString(out.resolve(set + ".md"), md);
    JSON.writeValue(
        out.resolve(set + ".roles.json").toFile(),
        batched.roles().stream().map(RoleRecord::toJson).toList());
  }

  private AnalysisRun withLlm(LlmAccess access, LlmProperties properties) {
    return new AnalysisConfiguration()
        .analysisRun(
            context.getBean(RepoRegistry.class),
            context.getBean(SystemRegistry.class),
            context.getBean(SnapshotStore.class),
            context.getBean(AnalysisLock.class),
            context.getBean(BuildSandbox.class),
            context.getBean(FactRepository.class),
            context.getBean(PlatformTransactionManager.class),
            access,
            callLog,
            context.getBean(AnswerStore.class),
            properties,
            context.getBean(ViewDefinitionStore.class),
            context.getBean(HtmlRenderer.class),
            context.getBean(C4ReportRenderer.class),
            context.getBean(JdbcExtractionCache.class),
            context.getBean(io.lantern.link.JdbcRuleRuns.class),
            context.getEnvironment().getRequiredProperty("lantern.version"),
            Path.of(context.getEnvironment().getRequiredProperty("lantern.workspace")));
  }
}
