package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.FactJson;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A unit's or the system's TOML answer (#904): read into numbered proposals with their evidence, an
 * empty answer allowed, and a problem named by the entry's title, not its number.
 */
class InvestigateExploreAnswerTest {

  private static final String RISK =
      """
      [[risk]]
      title = "Uploads have no size limit"
      finding = "upload keeps the whole file in memory."
      consequence = "One large upload can stop the service."
      impact = "medium"
      confidence = "low"
      units = ["service:g:files"]

      [[risk.evidence]]
      lines = "fs:F.java:6"
      quote = "void upload(MultipartFile file) {"
      shows = ["upload", "MultipartFile"]
      """;

  private static final String CONNECTION =
      """
      [[connection]]
      from = "service:g:files"
      to = "external-system:s3"
      interaction = "http"
      description = "Stores uploaded files"
      technology = "S3"

      [[connection.evidence]]
      lines = "fs:S.java:3-4"
      quote = "rest.post().uri(BUCKET)"
      shows = "BUCKET"
      """;

  private static JsonNode read(InvestigateExploreAnswer answer, String toml) {
    return answer.read(FactJson.newObject().put("toml", toml));
  }

  @Test
  void eachTableIsAProposalNumberedOnAcrossAnswers() {
    InvestigateExploreAnswer answer = new InvestigateExploreAnswer();

    JsonNode first = read(answer, RISK + "\n" + CONNECTION);
    JsonNode second = read(answer, RISK);

    JsonNode risk = first.path(InvestigatePrompt.PROPOSALS).path("P1");
    assertThat(risk.path("kind").asString()).isEqualTo("risk");
    assertThat(risk.path("title").asString()).isEqualTo("Uploads have no size limit");
    assertThat(risk.path("units").get(0).asString()).isEqualTo("service:g:files");
    assertThat(risk.path("cites").get(0).asString()).isEqualTo("fs:F.java:6-6");
    assertThat(risk.path("names")).hasSize(2);
    JsonNode connection = first.path(InvestigatePrompt.PROPOSALS).path("P2");
    assertThat(connection.path("kind").asString()).isEqualTo("connection");
    assertThat(connection.path("to").asString()).isEqualTo("external-system:s3");
    assertThat(connection.path("names").get(0).asString()).isEqualTo("BUCKET");
    assertThat(second.path(InvestigatePrompt.PROPOSALS).has("P3")).isTrue();
  }

  @Test
  void anAnswerWithNoTableSaysNothingWasFound() {
    assertThat(read(new InvestigateExploreAnswer(), "").path(InvestigatePrompt.PROPOSALS))
        .isEmpty();
  }

  @Test
  void anEntryWithoutEvidenceIsRefusedByItsTitle() {
    String bare = RISK.substring(0, RISK.indexOf("[[risk.evidence]]"));

    assertThatThrownBy(() -> read(new InvestigateExploreAnswer(), bare))
        .hasMessageContaining(
            "risk \"Uploads have no size limit\": give at least one [[risk.evidence]] piece.");
  }

  @Test
  void aProblemOfAProposalIsNamedByItsKindAndTitle() {
    InvestigateExploreAnswer answer = new InvestigateExploreAnswer();
    read(answer, RISK + "\n" + CONNECTION);

    String reply =
        answer.failed(
            List.of(
                "proposals.P1: Names the unit x, which is not a deployable unit.",
                "proposals.P2: Connects to infrastructure."),
            1,
            InvestigateExploreAnswer.ATTEMPTS);

    assertThat(reply)
        .contains("\"risk \\\"Uploads have no size limit\\\": Names the unit x")
        .contains("\"connection to external-system:s3: Connects to infrastructure.\"")
        .contains("answers_left = 2")
        .doesNotContain("proposals.P");
  }

  @Test
  void aTableWrittenAsTextIsAnAnswer() {
    InvestigateExploreAnswer answer = new InvestigateExploreAnswer();

    assertThat(answer.holdsAnswer(RISK)).isTrue();
    assertThat(answer.holdsAnswer("I found nothing.")).isFalse();
  }
}
