package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RejectedAnswer;
import io.lantern.harness.RunBudget;
import io.lantern.link.ValueResolution;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.CallLimiter;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmThinking;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.anthropic.AnthropicProvider;
import io.lantern.summarize.GitSourceFiles;
import io.lantern.summarize.Summarizer;
import io.lantern.summarize.Summary;
import io.lantern.summarize.SummaryLevel;
import io.lantern.summarize.SummaryNode;
import io.lantern.summarize.SummaryTree;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Summarize alone on real classes through a real model (#871): the type summaries of a saved
 * project-4 run, sent by Lantern's own Summarize role and harness to the model the settings file
 * names for it (Claude Haiku through OpenRouter in {@code llm-openrouter.properties}), within a
 * cost cap. It writes, per run, every request and answer (a folder {@code lantern.llm.replay-from}
 * can replay for free), what the checks kept and dropped and why, the repairs and re-asks, the
 * tokens by kind, the time and the cost, to {@code
 * ~/workspace/lantern-llm-logs/summarize/<time>-<description>/} (written in {@code current/} while
 * the run goes on, to watch, and moved there when it ends), and each call, as soon as it finishes,
 * as plain text to read in its {@code log/}. It is the measuring stick of the Summarize changes
 * that follow it (#872 to #875).
 *
 * <p>It costs money, so it runs only when asked, never in the full check: when {@code
 * lantern.test.summarize-run} names the documents folder of an analyze run of project-4 (its {@code
 * extracted.jsonl.gz} and {@code linked.jsonl.gz}; a free fake-model run serves) and {@code
 * lantern.test.project4} the folder project-4 is set up in, with {@code ANTHROPIC_API_KEY} set to
 * the OpenRouter key, and {@code lantern.test.summarize-description} says what the run tests, such
 * as "Baseline: today's format against Haiku through OpenRouter", written at the top of its report,
 * README and logs and into its folder's name. {@code lantern.test.summarize-items} (default 10)
 * says how many classes, and {@code lantern.test.summarize-cap} (default 0.10) the most US dollars
 * it may spend: a call whose worst case would pass it is not made. {@code
 * lantern.test.llm-properties} names another settings file. Commands: the {@code lantern-commands}
 * skill.
 */
@EnabledIfSystemProperty(
    named = "lantern.test.summarize-run",
    matches = ".+",
    disabledReason = "lantern.test.summarize-run names no saved run; this test costs money")
class SummarizeOpenRouterIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Where runs go unless {@code lantern.test.summarize-out} says otherwise: outside the checkout.
   */
  private static final Path DEFAULT_OUT =
      Path.of(System.getProperty("user.home"), "workspace", "lantern-llm-logs", "summarize");

  /** The folder a run writes into while it goes on. */
  static final String CURRENT = "current";

  private static final Path DEFAULT_SETTINGS =
      Path.of("..", ".claude", "skills", "lantern-commands", "llm-openrouter.properties");

  /** The log line Summarize writes when the checks leave sentences out of an answer it keeps. */
  private static final Pattern LEFT_OUT =
      Pattern.compile(
          "^Left out (\\d+) sentences from the answer to summarize type (\\S+): (.*)$",
          Pattern.DOTALL);

  /**
   * A kept sentence's citations, as a stored summary writes them after it: one run of markers per
   * sentence, however many it cites.
   */
  static final Pattern KEPT = Pattern.compile("(?:\\s*\\[(?:fact|summary):[^\\]]*\\])+");

  /** A left-out sentence's check, in that line: {@code sentences.2 (Names): ...}. */
  private static final Pattern CHECK = Pattern.compile("sentences(?:\\.\\d+)? \\(([^)]+)\\)");

  @Test
  void summarizesProject4sClassesThroughTheRealModelWithinTheCapAndRecordsWhatHappened()
      throws IOException {
    Path saved = Path.of(System.getProperty("lantern.test.summarize-run"));
    Path project4 = Path.of(required("lantern.test.project4"));
    String description = required("lantern.test.summarize-description");
    int items = Integer.parseInt(System.getProperty("lantern.test.summarize-items", "10"));
    BigDecimal cap = new BigDecimal(System.getProperty("lantern.test.summarize-cap", "0.10"));
    Path settingsFile =
        Path.of(System.getProperty("lantern.test.llm-properties", DEFAULT_SETTINGS.toString()));
    String key = System.getenv("ANTHROPIC_API_KEY");
    assertThat(key).as("ANTHROPIC_API_KEY, the OpenRouter key").isNotBlank();
    Path runs = Path.of(System.getProperty("lantern.test.summarize-out", DEFAULT_OUT.toString()));
    String runName =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-"
            + slug(description);
    // A run writes into one fixed folder, to watch, and is moved to a folder of its own when it
    // ends; a folder an interrupted run left there is moved aside first.
    Path out = runs.resolve(CURRENT);
    if (Files.exists(out)) {
      Files.move(out, runs.resolve("interrupted-before-" + runName));
    }
    Path calls = out.resolve("calls");
    Files.createDirectories(calls);
    Files.createDirectories(out.resolve("log"));

    // The classes: those the architecture model reads, as analyze picks them, spread evenly over
    // their sorted IDs so a run of a few covers every repository.
    List<Fact> extracted = read(saved.resolve(ExtractedFacts.FILE));
    List<Fact> linked = read(saved.resolve(ExtractedFacts.LINKED_FILE));
    List<Fact> summarizable = new ArrayList<>(extracted);
    summarizable.addAll(new ValueResolution().derive(extracted, 1));
    List<SummaryNode> types = new ArrayList<>();
    SummaryTree.fromFacts(summarizable, AnalysisRun.modelTypes(linked))
        .forEach(root -> collectTypes(root, types));
    types.sort(Comparator.comparing(n -> n.entity().toString()));
    List<SummaryNode> chosen = spread(types, items);

    // The prompts of the checkout, as a run reads them, so an edit there needs no rebuild.
    if (System.getProperty(io.lantern.harness.Prompts.FOLDER_PROPERTY) == null) {
      io.lantern.harness.Prompts.useFolder(Path.of("..", "prompts").toAbsolutePath().normalize());
    }

    // The model, as a run that counts builds it, recording every call.
    LlmProperties properties = settings(settingsFile, key);
    AnthropicProvider anthropic =
        AnthropicProvider.create(
            key,
            properties.anthropic().baseUrl(),
            properties.batchPoll(),
            properties.anthropic().onlyProviders());
    InMemoryLlmCallLog log = new InMemoryLlmCallLog();
    String model = properties.modelSettings().forRole(io.lantern.llm.Role.SUMMARIZE_TYPES).model();
    LlmClient client =
        new LlmClient(
            properties.modelSettings(),
            List.of(
                new LiveLog(
                    RecordReplayLlmProvider.recordingEvery(anthropic, calls),
                    out.resolve("log"),
                    description + " (" + model + ")")),
            new BudgetGuard("summarize test", null, cap),
            log,
            Clock.systemUTC(),
            new CallLimiter(properties.retry().toPolicy()));
    Map<String, Path> repos = repositories(project4);
    Harness harness =
        new Harness(client, new InMemoryAnswerStore(), RunBudget.NONE, (r, p) -> null, Set.of())
            .batchingAbove(Integer.MAX_VALUE);

    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    Logger summarizerLog = (Logger) LoggerFactory.getLogger(Summarizer.class);
    summarizerLog.addAppender(logged);
    Summarizer.Result result = null;
    String stopped = null;
    long started = System.nanoTime();
    try {
      Files.writeString(
          out.resolve("README.md"),
          "# " + about(description, chosen, model, cap, settingsFile, saved));
      result =
          new Summarizer(harness, new GitSourceFiles(name -> Optional.ofNullable(repos.get(name))))
              .summarize(chosen);
    } catch (RuntimeException e) {
      // Whatever stopped it, what was spent is reported.
      stopped = e.getClass().getSimpleName() + ": " + e.getMessage();
    } finally {
      summarizerLog.detachAppender(logged);
      anthropic.close();
    }
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    ObjectNode report =
        report(chosen, result, stopped, took, log.calls(), harness, logged, cap, calls);
    report.put("description", description);
    report.put("model", model);
    Files.writeString(out.resolve("report.json"), JSON.writeValueAsString(report));
    Files.writeString(out.resolve("report.md"), markdown(report, chosen, result));
    Path done = Files.move(out, runs.resolve(runName));
    System.out.println("Summarize test: " + done.toAbsolutePath());

    assertThat(log.calls()).as("calls made").isNotEmpty();
    assertThat(cost(log.calls()))
        .as("spent within the cap, plus what was running")
        .isLessThanOrEqualTo(cap.multiply(BigDecimal.TWO));
    if (result != null) {
      for (SummaryNode node : chosen) {
        assertThat(
                result.summaries().containsKey(node.entity())
                    || result.rejected().contains(node.entity())
                    || result.notAsked().contains(node.entity())
                    || result.omitted().contains(node.entity()))
            .as("an outcome for %s", node.entity())
            .isTrue();
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Inputs

  private static String required(String property) {
    String value = System.getProperty(property);
    assertThat(value).as(property).isNotBlank();
    return value;
  }

  static List<Fact> read(Path file) throws IOException {
    List<Fact> facts = new ArrayList<>();
    try (Reader in =
            new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8);
        BufferedReader lines = new BufferedReader(in)) {
      for (String line = lines.readLine(); line != null; line = lines.readLine()) {
        if (!line.isBlank()) {
          facts.add(FactJson.read(line));
        }
      }
    }
    return facts;
  }

  private static void collectTypes(SummaryNode node, List<SummaryNode> types) {
    if (node.level() == SummaryLevel.TYPE) {
      types.add(node);
      return;
    }
    node.children().forEach(child -> collectTypes(child, types));
  }

  /** {@code n} of the nodes, evenly spaced over them; all of them when there are fewer. */
  static <T> List<T> spread(List<T> all, int n) {
    if (all.size() <= n) {
      return List.copyOf(all);
    }
    List<T> chosen = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      chosen.add(all.get((int) ((long) i * all.size() / n)));
    }
    return chosen;
  }

  private static Map<String, Path> repositories(Path folder) throws IOException {
    Map<String, Path> repos = new TreeMap<>();
    try (Stream<Path> list = Files.list(folder)) {
      list.filter(repo -> Files.exists(repo.resolve(".git")))
          .forEach(repo -> repos.put(repo.getFileName().toString(), repo));
    }
    return repos;
  }

  /** The settings file's {@code lantern.llm} properties, bound as the app binds them. */
  private static LlmProperties settings(Path file, String key) throws IOException {
    Properties raw = new Properties();
    try (Reader in = Files.newBufferedReader(file)) {
      raw.load(in);
    }
    Map<String, String> map = new LinkedHashMap<>();
    raw.stringPropertyNames().forEach(name -> map.put(name, raw.getProperty(name)));
    map.put("lantern.llm.anthropic.api-key", key);
    return new Binder(new MapConfigurationPropertySource(map))
        .bind("lantern.llm", LlmProperties.class)
        .orElseThrow(() -> new IllegalStateException("No lantern.llm settings in " + file));
  }

  // -----------------------------------------------------------------------------------------------
  // The record

  private static BigDecimal cost(List<LlmCall> calls) {
    return calls.stream()
        .map(LlmCall::costUsd)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static ObjectNode report(
      List<SummaryNode> chosen,
      Summarizer.Result result,
      String stopped,
      Duration took,
      List<LlmCall> calls,
      Harness harness,
      ListAppender<ILoggingEvent> logged,
      BigDecimal cap,
      Path recordings)
      throws IOException {
    ObjectNode report = JSON.createObjectNode();
    report.put("items", chosen.size());
    report.put("cap", cap.toPlainString());
    if (stopped != null) {
      report.put("stopped", stopped);
    }
    if (result != null) {
      report.put("summarized", result.summaries().size());
      report.put("rejectedTwice", result.rejected().size());
      report.put("notAsked", result.notAsked().size());
      report.put("omitted", result.omitted().size());
    }
    // What the checks left out of answers they kept, by check.
    int leftOut = 0;
    Map<String, Integer> byCheck = new TreeMap<>();
    ObjectNode leftOutByItem = report.putObject("leftOutByItem");
    for (ILoggingEvent event : logged.list) {
      Matcher m = LEFT_OUT.matcher(event.getFormattedMessage());
      if (!m.matches()) {
        continue;
      }
      leftOut += Integer.parseInt(m.group(1));
      String earlier = leftOutByItem.path(m.group(2)).asString("");
      leftOutByItem.put(m.group(2), earlier.isEmpty() ? m.group(3) : earlier + " | " + m.group(3));
      Matcher check = CHECK.matcher(m.group(3));
      while (check.find()) {
        byCheck.merge(check.group(1), 1, Integer::sum);
      }
    }
    report.put("sentencesLeftOut", leftOut);
    ObjectNode checks = report.putObject("sentencesLeftOutByCheck");
    byCheck.forEach(checks::put);
    // Whole answers sent back (repair turns, re-asks) and items dropped, with why.
    List<RejectedAnswer> rejections = harness.rejections();
    report.put("answersSentBack", rejections.stream().filter(r -> !r.drop()).count());
    report.put("itemsDropped", rejections.stream().filter(RejectedAnswer::drop).count());
    var sentBack = report.putArray("rejections");
    rejections.forEach(
        r -> {
          ObjectNode n = sentBack.addObject();
          n.put("subject", r.subject());
          n.put("attempt", r.attempt());
          var reasons = n.putArray("reasons");
          r.reasons().forEach(reasons::add);
        });
    // The sentences the model wrote, read from its answers.
    report.put("sentencesAnswered", answeredSentences(recordings));
    if (result != null) {
      report.put(
          "sentencesKept",
          result.summaries().values().stream()
              .mapToLong(s -> KEPT.matcher(s.text()).results().count())
              .sum());
    }
    // Calls, tokens by kind, time and cost.
    ObjectNode tokens = report.putObject("tokens");
    tokens.put("input", calls.stream().mapToLong(c -> c.usage().input()).sum());
    tokens.put("cacheWrite", calls.stream().mapToLong(c -> c.usage().cacheWrite()).sum());
    tokens.put("cacheWriteLong", calls.stream().mapToLong(c -> c.usage().cacheWriteLong()).sum());
    tokens.put("cacheRead", calls.stream().mapToLong(c -> c.usage().cacheRead()).sum());
    tokens.put("outputWithThinking", calls.stream().mapToLong(c -> c.usage().output()).sum());
    report.put("calls", calls.size());
    report.set(
        "callsByOutcome",
        JSON.valueToTree(
            calls.stream()
                .collect(
                    java.util.stream.Collectors.groupingBy(
                        c -> c.outcome().name(),
                        TreeMap::new,
                        java.util.stream.Collectors.counting()))));
    report.put("costUsd", cost(calls).toPlainString());
    report.put("seconds", took.toMillis() / 1000.0);
    report.put(
        "callSeconds", calls.stream().mapToLong(c -> c.duration().toMillis()).sum() / 1000.0);
    report.set("roles", JSON.valueToTree(harness.records().stream().map(r -> r.toJson()).toList()));
    return report;
  }

  /**
   * Sentences in the answers the model gave, as the recordings hold them: every answer's items'
   * sentences, repair turns and re-asks included.
   */
  private static int answeredSentences(Path recordings) throws IOException {
    int sentences = 0;
    try (Stream<Path> files = Files.list(recordings)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
        String text =
            JSON.readTree(Files.readString(file)).path("response").path("text").asString();
        int start = text.indexOf('{');
        if (start < 0) {
          continue;
        }
        try {
          JsonNode answer = JSON.readTree(text.substring(start, text.lastIndexOf('}') + 1));
          for (JsonNode item : answer.path("items")) {
            sentences += item.path("sentences").size();
          }
        } catch (RuntimeException e) {
          // An answer that is not one JSON object counts no sentences; the checks rejected it.
        }
      }
    }
    return sentences;
  }

  private static String about(
      String description,
      List<SummaryNode> chosen,
      String model,
      BigDecimal cap,
      Path settings,
      Path facts) {
    return description
        + "\n\nSummarize on "
        + chosen.size()
        + " classes of project-4 through "
        + model
        + ", within $"
        + cap.toPlainString()
        + "; settings "
        + settings.toAbsolutePath().normalize()
        + ", facts "
        + facts
        + ", prompts "
        + io.lantern.harness.Prompts.folder()
        + ".\n\nlog/ gets one file per call as it finishes; report.md comes at the end.\n";
  }

  /**
   * A provider that writes each call, the moment it finishes, as one plain-text file to read: what
   * the run tests, what the call was for, its model, tokens, time and cost, then the system prompt,
   * every message of the request, the model's thinking when the provider returns it, and its
   * answer, pretty-printed when it is JSON. Numbered in the order the calls finish.
   */
  static final class LiveLog implements LlmProvider {

    private final LlmProvider provider;
    private final Path folder;
    private final String description;
    private final java.util.concurrent.atomic.AtomicInteger finished =
        new java.util.concurrent.atomic.AtomicInteger();

    LiveLog(LlmProvider provider, Path folder, String description) {
      this.provider = provider;
      this.folder = folder;
      this.description = description;
    }

    @Override
    public String name() {
      return provider.name();
    }

    @Override
    public LlmResponse complete(String model, LlmRequest request) {
      return logged(model, null, request, () -> provider.complete(model, request));
    }

    @Override
    public LlmResponse complete(ModelConfig model, LlmRequest request) {
      return logged(model.model(), model, request, () -> provider.complete(model, request));
    }

    private LlmResponse logged(
        String model,
        ModelConfig config,
        LlmRequest request,
        java.util.function.Supplier<LlmResponse> call) {
      long started = System.nanoTime();
      LlmResponse response = null;
      RuntimeException failure = null;
      try {
        response = call.get();
        return response;
      } catch (RuntimeException e) {
        failure = e;
        throw e;
      } finally {
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        String text = text(description, model, config, request, response, failure, took);
        String name =
            String.format(
                    "%03d-%s.md",
                    finished.incrementAndGet(), request.purpose().replaceAll("[^A-Za-z0-9.]+", "-"))
                .replaceAll("-{2,}", "-");
        try {
          Files.writeString(folder.resolve(name), text);
        } catch (IOException e) {
          // The call happened and is paid for; a log that cannot be written must not lose it.
          System.err.println("Cannot write the log of " + request.purpose() + ": " + e);
        }
      }
    }

    static String text(
        String description,
        String model,
        ModelConfig config,
        LlmRequest request,
        LlmResponse response,
        RuntimeException failure,
        Duration took) {
      StringBuilder text = new StringBuilder();
      text.append("# ").append(request.purpose()).append("\n\n");
      text.append("run: ").append(description).append('\n');
      text.append("model: ").append(model).append('\n');
      text.append("time: ").append(took.toMillis() / 1000.0).append(" s\n");
      if (response != null) {
        TokenUsage usage = response.usage();
        text.append("stop: ").append(response.stopReason()).append('\n');
        text.append("tokens: input ")
            .append(usage.input())
            .append(", cache write ")
            .append(usage.cacheWrite() + usage.cacheWriteLong())
            .append(", cache read ")
            .append(usage.cacheRead())
            .append(", output with thinking ")
            .append(usage.output())
            .append(" (output limit ")
            .append(request.maxOutputTokens())
            .append(")\n");
        if (config != null && config.pricing() != null) {
          text.append("cost: $").append(config.pricing().cost(usage).toPlainString()).append('\n');
        }
      }
      if (failure != null) {
        text.append("failed: ").append(failure.getMessage()).append('\n');
      }
      text.append("\n## System prompt\n\n").append(request.system()).append("\n\n");
      for (LlmMessage message : request.messages()) {
        text.append("## ")
            .append(message.author().name().toLowerCase(java.util.Locale.ROOT))
            .append("\n\n")
            .append(message.text())
            .append("\n\n");
      }
      if (response != null) {
        for (LlmThinking thought : response.thinking()) {
          text.append("## Thinking\n\n").append(thought.text()).append("\n\n");
        }
        text.append("## Answer\n\n").append(pretty(response.text())).append('\n');
      }
      return text.toString();
    }
  }

  /** A short file-name form of the run's description. */
  static String slug(String description) {
    String slug =
        description
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    return slug.length() > 60 ? slug.substring(0, 60).replaceAll("-$", "") : slug;
  }

  /** The text pretty-printed when it is one JSON object, else as it is. */
  private static String pretty(String text) {
    try {
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(text.strip()));
    } catch (RuntimeException e) {
      return text;
    }
  }

  private static String markdown(
      ObjectNode report, List<SummaryNode> chosen, Summarizer.Result result) {
    StringBuilder md =
        new StringBuilder("# ")
            .append(report.path("description").asString())
            .append("\n\nSummarize through a real model.\n\n");
    md.append("| Measure | Value |\n| --- | --- |\n");
    for (String field :
        List.of(
            "model",
            "items",
            "summarized",
            "rejectedTwice",
            "notAsked",
            "omitted",
            "sentencesAnswered",
            "sentencesKept",
            "sentencesLeftOut",
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
    report
        .path("sentencesLeftOutByCheck")
        .properties()
        .forEach(
            e ->
                md.append("| left out by ")
                    .append(e.getKey())
                    .append(" | ")
                    .append(e.getValue().asString())
                    .append(" |\n"));
    md.append("\n## Summaries\n\n");
    for (SummaryNode node : chosen) {
      md.append("- `").append(node.entity()).append("`: ");
      Summary summary = result == null ? null : result.summaries().get(node.entity());
      md.append(
          summary == null ? "(none)" : summary.text().replaceAll(" ?\\[fact:[0-9a-f]{64}\\]", ""));
      md.append('\n');
    }
    JsonNode left = report.path("leftOutByItem");
    if (!left.isEmpty()) {
      md.append("\n## Sentences left out\n\n");
      left.properties()
          .forEach(
              e ->
                  md.append("- `")
                      .append(e.getKey())
                      .append("`: ")
                      .append(e.getValue().asString())
                      .append('\n'));
    }
    return md.toString();
  }
}
