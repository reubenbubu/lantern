package io.lantern.harness.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Each check of the verification pipeline, accepting and rejecting (epic section 10). */
class ChecksTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final JsonNode SCHEMA =
      JSON.readTree(
          """
          {"type": "object", "required": ["text"], "additionalProperties": false,
           "properties": {"text": {"type": "string", "maxLength": 40}}}
          """);

  private static final List<String> FILE =
      List.of("package shop;", "class OrderService {", "  void place() {}", "}");

  private static CheckInputs inputs() {
    return new CheckInputs(
        SCHEMA,
        Map.of(
            "F1", "service:shop:orders HANDLED_BY method:shop.OrderController#create",
            "E1", "class OrderService { void place() {} }"),
        Set.of('F', 'E'),
        true,
        (repo, path) ->
            repo.equals("shop") && path.equals("src/OrderService.java")
                ? Optional.of(FILE)
                : Optional.empty(),
        Map.of(CheckInputs.claimKey("service:shop:orders", "PUBLISHES"), Set.of("topic:orders")),
        Set.of("hunter22secret"));
  }

  private static ItemAnswer text(CheckedText.Kind kind, String text, String... cites) {
    return new ItemAnswer(
        "I1",
        JSON.readTree("{\"text\": \"x\"}"),
        List.of(new CheckedText("text", kind, text, List.of(cites), 12, "OrderService", Set.of())),
        List.of());
  }

  private static List<String> reasons(Check check, ItemAnswer answer) {
    return check.check(answer, inputs()).stream().map(Rejection::reason).toList();
  }

  @Test
  void schema() {
    Check check = new Checks.Schema();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "ok", "F1"))).isEmpty();
    ItemAnswer extra =
        new ItemAnswer("I1", JSON.readTree("{\"text\": 3, \"more\": true}"), List.of(), List.of());
    assertThat(reasons(check, extra))
        .anyMatch(r -> r.contains("must be of type string"))
        .anyMatch(r -> r.contains("may not have: more"));
    ItemAnswer omitted =
        new ItemAnswer("I1", JSON.readTree("{\"omitted\": true}"), List.of(), List.of());
    assertThat(reasons(check, omitted)).isEmpty();
  }

  @Test
  void references() {
    Check check = new Checks.References();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Takes orders", "F1", "E1"))).isEmpty();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Takes orders", "F9")))
        .containsExactly("Cites F9, which is not in your input.");
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Takes orders", "S1")))
        .containsExactly("Cites S1, a kind of reference this task does not cite.");
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Takes orders", "the code")))
        .containsExactly("Cites the code, which is not a reference.");
  }

  @Test
  void ranges() {
    Check check = new Checks.Ranges();
    assertThat(
            reasons(
                check, text(CheckedText.Kind.PROSE, "Places", "shop:src/OrderService.java:2-3")))
        .isEmpty();
    assertThat(
            reasons(
                check, text(CheckedText.Kind.PROSE, "Places", "shop:src/OrderService.java:3-9")))
        .containsExactly("Cites shop:src/OrderService.java:3-9, outside the file's 4 lines.");
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Places", "shop:src/Other.java:1-2")))
        .containsExactly("Cites shop:src/Other.java:1-2, a file not of this snapshot.");
    CheckInputs longFile =
        new CheckInputs(
            SCHEMA,
            Map.of(),
            Set.of('F'),
            true,
            (repo, path) -> Optional.of(java.util.Collections.nCopies(100, "x")),
            Map.of(),
            Set.of());
    assertThat(
            check.check(text(CheckedText.Kind.PROSE, "Places", "shop:src/Big.java:1-41"), longFile))
        .extracting(Rejection::reason)
        .containsExactly("Cites shop:src/Big.java:1-41, longer than 40 lines.");
  }

  @Test
  void names() {
    Check check = new Checks.Names();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "OrderController creates orders", "F1")))
        .isEmpty();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Caches orders in Redis", "F1")))
        .containsExactly("Names Redis, which none of its citations show.");
  }

  @Test
  void aFileWhoseLinesAreCitedMayBeNamed() {
    // "application.yml sets ..." citing a range of application.yml is grounded (#685).
    assertThat(
            Checks.cited(
                text(CheckedText.Kind.PROSE, "x", "shop:conf/application.yml:1-2")
                    .texts()
                    .getFirst(),
                inputs()))
        .first()
        .asString()
        .startsWith("conf/application.yml\n");
  }

  @Test
  void aNameTheItemShowsOutsideItsReferencesNeedsNoCitation() {
    // A flow's steps name its containers in their headings, which cannot be cited (#685).
    ItemAnswer answer = text(CheckedText.Kind.PROSE, "Saves the order in main_db", "F1");
    assertThat(new Checks.Names().check(answer, inputs())).hasSize(1);
    assertThat(new Checks.Names().check(answer, inputs().withShown(List.of("main_db")))).isEmpty();
  }

  @Test
  void coverage() {
    Check check = new Checks.Coverage();
    ItemAnswer covered =
        new ItemAnswer(
            "I1",
            JSON.readTree("{}"),
            List.of(
                new CheckedText(
                    "proposal",
                    CheckedText.Kind.PROSE,
                    "place has no transaction",
                    List.of("shop:src/OrderService.java:2-3"),
                    40,
                    "",
                    Set.of("place"))),
            List.of());
    assertThat(check.check(covered, inputs())).isEmpty();
    ItemAnswer missing =
        new ItemAnswer(
            "I1",
            JSON.readTree("{}"),
            List.of(
                new CheckedText(
                    "proposal",
                    CheckedText.Kind.PROSE,
                    "refund has no transaction",
                    List.of("shop:src/OrderService.java:1-1"),
                    40,
                    "",
                    Set.of("refund"))),
            List.of());
    assertThat(check.check(missing, inputs()))
        .extracting(Rejection::reason)
        .containsExactly(
            "Declares the name refund, which its cited lines do not hold.",
            "Cites shop:src/OrderService.java:1-1, which holds none of its declared names.");
  }

  @Test
  void form() {
    Check check = new Checks.Form();
    assertThat(reasons(check, text(CheckedText.Kind.INTENT, "Charges a card for each order", "F1")))
        .isEmpty();
    assertThat(reasons(check, text(CheckedText.Kind.INTENT, "Calls the payment service", "F1")))
        .containsExactly(
            "\"Calls the payment service\" starts with an opening that says nothing about intent.");
    assertThat(reasons(check, text(CheckedText.Kind.INTENT, "Sends orders over HTTP", "F1")))
        .containsExactly(
            "\"Sends orders over HTTP\" names the protocol HTTP; say what the link is for.");
    assertThat(
            reasons(
                check,
                text(
                    CheckedText.Kind.PROSE,
                    "one two three four five six seven eight nine ten eleven twelve thirteen",
                    "F1")))
        .containsExactly("Has 13 words; the limit is 12.");
    assertThat(
            reasons(
                check,
                text(CheckedText.Kind.RESPONSIBILITY, "The order service places orders", "F1")))
        .containsExactly("Restates its subject's name; say what it is responsible for.");
    assertThat(
            reasons(
                check,
                text(CheckedText.Kind.RESPONSIBILITY, "Turns a cart into a paid purchase", "F1")))
        .isEmpty();
  }

  @Test
  void genericSentencesAndWeakOpeningsAreRejected() {
    Check check = new Checks.Generic();
    String generic =
        "\"The class is a Spring service, so it is available for injection\" would be true of any"
            + " Spring application; say what this part decides, owns or protects.";
    assertThat(
            reasons(
                check,
                text(
                    CheckedText.Kind.PROSE,
                    "The class is a Spring service, so it is available for injection",
                    "F1")))
        .containsExactly(generic);
    assertThat(
            reasons(
                check,
                text(
                    CheckedText.Kind.PROSE,
                    "It works mainly with SignUpRequest, User, and PasswordGenerator",
                    "F1")))
        .hasSize(1);
    assertThat(
            reasons(
                check,
                text(CheckedText.Kind.RESPONSIBILITY, "Is managed by Spring as a bean", "F1")))
        .hasSize(1);
    // Later sentences of a summary may refer back; a sentence shown alone may not.
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "It rejects duplicate files", "F1")))
        .isEmpty();
    assertThat(reasons(check, text(CheckedText.Kind.LEAD, "It rejects duplicate files", "F1")))
        .containsExactly(
            "\"It rejects duplicate files\" opens with a pronoun or restates what kind of part it"
                + " is; it is shown alone, so open with what the part does.");
    for (String weak :
        List.of(
            "This service keeps the file list of an organization",
            "A Spring service that keeps the file list of an organization",
            "The controller is where sign-up requests arrive",
            "Its job is keeping files")) {
      assertThat(reasons(check, text(CheckedText.Kind.RESPONSIBILITY, weak, "F1")))
          .as(weak)
          .hasSize(1);
    }
    for (String fine :
        List.of(
            "Keeps the files attached to each organization and refuses duplicates",
            "Organization files are kept here, one root per organization",
            "Thin wrapper over the tax rules, rounding to the cent",
            "Itemised invoices are built from the order lines",
            "Its client is annotated with @FeignClient(name=orders), so calls go to orders",
            "Topic names are bound as Spring configuration properties per environment")) {
      assertThat(reasons(check, text(CheckedText.Kind.PROSE, fine, "F1"))).as(fine).isEmpty();
    }
    // An intent phrase is the Form check's.
    assertThat(reasons(check, text(CheckedText.Kind.INTENT, "It works mainly with", "F1")))
        .isEmpty();
  }

  @Test
  void conflict() {
    Check check = new Checks.Conflict();
    ItemAnswer fresh = proposing("service:shop:orders", "CALLS_HTTP", "service:shop:payments");
    assertThat(check.check(fresh, inputs())).isEmpty();
    assertThat(check.check(proposing("service:shop:orders", "PUBLISHES", "topic:orders"), inputs()))
        .extracting(Rejection::reason)
        .containsExactly("Repeats a claim the static analysis already made.");
    assertThat(check.check(proposing("service:shop:orders", "PUBLISHES", "topic:other"), inputs()))
        .extracting(Rejection::reason)
        .singleElement()
        .asString()
        .startsWith("Contradicts a claim the static analysis made");
  }

  private static ItemAnswer proposing(String subject, String predicate, String object) {
    return new ItemAnswer(
        "I1",
        JSON.readTree("{}"),
        List.of(),
        List.of(new ProposedClaim("connections.0", subject, predicate, object)));
  }

  @Test
  void secrets() {
    Check check = new Checks.Secrets();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Signs tokens", "F1"))).isEmpty();
    assertThat(reasons(check, text(CheckedText.Kind.PROSE, "Signs with hunter22secret", "F1")))
        .containsExactly("Holds a value marked as secret; leave it out.");
  }

  @Test
  void thePipelineCollectsEveryRejectionForOneRepairTurn() {
    ItemAnswer bad = text(CheckedText.Kind.INTENT, "Uses Redis over HTTP", "F9");
    List<Rejection> found = Pipeline.STANDARD.check(bad, inputs());

    assertThat(found).extracting(Rejection::check).contains("References", "Names", "Form");
    assertThat(Pipeline.repairTurn(found))
        .startsWith("Some parts of your answer were rejected.")
        .contains(
            "<rejection item=\"I1\" field=\"text\">Cites F9, which is not in your input.</rejection>")
        .endsWith("holding only the items you are correcting or withdrawing.");
  }

  @Test
  void theSchemaSubsetCoversTheRolesSchemas() {
    JsonNode schema =
        JSON.readTree(
            """
            {"type": "object", "properties": {"items": {"type": "object", "additionalProperties": {
              "oneOf": [{"type": "object", "required": ["claims"], "additionalProperties": false,
                         "properties": {"claims": {"type": "array", "minItems": 1, "maxItems": 2,
                           "items": {"type": "object", "required": ["verdict"],
                                     "properties": {"verdict": {"enum": ["supported", "partly"]},
                                                    "lines": {"type": "string", "pattern": "^[0-9]+-[0-9]+$"}}}}}},
                        {"type": "object", "required": ["omitted"], "additionalProperties": false,
                         "properties": {"omitted": {"const": true}}}]}}}}
            """);
    assertThat(
            JsonSchema.violations(
                schema,
                JSON.readTree(
                    "{\"items\": {\"I1\": {\"claims\": [{\"verdict\": \"partly\", \"lines\": \"3-4\"}]}, \"I2\": {\"omitted\": true}}}")))
        .isEmpty();
    assertThat(
            JsonSchema.violations(
                schema,
                JSON.readTree(
                    "{\"items\": {\"I1\": {\"claims\": [{\"verdict\": \"maybe\", \"lines\": \"x\"}, {\"verdict\": \"partly\"}, {\"verdict\": \"partly\"}]}}}")))
        .anyMatch(v -> v.contains("must be one of"))
        .anyMatch(v -> v.contains("does not match"))
        .anyMatch(v -> v.contains("more than 2 entries"));
  }
}
