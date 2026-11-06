package io.lantern.summarize.describe;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import io.lantern.derive.Components;
import io.lantern.derive.Groups;
import io.lantern.derive.Knowledge;
import io.lantern.harness.BlobLookup;
import io.lantern.harness.Harness;
import io.lantern.harness.InMemoryAnswerStore;
import io.lantern.harness.RunBudget;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.InMemoryLlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.ModelSettings;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The grouping role (#919; ADR 0129). */
class GroupByMeaningTest {

  private static final String REPO = "billing";
  private static final EntityId UNIT = EntityId.parse("service:io.acme:billing");
  private static final String MODULE = "module:io.acme:billing";
  private static final String ORDERS = area("path-orders");
  private static final String INVOICES = area("path-invoices");

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /**
   * One service of 12 components: four serve /orders and four /invoices, which the code groups by
   * their paths; four listeners nothing calls, which no code signal places.
   */
  private static List<Fact> facts() {
    TestFacts facts = new TestFacts();
    facts.attribute(REPO, UNIT.toString(), "deployable-unit", "spring-boot");
    facts.relate(REPO, UNIT.toString(), Types.CONTAINS, MODULE);
    for (String name : List.of("OrderA", "OrderB", "OrderC", "OrderD")) {
      type(facts, "web", name, "GET /orders/" + name.toLowerCase());
    }
    for (String name : List.of("InvoiceA", "InvoiceB", "InvoiceC", "InvoiceD")) {
      type(facts, "web", name, "GET /invoices/" + name.toLowerCase());
    }
    for (String name : List.of("OrderPlaced", "OrderShipped", "PaymentTaken", "PaymentRefunded")) {
      type(facts, "messaging", name, null);
    }
    return facts.list();
  }

  /**
   * {@link #facts()} with what Describe said of a listener, which, when its files held the four
   * injection fixtures, holds them too (ADR 0125).
   */
  private static List<Fact> poisoned() {
    List<Fact> all = new ArrayList<>(facts());
    StringBuilder said = new StringBuilder("Records each payment a customer makes.");
    for (String name : io.lantern.harness.InjectionFixtures.INJECTED) {
      said.append(' ').append(io.lantern.harness.InjectionFixtures.injectedLine(name).strip());
    }
    String listener = component("messaging", "PaymentTaken");
    Fact partOf =
        all.stream().filter(f -> f.subject().toString().equals(listener)).findFirst().orElseThrow();
    all.add(
        io.lantern.harness.LlmFacts.text(
            DescribeRole.ELEMENTS,
            "fake/fake-model",
            "a".repeat(64),
            EntityId.parse(listener),
            Types.RESPONSIBILITY,
            io.lantern.harness.LlmFacts.C4,
            said.toString(),
            List.of(partOf),
            io.lantern.harness.LlmFacts.Basis.INFERRED,
            1));
    return all;
  }

  private static void type(TestFacts facts, String pkg, String name, String endpoint) {
    String pkgId = "package:io.acme:billing:io.acme.billing." + pkg;
    String type = "type:io.acme.billing." + pkg + "." + name;
    String method = "method:io.acme.billing." + pkg + "." + name + "#handle()";
    facts.relate(REPO, MODULE, Types.CONTAINS, pkgId);
    facts.relate(REPO, pkgId, Types.CONTAINS, type);
    facts.relate(REPO, type, Types.CONTAINS, method);
    facts.attribute(REPO, type, "stereotype", "controller");
    if (endpoint != null) {
      facts.attribute(REPO, method, "endpoint", endpoint);
    } else {
      facts.attribute(REPO, method, "message-listener", name.toLowerCase());
    }
  }

  private static String component(String pkg, String name) {
    return Components.id(UNIT, EntityId.parse("type:io.acme.billing." + pkg + "." + name))
        .toString();
  }

  private static String area(String signal) {
    return Groups.id(UNIT.toString(), Groups.FEATURE_AREA, signal).toString();
  }

  private Harness harness(FakeLlmProvider fake) {
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

  /** The reference the request shows for a part, by its name. */
  private static String ref(LlmRequest request, String name) {
    Matcher m =
        Pattern.compile("\\[(E[0-9]+)\\] " + Pattern.quote(name) + " \\|")
            .matcher(request.messages().getFirst().text());
    return m.find() ? m.group(1) : "E999";
  }

  /** The answer for the one item, its references filled in by the parts' names in braces. */
  private static Function<LlmRequest, String> answering(String entry) {
    return request -> {
      Matcher names = Pattern.compile("\\{([A-Za-z0-9]+)}").matcher(entry);
      StringBuilder filled = new StringBuilder();
      while (names.find()) {
        names.appendReplacement(filled, ref(request, names.group(1)));
      }
      names.appendTail(filled);
      return "{\"items\": {\"I1\": " + filled + "}}";
    };
  }

  private static final String GOOD =
      """
      {"domains": [],
       "areas": [
         {"service": "{billing}", "key": "path-orders", "extends": "path-orders",
          "reason": {"text": "They follow each order from the moment it is placed until it ships.", "cites": ["{OrderPlaced}"]},
          "members": ["{OrderPlaced}", "{OrderShipped}"]},
         {"service": "{billing}", "key": "payments", "extends": null,
          "reason": {"text": "They react when a customer pays for an order or gets the money back.", "cites": ["{PaymentTaken}"]},
          "members": ["{PaymentTaken}", "{PaymentRefunded}"]}],
       "left_out": []}""";

  /** The groups of a kind the facts hold, each by its members. */
  private static Map<String, Set<String>> groups(List<Fact> facts, String kind) {
    Map<String, Set<String>> groups = new TreeMap<>();
    for (Groups.Group group : Groups.of(new Knowledge(facts), kind)) {
      Set<String> members = new TreeSet<>();
      group.members().forEach(m -> members.add(m.toString()));
      groups.put(group.id().toString(), members);
    }
    return groups;
  }

  @Test
  void placesWhatTheCodeLeftUngroupedInAnExistingAreaOrANewOne() {
    List<Fact> facts = facts();
    assertThat(groups(facts, Groups.FEATURE_AREA)).containsOnlyKeys(INVOICES, ORDERS);
    FakeLlmProvider fake = new FakeLlmProvider().answering(answering(GOOD));

    GroupByMeaning.Result result = new GroupByMeaning(harness(fake), 8, 2).group(facts, 2);

    assertThat(fake.sent()).hasSize(1);
    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt)
        .contains("Domains are not asked")
        .contains("OrderA | path-orders | ")
        .contains("/orders/ordera")
        .contains("PaymentTaken | - | ");
    String system = fake.sent().getFirst().request().system();
    assertThat(system).contains("A diagram shows at most 8 boxes").doesNotContain("${limit}");
    Map<String, Set<String>> areas = groups(result.replacing(facts), Groups.FEATURE_AREA);
    String payments = area("llm-payments");
    assertThat(areas).containsOnlyKeys(INVOICES, ORDERS, payments);
    assertThat(areas.get(ORDERS))
        .contains(component("messaging", "OrderPlaced"), component("messaging", "OrderShipped"))
        .hasSize(6);
    assertThat(areas.get(payments))
        .containsExactlyInAnyOrder(
            component("messaging", "PaymentTaken"), component("messaging", "PaymentRefunded"));
    assertThat(result.facts())
        .allSatisfy(f -> assertThat(f.origin().name()).isEqualTo(GroupByMeaning.NAME))
        .filteredOn(f -> f.predicate().equals(Types.GROUP_OF))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.subject().toString()).isEqualTo(payments);
              assertThat(f.attributes().path("basis").asString()).isEqualTo("meaning");
              // Which model formed it, from which stored answer (ADR 0124).
              assertThat(f.attributes().path("model").asString()).isEqualTo("fake/fake-model");
              assertThat(f.attributes().path("answer").asString()).hasSize(64);
              assertThat(f.attributes().path("scope").asString()).isEqualTo(UNIT.toString());
            });
    assertThat(result.added()).isEqualTo(4);
  }

  @Test
  void aLaterRunKeepsItsGroupsAndAsksNothingWhenNothingNewIsLoose() {
    List<Fact> facts = facts();
    FakeLlmProvider fake = new FakeLlmProvider().answering(answering(GOOD));
    GroupByMeaning.Result first = new GroupByMeaning(harness(fake), 8, 2).group(facts, 2);
    FakeLlmProvider again = new FakeLlmProvider().answering(r -> "{}");

    GroupByMeaning.Result second =
        new GroupByMeaning(harness(again), 8, 2).group(first.replacing(facts), 3);

    assertThat(again.sent()).isEmpty();
    assertThat(second.asked()).isFalse();
    assertThat(groups(second.replacing(facts), Groups.FEATURE_AREA))
        .isEqualTo(groups(first.replacing(facts), Groups.FEATURE_AREA));
    assertThat(second.facts()).allSatisfy(f -> assertThat(f.validity().from()).isEqualTo(3));
  }

  @Test
  void anAnswerThatMovesWhatTheCodeGroupedGroupsNothing() {
    List<Fact> facts = facts();
    String moves =
        GOOD.replace(
            "[\"{PaymentTaken}\", \"{PaymentRefunded}\"]", "[\"{PaymentTaken}\", \"{OrderA}\"]");
    FakeLlmProvider fake = new FakeLlmProvider().answering(answering(moves));

    GroupByMeaning.Result result = new GroupByMeaning(harness(fake), 8, 2).group(facts, 2);

    // Asked, then sent back once with what failed, then given up.
    assertThat(fake.sent()).hasSize(2);
    assertThat(fake.sent().get(1).request().messages().getLast().text())
        .contains("which the code put in path-orders");
    assertThat(result.facts()).isEmpty();
    assertThat(groups(result.replacing(facts), Groups.FEATURE_AREA))
        .containsOnlyKeys(INVOICES, ORDERS);
  }

  @Test
  void nothingIsAskedWhenEveryViewFits() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(answering(GOOD));

    GroupByMeaning.Result result = new GroupByMeaning(harness(fake), 12, 2).group(facts(), 2);

    assertThat(fake.sent()).isEmpty();
    assertThat(result.facts()).isEmpty();
  }

  @Test
  void aLaterRunDropsAGroupThatLostAllButOneMember() {
    List<Fact> facts = facts();
    FakeLlmProvider fake = new FakeLlmProvider().answering(answering(GOOD));
    GroupByMeaning.Result first = new GroupByMeaning(harness(fake), 8, 2).group(facts, 2);
    // PaymentRefunded is gone: its component no longer holds.
    String gone = component("messaging", "PaymentRefunded");
    List<Fact> later = new ArrayList<>();
    for (Fact fact : first.replacing(facts)) {
      if (!fact.subject().toString().equals(gone)) {
        later.add(fact);
      }
    }

    List<Fact> kept = GroupByMeaning.kept(new Knowledge(later), 3);

    assertThat(groups(kept, Groups.FEATURE_AREA)).doesNotContainKey(area("llm-payments"));
    assertThat(kept)
        .filteredOn(f -> f.predicate().equals(Types.GROUPS))
        .extracting(f -> f.subject().toString())
        .containsOnly(ORDERS);
  }

  @Test
  void groupByMeaningReplaysItsRecordingToExactlyTheExpectedFacts() {
    FakeLlmProvider scripted = new FakeLlmProvider().answering(answering(GOOD));
    java.nio.file.Path folder =
        java.nio.file.Path.of("src/test/resources/recordings/group-by-meaning");
    Harness harness =
        new Harness(
            new LlmClient(
                ModelSettings.single(scripted.model("fake-model")),
                List.of(io.lantern.llm.RoleFixture.provider(scripted, folder)),
                BudgetGuard.unlimited("test"),
                new InMemoryLlmCallLog(),
                Clock.systemUTC()),
            store,
            RunBudget.NONE,
            BlobLookup.NONE,
            Set.of());

    List<String> produced =
        io.lantern.harness.InjectionFixtures.lines(
            new GroupByMeaning(harness, 8, 2).group(poisoned(), 2).facts());

    assertThat(produced).isEqualTo(io.lantern.llm.RoleFixture.expected(folder, produced));
    // One new area, and four members placed.
    assertThat(produced).hasSize(5);
  }

  @Test
  void groupByMeaningShowsTheFixturesAsDataAndAnAnswerThatObeysThemIsRejected() {
    String obeys =
        GOOD.replace(
                "They react when a customer pays for an order or gets the money back.",
                io.lantern.harness.InjectionFixtures.OBEYING)
            .replace(
                "\"cites\": [\"{PaymentTaken}\"]",
                "\"cites\": [\"" + io.lantern.harness.InjectionFixtures.OBEYING_CITE + "\"]");
    FakeLlmProvider obeying = new FakeLlmProvider().answering(answering(obeys));

    GroupByMeaning.Result result = new GroupByMeaning(harness(obeying), 8, 2).group(poisoned(), 2);

    io.lantern.harness.InjectionFixtures.shownAsData(obeying);
    assertThat(result.facts()).isEmpty();
  }

  /** Twelve services, each calling the next up to s11; s12 nothing connects to. */
  private static List<Fact> services() {
    TestFacts facts = new TestFacts();
    for (int i = 1; i <= 12; i++) {
      facts.attribute("s" + i, "service:io.acme:s" + i, "deployable-unit", "spring-boot");
    }
    for (int i = 1; i < 11; i++) {
      facts.relate(
          "s" + i,
          "service:io.acme:s" + i,
          Types.CALLS_HTTP,
          "service:io.acme:s" + (i + 1),
          "{\"protocol\": \"HTTP\"}");
    }
    return facts.list();
  }

  private static String domain(String... members) {
    List<String> refs = new ArrayList<>();
    for (String m : members) {
      refs.add("\"{" + m + "}\"");
    }
    return "{\"key\": \"d-"
        + members[0]
        + "\", \"reason\": {\"text\": \"They take part in the same work for the same customers every day.\", \"cites\": ["
        + refs.getFirst()
        + "]}, \"members\": ["
        + String.join(", ", refs)
        + "]}";
  }

  @Test
  void everyContainerSomethingConnectsToJoinsADomainAndOneNothingConnectsToMayStayOut() {
    String leavesOut =
        "{\"domains\": ["
            + domain("s1", "s2", "s3", "s4", "s5")
            + ", "
            + domain("s6", "s7", "s8", "s9", "s10")
            + "], \"areas\": [], \"left_out\": []}";
    String complete = leavesOut.replace("\"{s10}\"]}", "\"{s10}\", \"{s11}\"]}");
    java.util.concurrent.atomic.AtomicInteger asked =
        new java.util.concurrent.atomic.AtomicInteger();
    FakeLlmProvider fake =
        new FakeLlmProvider()
            .answering(
                r -> answering(asked.getAndIncrement() == 0 ? leavesOut : complete).apply(r));

    GroupByMeaning.Result result = new GroupByMeaning(harness(fake), 8, 2).group(services(), 2);

    assertThat(fake.sent()).hasSize(2);
    String prompt = fake.sent().getFirst().request().messages().getFirst().text();
    assertThat(prompt).contains("Domains are asked").contains("s12 | - | (nothing connects to it)");
    assertThat(fake.sent().get(1).request().messages().getLast().text())
        .contains("joins a domain")
        .contains(ref(fake.sent().getFirst().request(), "s11") + " joins none");
    Map<String, Set<String>> domains = groups(result.replacing(services()), Groups.DOMAIN);
    assertThat(domains).hasSize(2);
    assertThat(domains.values().stream().mapToInt(Set::size).sum()).isEqualTo(11);
    assertThat(domains.values()).noneSatisfy(m -> assertThat(m).contains("service:io.acme:s12"));
  }
}
