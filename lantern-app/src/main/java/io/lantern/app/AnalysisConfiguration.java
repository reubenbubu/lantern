package io.lantern.app;

import io.lantern.compose.ViewDefinitionStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.SandboxSettings;
import io.lantern.summarize.SummaryStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
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
class AnalysisConfiguration {

  @Bean
  BuildSandbox buildSandbox(
      @Value("${lantern.workspace}") Path workspace,
      @Value("${lantern.sandbox.maven-cache-volume}") String mavenCacheVolume) {
    return new BuildSandbox(
        SandboxSettings.defaults(absolute(workspace).resolve("builds"))
            .withMavenCacheVolume(mavenCacheVolume));
  }

  @Bean
  HtmlRenderer htmlRenderer() {
    return HtmlRenderer.fromClassPath();
  }

  @Bean
  AnalysisRun analysisRun(
      RepoRegistry registry,
      SnapshotStore snapshots,
      AnalysisLock lock,
      BuildSandbox sandbox,
      FactRepository facts,
      PlatformTransactionManager transactionManager,
      LlmAccess llm,
      JdbcLlmCallLog callLog,
      SummaryStore summaries,
      ViewDefinitionStore views,
      HtmlRenderer renderer,
      @Value("${lantern.version}") String toolVersion,
      @Value("${lantern.workspace}") Path workspace) {
    return new AnalysisRun(
        registry,
        snapshots,
        lock,
        sandbox,
        facts,
        new TransactionTemplate(transactionManager),
        llm,
        callLog,
        summaries,
        views,
        renderer,
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
}
