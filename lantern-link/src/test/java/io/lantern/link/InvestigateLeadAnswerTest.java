package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.fact.FactJson;
import io.lantern.harness.Toml;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Rejection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A lead's answer through the {@code answer} tool, as TOML, and its quotes check (#892). */
class InvestigateLeadAnswerTest {

  private static final String PATH = "orders/src/main/java/shop/orders/OrderController.java";
  private static final String CONFIG = "orders/src/main/resources/application.yml";
  private static final String LONG = "orders/src/main/java/shop/orders/Routes.java";
  private static final String LONG_LINE =
      "  @GetMapping(path = \"/orders/{orderId}/lines/{lineId}/refunds\", produces ="
          + " MediaType.APPLICATION_JSON_VALUE, headers = \"X-Api-Version=2\") // the v2 refunds"
          + " route";

  /** The files as the model was shown them: the password masked. */
  private static final Map<String, List<String>> FILES =
      Map.of(
          PATH,
          List.of(
              "package shop.orders;",
              "",
              "class OrderController {",
              "  @PostMapping(\"/orders\")",
              "  Order place(@RequestHeader(\"X-User-Id\") String userId, Order order) {",
              "    return service.place(userId, order);",
              "  }",
              "}"),
          CONFIG,
          List.of("spring:", "  datasource:", "    password: ******"),
          LONG,
          List.of("class Routes {", LONG_LINE, "}"));

  private static final CheckInputs INPUTS =
      new CheckInputs(
          InvestigateLeadAnswer.ENTRY_SCHEMA,
          Map.of(),
          Set.of(),
          true,
          (repo, path) ->
              repo.equals("shop") ? Optional.ofNullable(FILES.get(path)) : Optional.empty(),
          Map.of(),
          Set.of());

  private static final String CONFIRMED =
      """
      verdict = "confirmed"
      title = "Orders trust the caller's user header"
      finding = "place takes the user from the X-User-Id header as the caller sent it."
      consequence = "Any caller can place orders as any user by setting the header."
      impact = "high"
      confidence = "high"

      [[evidence]]
      lines = "shop:%s:4-7"
      quote = "Order place(@RequestHeader(\\"X-User-Id\\") String userId, Order order) {"
      shows = ["X-User-Id", "place"]
      """
          .formatted(PATH);

  private final InvestigateLeadAnswer answer = new InvestigateLeadAnswer();

  private static JsonNode toml(String text) {
    return FactJson.newObject().put("toml", text);
  }

  private List<Rejection> quotes(JsonNode entry) {
    return new InvestigateLeadAnswer.Quotes()
        .check(new ItemAnswer("I1", entry, List.of(), List.of()), INPUTS);
  }

  @Test
  void anAnswerThatPassesIsReadIntoTheEntryTheChecksVerify() {
    JsonNode entry = answer.read(toml(CONFIRMED));

    assertThat(entry.path("verdict").asString()).isEqualTo("confirmed");
    assertThat(entry.path("kind").asString()).isEqualTo("risk");
    assertThat(entry.path("title").asString()).isEqualTo("Orders trust the caller's user header");
    assertThat(entry.path("cites").get(0).asString()).isEqualTo("shop:" + PATH + ":4-7");
    assertThat(entry.path("names")).hasSize(2);
    assertThat(quotes(entry)).isEmpty();
    assertThat(answer.passed()).isEqualTo("recorded = true");
  }

  @Test
  void theAnswerMayComeAsTheToolsInputItselfOrAsAPlainString() {
    JsonNode asString =
        answer.read(tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(CONFIRMED));
    JsonNode asFields = answer.read(Toml.read(CONFIRMED).root());

    assertThat(asString).isEqualTo(answer.read(toml(CONFIRMED)));
    assertThat(asFields).isEqualTo(asString);
  }

  @Test
  void eachFaultOfTheAnswerItselfIsAProblemSaidInWords() {
    assertThatThrownBy(() -> answer.read(toml(""))).hasMessageContaining("The answer is empty");
    assertThatThrownBy(() -> answer.read(toml("verdict = confirmed")))
        .hasMessageStartingWith("The answer is not TOML");
    assertThatThrownBy(() -> answer.read(toml("verdict = \"maybe\"")))
        .hasMessage("verdict: give confirmed, refuted or unclear, as verdict = \"confirmed\".");
    assertThatThrownBy(() -> answer.read(toml(CONFIRMED.replace(":4-7\"", ":four\""))))
        .hasMessageContaining(
            "evidence 1: lines must be repo:path:from-to, such as Shop:src/Orders.java:12-30.");
    assertThatThrownBy(
            () -> answer.read(toml(CONFIRMED.substring(0, CONFIRMED.indexOf("[[evidence]]")))))
        .hasMessage("evidence: a confirmed verdict needs at least one [[evidence]] piece.");
    assertThatThrownBy(() -> answer.read(toml("verdict = \"unclear\"")))
        .hasMessage(
            "An unclear verdict needs evidence or a reason = \"what the code does not show\".");
    String nine = ("\n[[evidence]]\nlines = \"shop:" + PATH + ":5-5\"\nquote = \"x\"\n").repeat(9);
    assertThatThrownBy(() -> answer.read(toml("verdict = \"refuted\"\n" + nine)))
        .hasMessageContaining("evidence: give at most 8 pieces.");
  }

  @Test
  void oneLineWrittenWithoutItsEndIsThatLine() {
    JsonNode entry = answer.read(toml(CONFIRMED.replace(":4-7\"", ":5\"")));

    assertThat(entry.path("cites").get(0).asString()).isEqualTo("shop:" + PATH + ":5-5");
    assertThat(quotes(entry)).isEmpty();
  }

  @Test
  void anUnclearVerdictWithAReasonAndNoEvidenceIsAnAnswer() {
    JsonNode entry =
        answer.read(
            toml("verdict = \"unclear\"\nreason = \"The gateway's filters are not here.\""));

    assertThat(entry.path("verdict").asString()).isEqualTo("unclear");
    assertThat(entry.path("evidence")).isEmpty();
  }

  @Test
  void aQuoteWithOtherSpacesAtEitherEndPassesAndOneWithAChangedCharacterFails() {
    JsonNode spaced =
        answer.read(
            toml(
                CONFIRMED
                    .replace("quote = \"Order place(", "quote = \"   Order place(")
                    .replace("Order order) {\"", "Order order) {  \"")));
    JsonNode changed = answer.read(toml(CONFIRMED.replace("String userId", "String userID")));

    assertThat(quotes(spaced)).isEmpty();
    assertThat(quotes(changed))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.field()).isEqualTo("evidence 1");
              assertThat(r.reason())
                  .startsWith("the quote is not a line of shop:" + PATH + ":4-7; line 5 reads: '")
                  .endsWith("; copy one line of the range exactly.");
            });
  }

  @Test
  void aQuoteThatShowsNothingFailsThoughTheRangeHasABlankLine() {
    JsonNode blank =
        answer.read(
            toml(
                CONFIRMED
                    .replace(":4-7\"", ":1-7\"")
                    .replaceAll("quote = \".*\"", "quote = \"\"")));
    JsonNode brace = answer.read(toml(CONFIRMED.replaceAll("quote = \".*\"", "quote = \"}\"")));

    assertThat(quotes(blank))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.reason())
                    .isEqualTo(
                        "the quote shows nothing; copy one line of shop:"
                            + PATH
                            + ":1-7 that holds what the piece shows."));
    assertThat(quotes(brace))
        .singleElement()
        .satisfies(r -> assertThat(r.reason()).startsWith("the quote shows nothing"));
  }

  @Test
  void aLongLineIsQuotedAsTheFirstMessageCutItWithOrWithoutItsEllipsis() {
    String cut = LONG_LINE.strip().substring(0, InvestigateLeadMessage.LINE_WIDTH - 3);
    String piece =
        """
        verdict = "refuted"

        [[evidence]]
        lines = "shop:%s:2-2"
        quote = "%s"
        """;
    java.util.function.Function<String, JsonNode> quoting =
        quote -> answer.read(toml(piece.formatted(LONG, quote.replace("\"", "\\\""))));

    assertThat(quotes(quoting.apply(cut + "…"))).isEmpty();
    assertThat(quotes(quoting.apply(cut))).isEmpty();
    assertThat(quotes(quoting.apply("@GetMapping(path = \"/orders/{orderId}/lines"))).hasSize(1);
  }

  @Test
  void aNameTheRangeDoesNotHoldIsAProblem() {
    JsonNode entry =
        answer.read(
            toml(
                CONFIRMED.replace("shows = [\"X-User-Id\", \"place\"]", "shows = [\"X-Org-Id\"]")));

    assertThat(quotes(entry))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.reason())
                    .isEqualTo(
                        "\"X-Org-Id\" is not in shop:"
                            + PATH
                            + ":4-7; list in shows only names the range holds, or give the range"
                            + " that holds it."));
  }

  @Test
  void aNameIsShownByItsRangesLinesOrPathAndADottedNamePartByPart() {
    String text =
        "    password: ******\n  datasource:\n  @GetMapping(\"/refunds\")\n  void refund() {";
    String where = "orders/src/main/java/shop/RefundService.java";
    java.util.function.Predicate<String> shown =
        name ->
            InvestigateLeadAnswer.Quotes.holds(
                name,
                text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", ""),
                where.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", ""));

    assertThat(shown).accepts("refund", "RefundService", "RefundService.refund", "GET");
    assertThat(shown).accepts("spring.datasource.password".substring(7), "/refunds");
    assertThat(shown).rejects("X-User-Id", "OrderService.refund", "spring.r2dbc.password");
  }

  @Test
  void aMaskedLinesQuoteIsCheckedAgainstTheMaskedText() {
    String piece =
        """
        verdict = "refuted"
        finding = "The password is masked."

        [[evidence]]
        lines = "shop:%s:1-3"
        quote = "%s"
        shows = ["password"]
        """;

    assertThat(quotes(answer.read(toml(piece.formatted(CONFIG, "password: ******"))))).isEmpty();
    assertThat(quotes(answer.read(toml(piece.formatted(CONFIG, "password: hunter2")))))
        .singleElement()
        .satisfies(r -> assertThat(r.reason()).contains("line 3 reads: 'password: ******'"));
  }

  @Test
  void aFailedAnswerIsToldItsProblemsInTomlAndHowManyAnswersAreLeft() {
    String second = answer.failed(List.of("evidence 1: quotes: a \"problem\""), 1, 3);
    String last = answer.failed(List.of("verdict: wrong"), 3, 3);

    assertThat(Toml.read(second).root().path("recorded").asBoolean(true)).isFalse();
    assertThat(Toml.read(second).root().path("answers_left").asInt()).isEqualTo(2);
    assertThat(Toml.read(second).root().path("problems").get(0).asString())
        .isEqualTo("evidence 1: quotes: a \"problem\"");
    assertThat(Toml.read(last).root().path("ended").asString()).isEqualTo("no verdict is recorded");
  }

  @Test
  void aTextHoldsAnAnswerOnlyWhenItIsTomlWithAVerdict() {
    assertThat(answer.holdsAnswer(CONFIRMED)).isTrue();
    assertThat(answer.holdsAnswer("I think the verdict is confirmed.")).isFalse();
    assertThat(answer.holdsAnswer("title = \"No verdict here\"")).isFalse();
    assertThat(answer.holdsAnswer(null)).isFalse();
  }
}
