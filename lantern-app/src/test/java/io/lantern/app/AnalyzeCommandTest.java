package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
  }

  @Test
  void namesRepositoriesAfterTheirDirectoryUnlessNamed() {
    Map<String, Path> repos =
        AnalyzeCommand.repos(List.of("/clones/org.openwms.tms.transportation/", "shop=/x/../y"));

    assertThat(repos)
        .containsExactly(
            Map.entry(
                "org.openwms.tms.transportation",
                Path.of("/clones/org.openwms.tms.transportation")),
            Map.entry("shop", Path.of("/y")));
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
    when(run.run(any())).thenThrow(new LlmNotConfiguredException("no tiers"));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    AnalyzeCommand command =
        new AnalyzeCommand(run, new PrintStream(bytes, true, StandardCharsets.UTF_8));

    command.run(new DefaultApplicationArguments("analyze", "/clones/shop"));

    assertThat(command.getExitCode()).isEqualTo(1);
    assertThat(bytes.toString(StandardCharsets.UTF_8))
        .contains("The analysis failed: The LLM is not configured: no tiers");
  }
}
