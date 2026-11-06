package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.types.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The first message of a unit's and of the system's conversation (#904): TOML that reads back, with
 * the task, the unit IDs a connection may name, the facts as short sentences and what is already
 * known without the leads' IDs.
 */
class InvestigateExploreMessageTest {

  private static final String ORDERS = "service:shop:orders";
  private static final String BILLING = "service:shop:billing";

  private static Fact fact(String subject, String predicate, FactObject object, String path) {
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        Evidence.of("shop", path, new Evidence.LineRange(3, 3), null),
        1.0,
        Validity.from(1));
  }

  @Test
  void aUnitsMessageIsItsTaskItsFactsAndWhatIsKnown() {
    Fact summary =
        fact(
            "module:shop:orders",
            Types.SUMMARY,
            FactObject.string("Takes orders from the web shop\nand stores them."),
            "orders/pom.xml");
    Fact call =
        fact(
            ORDERS,
            Types.CALLS_HTTP,
            FactObject.entity(EntityId.parse(BILLING)),
            "orders/src/main/java/shop/BillingClient.java");

    String message =
        InvestigateExploreMessage.unit(
            ORDERS,
            Set.of("shop"),
            new java.util.TreeSet<>(Set.of(ORDERS, BILLING)),
            List.of(summary),
            List.of(call, call),
            List.of(
                "lead:identity-from-request/" + ORDERS + " confirmed: Orders trust the user header",
                "lead:open-cors/" + ORDERS + " refuted: CorsConfig allows every origin",
                "risk: Orders are sent before they commit"));

    assertThat(message)
        .isEqualTo(
            """
            [task]
            what = "Find the risks and connections static analysis missed in the deployable unit service:shop:orders, reading its code."
            repositories = ["shop"]
            units = ["service:shop:billing", "service:shop:orders"]
            ends = "A connection ends at a unit or element by its ID, or at a system no ID names as external-system:<name> or database:<name>."
            already_known = "Each [[known]] below is a lead already checked or a risk already found; do not report it again, nor a link the facts already state."

            [unit]
            id = "service:shop:orders"
            summaries = [
              "Takes orders from the web shop and stores them.",
            ]
            facts = [
              "orders calls over HTTP billing.",
            ]

            [[known]]
            verdict = "confirmed"
            what = "Orders trust the user header"

            [[known]]
            verdict = "refuted"
            what = "CorsConfig allows every origin"

            [[known]]
            verdict = "found"
            what = "Orders are sent before they commit"
            """);
    JsonNode read = InvestigateLeadAnswer.parse(message);
    assertThat(read.path("unit").path("facts")).hasSize(1);
    assertThat(read.path("known")).hasSize(3);
  }

  @Test
  void theSystemsMessageListsEachUnitOnceWithItsLinksAndTheKnownRisks() {
    Map<String, Set<String>> units = new LinkedHashMap<>();
    units.put(BILLING, Set.of("billing"));
    units.put(ORDERS, Set.of("shop"));

    String message =
        InvestigateExploreMessage.system(
            List.of("billing", "shop"),
            units,
            Map.of(),
            List.of(
                ORDERS + " -> " + BILLING + " [CALLS_HTTP]",
                ORDERS + " -> " + BILLING + " [CALLS_HTTP]"),
            List.of("lead confirmed: " + ORDERS + ": Orders trust the user header"));

    JsonNode read = InvestigateLeadAnswer.parse(message);
    assertThat(read.path("task").path("units")).hasSize(2);
    assertThat(read.path("task").path("links").get(0).asString())
        .isEqualTo(ORDERS + " -> " + BILLING + " [CALLS_HTTP] x2");
    assertThat(read.path("task").path("links")).hasSize(1);
    assertThat(read.path("unit")).hasSize(2);
    assertThat(read.path("unit").get(1).path("id").asString()).isEqualTo(ORDERS);
    assertThat(read.path("known").get(0).path("verdict").asString()).isEqualTo("confirmed");
    assertThat(read.path("known").get(0).path("what").asString())
        .isEqualTo(ORDERS + ": Orders trust the user header");
  }

  @Test
  void aKnownLineIsItsVerdictAndWhatItSaysWithoutTheLeadsId() {
    assertThat(InvestigateExploreMessage.known("lead:x/y unclear: a header is trusted"))
        .containsExactly("unclear", "a header is trusted");
    assertThat(InvestigateExploreMessage.known("risk: Uploads have no size limit"))
        .containsExactly("found", "Uploads have no size limit");
  }
}
