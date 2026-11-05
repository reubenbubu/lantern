package io.lantern.app;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.SystemRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.harness.AnswerStore;
import io.lantern.link.JdbcRuleRuns;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.SandboxSettings;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the build sandbox and the analysis run. Build outputs go to {@code
 * lantern.sandbox.builds-dir}, by default {@code <workspace>/builds}, and rendered documents to
 * {@code <workspace>/documents}, where the workspace is {@code lantern.workspace} (ADR 0016).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SandboxProperties.class, DiagramProperties.class})
class AnalysisConfiguration {

  @Bean
  BuildSandbox buildSandbox(
      @Value("${lantern.workspace}") Path workspace, SandboxProperties sandbox) {
    return new BuildSandbox(sandboxSettings(workspace, sandbox));
  }

  /**
   * The sandbox settings: the defaults, with builds in the configured builds directory, else in the
   * workspace, and the configured ones.
   */
  static SandboxSettings sandboxSettings(Path workspace, SandboxProperties sandbox) {
    return SandboxSettings.defaults(buildsDirectory(sandbox.buildsDir(), workspace))
        .withMavenCacheDirectory(mavenCacheDirectory(sandbox.mavenCacheDir()))
        .withMavenRepositories(sandbox.mavenRepositories())
        .withMavenMirror(sandbox.mavenMirror());
  }

  @Bean
  HtmlRenderer htmlRenderer() {
    return HtmlRenderer.fromClassPath();
  }

  @Bean
  C4ReportRenderer c4ReportRenderer(DiagramProperties diagrams) {
    return C4ReportRenderer.fromClassPath(diagrams.limits());
  }

  @Bean
  AnalysisRun analysisRun(
      RepoRegistry registry,
      SystemRegistry systems,
      SnapshotStore snapshots,
      AnalysisLock lock,
      BuildSandbox sandbox,
      FactRepository facts,
      PlatformTransactionManager transactionManager,
      LlmAccess llm,
      JdbcLlmCallLog callLog,
      AnswerStore answers,
      LlmProperties llmProperties,
      ViewDefinitionStore views,
      HtmlRenderer renderer,
      C4ReportRenderer c4Renderer,
      JdbcExtractionCache extractionCache,
      JdbcRuleRuns ruleRuns,
      @Value("${lantern.version}") String toolVersion,
      @Value("${lantern.workspace}") Path workspace,
      @Value("${lantern.judge.wording:false}") boolean judgeWording,
      @Value("${lantern.harness.calibrate:}") String calibrate,
      @Value("${lantern.harness.calibration-trial:1}") String calibrationTrial,
      org.springframework.core.env.Environment environment) {
    return new AnalysisRun(
            registry,
            systems,
            snapshots,
            lock,
            sandbox,
            facts,
            new TransactionTemplate(transactionManager),
            llm,
            callLog,
            answers,
            llmProperties.runBudget(),
            views,
            renderer,
            c4Renderer,
            extractionCache,
            ruleRuns,
            toolVersion,
            absolute(workspace).resolve("documents"))
        .batchingAbove(llmProperties.batchingAbove())
        .judgingWording(judgeWording)
        .supportJudgedBy(environment.getProperty("lantern.harness.support-judge.model", "writer"))
        .calibrating(calibrations(calibrate, calibrationTrial))
        .itemsPerRequest(itemsPerRequest(environment::getProperty))
        .packing(packets(environment::getProperty));
  }

  /**
   * How the first requests of the roles that join them are packed (#874): at most {@code
   * lantern.harness.packets.items} items a request, under {@code
   * lantern.harness.packets.input-tokens} input tokens of the model, leaving {@code
   * lantern.harness.packets.thinking-per-item} output tokens an item for thinking; no packing
   * without {@code items}.
   */
  static io.lantern.harness.Packing.Limits packets(
      java.util.function.Function<String, String> properties) {
    String items = properties.apply("lantern.harness.packets.items");
    if (items == null || items.isBlank()) {
      return null;
    }
    return new io.lantern.harness.Packing.Limits(
        number(properties, "lantern.harness.packets.input-tokens", "90000"),
        (int) number(properties, "lantern.harness.packets.items", items),
        (int) number(properties, "lantern.harness.packets.thinking-per-item", "1500"));
  }

  private static long number(
      java.util.function.Function<String, String> properties, String key, String otherwise) {
    String value = properties.apply(key);
    value = value == null || value.isBlank() ? otherwise : value.strip();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalStateException(key + " needs a whole number: " + value, e);
    }
  }

  /**
   * The items a request of each role that asks several, from {@code
   * lantern.harness.items-per-request.<role>} (#529, ADR 0112); a role without the property keeps
   * its spec's size.
   */
  static java.util.Map<String, Integer> itemsPerRequest(
      java.util.function.Function<String, String> properties) {
    java.util.Map<String, Integer> sizes = new java.util.TreeMap<>();
    for (String role : CALIBRATABLE) {
      String key = "lantern.harness.items-per-request." + role;
      String value = properties.apply(key);
      if (value == null || value.isBlank()) {
        continue;
      }
      int size;
      try {
        size = Integer.parseInt(value.strip());
      } catch (NumberFormatException e) {
        throw new IllegalStateException(key + " needs a whole number of items: " + value, e);
      }
      if (size < 1) {
        throw new IllegalStateException(key + " must be at least 1: " + value);
      }
      sizes.put(role, size);
    }
    return sizes;
  }

  /**
   * The roles {@code lantern.harness.calibrate} names, as {@code <role>:<packet size>} separated by
   * commas, each under the trial {@code lantern.harness.calibration-trial}.
   */
  /** The roles that ask several items a request, the ones a calibration can name. */
  static final java.util.Set<String> CALIBRATABLE =
      java.util.Set.of(
          "resolve",
          "investigate-leads",
          "describe-element",
          "name-groups",
          "support-judge",
          "injection-screen",
          "wording-judge-role",
          "wording-judge-specific");

  static java.util.Map<String, io.lantern.harness.Harness.Calibration> calibrations(
      String calibrate, String trial) {
    java.util.Map<String, io.lantern.harness.Harness.Calibration> calibrations =
        new java.util.LinkedHashMap<>();
    if (calibrate == null || calibrate.isBlank()) {
      return calibrations;
    }
    for (String entry : calibrate.split(",")) {
      String[] parts = entry.strip().split(":");
      if (parts.length != 2 || parts[0].isBlank()) {
        throw new IllegalStateException(
            "lantern.harness.calibrate takes <role>:<packet size>, separated by commas: " + entry);
      }
      int size;
      try {
        size = Integer.parseInt(parts[1].strip());
      } catch (NumberFormatException e) {
        throw new IllegalStateException(
            "lantern.harness.calibrate needs a whole packet size: " + entry, e);
      }
      if (!CALIBRATABLE.contains(parts[0].strip())) {
        throw new IllegalStateException(
            "lantern.harness.calibrate names "
                + parts[0].strip()
                + ", not a role that asks several items a request: one of "
                + new java.util.TreeSet<>(CALIBRATABLE));
      }
      calibrations.put(parts[0].strip(), new io.lantern.harness.Harness.Calibration(size, trial));
    }
    return calibrations;
  }

  @Bean
  JdbcRuleRuns ruleRuns(JdbcClient jdbc) {
    return new JdbcRuleRuns(jdbc);
  }

  @Bean
  JdbcExtractionCache extractionCache(JdbcClient jdbc) {
    return new JdbcExtractionCache(jdbc);
  }

  @Bean
  AnalyzeCommand analyzeCommand(AnalysisRun run) {
    return new AnalyzeCommand(run);
  }

  @Bean
  JudgeLabelsCommand judgeLabelsCommand(
      LlmAccess llm, AnswerStore answers, LlmProperties llmProperties) {
    return new JudgeLabelsCommand(llm, answers, llmProperties.runBudget(), System.out);
  }

  private static Path absolute(Path path) {
    return path.toAbsolutePath().normalize();
  }

  /**
   * The Maven cache directory a setting names: a leading {@code ~} is the user's home directory,
   * and a relative path is resolved against the working directory. The default if not set.
   */
  static Path mavenCacheDirectory(String setting) {
    if (setting == null || setting.isBlank()) {
      return SandboxSettings.defaultMavenCacheDirectory();
    }
    return hostDirectory(setting);
  }

  /**
   * The builds directory a setting names, read as {@link #mavenCacheDirectory} reads its setting;
   * {@code <workspace>/builds} if not set (#786).
   */
  static Path buildsDirectory(String setting, Path workspace) {
    if (setting == null || setting.isBlank()) {
      return absolute(workspace).resolve("builds");
    }
    return hostDirectory(setting);
  }

  private static Path hostDirectory(String setting) {
    String home = System.getProperty("user.home");
    if (setting.equals("~")) {
      return absolute(Path.of(home));
    }
    if (setting.startsWith("~/")) {
      return absolute(Path.of(home, setting.substring(2)));
    }
    return absolute(Path.of(setting));
  }
}
