package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lantern.core.fact.EntityId;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.llm.Role;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class AnalyzeCommandTest {

  @Test
  void isRequestedByItsNameAsTheFirstWord() {
    assertThat(AnalyzeCommand.requested("analyze", "repo")).isTrue();
    assertThat(AnalyzeCommand.requested("--lantern.workspace=/w", "analyze", "repo")).isTrue();
    assertThat(AnalyzeCommand.requested()).isFalse();
    assertThat(AnalyzeCommand.requested("--debug")).isFalse();
    assertThat(AnalyzeCommand.requested("repo", "analyze")).isFalse();
    assertThat(AnalyzeCommand.requested("--system=s", "remove", "repo")).isTrue();
  }

  @Test
  void namesTheSystemAfterTheRepositoriesFolderUnlessNamed() {
    Map<String, Path> together =
        AnalyzeCommand.repos(List.of("/sets/project-4/Auth-Service", "/sets/project-4/Main"));
    assertThat(AnalyzeCommand.system(null, together)).isEqualTo("project-4");
    assertThat(AnalyzeCommand.system("crm", together)).isEqualTo("crm");

    Map<String, Path> apart = AnalyzeCommand.repos(List.of("/a/one", "/b/two"));
    assertThatThrownBy(() -> AnalyzeCommand.system(null, apart))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--system=<name>");
    assertThat(AnalyzeCommand.system("both", apart)).isEqualTo("both");
    assertThatThrownBy(() -> AnalyzeCommand.system(null, AnalyzeCommand.repos(List.of("/one"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--system=<name>");
    assertThatThrownBy(
            () -> AnalyzeCommand.system(null, AnalyzeCommand.repos(List.of("/my set/one"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid system name");
  }

  @Test
  void passesTheSystemAndTitleToTheRun() {
    AnalysisRun run = mock(AnalysisRun.class);
    when(run.run(any(), any(), any()))
        .thenReturn(result(new JdbcLlmCallLog.Spending(0, 0, BigDecimal.ZERO, Map.of())));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    command.run(
        new DefaultApplicationArguments(
            "analyze", "--system=crm", "--title=APA CRM", "/sets/project-4/Main"));

    assertThat(command.getExitCode()).isZero();
    verify(run).run("crm", "APA CRM", Map.of("Main", Path.of("/sets/project-4/Main")));
    assertThat(bytes.toString(StandardCharsets.UTF_8)).startsWith("System clones (shop)\n");
  }

  @Test
  void removesRepositoriesFromTheNamedSystem() {
    AnalysisRun run = mock(AnalysisRun.class);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    command.run(new DefaultApplicationArguments("remove", "--system=crm", "Deployment", "Docs"));

    assertThat(command.getExitCode()).isZero();
    verify(run).remove("crm", List.of("Deployment", "Docs"));
    assertThat(bytes.toString(StandardCharsets.UTF_8))
        .contains("Removed from crm: Deployment, Docs");

    AnalyzeCommand withoutSystem =
        new AnalyzeCommand(run, new PrintStream(new ByteArrayOutputStream()));
    withoutSystem.run(new DefaultApplicationArguments("remove", "Deployment"));
    assertThat(withoutSystem.getExitCode()).isEqualTo(1);
  }

  @Test
  void namesRepositoriesAfterTheirDirectoryUnlessNamed() {
    Map<String, Path> repos =
        AnalyzeCommand.repos(
            List.of("/clones/org.openwms.tms.transportation/", "shop=/x/../y", "/x/a=b"));

    assertThat(repos)
        .containsExactly(
            Map.entry(
                "org.openwms.tms.transportation",
                Path.of("/clones/org.openwms.tms.transportation")),
            Map.entry("shop", Path.of("/y")),
            Map.entry("a=b", Path.of("/x/a=b")));
  }

  @Test
  void refusesNoRepositoriesAndNamesGivenTwice() {
    assertThatThrownBy(() -> AnalyzeCommand.repos(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Usage");
    assertThatThrownBy(() -> AnalyzeCommand.repos(List.of("/a/shop", "shop=/b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("named twice");
  }

  @Test
  void doesNothingWithoutTheCommand() {
    AnalysisRun run = mock(AnalysisRun.class);
    AnalyzeCommand command = new AnalyzeCommand(run);

    command.run(new DefaultApplicationArguments("--debug"));

    verifyNoInteractions(run);
    assertThat(command.getExitCode()).isZero();
  }

  @Test
  void aFailedRunExitsWithOneAndSaysWhy() {
    AnalysisRun run = mock(AnalysisRun.class);
    when(run.run(any(), any(), any())).thenThrow(new LlmNotConfiguredException("no tiers"));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    command.run(new DefaultApplicationArguments("analyze", "/clones/shop"));

    assertThat(command.getExitCode()).isEqualTo(1);
    assertThat(bytes.toString(StandardCharsets.UTF_8))
        .contains("The analysis failed: The LLM is not configured: no tiers");
  }

  @Test
  void listsTheUnitsLeftWithoutASummary() {
    String printed =
        printed(result(new JdbcLlmCallLog.Spending(7, 100, BigDecimal.ZERO, Map.of())));

    assertThat(printed)
        .contains(
            "Units without a summary, the LLM's answer rejected twice (1); rerun to try them again:\n"
                + "  type:com.example.Order\n");
  }

  @Test
  void countsTheLinksResolvedAndEveryCallUnderItsRole() {
    String printed =
        printed(
            result(
                new JdbcLlmCallLog.Spending(
                    9,
                    100,
                    new BigDecimal("0.25"),
                    Map.of(Role.COMPOSE, 1L, Role.SUMMARIZE, 6L, Role.RESOLVE, 2L))));

    assertThat(printed)
        .contains("Links left open: 2 asked of the LLM, 1 reused\n")
        .contains("LLM calls: 9 (SUMMARIZE=6, RESOLVE=2, COMPOSE=1), tokens: 100, cost: $0.25\n");
  }

  private static AnalysisRun.Result result(JdbcLlmCallLog.Spending llm) {
    return new AnalysisRun.Result(
        "clones",
        "shop",
        new Snapshot(1, Map.of("shop", "abc"), 0, "test", Instant.EPOCH),
        Map.of(),
        List.of(),
        10,
        10,
        2,
        1,
        5,
        0,
        List.of(EntityId.parse("type:com.example.Order")),
        1,
        0,
        llm,
        List.of());
  }

  /** What a successful run with the given result prints. */
  private static String printed(AnalysisRun.Result result) {
    AnalysisRun run = mock(AnalysisRun.class);
    when(run.run(any(), any(), any())).thenReturn(result);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    command.run(new DefaultApplicationArguments("analyze", "/clones/shop"));

    assertThat(command.getExitCode()).isZero();
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
