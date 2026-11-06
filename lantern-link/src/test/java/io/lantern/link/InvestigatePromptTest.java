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
            + InvestigatePrompt.role()
            + "\n--\n"
            + InvestigatePrompt.unit("service:g:a", List.of("fs"))
            + "\n--\n"
            + InvestigatePrompt.system(List.of("fs"))
            + "\n--\n"
            + InvestigatePrompt.answers(List.of("a risk"))
            + "\n--\n"
            + InvestigatePrompt.systemMap(List.of("service:g:a"), List.of("a -> b [CALLS_HTTP]"))
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
            + InvestigateLeadAnswer.MAX_EVIDENCE;
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(InvestigatePrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise InvestigatePrompt.VERSION and update this fingerprint")
        .isEqualTo("10 8e7740329d64cdf53c530890d738af56dd3a9e6ecca0bbdd2e72c8c43c51da6c");
  }

  /**
   * Modes B and C's instructions and the submit schema are the epic's, character for character
   * (knowledge-build epic, section 11.2); Mode A's own instructions live only in the prompt files
   * (#892).
   */
  @Test
  void theInvestigatePromptIsTheEpics() throws Exception {
    String epic = Files.readString(Path.of("../docs/epics/001-knowledge-build.md"));
    String section = epic.substring(epic.indexOf("### 11.2 Investigate"), epic.indexOf("### 11.3"));

    assertThat(section)
        .doesNotContain(InvestigatePrompt.LEADS_TASK)
        .contains("```text\n" + InvestigatePrompt.EXPLORE_TASK + "\n```")
        .contains("```json\n" + InvestigatePrompt.SCHEMA + "\n```");
    assertThat(InvestigatePrompt.LEADS_TASK).startsWith("Task: check one lead.");
    assertThat(InvestigatePrompt.EXPLORE_TASK).startsWith("Task: investigate what static");
    assertThat(InvestigatePrompt.SUBMIT_SCHEMA).isEqualTo(FactJson.parse(InvestigatePrompt.SCHEMA));
  }
}
