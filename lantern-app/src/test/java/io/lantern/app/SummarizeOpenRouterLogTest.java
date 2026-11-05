package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live log of {@link SummarizeOpenRouterIT}: each call, as soon as it finishes, as one text
 * file headed by what the run tests.
 */
class SummarizeOpenRouterLogTest {

  @TempDir Path dir;

  @Test
  void eachCallIsWrittenAsItFinishesWithTheRunsDescriptionPromptAndPrettyAnswer()
      throws IOException {
    FakeLlmProvider fake =
        new FakeLlmProvider().answering("{\"items\": {\"I1\": {\"sentences\": []}}}");
    SummarizeOpenRouterIT.LiveLog live =
        new SummarizeOpenRouterIT.LiveLog(fake, dir, "Baseline against Haiku through OpenRouter");

    live.complete(
        fake.model("m"),
        LlmRequest.of(
            Role.SUMMARIZE_TYPES,
            "summarize type type:p.Orders",
            "You help document.",
            "<items>I1</items>",
            4400));

    Path file = dir.resolve("001-summarize-type-type-p.Orders.md");
    assertThat(file).exists();
    assertThat(Files.readString(file))
        .startsWith(
            "# summarize type type:p.Orders\n\nrun: Baseline against Haiku through OpenRouter\n"
                + "model: m\n")
        .contains("(output limit 4400)")
        .contains("## System prompt\n\nYou help document.")
        .contains("## user\n\n<items>I1</items>")
        .contains("## Answer\n\n{\n  \"items\"");
  }

  @Test
  void aRunsFolderIsNamedByAShortFormOfItsDescription() {
    assertThat(SummarizeOpenRouterIT.slug("Baseline: today's format against Haiku (OpenRouter)"))
        .isEqualTo("baseline-today-s-format-against-haiku-openrouter");
  }
}
