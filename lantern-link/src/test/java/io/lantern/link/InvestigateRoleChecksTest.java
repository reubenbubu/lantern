package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.ProposedClaim;
import io.lantern.harness.verify.Rejection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Investigate's Coverage, quotes and contradicting Conflict through its own pipeline (#769, #892):
 * a verdict or proposal declaring a name its cited lines do not hold, and a claim against a static
 * one.
 */
class InvestigateRoleChecksTest {

  private static final String CODE =
      "package f;\n"
          + "class FileController {\n"
          + "  void upload(String user) {\n"
          + "    store(user);\n"
          + "  }\n";

  private static InvestigateRole role(InvestigateRole.Mode mode) {
    GraphTools.Graph graph =
        new GraphTools.Graph(
            List.of(),
            (repo, path) ->
                repo.equals("fs") && path.equals("F.java")
                    ? Optional.of(CODE.getBytes(StandardCharsets.UTF_8))
                    : Optional.empty());
    return new InvestigateRole(
        mode,
        "",
        "",
        graph,
        graph.tools(),
        "service:g:files",
        Set.of("service:g:files"),
        e -> false,
        Set.of());
  }

  private static List<Rejection> checked(InvestigateRole role, String entry) {
    ItemAnswer answer = role.read("I1", null, FactJson.parse(entry));
    return role.pipeline().check(answer, role.inputs(List.of(), List.of("I1")));
  }

  @Test
  void aVerdictIsNotCheckedByCoverageWhichTheQuotesCheckReplaces() {
    // For a lead, the quotes check says it of the piece; coverage would only repeat it (#892).
    List<Rejection> found =
        checked(
            role(InvestigateRole.Mode.LEADS),
            "{\"verdict\": \"refuted\", \"kind\": \"risk\", \"names\": [\"X-User-Id\"],"
                + " \"cites\": [\"fs:F.java:3-4\"], \"evidence\": [{\"lines\": \"fs:F.java:3-4\","
                + " \"quote\": \"store(user);\", \"shows\": [\"X-User-Id\"]}]}");

    // Coverage runs whatever the file (it said this of the same entry before #892); the quotes
    // check's own problems are LeadAnswerTest's.
    assertThat(found).isNotEmpty().noneMatch(r -> r.check().equals("Coverage"));
  }

  @Test
  void aUnitsProposalIsNotCheckedByCoverageWhichTheQuotesCheckReplacesAndIsRejectedWhole() {
    // A unit's proposal is checked piece by piece as a lead's answer is (#904); the quotes check's
    // own problems are LeadAnswerTest's. Any rejection rejects the whole proposal.
    List<Rejection> found =
        checked(
            role(InvestigateRole.Mode.UNIT),
            "{\"proposals\": {\"P1\": {\"kind\": \"risk\", \"names\": [\"upload\"],"
                + " \"cites\": [\"fs:F.java:1-1\"], \"evidence\": [{\"lines\":"
                + " \"fs:F.java:1-1\", \"quote\": \"package f;\", \"shows\": [\"upload\"]}]}}}");

    assertThat(found)
        .isNotEmpty()
        .noneMatch(r -> r.check().equals("Coverage"))
        .allMatch(r -> r.field().equals("proposals.P1"));
  }

  @Test
  void aProposalNumberedPastTheCapIsNotRejectedForItsNumber() {
    // Numbers run on across a conversation's answers; the store keeps at most the cap (#927).
    List<Rejection> found =
        checked(
            role(InvestigateRole.Mode.UNIT),
            "{\"proposals\": {\"P11\": {\"kind\": \"risk\", \"names\": [\"upload\"],"
                + " \"cites\": [\"fs:F.java:3-3\"]}}}");

    assertThat(found).noneMatch(r -> r.reason().contains("at most"));
  }

  @Test
  void aClaimContradictingAStaticClaimIsRejectedByConflict() {
    InvestigateRole role = role(InvestigateRole.Mode.UNIT);
    CheckInputs inputs = role.inputs(List.of(), List.of("I1"));
    CheckInputs withStatic =
        new CheckInputs(
            inputs.schema(),
            inputs.references(),
            inputs.citable(),
            inputs.ranges(),
            inputs.files(),
            Map.of(CheckInputs.claimKey("service:g:files", "calls"), Set.of("service:g:auth")),
            inputs.secrets(),
            inputs.shown());
    ItemAnswer answer =
        new ItemAnswer(
            "I1",
            FactJson.newObject(),
            List.of(
                new CheckedText(
                    "proposals.P1.cites",
                    CheckedText.Kind.PROSE,
                    "",
                    List.of("fs:F.java:3-3"),
                    io.lantern.harness.Contract.Range.ANY,
                    "",
                    Set.of("upload"))),
            List.of(
                new ProposedClaim(
                    "proposals.P1.calls", "service:g:files", "calls", "service:g:mail")));

    List<Rejection> found = role.pipeline().check(answer, withStatic);

    // A contradiction, not a repetition, and it rejects the whole proposal.
    assertThat(found)
        .anyMatch(
            r ->
                r.check().equals("Conflict")
                    && r.field().equals("proposals.P1")
                    && r.reason()
                        .equals(
                            "Contradicts a claim the static analysis made: service:g:files calls"
                                + " service:g:auth."));
  }
}
