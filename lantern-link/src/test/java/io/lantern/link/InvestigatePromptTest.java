package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import io.lantern.harness.checks.CommonPrompt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps the prompt's version in step with its text, so cached answers are not reused wrongly. */
class InvestigatePromptTest {

  /**
   * The fingerprint of the prompt texts for {@link InvestigatePrompt#VERSION}. When this fails, the
   * prompt changed: raise the version, then update the fingerprint and the version here.
   */
  @Test
  void theVersionIsRaisedWhenThePromptChanges() throws Exception {
    String texts =
        CommonPrompt.SYSTEM
            + "\n--\n"
            + InvestigatePrompt.LEADS_SYSTEM
            + "\n--\n"
            + InvestigatePrompt.LEADS_TASK
            + "\n--\n"
            + io.lantern.harness.Prompts.text(InvestigateLeadMessage.QUESTIONS)
            + "\n--\n"
            + InvestigateLeadAnswer.DESCRIPTION
            + "\n--\n"
            + InvestigateLeadAnswer.REMINDER
            + "\n--\n"
            + new InvestigateLeadAnswer()
                .failed(List.of("a problem"), 1, InvestigateLeadAnswer.ATTEMPTS)
            + "\n--\n"
            + InvestigatePrompt.EXPLORE_SYSTEM
            + "\n--\n"
            + InvestigatePrompt.EXPLORE_TASK
            + "\n--\n"
            + InvestigateExploreAnswer.DESCRIPTION
            + "\n--\n"
            + new InvestigateExploreAnswer()
                .failed(List.of("a problem"), 1, InvestigateExploreAnswer.ATTEMPTS)
            + "\n--\n"
            + InvestigateExploreMessage.unit(
                "service:g:a",
                java.util.Set.of("fs"),
                java.util.Set.of("service:g:a"),
                List.of(),
                List.of(),
                List.of("lead:x confirmed: A risk", "risk: Another risk"))
            + "\n--\n"
            + InvestigateExploreMessage.system(
                List.of("fs"),
                java.util.Map.of("service:g:a", java.util.Set.of("fs")),
                java.util.Map.of(),
                List.of("a -> b [CALLS_HTTP]"),
                List.of("risk: service:g:a: A risk"))
            + "\n--\n"
            + InvestigateExploreMessage.MAX_SUMMARY
            + " "
            + InvestigateExploreAnswer.ATTEMPTS
            + " "
            + "\n--\n"
            + Investigator.MAX_OUTPUT_TOKENS
            + " "
            + Investigator.LEAD_LIMITS
            + " "
            + Investigator.EXPLORE_LIMITS
            + " "
            + InvestigateRole.MAX_PROPOSALS
            + " "
            + InvestigatePrompt.MAX_FACTS
            + " "
            + InvestigateLeadMessage.MAX_CHARS
            + " "
            + InvestigateLeadMessage.MAX_WHAT
            + " "
            + InvestigateLeadMessage.MAX_EVIDENCE
            + " "
            + InvestigateLeadMessage.METHOD_LINES
            + " "
            + InvestigateLeadMessage.AROUND
            + " "
            + InvestigateLeadAnswer.ATTEMPTS
            + " "
            + InvestigateLeadAnswer.MAX_EVIDENCE
            + " "
            + InvestigateLeadAnswer.BuiltAndDeployed.REASON
            + " "
            + new java.util.TreeSet<>(InvestigateLeadAnswer.BUILT_KINDS)
            + " "
            + InvestigateLeadAnswer.BUILD_OR_DEPLOY.pattern()
            + " "
            + ShellTools.CHARS
            + " "
            + ShellTools.WIDTH;
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(InvestigatePrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise InvestigatePrompt.VERSION and update this fingerprint")
        .isEqualTo("13 6276af18fefdeb526abc4d2e02f3d279bc0916a3ce1a47c7249cc04b861aee96");
  }

  /** Mode A is told the cut of a tool answer, the same number the tools cut at (#915). */
  @Test
  void theLeadInstructionsStateTheCutOfAToolAnswer() {
    assertThat(InvestigatePrompt.LEADS_TASK)
        .contains(
            "A tool answer longer than "
                + java.text.NumberFormat.getIntegerInstance(java.util.Locale.ENGLISH)
                    .format(ShellTools.CHARS)
                + " characters is cut");
  }

  /**
   * The submit schema is the epic's, character for character (knowledge-build epic, section 11.2);
   * the instructions of every mode live only in the prompt files, Mode A's since #892, Modes B and
   * C's since #904.
   */
  @Test
  void theInvestigatePromptIsTheEpics() throws Exception {
    String epic = Files.readString(Path.of("../docs/epics/001-knowledge-build.md"));
    String section = epic.substring(epic.indexOf("### 11.2 Investigate"), epic.indexOf("### 11.3"));

    assertThat(section)
        .doesNotContain(InvestigatePrompt.LEADS_TASK)
        .doesNotContain(InvestigatePrompt.EXPLORE_TASK)
        .contains("prompts/investigate-explore.task.txt")
        .contains("```json\n" + InvestigatePrompt.SCHEMA + "\n```");
    assertThat(InvestigatePrompt.LEADS_TASK).startsWith("Task: check one lead.");
    assertThat(InvestigatePrompt.EXPLORE_TASK).startsWith("Task: find what static analysis missed");
    assertThat(InvestigatePrompt.SUBMIT_SCHEMA).isEqualTo(FactJson.parse(InvestigatePrompt.SCHEMA));
  }
}
