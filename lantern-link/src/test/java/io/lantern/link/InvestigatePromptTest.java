package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.checks.CommonPrompt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Keeps the prompt's version in step with its text, so cached answers are not reused wrongly. */
class InvestigatePromptTest {

  /**
   * The fingerprint of the prompt texts for {@link InvestigatePrompt#VERSION}. When this fails, the
   * prompt changed: raise the version, then update the fingerprint and the version here.
   */
  @Test
  void theVersionIsRaisedWhenThePromptChanges() throws Exception {
    Fact read =
        Fact.create(
            EntityId.parse("method:f.C#upload()"),
            "reads-request-value",
            FactObject.string("X-User-Id"),
            FactJson.newObject(),
            new Origin(Origin.Kind.STATIC, "spring", "1"),
            Evidence.of("fs", "C.java", new Evidence.LineRange(2, 2), null),
            1.0,
            Validity.from(1));
    String texts =
        CommonPrompt.SYSTEM
            + "\n--\n"
            + InvestigatePrompt.role(true)
            + "\n--\n"
            + InvestigatePrompt.role(false)
            + "\n--\n"
            + InvestigatePrompt.lead(
                "lead:k/x",
                "k",
                "why",
                "judge",
                List.of("service:g:a"),
                List.of("method:f.C#upload()"),
                List.of(read),
                (repo, path) -> Optional.of(List.of("class C {", "  void upload() {}", "}")))
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
            + Investigator.LEADS_PER_PACKET
            + " "
            + Investigator.LEAD_LIMITS
            + " "
            + Investigator.EXPLORE_LIMITS
            + " "
            + InvestigateRole.MAX_PROPOSALS
            + " "
            + InvestigatePrompt.MAX_CODE_LINES
            + " "
            + InvestigatePrompt.MAX_FACTS;
    String fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(texts.getBytes(StandardCharsets.UTF_8)));

    assertThat(InvestigatePrompt.VERSION + " " + fingerprint)
        .as("The prompt changed: raise InvestigatePrompt.VERSION and update this fingerprint")
        .isEqualTo("9 6686231376754388324b1e9d5c1eb956d84cdbd34247fa79e81108129096d1dc");
  }

  /**
   * Both instructions and the submit schema are the epic's, character for character
   * (knowledge-build epic, section 11.2).
   */
  @Test
  void theInvestigatePromptIsTheEpics() throws Exception {
    String epic = Files.readString(Path.of("../docs/epics/001-knowledge-build.md"));
    String section = epic.substring(epic.indexOf("### 11.2 Investigate"), epic.indexOf("### 11.3"));

    assertThat(section)
        .contains("```text\n" + InvestigatePrompt.LEADS_TASK + "\n```")
        .contains("```text\n" + InvestigatePrompt.EXPLORE_TASK + "\n```")
        .contains("```json\n" + InvestigatePrompt.SCHEMA + "\n```");
    assertThat(InvestigatePrompt.LEADS_TASK).startsWith("Task: check leads.");
    assertThat(InvestigatePrompt.EXPLORE_TASK).startsWith("Task: investigate what static");
    assertThat(InvestigatePrompt.SUBMIT_SCHEMA).isEqualTo(FactJson.parse(InvestigatePrompt.SCHEMA));
  }

  @Test
  void aLeadsSystemMapShowsEachLinkOnceWithItsCount() {
    String map =
        InvestigatePrompt.systemMap(
            List.of("service:g:a"),
            List.of("a -> b [CALLS_HTTP]", "a -> b [CALLS_HTTP]", "a -> c [PUBLISHES]"),
            true);

    assertThat(map)
        .contains(
            "(xN: N links of that kind)", "- a -> b [CALLS_HTTP] x2\n", "- a -> c [PUBLISHES]\n");
    assertThat(map.split("a -> b", -1)).hasSize(2);
  }

  @Test
  void aLeadsAnswerMustNameItsLeadVerdictAndWhatItCites() {
    // Mode A's submit tool requires them (#892); the other modes keep the shared schema.
    assertThat(
            InvestigateRole.LEADS_SCHEMA
                .path("properties")
                .path("items")
                .path("items")
                .path("required"))
        .extracting(tools.jackson.databind.JsonNode::asString)
        .containsExactly("lead", "verdict", "kind", "names", "cites");
    assertThat(
            InvestigatePrompt.SUBMIT_SCHEMA
                .path("properties")
                .path("items")
                .path("items")
                .path("required"))
        .extracting(tools.jackson.databind.JsonNode::asString)
        .doesNotContain("lead");
  }
}
