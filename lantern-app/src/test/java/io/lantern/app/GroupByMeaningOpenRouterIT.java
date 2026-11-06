package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.Composer;
import io.lantern.compose.ViewDefinition;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.CallLimiter;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.anthropic.AnthropicProvider;
import io.lantern.summarize.describe.GroupByMeaning;
import io.lantern.summarize.describe.NameGroups;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The grouping role (#919; ADR 0129) on a real model, alone, from the facts an earlier analyze run
 * stored: what static analysis found, re-derived with today's rules, and the responsibilities
 * Describe wrote then on a real model. It composes the system's document before and after, so the
 * scorer can count each view's boxes and score the feature areas pair by pair on the same inputs,
 * and Name groups names what the grouping formed. It writes {@code before/} and {@code after/}
 * (each {@code _system.c4.html}, {@code linked.jsonl.gz} and {@code llm.jsonl.gz}), {@code calls/},
 * and {@code report.md} with the groups formed and the cost, to the checkout's git-ignored {@code
 * workspace/llm-logs/grouping/<time>-<description>/}.
 *
 * <p>It costs money, so it runs only when asked: {@code lantern.test.grouping-run} names the
 * documents folder of a real-model analyze run (its {@code extracted.jsonl.gz}, {@code
 * linked.jsonl.gz} and {@code llm.jsonl.gz}), {@code lantern.test.grouping-description} what the
 * run tests, and {@code ANTHROPIC_API_KEY} the OpenRouter key; {@code lantern.test.grouping-model}
 * names the settings file's model the role uses (default {@code haiku}), {@code
 * lantern.test.grouping-cap} the most US dollars it may spend (default 0.50), and {@code
 * lantern.test.grouping-replay} the {@code calls/} folder of an earlier run to answer from instead,
 * free. Commands: the {@code lantern-commands} skill.
 */
@EnabledIfSystemProperty(
    named = "lantern.test.grouping-run",
    matches = ".+",
    disabledReason = "lantern.test.grouping-run names no saved run; this test costs money")
class GroupByMeaningOpenRouterIT {

  private static final Path DEFAULT_OUT =
      Path.of("..", "workspace", "llm-logs", "grouping").toAbsolutePath().normalize();

  private static final Path DEFAULT_SETTINGS =
      Path.of("..", ".claude", "skills", "lantern-commands", "llm-openrouter.properties");

  /** The roles whose earlier texts the run keeps: Describe's, the flows', Purpose's. */
  private static final Set<String> KEPT =
      Set.of("describe-unit", "describe-element", "flow-significance", "flow-narrative", "purpose");

  @Test
  void groupsWhatTheCodeLeftUngroupedOnTheRealModelAndComposesBeforeAndAfter() throws IOException {
    Path saved = Path.of(required("lantern.test.grouping-run"));
    String description = required("lantern.test.grouping-description");
    String replay = System.getProperty("lantern.test.grouping-replay", "");
    BigDecimal cap =
        new BigDecimal(
            System.getProperty("lantern.test.grouping-cap", replay.isBlank() ? "0.50" : "100"));
    String modelName = System.getProperty("lantern.test.grouping-model", "haiku");
    Path settingsFile =
        Path.of(System.getProperty("lantern.test.llm-properties", DEFAULT_SETTINGS.toString()));
    String key = replay.isBlank() ? System.getenv("ANTHROPIC_API_KEY") : "replaying";
    assertThat(key).as("ANTHROPIC_API_KEY, the OpenRouter key").isNotBlank();
    Path out =
        DEFAULT_OUT.resolve(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-"
                + SummarizeOpenRouterIT.slug(description));
    Path calls = out.resolve("calls");
    Files.createDirectories(calls);
    if (System.getProperty(io.lantern.harness.Prompts.FOLDER_PROPERTY) == null) {
      io.lantern.harness.Prompts.useFolder(Path.of("..", "prompts").toAbsolutePath().normalize());
    }

    // What static analysis and the linker found, the model derived again by today's rules.
    List<Fact> found =
        new ArrayList<>(SummarizeOpenRouterIT.read(saved.resolve(ExtractedFacts.FILE)));
    for (Fact fact : SummarizeOpenRouterIT.read(saved.resolve(ExtractedFacts.LINKED_FILE))) {
      if (fact.origin().kind() != Origin.Kind.LLM) {
        found.add(fact);
      }
    }
    long snapshot = found.stream().mapToLong(f -> f.validity().from()).max().orElse(1) + 1;
    List<Fact> derived = io.lantern.derive.Derivation.withModel(found, snapshot);
    // The earlier run's texts of the roles before grouping, and its resolved links.
    List<Fact> texts = new ArrayList<>();
    for (Fact fact : SummarizeOpenRouterIT.read(saved.resolve(ExtractedFacts.LLM_FILE))) {
      if (KEPT.contains(fact.origin().name()) || !LlmFacts.isText(fact)) {
        if (!fact.origin().name().equals(GroupByMeaning.NAME)
            && !fact.origin().name().equals(NameGroups.ROLE.name())) {
          texts.add(fact);
        }
      }
    }
    List<Fact> before = new ArrayList<>(derived);
    before.addAll(texts);

    // The model: the settings file's, with the grouping role and Name groups on the one named.
    LlmProperties properties =
        SummarizeOpenRouterIT.settings(
            settingsFile,
            key,
            Map.of(
                "lantern.llm.roles.group-by-meaning",
                modelName,
                "lantern.llm.roles.name-groups",
                modelName));
    ModelSettings models = properties.modelSettings();
    io.lantern.llm.LlmProvider model =
        replay.isBlank()
            ? AnthropicProvider.create(
                key,
                properties.anthropic().baseUrl(),
                properties.batchPoll(),
                properties.anthropic().onlyProviders())
            : RecordReplayLlmProvider.replaying("anthropic", Path.of(replay));
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    LlmClient client =
        new LlmClient(
            models,
            List.of(RecordReplayLlmProvider.recordingEvery(model, calls)),
            new BudgetGuard("grouping test", null, cap),
            log,
            Clock.systemUTC(),
            new CallLimiter(properties.retry().toPolicy()));
    Harness harness =
        new Harness(client, new InMemoryAnswerStore(), RunBudget.NONE, (r, p) -> null, Set.of())
            .batchingAbove(Integer.MAX_VALUE);
    C4ReportRenderer renderer = C4ReportRenderer.fromClassPath();

    GroupByMeaning.Result grouped =
        new GroupByMeaning(harness, renderer.limits().target(), renderer.limits().tolerance())
            .group(before, snapshot);
    List<Fact> after = grouped.replacing(before);
    NameGroups.Result named = new NameGroups(harness).name(after, snapshot);
    after.addAll(named.facts());

    String system = write(out.resolve("before"), before, renderer, snapshot);
    write(out.resolve("after"), after, renderer, snapshot);

    BigDecimal cost =
        log.calls().stream().map(LlmCall::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    StringBuilder report = new StringBuilder();
    report.append("# ").append(description).append("\n\n");
    report
        .append("From ")
        .append(saved)
        .append(", system ")
        .append(system)
        .append("; the grouping role and Name groups on ")
        .append(modelName)
        .append(replay.isBlank() ? "" : " (replayed from " + replay + ")")
        .append(".\n\n");
    report
        .append("Calls: ")
        .append(log.calls().size())
        .append(", cost $")
        .append(cost.toPlainString())
        .append(". Asked: ")
        .append(grouped.asked())
        .append(", members placed: ")
        .append(grouped.added())
        .append(".\n\n## Groups formed or extended\n\n");
    Map<String, List<String>> members = new TreeMap<>();
    for (Fact fact : grouped.facts()) {
      if (fact.predicate().equals(io.lantern.core.types.Types.GROUPS)) {
        members
            .computeIfAbsent(fact.subject().toString(), g -> new ArrayList<>())
            .add(fact.object().value().toString());
      }
    }
    members.forEach(
        (group, list) -> {
          report.append("- ").append(group).append(":\n");
          list.forEach(m -> report.append("  - ").append(m).append('\n'));
        });
    report.append("\n## Names\n\n");
    for (Fact fact : named.facts()) {
      if (fact.predicate().equals(io.lantern.core.types.Types.DISPLAY_NAME)) {
        report
            .append("- ")
            .append(fact.subject())
            .append(": ")
            .append(fact.attributes().path("text").asString())
            .append('\n');
      }
    }
    Files.writeString(out.resolve("report.md"), report);
    System.out.println("Wrote " + out);
    assertThat(cost).isLessThanOrEqualTo(cap);
  }

  /** Composes and writes the system's document and the facts the scorer reads; its system name. */
  private static String write(
      Path folder, List<Fact> facts, C4ReportRenderer renderer, long snapshot) {
    Map<String, String> commits = new TreeMap<>();
    for (Fact fact : facts) {
      String repo = fact.evidence().repo();
      if (repo != null && !repo.isEmpty()) {
        commits.put(repo, "0".repeat(40));
      }
    }
    Snapshot at = new Snapshot(snapshot, commits, 0, "test", Instant.now());
    String title = "System";
    Composer composer =
        new Composer("test", io.lantern.derive.Vocabularies.standard(), renderer.limits());
    var document =
        composer.compose(
            ViewDefinition.c4System(AnalysisRun.SYSTEM_C4, title, AnalysisRun.C4_REPORT),
            at,
            facts,
            title);
    try {
      Files.createDirectories(folder);
      Files.writeString(folder.resolve("_system.c4.html"), renderer.render(document));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    List<Fact> llm = facts.stream().filter(f -> f.origin().kind() == Origin.Kind.LLM).toList();
    List<Fact> linked = facts.stream().filter(f -> f.origin().kind() != Origin.Kind.LLM).toList();
    ExtractedFacts.write(folder, ExtractedFacts.LINKED_FILE, linked);
    ExtractedFacts.write(folder, ExtractedFacts.LLM_FILE, llm);
    return title;
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    assertThat(value).as(property).isNotBlank();
    return value;
  }
}
