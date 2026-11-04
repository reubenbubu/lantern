package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.FactJson;
import io.lantern.derive.ScopeFlows;
import io.lantern.harness.PacketRole;
import io.lantern.harness.render.Refs;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.ProposedClaim;
import io.lantern.harness.verify.Rejection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Each of the nine checks through the own pipeline of Purpose, the flow significance role and the
 * flow narrative role (#769): a crafted bad answer, read as the role reads it, is rejected by the
 * check named. Where a role's answers cannot carry what a check reads (declared names, proposed
 * claims, file ranges, secrets), the role's own pipeline is shown to hold the check all the same,
 * on an answer made to carry it.
 */
class RolePipelineChecksTest {

  /** What the one reference each item shows says. */
  private static final String LINE = "app calls pay.example.com to take the card payment";

  private static final String SECRET = "s3cr3t-token-value";

  private static Refs refs() {
    Refs refs = new Refs();
    refs.add('F', "call", "call", LINE);
    return refs;
  }

  private static PacketRole purpose() {
    return new PurposeRole.OneItem(new PurposeRole.Asked(null, refs()));
  }

  private static PacketRole significance() {
    Map<String, ScopeFlows.Candidate> flows = new HashMap<>();
    flows.put("W1", null);
    return new FlowJudgements.OneItem(
        new FlowJudgements.Asked(null, refs(), flows, Set.of(), Set.of()), false);
  }

  private static PacketRole narrative() {
    return new FlowJudgements.OneItem(
        new FlowJudgements.Asked(null, refs(), Map.of(), Set.of(), Set.of()), true);
  }

  /** A purpose answer whose sentence is the given text, citing the given references. */
  private static String purposeAnswer(String text, String... cites) {
    return "{\"purpose\": {\"text\": \"" + text + "\", \"cites\": " + cites(cites) + "}}";
  }

  /** A significance answer for the one flow, its reason the given text. */
  private static String significanceAnswer(String text, String... cites) {
    return "{\"flows\": {\"W1\": {\"significant\": true, \"category\": \"cross-service\","
        + " \"reason\": {\"text\": \""
        + text
        + "\", \"cites\": "
        + cites(cites)
        + "}}}, \"order\": [\"W1\"]}";
  }

  /** A narrative answer, its title the given text. */
  private static String narrativeAnswer(String text, String... cites) {
    return "{\"title\": {\"text\": \"" + text + "\", \"cites\": " + cites(cites) + "}}";
  }

  private static String cites(String... cites) {
    return "["
        + String.join(", ", java.util.Arrays.stream(cites).map(c -> "\"" + c + "\"").toList())
        + "]";
  }

  /** The checks that reject the answer, read and checked as the role does. */
  private static List<Rejection> checked(PacketRole role, String answer) {
    ItemAnswer read = role.read("I1", null, FactJson.parse(answer));
    return role.pipeline().check(read, role.inputs(List.of(), List.of("I1")));
  }

  private static List<String> names(List<Rejection> rejections) {
    return rejections.stream().map(Rejection::check).distinct().toList();
  }

  /** The role's inputs with the given secrets and static claims. */
  private static CheckInputs with(
      PacketRole role, Set<String> secrets, Map<String, Set<String>> staticClaims) {
    CheckInputs inputs = role.inputs(List.of(), List.of("I1"));
    return new CheckInputs(
        inputs.schema(),
        inputs.references(),
        inputs.citable(),
        inputs.ranges(),
        inputs.files(),
        staticClaims,
        secrets,
        inputs.shown());
  }

  /** One prose text citing F1, declaring the given names. */
  private static ItemAnswer carrying(
      String field, String text, Set<String> names, List<ProposedClaim> claims) {
    return new ItemAnswer(
        "I1",
        FactJson.newObject(),
        List.of(
            new CheckedText(
                field,
                CheckedText.Kind.PROSE,
                text,
                List.of("F1"),
                io.lantern.harness.Contract.Range.ANY,
                "",
                names)),
        claims);
  }

  private static List<Rejection> secretChecked(PacketRole role) {
    return role.pipeline()
        .check(
            carrying(
                "purpose", "Pays with " + SECRET + " at the card provider.", Set.of(), List.of()),
            with(role, Set.of(SECRET), Map.of()));
  }

  private static List<Rejection> coverageChecked(PacketRole role) {
    return role.pipeline()
        .check(
            carrying("purpose", "Takes the card payment.", Set.of("RefundJob"), List.of()),
            role.inputs(List.of(), List.of("I1")));
  }

  private static List<Rejection> conflictChecked(PacketRole role) {
    return role.pipeline()
        .check(
            carrying(
                "purpose",
                "Takes the card payment.",
                Set.of(),
                List.of(new ProposedClaim("purpose", "service:app", "calls", "mail.example.com"))),
            with(
                role,
                Set.of(),
                Map.of(CheckInputs.claimKey("service:app", "calls"), Set.of("pay.example.com"))));
  }

  /**
   * A range past the end of its file, checked by the role's own pipeline on its inputs made to
   * allow ranges, as these roles' inputs never do.
   */
  private static List<Rejection> rangesChecked(PacketRole role) {
    CheckInputs inputs = role.inputs(List.of(), List.of("I1"));
    CheckInputs allowing =
        new CheckInputs(
            inputs.schema(),
            inputs.references(),
            inputs.citable(),
            true,
            (repo, path) -> java.util.Optional.of(List.of("line 1", "line 2")),
            inputs.staticClaims(),
            inputs.secrets(),
            inputs.shown());
    ItemAnswer answer =
        new ItemAnswer(
            "I1",
            FactJson.newObject(),
            List.of(
                new CheckedText(
                    "purpose",
                    CheckedText.Kind.PROSE,
                    "Takes the card payment.",
                    List.of("shop:A.java:1-5"),
                    io.lantern.harness.Contract.Range.ANY,
                    "",
                    Set.of())),
            List.of());
    return role.pipeline().check(answer, allowing);
  }

  /** Whether the role's own reading of a valid answer declares no names. */
  private static boolean declaresNoNames(PacketRole role, String answer) {
    return role.read("I1", null, FactJson.parse(answer)).texts().stream()
        .allMatch(t -> t.names().isEmpty());
  }

  private static final String GENERIC =
      "Its classes are managed by Spring and wired through constructor injection for the shop.";
  private static final String NAMING =
      "Takes the card payment through the PaymentGatewayClient before the order is confirmed.";

  // --- Purpose --------------------------------------------------------------------------------

  @Test
  void purposeSchemaRejectsATextThatIsNotAString() {
    assertThat(names(checked(purpose(), "{\"purpose\": {\"text\": 5, \"cites\": [\"F1\"]}}")))
        .contains("Schema");
  }

  @Test
  void purposeReferencesRejectACiteNotInTheInput() {
    assertThat(
            checked(
                purpose(),
                purposeAnswer("Takes the card payment for every order the shop sells.", "F9")))
        .anyMatch(r -> r.check().equals("References") && r.reason().contains("F9"));
  }

  @Test
  void purposeRangeIsRejectedByReferencesForItCitesOnlyItsInput() {
    List<Rejection> found =
        checked(
            purpose(),
            purposeAnswer(
                "Takes the card payment for every order the shop sells.", "shop:src/A.java:1-3"));
    assertThat(found)
        .anyMatch(
            r ->
                r.check().equals("References")
                    && r.reason().contains("cites only references of its input"));
  }

  @Test
  void purposeRangesRejectARangePastTheEndOfItsFile() {
    assertThat(rangesChecked(purpose()))
        .anyMatch(
            r -> r.check().equals("Ranges") && r.reason().contains("outside the file's 2 lines"));
  }

  @Test
  void purposeNamesRejectANameNoCitationShows() {
    assertThat(checked(purpose(), purposeAnswer(NAMING, "F1")))
        .anyMatch(r -> r.check().equals("Names") && r.reason().contains("PaymentGatewayClient"));
  }

  @Test
  void purposeCoverageRejectsADeclaredNameItsCitationDoesNotHold() {
    assertThat(coverageChecked(purpose()))
        .anyMatch(r -> r.check().equals("Coverage") && r.reason().contains("RefundJob"));
    // The role's own answers declare no names.
    assertThat(
            purpose()
                .read(
                    "I1",
                    null,
                    FactJson.parse(purposeAnswer("Takes the card payment for every order.", "F1")))
                .texts())
        .allMatch(t -> t.names().isEmpty());
  }

  @Test
  void purposeFormRejectsASentenceOfOneWord() {
    assertThat(names(checked(purpose(), purposeAnswer("Pays.", "F1")))).contains("Form");
  }

  @Test
  void purposeGenericRejectsASentenceTrueOfAnySpringApplication() {
    assertThat(names(checked(purpose(), purposeAnswer(GENERIC, "F1")))).contains("Generic");
  }

  @Test
  void purposeConflictRejectsAClaimThatContradictsAStaticOne() {
    assertThat(conflictChecked(purpose()))
        .anyMatch(r -> r.check().equals("Conflict") && r.reason().startsWith("Contradicts"));
  }

  @Test
  void purposeSecretsRejectATextHoldingASecret() {
    assertThat(names(secretChecked(purpose()))).contains("Secrets");
  }

  // --- Flow significance ----------------------------------------------------------------------

  @Test
  void significanceSchemaRejectsAFlowWithoutItsCategory() {
    assertThat(
            names(
                checked(
                    significance(),
                    "{\"flows\": {\"W1\": {\"significant\": true, \"reason\": {\"text\":"
                        + " \"Takes the card payment.\", \"cites\": [\"F1\"]}}}, \"order\":"
                        + " [\"W1\"]}")))
        .contains("Schema");
  }

  @Test
  void significanceReferencesRejectACiteNotInTheInput() {
    assertThat(
            checked(
                significance(),
                significanceAnswer("Takes the card payment across two services.", "F9")))
        .anyMatch(r -> r.check().equals("References") && r.reason().contains("F9"));
  }

  @Test
  void significanceRangeIsRejectedByReferencesForItCitesOnlyItsInput() {
    List<Rejection> found =
        checked(
            significance(),
            significanceAnswer(
                "Takes the card payment across two services.", "shop:src/A.java:1-3"));
    assertThat(found)
        .anyMatch(
            r ->
                r.check().equals("References")
                    && r.reason().contains("cites only references of its input"));
  }

  @Test
  void significanceRangesRejectARangePastTheEndOfItsFile() {
    assertThat(rangesChecked(significance()))
        .anyMatch(
            r -> r.check().equals("Ranges") && r.reason().contains("outside the file's 2 lines"));
  }

  @Test
  void significanceNamesRejectANameNoCitationShows() {
    assertThat(
            checked(
                significance(),
                significanceAnswer("Pays through the PaymentGatewayClient across services.", "F1")))
        .anyMatch(r -> r.check().equals("Names") && r.reason().contains("PaymentGatewayClient"));
  }

  @Test
  void significanceCoverageRejectsADeclaredNameItsCitationDoesNotHold() {
    assertThat(coverageChecked(significance()))
        .anyMatch(r -> r.check().equals("Coverage") && r.reason().contains("RefundJob"));
    // The role's own answers declare no names.
    assertThat(
            declaresNoNames(
                significance(),
                significanceAnswer("Takes the card payment across services.", "F1")))
        .isTrue();
  }

  @Test
  void significanceFormRejectsAReasonOfTwoWords() {
    assertThat(names(checked(significance(), significanceAnswer("Takes payment.", "F1"))))
        .contains("Form");
  }

  @Test
  void significanceGenericRejectsAReasonTrueOfAnySpringApplication() {
    assertThat(
            names(
                checked(
                    significance(),
                    significanceAnswer(
                        "Its beans are managed by Spring for the whole shop.", "F1"))))
        .contains("Generic");
  }

  @Test
  void significanceConflictRejectsAClaimThatContradictsAStaticOne() {
    assertThat(conflictChecked(significance()))
        .anyMatch(r -> r.check().equals("Conflict") && r.reason().startsWith("Contradicts"));
  }

  @Test
  void significanceSecretsRejectATextHoldingASecret() {
    assertThat(names(secretChecked(significance()))).contains("Secrets");
  }

  // --- Flow narrative -------------------------------------------------------------------------

  @Test
  void narrativeSchemaRejectsAFieldTheSchemaDoesNotHave() {
    assertThat(
            names(
                checked(
                    narrative(),
                    "{\"title\": {\"text\": \"Card payment taken\", \"cites\": [\"F1\"]},"
                        + " \"moral\": {\"text\": \"Pay first.\", \"cites\": [\"F1\"]}}")))
        .contains("Schema");
  }

  @Test
  void narrativeReferencesRejectACiteNotInTheInput() {
    assertThat(checked(narrative(), narrativeAnswer("Card payment taken", "F9")))
        .anyMatch(r -> r.check().equals("References") && r.reason().contains("F9"));
  }

  @Test
  void narrativeRangeIsRejectedByReferencesForItCitesOnlyItsInput() {
    List<Rejection> found =
        checked(narrative(), narrativeAnswer("Card payment taken", "shop:src/A.java:1-3"));
    assertThat(found)
        .anyMatch(
            r ->
                r.check().equals("References")
                    && r.reason().contains("cites only references of its input"));
  }

  @Test
  void narrativeRangesRejectARangePastTheEndOfItsFile() {
    assertThat(rangesChecked(narrative()))
        .anyMatch(
            r -> r.check().equals("Ranges") && r.reason().contains("outside the file's 2 lines"));
  }

  @Test
  void narrativeNamesRejectANameNoCitationShows() {
    assertThat(checked(narrative(), narrativeAnswer("PaymentGatewayClient takes payment", "F1")))
        .anyMatch(r -> r.check().equals("Names") && r.reason().contains("PaymentGatewayClient"));
  }

  @Test
  void narrativeCoverageRejectsADeclaredNameItsCitationDoesNotHold() {
    assertThat(coverageChecked(narrative()))
        .anyMatch(r -> r.check().equals("Coverage") && r.reason().contains("RefundJob"));
    // The role's own answers declare no names.
    assertThat(declaresNoNames(narrative(), narrativeAnswer("Card payment taken", "F1"))).isTrue();
  }

  @Test
  void narrativeFormRejectsATitleOfOneWord() {
    assertThat(names(checked(narrative(), narrativeAnswer("Payment", "F1")))).contains("Form");
  }

  @Test
  void narrativeGenericRejectsATextTrueOfAnySpringApplication() {
    assertThat(
            names(
                checked(
                    narrative(),
                    "{\"preconditions\": {\"text\": \"" + GENERIC + "\", \"cites\": [\"F1\"]}}")))
        .contains("Generic");
  }

  @Test
  void narrativeConflictRejectsAClaimThatContradictsAStaticOne() {
    assertThat(conflictChecked(narrative()))
        .anyMatch(r -> r.check().equals("Conflict") && r.reason().startsWith("Contradicts"));
  }

  @Test
  void narrativeSecretsRejectATextHoldingASecret() {
    assertThat(names(secretChecked(narrative()))).contains("Secrets");
  }
}
