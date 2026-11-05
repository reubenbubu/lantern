package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RejectedAnswer;
import io.lantern.harness.RunBudget;
import io.lantern.link.GraphTools;
import io.lantern.link.Investigator;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.CallLimiter;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.Role;
import io.lantern.llm.anthropic.AnthropicProvider;
import io.lantern.summarize.GitSourceFiles;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Investigate alone on real leads through a real model (#890): the leads of a saved analyze run,
 * each checked by Lantern's own Investigate role and harness as a run checks it (Mode A, with its
 * tools over the repositories at their checked-out commits), on Claude Haiku through OpenRouter
 * (the owner's choice for this milestone, 5 Nov 2026), within a cost cap. No unit or the system is
 * explored. It writes, per run, every request and answer (replayable for free with {@code
 * lantern.llm.replay-from}), each call as plain text the moment it finishes, and a report of each
 * lead's verdict, turns, tokens by kind, tool calls, time and cost, to the checkout's git-ignored
 * {@code workspace/llm-logs/investigate/<time>-<description>/} (in {@code current/} while it runs).
 * It is the measuring stick of milestone 2.9's changes.
 *
 * <p>It costs money, so it runs only when asked: {@code lantern.test.investigate-run} names the
 * documents folder of an analyze run (its {@code extracted.jsonl.gz} and {@code linked.jsonl.gz}; a
 * free fake-model run serves), {@code lantern.test.investigate-repos} the folder its repositories
 * are set up in, {@code lantern.test.investigate-description} what the run tests, and {@code
 * ANTHROPIC_API_KEY} the OpenRouter key. {@code lantern.test.investigate-leads} names a file that
 * lists the leads (such as {@code investigate/project-4-panel.txt} in the test resources), and
 * {@code lantern.test.investigate-limit} takes only its first so many; {@code
 * lantern.test.investigate-cap} (default 0.05) is the most US dollars it may spend. {@code
 * lantern.test.investigate-model} names the model of the settings file Investigate uses (default
 * {@code haiku}); {@code lantern.test.llm-properties} names another settings file. Commands: the
 * {@code lantern-commands} skill.
 */
@EnabledIfSystemProperty(
    named = "lantern.test.investigate-run",
    matches = ".+",
    disabledReason = "lantern.test.investigate-run names no saved run; this test costs money")
class InvestigateOpenRouterIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Path DEFAULT_OUT =
      Path.of("..", "workspace", "llm-logs", "investigate").toAbsolutePath().normalize();

  private static final Path DEFAULT_SETTINGS =
      Path.of("..", ".claude", "skills", "lantern-commands", "llm-openrouter.properties");

  /** The purpose of a lead's conversation, as Investigate names it. */
  private static final String CONVERSATION = "investigate-leads conversation: ";

  @Test
  void checksTheChosenLeadsThroughTheRealModelWithinTheCapAndRecordsWhatHappened()
      throws IOException {
    Path saved = Path.of(required("lantern.test.investigate-run"));
    Path reposFolder = Path.of(required("lantern.test.investigate-repos"));
    String description = required("lantern.test.investigate-description");
    Path leadsFile = Path.of(required("lantern.test.investigate-leads"));
    int limit = Integer.parseInt(System.getProperty("lantern.test.investigate-limit", "1000"));
    BigDecimal cap = new BigDecimal(System.getProperty("lantern.test.investigate-cap", "0.05"));
    String modelName = System.getProperty("lantern.test.investigate-model", "haiku");
    Path settingsFile =
        Path.of(System.getProperty("lantern.test.llm-properties", DEFAULT_SETTINGS.toString()));
    String key = System.getenv("ANTHROPIC_API_KEY");
    assertThat(key).as("ANTHROPIC_API_KEY, the OpenRouter key").isNotBlank();
    Path runs = Path.of(System.getProperty("lantern.test.investigate-out", DEFAULT_OUT.toString()));
    String runName =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-"
            + SummarizeOpenRouterIT.slug(description);
    Path out = runs.resolve(SummarizeOpenRouterIT.CURRENT);
    if (Files.exists(out)) {
      Files.move(out, runs.resolve("interrupted-before-" + runName));
    }
    Path calls = out.resolve("calls");
    Files.createDirectories(calls);
    Files.createDirectories(out.resolve("log"));

    // The facts as a run stores them before Investigate: static and linked facts, leads included.
    List<Fact> facts =
        new ArrayList<>(SummarizeOpenRouterIT.read(saved.resolve(ExtractedFacts.FILE)));
    facts.addAll(SummarizeOpenRouterIT.read(saved.resolve(ExtractedFacts.LINKED_FILE)));
    Set<String> known = new LinkedHashSet<>();
    facts.stream()
        .filter(f -> f.predicate().equals(Types.LEAD_TRIGGER))
        .forEach(f -> known.add(f.subject().toString()));
    List<String> chosen = listed(leadsFile, known).stream().limit(limit).toList();

    if (System.getProperty(io.lantern.harness.Prompts.FOLDER_PROPERTY) == null) {
      io.lantern.harness.Prompts.useFolder(Path.of("..", "prompts").toAbsolutePath().normalize());
    }

    // The model: the settings file's, with Investigate on the model this run names.
    LlmProperties properties =
        SummarizeOpenRouterIT.settings(
            settingsFile, key, Map.of("lantern.llm.roles.investigate", modelName));
    ModelSettings models = properties.modelSettings();
    ModelConfig investigateModel = models.forRole(Role.INVESTIGATE);
    AnthropicProvider anthropic =
        AnthropicProvider.create(
            key,
            properties.anthropic().baseUrl(),
            properties.batchPoll(),
            properties.anthropic().onlyProviders());
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    LlmClient client =
        new LlmClient(
            models,
            List.of(
                new SummarizeOpenRouterIT.LiveLog(
                    RecordReplayLlmProvider.recordingEvery(anthropic, calls),
                    out.resolve("log"),
                    description + " (" + investigateModel.model() + ")")),
            new BudgetGuard("investigate test", null, cap),
            log,
            Clock.systemUTC(),
            new CallLimiter(properties.retry().toPolicy()));

    // The repositories at the commits they are checked out at, as a run reads its snapshot.
    Map<String, Path> repos = SummarizeOpenRouterIT.repositories(reposFolder);
    Map<String, String> commits = new TreeMap<>();
    for (Map.Entry<String, Path> repo : repos.entrySet()) {
      commits.put(repo.getKey(), head(repo.getValue()));
    }
    Set<String> secrets = ConcurrentHashMap.newKeySet();
    GitSourceFiles sources = new GitSourceFiles(name -> Optional.ofNullable(repos.get(name)));
    GraphTools.RepoFiles files =
        new GraphTools.RepoFiles() {
          @Override
          public void masked(String value) {
            secrets.add(value);
          }

          @Override
          public Optional<byte[]> read(String repo, String path) {
            return Optional.ofNullable(commits.get(repo))
                .flatMap(commit -> sources.read(repo, commit, path));
          }

          @Override
          public List<GraphTools.Hit> find(String repo, String text, int max) {
            String commit = commits.get(repo);
            return commit == null
                ? List.of()
                : sources.grep(repo, commit, text, max).stream()
                    .map(match -> new GraphTools.Hit(match.path(), match.line()))
                    .toList();
          }
        };
    Harness harness =
        new Harness(client, new InMemoryAnswerStore(), RunBudget.NONE, (r, p) -> null, secrets)
            .batchingAbove(Integer.MAX_VALUE);

    Investigator.Result result = null;
    String stopped = null;
    long started = System.nanoTime();
    try {
      Files.writeString(
          out.resolve("README.md"),
          "# "
              + description
              + "\n\nInvestigate (Mode A only) on "
              + chosen.size()
              + " leads through "
              + investigateModel.model()
              + ", within $"
              + cap.toPlainString()
              + "; leads "
              + leadsFile.toAbsolutePath().normalize()
              + ", facts "
              + saved
              + ", repositories "
              + reposFolder
              + ", prompts "
              + io.lantern.harness.Prompts.folder()
              + ".\n\nlog/ gets one file per call as it finishes; report.md comes at the end.\n");
      result =
          new Investigator(harness, files, io.lantern.derive.LeadRules.judges())
              .leads(facts, 1, Set.copyOf(chosen)::contains);
    } catch (RuntimeException e) {
      stopped = e.getClass().getSimpleName() + ": " + e.getMessage();
    } finally {
      anthropic.close();
    }
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    ObjectNode report = report(chosen, result, stopped, took, log.calls(), harness, cap);
    report.put("description", description);
    report.put("model", investigateModel.model());
    Files.writeString(out.resolve("report.json"), JSON.writeValueAsString(report));
    Files.writeString(out.resolve("report.md"), markdown(report));
    Path done = Files.move(out, runs.resolve(runName));
    System.out.println("Investigate test: " + done.toAbsolutePath());

    assertThat(log.calls()).as("calls made").isNotEmpty();
    assertThat(SummarizeOpenRouterIT.cost(log.calls()))
        .as("spent within the cap, plus what was running")
        .isLessThanOrEqualTo(cap.multiply(BigDecimal.TWO));
  }

  // -----------------------------------------------------------------------------------------------
  // Inputs

  private static String required(String property) {
    String value = System.getProperty(property);
    assertThat(value).as(property).isNotBlank();
    return value;
  }

  /** The leads a list file names, in its order; blank lines and text after {@code #} left out. */
  static List<String> listed(Path file, Set<String> known) throws IOException {
    List<String> leads = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      String id = line.replaceFirst("\\s#.*|^#.*", "").strip();
      if (!id.isEmpty()) {
        assertThat(known).as("a lead the saved run holds").contains(id);
        leads.add(id);
      }
    }
    return leads;
  }

  private static String head(Path repo) throws IOException {
    Process git = new ProcessBuilder("git", "-C", repo.toString(), "rev-parse", "HEAD").start();
    try {
      String commit = new String(git.getInputStream().readAllBytes()).strip();
      assertThat(git.waitFor()).as("git rev-parse HEAD in %s", repo).isZero();
      return commit;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The record

  private static ObjectNode report(
      List<String> chosen,
      Investigator.Result result,
      String stopped,
      Duration took,
      List<LlmCall> calls,
      Harness harness,
      BigDecimal cap) {
    ObjectNode report = JSON.createObjectNode();
    report.put("leads", chosen.size());
    report.put("cap", cap.toPlainString());
    if (stopped != null) {
      report.put("stopped", stopped);
    }
    // Each lead's verdict, as Investigate stores it.
    Map<String, Fact> verdicts = new LinkedHashMap<>();
    if (result != null) {
      result.facts().stream()
          .filter(f -> f.predicate().equals(Types.LEAD_VERDICT))
          .forEach(f -> verdicts.put(f.subject().toString(), f));
      report.put("confirmed", result.confirmed());
      report.put("asked", result.asked());
    }
    // Each lead's calls: its conversation and, when it was left unanswered, its retry alone.
    Map<String, List<LlmCall>> byLead = new LinkedHashMap<>();
    chosen.forEach(lead -> byLead.put(lead, new ArrayList<>()));
    for (LlmCall call : calls) {
      String purpose = call.purpose() == null ? "" : call.purpose();
      if (purpose.startsWith(CONVERSATION)) {
        String lead = purpose.substring(CONVERSATION.length()).replaceFirst(" \\(again\\)$", "");
        byLead.computeIfAbsent(lead, l -> new ArrayList<>()).add(call);
      }
    }
    int answered = 0;
    ArrayNode leads = report.putArray("byLead");
    for (Map.Entry<String, List<LlmCall>> e : byLead.entrySet()) {
      ObjectNode n = leads.addObject();
      n.put("lead", e.getKey());
      Fact verdict = verdicts.get(e.getKey());
      String word = verdict == null ? "(none)" : verdict.object().value();
      String reason = verdict == null ? null : verdict.attributes().path("reason").asString(null);
      n.put("verdict", word);
      if (reason != null) {
        n.put("reason", reason);
      } else if (verdict != null) {
        answered++;
      }
      List<LlmCall> mine = e.getValue();
      n.put("turns", mine.size());
      n.put("retried", mine.stream().anyMatch(c -> c.purpose().endsWith(" (again)")));
      tokens(n.putObject("tokens"), mine);
      Map<String, Integer> tools = new TreeMap<>();
      mine.forEach(
          c -> {
            if (c.toolCalls() != null) {
              c.toolCalls().forEach((tool, count) -> tools.merge(tool, count, Integer::sum));
            }
          });
      n.set("toolCalls", JSON.valueToTree(tools));
      n.put("costUsd", SummarizeOpenRouterIT.cost(mine).toPlainString());
      n.put("callSeconds", mine.stream().mapToLong(c -> c.duration().toMillis()).sum() / 1000.0);
      if (verdict != null && verdict.attributes().path("finding").isString()) {
        n.put("finding", verdict.attributes().path("finding").asString());
      }
    }
    report.put("answered", answered);
    List<RejectedAnswer> rejections = harness.rejections();
    report.put("answersSentBack", rejections.stream().filter(r -> !r.drop()).count());
    report.put("itemsDropped", rejections.stream().filter(RejectedAnswer::drop).count());
    ArrayNode sentBack = report.putArray("rejections");
    rejections.forEach(
        r -> {
          ObjectNode n = sentBack.addObject();
          n.put("subject", r.subject());
          n.put("attempt", r.attempt());
          ArrayNode reasons = n.putArray("reasons");
          r.reasons().forEach(reasons::add);
        });
    tokens(report.putObject("tokens"), calls);
    report.put("calls", calls.size());
    report.put("costUsd", SummarizeOpenRouterIT.cost(calls).toPlainString());
    report.put("seconds", took.toMillis() / 1000.0);
    report.put(
        "callSeconds", calls.stream().mapToLong(c -> c.duration().toMillis()).sum() / 1000.0);
    report.set("roles", JSON.valueToTree(harness.records().stream().map(r -> r.toJson()).toList()));
    return report;
  }

  private static void tokens(ObjectNode tokens, List<LlmCall> calls) {
    tokens.put("input", calls.stream().mapToLong(c -> c.usage().input()).sum());
    tokens.put(
        "cacheWrite",
        calls.stream().mapToLong(c -> c.usage().cacheWrite() + c.usage().cacheWriteLong()).sum());
    tokens.put("cacheRead", calls.stream().mapToLong(c -> c.usage().cacheRead()).sum());
    tokens.put("outputWithThinking", calls.stream().mapToLong(c -> c.usage().output()).sum());
    tokens.put(
        "largestTurn",
        calls.stream()
            .mapToLong(
                c ->
                    c.usage().input()
                        + c.usage().cacheWrite()
                        + c.usage().cacheWriteLong()
                        + c.usage().cacheRead())
            .max()
            .orElse(0));
  }

  private static String markdown(ObjectNode report) {
    StringBuilder md =
        new StringBuilder("# ")
            .append(report.path("description").asString())
            .append("\n\nInvestigate (Mode A) through a real model.\n\n| Measure | Value |\n")
            .append("| --- | --- |\n");
    for (String field :
        List.of(
            "model",
            "leads",
            "answered",
            "confirmed",
            "answersSentBack",
            "itemsDropped",
            "calls",
            "costUsd",
            "cap",
            "seconds",
            "callSeconds",
            "stopped")) {
      if (report.has(field)) {
        md.append("| ")
            .append(field)
            .append(" | ")
            .append(report.get(field).asString())
            .append(" |\n");
      }
    }
    report
        .path("tokens")
        .properties()
        .forEach(
            e ->
                md.append("| tokens ")
                    .append(e.getKey())
                    .append(" | ")
                    .append(e.getValue().asString())
                    .append(" |\n"));
    md.append("\n## Leads\n\n| Lead | Verdict | Turns | Input | Cache read | Output | Cost |\n")
        .append("| --- | --- | --- | --- | --- | --- | --- |\n");
    for (var lead : report.path("byLead")) {
      var t = lead.path("tokens");
      md.append("| `")
          .append(lead.path("lead").asString())
          .append("` | ")
          .append(lead.path("verdict").asString())
          .append(lead.has("reason") ? " (" + lead.path("reason").asString() + ")" : "")
          .append(lead.path("retried").asBoolean() ? ", retried" : "")
          .append(" | ")
          .append(lead.path("turns").asString())
          .append(" | ")
          .append(t.path("input").asString())
          .append(" | ")
          .append(t.path("cacheRead").asString())
          .append(" | ")
          .append(t.path("outputWithThinking").asString())
          .append(" | ")
          .append(lead.path("costUsd").asString())
          .append(" |\n");
    }
    return md.toString();
  }
}
