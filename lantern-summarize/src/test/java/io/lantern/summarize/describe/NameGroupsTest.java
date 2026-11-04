package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.derive.Findings;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The Name groups role (knowledge-build epic, section 11.8; ADR 0107). */
class NameGroupsTest {

  private static final String ORDERS = "group:system/domain/orders";
  private static final String BILLING = "group:system/domain/billing";
  private static final String API = "service:com.example:orders-api";
  private static final String WORKER = "service:com.example:orders-worker";
  private static final String PAY = "service:com.example:payments";
  private static final String INVOICES = "service:com.example:invoices";
  private static final Pattern ITEM =
      Pattern.compile("<item id=\"(I[0-9]+)\">\n(.*?)\n</item>", Pattern.DOTALL);

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** Two domains of two units each; the orders API calls payments, and Describe worded both. */
  private static List<Fact> facts() {
    return facts("Takes customers' orders.");
  }

  private static List<Fact> facts(String apiSays) {
    TestFacts facts = new TestFacts();
    for (String[] group : new String[][] {{ORDERS, API, WORKER}, {BILLING, PAY, INVOICES}}) {
      String signal = group[0].substring(group[0].lastIndexOf('/') + 1);
      facts.attribute(
          "shop",
          group[0],
          "group-of",
          "domain",
          "{\"scope\": \"system\", \"basis\": \"repository\", \"signal\": \"" + signal + "\"}");
      for (int i = 1; i < group.length; i++) {
        facts.relate("shop", group[0], "GROUPS", group[i], "{\"basis\": \"repository\"}");
      }
    }
    facts.relate("shop", API, "CALLS_HTTP", PAY);
    List<Fact> all = new ArrayList<>(facts.list());
    Fact call =
        all.stream().filter(f -> f.predicate().equals("CALLS_HTTP")).findFirst().orElseThrow();
    Fact apiGroups =
        all.stream()
            .filter(f -> f.predicate().equals("GROUPS") && f.object().value().equals(API))
            .findFirst()
            .orElseThrow();
    all.add(said(API, Types.RESPONSIBILITY, LlmFacts.C4, apiSays, apiGroups));
    all.add(
        said(
            API,
            Types.INTENT,
            call.claimId() != null ? call.claimId() : call.id(),
            "Charges each order it takes",
            call));
    return all;
  }

  private static Fact said(String subject, String predicate, String object, String text, Fact on) {
    return LlmFacts.text(
        DescribeRole.UNITS,
        "fake/fake-model",
        EntityId.parse(subject),
        predicate,
        object,
        text,
        List.of(on),
        LlmFacts.Basis.INFERRED,
        1);
  }

  private Harness last;

  private Harness harness(FakeLlmProvider fake) {
    last = harnessOf(fake);
    return last;
  }

  private Harness harnessOf(FakeLlmProvider fake) {
    return new Harness(
        new LlmClient(
            ModelSettings.single(fake.model("fake-model")),
            List.of(fake),
            BudgetGuard.unlimited("test"),
            new InMemoryLlmCallLog(),
            Clock.systemUTC()),
        store,
        RunBudget.NONE,
        BlobLookup.NONE,
        Set.of());
  }

  /** Answers each item with what {@code entry} gives its body. */
  private static String answer(LlmRequest request, Function<String, String> entry) {
    Matcher items = ITEM.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (items.find()) {
      // "F1" stands for the item's own first fact, as the request numbers it.
      String body = items.group(2);
      entries.add(
          "\""
              + items.group(1)
              + "\": "
              + entry.apply(body).replace("\"F1\"", "\"" + ref(body, "group-of") + "\""));
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  /** The reference a line of the item shows before the given text, such as {@code A1}. */
  private static String ref(String body, String before) {
    Matcher m =
        Pattern.compile("\\[([A-Z][0-9]+)\\] [^\\n]*" + Pattern.quote(before)).matcher(body);
    return m.find() ? m.group(1) : "F1";
  }

  private static String good(String body) {
    if (body.startsWith("Group " + ORDERS)) {
      return """
          {"name": {"text": "Ordering", "cites": ["%s"]},
           "responsibility": {"text": "Takes what customers buy and sees each order through to payment and delivery.",
                              "cites": ["%s"]},
           "lines": {"R1": {"text": "Charges each order it takes", "cites": ["%s"]}},
           "merge": {"with": "G1", "reason": {"text": "Both handle the money customers pay for their orders.",
                                              "cites": ["F1"]}}}"""
          .formatted(
              ref(body, "Takes customers"), ref(body, "Takes customers"), ref(body, "Charges"));
    }
    return """
        {"name": {"text": "Billing", "cites": ["%1$s"]},
         "responsibility": {"text": "Bills customers for what they bought and keeps their invoices until each one is paid.", "cites": ["%1$s"]}}"""
        .formatted(ref(body, "group-of"));
  }

  @Test
  void namesEachGroupItsLinesAndStoresAMergeSuggestionAsAFinding() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(r -> answer(r, NameGroupsTest::good));

    NameGroups.Result result = new NameGroups(harness(fake)).name(facts(), 2);

    assertThat(fake.sent()).hasSize(1);
    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt)
        .contains("Group " + ORDERS + ", a domain of the system")
        .contains("orders-api: [A")
        .contains("Takes customers' orders.")
        .contains("[R1] to the group " + BILLING + " (CALLS_HTTP)")
        .contains("orders-api -> payments: Charges each order")
        .contains("[G1] " + BILLING);
    assertThat(result.unknown()).isEmpty();
    assertThat(result.facts())
        .extracting(f -> f.subject() + " " + f.predicate() + " " + f.object().value())
        .containsExactlyInAnyOrder(
            ORDERS + " display-name c4",
            ORDERS + " responsibility c4",
            ORDERS + " intent " + BILLING + " CALLS_HTTP",
            BILLING + " display-name c4",
            BILLING + " responsibility c4");
    Fact name =
        result.facts().stream()
            .filter(f -> f.subject().toString().equals(ORDERS))
            .filter(f -> f.predicate().equals(Types.DISPLAY_NAME))
            .findFirst()
            .orElseThrow();
    assertThat(name.attributes().path("text").asString()).isEqualTo("Ordering");
    assertThat(name.origin().name()).isEqualTo("name-groups");
    // It cites the API's responsibility, an earlier answer.
    String apiSaid =
        facts().stream()
            .filter(f -> f.predicate().equals(Types.RESPONSIBILITY))
            .findFirst()
            .orElseThrow()
            .id();
    assertThat(name.attributes().path("cites").toString()).contains(apiSaid);
    assertThat(result.findings()).hasSize(1);
    Fact merge = result.findings().getFirst();
    assertThat(merge.subject())
        .isEqualTo(Findings.id(NameGroups.GROUP_MERGE, BILLING + "/" + ORDERS));
    assertThat(merge.object().value()).isEqualTo(NameGroups.GROUP_MERGE);
    assertThat(merge.attributes().path("about").asString()).isEqualTo(BILLING);
    assertThat(merge.attributes().path("target").asString()).isEqualTo(ORDERS);
    assertThat(merge.attributes().path("reason").asString())
        .isEqualTo("Both handle the money customers pay for their orders.");

    // The run's record shows the role, its calls and cost.
    assertThat(last.records())
        .filteredOn(r -> r.role().equals("name-groups"))
        .singleElement()
        .satisfies(r -> assertThat(r.calls()).isEqualTo(1))
        .satisfies(r -> assertThat(r.costUsd()).isNotNull());

    // A second run over the same facts is answered from the cache.
    FakeLlmProvider again = new FakeLlmProvider().answering(r -> "{}");
    NameGroups.Result cached = new NameGroups(harness(again)).name(facts(), 3);
    assertThat(again.sent()).isEmpty();
    assertThat(cached.facts()).hasSize(5);
    assertThat(cached.findings()).hasSize(1);
    // Billing shared its request with orders, its facts numbered after them; what its cached
    // answer cites is still its own group-of fact.
    String billingGroupOf =
        facts().stream()
            .filter(f -> f.subject().toString().equals(BILLING))
            .filter(f -> f.predicate().equals("group-of"))
            .findFirst()
            .orElseThrow()
            .id();
    assertThat(
            cached.facts().stream()
                .filter(f -> f.subject().toString().equals(BILLING))
                .filter(f -> f.predicate().equals(Types.DISPLAY_NAME))
                .findFirst()
                .orElseThrow()
                .attributes()
                .path("cites")
                .toString())
        .contains(billingGroupOf);

    // A member's responsibility worded anew asks only its group again.
    FakeLlmProvider changed = new FakeLlmProvider().answering(r -> answer(r, NameGroupsTest::good));
    new NameGroups(harness(changed)).name(facts("Takes and tracks customers' orders."), 4);
    assertThat(changed.sent()).hasSize(1);
    assertThat(changed.sent().getFirst().request().messages().getFirst().text())
        .contains("Group " + ORDERS)
        .doesNotContain("Group " + BILLING + ",");
  }

  @Test
  void aNameThatIsAMembersOrNamesAProtocolAndAMergeWithAnUnknownGroupAreSentBack() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                r ->
                    r.messages().size() > 1
                        ? answer(r, NameGroupsTest::good)
                        : answer(
                            r,
                            body ->
                                body.startsWith("Group " + ORDERS)
                                    ? """
                                    {"name": {"text": "Orders API", "cites": ["F1"]},
                                     "merge": {"with": "G7", "reason": {"text": "Both groups do the same work for orders.",
                                                                        "cites": ["F1"]}}}"""
                                    : """
                                    {"name": {"text": "Billing over HTTP", "cites": ["F1"]}}"""));

    NameGroups.Result result = new NameGroups(harness(fake)).name(facts(), 2);

    String repair = fake.sent().get(1).request().messages().get(2).text();
    assertThat(repair)
        .contains("\"Orders API\" is the name of a member, orders-api")
        .contains("Merges with G7, which is not one of this item's groups [G..].")
        .contains("\"Billing over HTTP\" names the protocol HTTP");
    assertThat(result.unknown()).isEmpty();
  }

  @Test
  void aMergeSuggestionRejectedTwiceIsLeftOutAndTheGroupKeepsItsName() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                r ->
                    answer(
                        r,
                        body ->
                            """
                            {"name": {"text": "%s", "cites": ["F1"]},
                             "merge": {"with": "G9", "reason": {"text": "Both groups do the same work for orders.",
                                                                "cites": ["F1"]}}}"""
                                .formatted(
                                    body.startsWith("Group " + ORDERS) ? "Ordering" : "Billing")));

    NameGroups.Result result = new NameGroups(harness(fake)).name(facts(), 2);

    assertThat(result.unknown()).isEmpty();
    assertThat(result.findings()).isEmpty();
  }

  /** Two feature areas of one unit, whose members are components anchored on their classes. */
  private static List<Fact> featureAreas() {
    String unit = "service:com.example:billing";
    String invoices = "group:" + unit + "/area/invoice";
    String payments = "group:" + unit + "/area/payment";
    String[][] areas = {
      {invoices, "com.example.invoice.InvoiceController", "com.example.invoice.InvoiceService"},
      {payments, "com.example.payment.PaymentController", "com.example.payment.PaymentService"}
    };
    TestFacts facts = new TestFacts();
    facts.attribute("shop", unit, "technology", "Spring Boot 3.5.14 / Java 21");
    for (String[] area : areas) {
      String signal = area[0].substring(area[0].lastIndexOf('/') + 1);
      facts.attribute(
          "shop",
          area[0],
          "group-of",
          "feature-area",
          "{\"scope\": \""
              + unit
              + "\", \"basis\": \"feature-folder\", \"signal\": \""
              + signal
              + "\"}");
      for (int i = 1; i < area.length; i++) {
        String component = "component:" + unit + "/type:" + area[i];
        facts.relate("shop", area[0], "GROUPS", component, "{\"basis\": \"feature-folder\"}");
        facts.relate("shop", component, "ANCHORED_ON", "type:" + area[i]);
      }
    }
    facts.relate(
        "shop",
        "component:" + unit + "/type:com.example.invoice.InvoiceService",
        "COMPONENT_LINK",
        "component:" + unit + "/type:com.example.payment.PaymentService",
        "{\"kind\": \"calls\"}");
    return facts.list();
  }

  @Test
  void aFeatureAreasMembersAreShownByTheirClassesAndANameThatIsOneOrATechnologyIsSentBack() {
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                r ->
                    answer(
                        r,
                        body ->
                            r.messages().size() > 1
                                ? """
                                {"name": {"text": "%s", "cites": ["F1"]}}"""
                                    .formatted(
                                        body.startsWith(
                                                "Group group:service:com.example:billing/area/invoice")
                                            ? "Invoicing"
                                            : "Paying")
                                : body.startsWith(
                                        "Group group:service:com.example:billing/area/invoice")
                                    ? """
                                    {"name": {"text": "Invoice Controller", "cites": ["F1"]}}"""
                                    : """
                                    {"name": {"text": "Spring Boot payments", "cites": ["F1"]}}"""));

    NameGroups.Result result = new NameGroups(harness(fake)).name(featureAreas(), 2);

    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt)
        .contains("a feature area of the unit")
        .contains("- InvoiceController\n")
        .contains("InvoiceService -> PaymentService")
        .doesNotContain("- component:");
    String repair = fake.sent().get(1).request().messages().get(2).text();
    assertThat(repair)
        .contains("\"Invoice Controller\" is the name of a member, InvoiceController")
        .contains("\"Spring Boot payments\" names the technology Spring Boot");
    assertThat(result.unknown()).isEmpty();
  }

  @Test
  void aGroupTheModelLeavesOutIsUnnamed() {
    FakeLlmProvider fake =
        new FakeLlmProvider().answering(r -> answer(r, body -> "{\"omitted\": true}"));

    NameGroups.Result result = new NameGroups(harness(fake)).name(facts(), 2);

    assertThat(result.facts()).isEmpty();
    assertThat(result.unknown()).containsExactlyInAnyOrder(ORDERS, BILLING);
  }

  @Test
  void theNameGroupsPromptIsTheEpics() throws Exception {
    String epic = Files.readString(Path.of("../docs/epics/001-knowledge-build.md"));
    String section = epic.substring(epic.indexOf("### 11.8 Name groups"));
    assertThat(section).contains("```text\n" + NameGroups.TASK + "\n```");
    assertThat(section).contains("```json\n" + NameGroups.SCHEMA + "\n```");
  }
}
