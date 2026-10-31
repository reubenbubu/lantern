package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FactSheetTest {

  private static final String MODULE = "module:com.example:orders";
  private static final String SERVICE = "service:com.example:orders";
  private static final String PKG = "package:com.example:orders:com.example.orders";
  private static final String CONTROLLER = "type:com.example.orders.OrderController";
  private static final String REPOSITORY = "type:com.example.orders.OrderRepository";
  private static final String CLIENT = "type:com.example.orders.PaymentClient";

  /**
   * A repository {@code orders} with one module, built as a service that uses a database and calls
   * another service, holding a controller with the given number of endpoints, a repository class
   * that reads a table and a client that calls payments.
   */
  static TestFacts service(int endpoints) {
    String repo = "orders";
    TestFacts facts =
        new TestFacts()
            .relate(repo, SERVICE, "CONTAINS", MODULE)
            .attribute(repo, SERVICE, "technology", "Spring Boot 3.5.14 / Java 21")
            .relate(repo, SERVICE, "USES_DATABASE", "database:orders-db:5432/orders")
            .relate(repo, SERVICE, "CALLS_HTTP", "service:com.example:payments")
            .attribute(repo, MODULE, "java-release", "21")
            .attribute(repo, MODULE, "datasource", "jdbc:postgresql://orders-db:5432/orders")
            .relate(repo, MODULE, "CONTAINS", PKG)
            .relate(repo, PKG, "CONTAINS", CONTROLLER)
            .relate(repo, PKG, "CONTAINS", REPOSITORY)
            .relate(repo, PKG, "CONTAINS", CLIENT)
            .attribute(repo, CONTROLLER, "kind", "class")
            .attribute(repo, CONTROLLER, "stereotype", "controller")
            .attribute(repo, REPOSITORY, "kind", "class")
            .relate(repo, REPOSITORY, "READS_TABLE", "table:com.example:orders:orders")
            .attribute(repo, CLIENT, "kind", "class")
            .relate(repo, CLIENT, "CONTAINS", "method:com.example.orders.PaymentClient#pay()")
            .attribute(
                repo,
                "method:com.example.orders.PaymentClient#pay()",
                "http-call",
                "POST http://payments/api/payments");
    for (int i = 0; i < endpoints; i++) {
      String method = "method:com.example.orders.OrderController#get" + i + "()";
      facts
          .relate(repo, CONTROLLER, "CONTAINS", method)
          .attribute(repo, method, "endpoint", "GET /orders/" + i);
    }
    return facts;
  }

  private static SummaryNode repo(List<Fact> facts) {
    return SummaryTree.fromFacts(facts).getFirst();
  }

  private static List<String> lines(List<FactSheet.Entry> sheet) {
    return sheet.stream().map(e -> e.heading() + ": " + Prompt.fact(e.fact())).toList();
  }

  @Test
  void aModuleSheetListsItsPartsEntryPointsLinksAndDataStoresButNotItsOwnFacts() {
    SummaryNode module = repo(service(2).list()).children().getFirst();

    assertThat(lines(FactSheet.of(module)))
        .containsExactly(
            "Entry points: method:com.example.orders.OrderController#get0() endpoint"
                + " \"GET /orders/0\"",
            "Entry points: method:com.example.orders.OrderController#get1() endpoint"
                + " \"GET /orders/1\"",
            "Links: method:com.example.orders.PaymentClient#pay() http-call"
                + " \"POST http://payments/api/payments\"",
            "Data stores: type:com.example.orders.OrderRepository READS_TABLE"
                + " table:com.example:orders:orders");
  }

  @Test
  void aRepositorySheetAlsoListsItsModulesAndTheirUnitsFacts() {
    SummaryNode repo = repo(service(1).list());

    assertThat(lines(FactSheet.of(repo)))
        .containsExactly(
            "Entry points: method:com.example.orders.OrderController#get0() endpoint"
                + " \"GET /orders/0\"",
            "Links: service:com.example:orders CALLS_HTTP service:com.example:payments",
            "Links: method:com.example.orders.PaymentClient#pay() http-call"
                + " \"POST http://payments/api/payments\"",
            "Data stores: service:com.example:orders USES_DATABASE"
                + " database:orders-db:5432/orders",
            "Data stores: module:com.example:orders datasource"
                + " \"jdbc:postgresql://orders-db:5432/orders\"",
            "Data stores: type:com.example.orders.OrderRepository READS_TABLE"
                + " table:com.example:orders:orders",
            "Technology: service:com.example:orders technology \"Spring Boot 3.5.14 / Java 21\"",
            "Technology: module:com.example:orders java-release \"21\"");
  }

  @Test
  void typesAndPackagesHaveNoSheet() {
    SummaryNode pkg = repo(service(1).list()).children().getFirst().children().getFirst();

    assertThat(FactSheet.of(pkg)).isEmpty();
    assertThat(FactSheet.of(pkg.children().getFirst())).isEmpty();
  }

  @Test
  void aLongGroupIsCutShortTheSameWayWhateverOrderTheFactsComeIn() {
    List<Fact> facts = new ArrayList<>(service(12).list());
    List<Fact> shuffled = new ArrayList<>(facts);
    Collections.shuffle(shuffled, new Random(197));

    List<FactSheet.Entry> sheet = FactSheet.of(repo(facts).children().getFirst());

    assertThat(sheet.stream().filter(e -> e.heading().startsWith("Entry points")))
        .hasSize(FactSheet.MAX_PER_GROUP)
        .allSatisfy(e -> assertThat(e.heading()).isEqualTo("Entry points (10 of 12)"));
    assertThat(FactSheet.of(repo(shuffled).children().getFirst())).isEqualTo(sheet);
  }

  @Test
  void theModulePromptShowsTheSheetAfterItsFactsUnderGroupHeadings() {
    SummaryNode module = repo(service(1).list()).children().getFirst();

    String prompt = Prompt.user(module, List.of(), List.of());

    int facts = module.facts().size();
    assertThat(prompt)
        .contains(
            """

            Fact sheet: entry points, links, data stores and technology of the parts it contains:
            Entry points:
            [F%d] method:com.example.orders.OrderController#get0() endpoint "GET /orders/0"
            Links:
            [F%d] method:com.example.orders.PaymentClient#pay() http-call \
            "POST http://payments/api/payments"
            Data stores:
            [F%d] type:com.example.orders.OrderRepository READS_TABLE \
            table:com.example:orders:orders
            """
                .formatted(facts + 1, facts + 2, facts + 3));
    assertThat(Summarizer.promptFacts(module))
        .hasSize(facts + 3)
        .startsWith(module.facts().toArray(Fact[]::new));
  }

  @Test
  void theSheetReadsTheSameAtAnotherCommit() {
    List<Fact> later =
        service(12).list().stream()
            .map(
                f ->
                    Fact.create(
                        f.subject(),
                        f.predicate(),
                        f.object(),
                        f.attributes(),
                        f.origin(),
                        Evidence.of(f.evidence().repo(), "pom.xml", null, null),
                        f.confidence(),
                        f.validity()))
            .toList();

    assertThat(Prompt.user(repo(later), List.of(), List.of()))
        .isEqualTo(Prompt.user(repo(service(12).list()), List.of(), List.of()));
  }
}
