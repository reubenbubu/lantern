package io.lantern.extract.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.Project0;
import io.lantern.extract.RepoBuild;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The docs facts of the synthetic test set project-0 (docs/adr/0029), checked against the cases its
 * README lists, named by id. Runs only when {@code lantern.test.project0} names the folder {@code
 * testsets/project-0/setup.sh} set it up in; see Commands in {@code CLAUDE.md}. Reads the
 * repositories from git without building them.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0DocsIT {

  private static final String ADR = "docs/adr/0001-order-events-over-rabbitmq.md";
  private static final String DECISION = "decision:storefront:" + ADR + " ";

  private static List<String> facts;

  @BeforeAll
  static void extract() throws IOException {
    Map<String, Repo> project = Project0.repos();
    List<Repo> repos = new ArrayList<>(project.values());
    List<RepoBuild> builds = Project0.unbuilt(project);
    List<Fact> extracted = new DocsExtractor().extract(builds, 1);
    Fixtures.checkEnvelope(extracted, repos, 1, DocsExtractor.NAME, DocsExtractor.VERSION);
    facts = Fixtures.render(extracted).lines().toList();
  }

  @Test
  void doc1TheAdrIsADecision() {
    assertThat(facts)
        .containsSubsequence(
            DECISION
                + "decision-consequences Storefront does not need to know who listens to order"
                + " events. @storefront:"
                + ADR
                + "#17-17 0.8",
            DECISION
                + "decision-context Other services need to know when an order is placed."
                + " @storefront:"
                + ADR
                + "#7-7 0.8",
            DECISION + "decision-number 1 @storefront:" + ADR + " 0.8",
            DECISION
                + "decision-outcome Storefront publishes an event for every placed order to the"
                + " RabbitMQ topic exchange",
            "`shop.orders`, with routing keys of the form `order.<event>`. Services bind their own"
                + " queues with",
            "the routing keys they need. @storefront:" + ADR + "#11-13 0.8",
            DECISION + "decision-status accepted @storefront:" + ADR + "#3-3 0.8",
            DECISION + "title Publish order events over RabbitMQ @storefront:" + ADR + "#1-1 0.8");
  }

  @Test
  void doc2ReadmeClaimsAreRead() {
    assertThat(facts)
        .contains(
            "readme:storefront:README.md claim Storefront never calls another service"
                + " synchronously when an order is placed. It publishes an `order.created` event to"
                + " RabbitMQ and the payments service charges the order from that event."
                + " {\"section\":\"Storefront\"} @storefront:README.md#5-6 0.5",
            "readme:payments:README.md claim Payments has a database of its own and never reads"
                + " another service's tables. {\"section\":\"Payments\"} @payments:README.md#5-5"
                + " 0.5");
  }

  @Test
  void onlyTheAdrIsADecision() {
    assertThat(facts.stream().filter(f -> f.startsWith("decision:")).map(f -> f.split(" ")[0]))
        .containsOnly(DECISION.strip());
  }
}
