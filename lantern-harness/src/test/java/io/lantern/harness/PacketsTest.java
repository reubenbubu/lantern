package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.llm.CacheBreak;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Packets (knowledge-build epic, section 10): an entry per item, each verified on its own, one
 * repair turn, missing items asked again alone once, and deterministic requests.
 */
class PacketsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final RoleSpec ROLE =
      RoleSpec.single("describe-element", Role.INFER_INTENT, "1", "describe", RunMode.BULK, 50)
          .withPacketSize(5);

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  /** A role whose items each get one responsibility sentence citing facts F1 to F3. */
  static final PacketRole SENTENCES =
      new PacketRole() {
        @Override
        public PromptLayout layout(List<Item> packet, List<String> ids) {
          StringBuilder items = new StringBuilder("<items>\n");
          for (int i = 0; i < packet.size(); i++) {
            items
                .append('[')
                .append(ids.get(i))
                .append("] ")
                .append(packet.get(i).rendering())
                .append('\n');
          }
          return new PromptLayout(
              "Common rules.",
              "Task: word each item's responsibility.",
              "<facts>\n[F1] a\n[F2] b\n[F3] c\n</facts>",
              "",
              false,
              items.append("</items>").toString());
        }

        @Override
        public int maxOutputTokens(int items) {
          return 100 * items;
        }

        @Override
        public CheckInputs inputs(List<Item> packet, List<String> ids) {
          return new CheckInputs(
              JSON.readTree(
                  """
                  {"type": "object", "required": ["responsibility"], "additionalProperties": false,
                   "properties": {"responsibility": {"type": "object", "required": ["text", "cites"],
                     "properties": {"text": {"type": "string", "maxLength": 200},
                                    "cites": {"type": "array", "items": {"type": "string"}}}}}}
                  """),
              Map.of("F1", "a", "F2", "b", "F3", "c"),
              Set.of('F'),
              false,
              (repo, path) -> Optional.empty(),
              Map.of(),
              Set.of());
        }

        @Override
        public ItemAnswer read(String id, Item item, JsonNode entry) {
          List<CheckedText> texts = new ArrayList<>();
          JsonNode text = entry.path("responsibility");
          if (text.isObject()) {
            List<String> cites = new ArrayList<>();
            text.path("cites").forEach(c -> cites.add(c.asString()));
            texts.add(
                new CheckedText(
                    "responsibility",
                    CheckedText.Kind.RESPONSIBILITY,
                    text.path("text").asString(),
                    cites,
                    40,
                    item.subject(),
                    Set.of()));
          }
          return new ItemAnswer(id, entry, texts, List.of());
        }

        @Override
        public String store(Item item, ItemAnswer accepted) {
          return accepted.raw().toString();
        }
      };

  private static String good(String text) {
    return "{\"responsibility\": {\"text\": \"" + text + "\", \"cites\": [\"F1\"]}}";
  }

  private static Item item(String subject) {
    return new Item(
        subject, "repo/unit", List.of(FingerprintInput.text(subject)), "store " + subject);
  }

  private Map<String, Outcome> ask(FakeLlmProvider fake, List<Item> items) {
    return harness(client(fake), store).session(ROLE).askPackets(items, SENTENCES);
  }

  /** The item IDs a request shows, in order. */
  private static List<String> ids(LlmRequest request) {
    List<String> ids = new ArrayList<>();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\\[(I\\d+)\\] store (\\w+)")
            .matcher(request.messages().getFirst().text());
    while (m.find()) {
      ids.add(m.group(1) + "=" + m.group(2));
    }
    return ids;
  }

  private static FakeLlmProvider answering(Function<LlmRequest, String> answer) {
    return new FakeLlmProvider().answering(answer);
  }

  @Test
  void underABudgetPacketsGoInRoundsUntilTheShareIsSpentAndTheSameItemsAreAnsweredEveryTime() {
    // Items grouped five to a request, as the injection screen's: a share decided once on estimates
    // would stop well short of it, as answers are shorter than expected. Round after round, each
    // from what is really left, the role spends most of it, the same items every time (#659).
    List<Item> items = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      items.add(item(String.format("item%03d", i)));
    }
    java.util.Set<java.util.Set<String>> answeredSets = new java.util.HashSet<>();
    java.util.Set<java.math.BigDecimal> spent = new java.util.HashSet<>();
    java.math.BigDecimal share = new java.math.BigDecimal("0.01");
    for (int run = 0; run < 5; run++) {
      FakeLlmProvider fake =
          answering(
              request -> {
                List<String> entries = new ArrayList<>();
                for (String id : ids(request)) {
                  entries.add(
                      "\"" + id.substring(0, id.indexOf('=')) + "\": " + good("Keeps records"));
                }
                return "{\"items\": {" + String.join(", ", entries) + "}}";
              });
      Harness harness =
          new Harness(
              TestHarness.client(fake, TestHarness.priced(fake)),
              new InMemoryAnswerStore(),
              new RunBudget(
                  new java.math.BigDecimal("0.02"), Map.of("describe", 50, "summarize", 50)),
              BlobLookup.NONE,
              Set.of());
      RoleSession session = harness.session(ROLE);
      session.plan(items, 300);
      Map<String, Outcome> outcomes = session.askPackets(items, SENTENCES);
      java.util.Set<String> answered = new java.util.TreeSet<>();
      outcomes.forEach(
          (subject, outcome) -> {
            if (outcome.kind() == Outcome.Kind.ANSWERED) {
              answered.add(subject);
            }
          });
      answeredSets.add(answered);
      spent.add(session.record().costUsd());
      assertThat(outcomes.values()).doesNotContainNull();
      assertThat(session.record().settled()).isEqualTo(200);
    }
    assertThat(answeredSets).hasSize(1);
    assertThat(spent).hasSize(1);
    java.math.BigDecimal cost = spent.iterator().next();
    assertThat(cost).isLessThanOrEqualTo(share);
    assertThat(cost).isGreaterThan(share.multiply(new java.math.BigDecimal("0.8")));
    assertThat(answeredSets.iterator().next()).hasSizeLessThan(200);
  }

  @Test
  void aPacketWithOneBadItemKeepsTheOthers() {
    FakeLlmProvider fake =
        answering(
            request -> {
              if (request.messages().size() > 1) {
                // The repair turn: the bad item is still bad.
                return "{\"items\": {\"I2\": {\"responsibility\": {\"text\": \"Still bad\", \"cites\": [\"F9\"]}}}}";
              }
              return "{\"items\": {\"I1\": "
                  + good("Holds what customers bought")
                  + ", \"I2\": {\"responsibility\": {\"text\": \"Bad\", \"cites\": [\"F9\"]}}"
                  + ", \"I3\": "
                  + good("Bills each customer monthly")
                  + "}}";
            });
    List<Item> items = List.of(item("orders"), item("payments"), item("invoices"));
    // Packing sorts by fingerprint: find which item is I2 in the request.
    Map<String, Outcome> outcomes = ask(fake, items);

    assertThat(fake.sent()).hasSize(2);
    List<String> ids = ids(fake.sent().getFirst().request());
    String bad =
        ids.stream().filter(i -> i.startsWith("I2=")).findFirst().orElseThrow().substring(3);
    assertThat(outcomes.get(bad).kind()).isEqualTo(Outcome.Kind.DROPPED);
    assertThat(outcomes.values().stream().filter(o -> o.kind() == Outcome.Kind.ANSWERED))
        .hasSize(2);
    assertThat(store.size()).isEqualTo(2);
    // The repair turn lists the rejection of the bad item only.
    String repair = fake.sent().get(1).request().messages().getLast().text();
    assertThat(repair)
        .contains(
            "<rejection item=\"I2\" field=\"responsibility\">Cites F9, which is not in your input.</rejection>");
    assertThat(repair).doesNotContain("item=\"I1\"").doesNotContain("item=\"I3\"");
  }

  @Test
  void aMissingItemIsAskedAgainAloneOnceThenNotAsked() {
    // The model never answers payments, and answers invoices only when asked alone.
    FakeLlmProvider fake =
        answering(
            request -> {
              List<String> ids = ids(request);
              StringBuilder answer = new StringBuilder("{\"items\": {");
              List<String> entries = new ArrayList<>();
              for (String id : ids) {
                String local = id.substring(0, id.indexOf('='));
                String subject = id.substring(id.indexOf('=') + 1);
                if (subject.equals("orders") || (subject.equals("invoices") && ids.size() == 1)) {
                  entries.add("\"" + local + "\": " + good("Keeps the records"));
                }
              }
              return answer.append(String.join(", ", entries)).append("}}").toString();
            });

    Map<String, Outcome> outcomes =
        ask(fake, List.of(item("orders"), item("payments"), item("invoices")));

    assertThat(outcomes.get("orders").kind()).isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(outcomes.get("invoices").kind()).isEqualTo(Outcome.Kind.ANSWERED);
    assertThat(outcomes.get("payments").kind()).isEqualTo(Outcome.Kind.NOT_ASKED);
    assertThat(outcomes.get("payments").reason()).isEqualTo("missing from the answer twice");
    // The packet, then payments and invoices each alone.
    assertThat(fake.sent()).hasSize(3);
    assertThat(fake.sent().subList(1, 3)).allSatisfy(s -> assertThat(ids(s.request())).hasSize(1));
  }

  @Test
  void anOmittedItemIsACorrectAnswerAndIsCached() {
    FakeLlmProvider fake = answering(request -> "{\"items\": {\"I1\": {\"omitted\": true}}}");

    assertThat(ask(fake, List.of(item("orders"))).get("orders").kind())
        .isEqualTo(Outcome.Kind.OMITTED);
    assertThat(ask(fake, List.of(item("orders"))).get("orders").kind())
        .isEqualTo(Outcome.Kind.CACHED);
    assertThat(fake.sent()).hasSize(1);
  }

  @Test
  void theSameItemsMakeByteIdenticalRequestsWithBreakpointsAtBlockBoundaries() {
    Function<LlmRequest, String> all =
        request -> {
          List<String> entries = new ArrayList<>();
          for (String id : ids(request)) {
            entries.add("\"" + id.substring(0, id.indexOf('=')) + "\": " + good("Keeps it"));
          }
          return "{\"items\": {" + String.join(", ", entries) + "}}";
        };
    List<Item> items = new ArrayList<>();
    for (String subject : List.of("e", "b", "g", "a", "f", "c", "d")) {
      items.add(item(subject));
    }
    FakeLlmProvider first = answering(all);
    harness(client(first), new InMemoryAnswerStore()).session(ROLE).askPackets(items, SENTENCES);
    FakeLlmProvider second = answering(all);
    harness(client(second), new InMemoryAnswerStore())
        .session(ROLE)
        .askPackets(items.reversed(), SENTENCES);

    assertThat(first.sent()).hasSize(2);
    assertThat(second.sent().stream().map(s -> s.request()).toList())
        .containsExactlyInAnyOrderElementsOf(first.sent().stream().map(s -> s.request()).toList());
    LlmRequest request = first.sent().getFirst().request();
    assertThat(ids(request)).hasSize(5);
    assertThat(request.cacheBreaks())
        .containsExactly(
            CacheBreak.inSystem(request.system().length()),
            new CacheBreak(0, "<facts>\n[F1] a\n[F2] b\n[F3] c\n</facts>".length()));
  }
}
