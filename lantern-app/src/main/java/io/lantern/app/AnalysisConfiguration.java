package io.lantern.app;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.compose.WordingStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.SystemRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.link.InvestigationStore;
import io.lantern.link.ResolutionStore;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.SandboxSettings;
import io.lantern.summarize.SummaryStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the build sandbox and the analysis run. Build outputs go to {@code <workspace>/builds} and
 * rendered documents to {@code <workspace>/documents}, where the workspace is {@code
 * lantern.workspace} (ADR 0016).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProperties.class)
class AnalysisConfiguration {

  @Bean
  BuildSandbox buildSandbox(
      @Value("${lantern.workspace}") Path workspace, SandboxProperties sandbox) {
    return new BuildSandbox(sandboxSettings(workspace, sandbox));
  }

  /** The sandbox settings: the defaults, with builds in the workspace and the configured ones. */
  static SandboxSettings sandboxSettings(Path workspace, SandboxProperties sandbox) {
    return SandboxSettings.defaults(absolute(workspace).resolve("builds"))
        .withMavenCacheDirectory(mavenCacheDirectory(sandbox.mavenCacheDir()))
        .withMavenRepositories(sandbox.mavenRepositories())
        .withMavenMirror(sandbox.mavenMirror());
  }

  @Bean
  HtmlRenderer htmlRenderer() {
    return HtmlRenderer.fromClassPath();
  }

  @Bean
  C4ReportRenderer c4ReportRenderer() {
    return C4ReportRenderer.fromClassPath();
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
      SummaryStore summaries,
      ResolutionStore resolutions,
      InvestigationStore investigations,
      WordingStore wordings,
      ViewDefinitionStore views,
      HtmlRenderer renderer,
      C4ReportRenderer c4Renderer,
      @Value("${lantern.version}") String toolVersion,
      @Value("${lantern.workspace}") Path workspace) {
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
        summaries,
        resolutions,
        investigations,
        wordings,
        views,
        renderer,
        c4Renderer,
        toolVersion,
        absolute(workspace).resolve("documents"));
  }

  @Bean
  AnalyzeCommand analyzeCommand(AnalysisRun run) {
    return new AnalyzeCommand(run);
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
