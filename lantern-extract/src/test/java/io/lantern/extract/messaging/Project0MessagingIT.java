package io.lantern.extract.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.Project0;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildSandbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The messaging facts of the synthetic test set project-0 (docs/adr/0029), checked against the
 * cases its README lists, named by id, both ways the code is read: built in the sandbox and read
 * through javac, and parsed without builds (degraded mode). Runs only when {@code
 * lantern.test.project0} names the folder {@code testsets/project-0/setup.sh} set it up in; see
 * Commands in {@code CLAUDE.md}. Needs Docker and Maven Central, or the Maven mirror of the test
 * properties; the builds use the default shared Maven cache directory.
 */
@EnabledIfSystemProperty(named = "lantern.test.project0", matches = ".+")
class Project0MessagingIT {

  @TempDir static Path temp;

  private static List<String> compiled;
  private static List<String> parsed;

  @BeforeAll
  static void extract() throws IOException {
    BuildSandbox sandbox = new BuildSandbox(Fixtures.sandboxSettings(temp.resolve("builds")));
    Map<String, Repo> project = Project0.repos();
    List<Repo> repos = new ArrayList<>(project.values());
    List<RepoBuild> unbuilt = Project0.unbuilt(project);
    List<RepoBuild> built = Project0.built(project, sandbox);
    JavaAnalysis code = JavaExtractor.analyze(built);
    assertThat(code.modules())
        .as("every module with code is read through javac")
        .allMatch(m -> m.compiled() || m.structure().types().isEmpty());
    compiled = render(new MessagingExtractor().extract(code, 1), repos);
    parsed = render(new MessagingExtractor().extract(unbuilt, 1), repos);
  }

  private static List<String> render(List<Fact> facts, List<Repo> repos) throws IOException {
    Fixtures.checkEnvelope(facts, repos, 1, MessagingExtractor.NAME, MessagingExtractor.VERSION);
    return Fixtures.render(facts)
        .lines()
        .map(line -> line.replaceAll(" [01]\\.[07]$", ""))
        .toList();
  }

  /** Checks a case both ways: through javac and parsed. */
  private static void assertBoth(String... lines) {
    assertThat(compiled).as("through javac").contains(lines);
    assertThat(parsed).as("parsed").contains(lines);
  }

  private static final String FULFILMENT = " @fulfilment:src/main/java/com/acme/shop/fulfilment/";
  private static final String PAYMENTS = " @payments:src/main/java/com/acme/shop/payments/";
  private static final String STOREFRONT = " @storefront:storefront-";
  private static final String JMS_SEND =
      " {\"broker\":\"jms\",\"destinationKind\":\"destination\",\"method\":\"convertAndSend\","
          + "\"template\":\"org.springframework.jms.core.JmsTemplate\"}";
  private static final String JMS_LISTENER =
      "{\"annotation\":\"org.springframework.jms.annotation.JmsListener\",\"broker\":\"jms\","
          + "\"destinationKind\":\"destination\"";
  private static final String RABBIT_LISTENER =
      " {\"annotation\":\"org.springframework.amqp.rabbit.annotation.RabbitListener\","
          + "\"broker\":\"rabbitmq\",\"destinationKind\":\"queue\"}";

  @Test
  void msg1AndVal3AndVal4JmsAcrossRepositories() {
    assertBoth(
        // The constant Destinations.SHIPMENTS_DISPATCHED, folded.
        "method:com.acme.shop.fulfilment.ShipmentService#ship(long) message-send"
            + " shipments.dispatched"
            + JMS_SEND
            + FULFILMENT
            + "ShipmentService.java#19-19",
        // The listener's placeholder, resolved against application.yml.
        "method:com.acme.shop.storefront.order.ShipmentListener#dispatched(java.lang.String)"
            + " message-listener shipments.dispatched "
            + JMS_LISTENER
            + ",\"written\":\"${shop.jms.shipments-queue}\"}"
            + STOREFRONT
            + "app/src/main/java/com/acme/shop/storefront/order/ShipmentListener.java#18-18");
  }

  @Test
  void msg2AndVal4RabbitTopicExchangeWithAWildcardBinding() {
    assertBoth(
        // Exchange and routing key from constants, the key folded from a concatenation.
        "method:com.acme.shop.storefront.integration.OrderEventPublisher#orderCreated("
            + "com.acme.shop.storefront.integration.OrderCreatedEvent) message-send shop.orders"
            + " {\"broker\":\"rabbitmq\",\"destinationKind\":\"exchange\","
            + "\"method\":\"convertAndSend\",\"routingKey\":\"order.created\","
            + "\"template\":\"org.springframework.amqp.rabbit.core.RabbitTemplate\"}"
            + STOREFRONT
            + "integration/src/main/java/com/acme/shop/storefront/integration/"
            + "OrderEventPublisher.java#16-16",
        // The queue and exchange from the beans the binding method's parameters name.
        "method:com.acme.shop.payments.RabbitConfig#orderEventsBinding("
            + "org.springframework.amqp.core.Queue,org.springframework.amqp.core.TopicExchange)"
            + " message-binding payments.order-events"
            + " {\"broker\":\"rabbitmq\",\"exchange\":\"shop.orders\",\"exchangeType\":\"topic\","
            + "\"routingKey\":\"order.*\"}"
            + PAYMENTS
            + "RabbitConfig.java#33-33",
        "method:com.acme.shop.payments.OrderEventsListener#orderEvent(java.lang.String)"
            + " message-listener payments.order-events"
            + RABBIT_LISTENER
            + PAYMENTS
            + "OrderEventsListener.java#15-15");
  }

  @Test
  void msg3AWildcardBindingNothingMatches() {
    assertBoth(
        "method:com.acme.shop.payments.RabbitConfig#refundsBinding("
            + "org.springframework.amqp.core.Queue,org.springframework.amqp.core.TopicExchange)"
            + " message-binding payments.refunds"
            + " {\"broker\":\"rabbitmq\",\"exchange\":\"shop.orders\",\"exchangeType\":\"topic\","
            + "\"routingKey\":\"refund.#\"}"
            + PAYMENTS
            + "RabbitConfig.java#38-38",
        "method:com.acme.shop.payments.OrderEventsListener#refund(java.lang.String)"
            + " message-listener payments.refunds"
            + RABBIT_LISTENER
            + PAYMENTS
            + "OrderEventsListener.java#20-20");
    // Nothing sends to shop.orders with a refund key (MSG-5's refund key, to shop.returns, is not
    // known statically).
    assertThat(compiled)
        .noneMatch(l -> l.contains(" message-send ") && l.contains("\"routingKey\":\"refund."));
  }

  @Test
  void msg4AndVal1KafkaTopicFromAValueField() {
    assertBoth(
        "method:com.acme.shop.payments.PaymentCompletedPublisher#publish(java.lang.String)"
            + " message-send payments.completed"
            + " {\"broker\":\"kafka\",\"destinationKind\":\"topic\","
            + "\"followed\":[\"field:com.acme.shop.payments.PaymentCompletedPublisher#completedTopic\"],"
            + "\"method\":\"send\",\"template\":\"org.springframework.kafka.core.KafkaTemplate\","
            + "\"written\":\"${payments.kafka.completed-topic}\"}"
            + PAYMENTS
            + "PaymentCompletedPublisher.java#20-20",
        "method:com.acme.shop.fulfilment.PaymentCompletedListener#paymentCompleted("
            + "java.lang.String) message-listener payments.completed"
            + " {\"annotation\":\"org.springframework.kafka.annotation.KafkaListener\","
            + "\"broker\":\"kafka\",\"destinationKind\":\"topic\",\"group\":\"fulfilment\"}"
            + FULFILMENT
            + "PaymentCompletedListener.java#15-15");
  }

  @Test
  void spr4TheProfileGatedSend() {
    // The gate itself is the Spring extractor's fact on AuditTrail (Project0SpringIT).
    assertBoth(
        "method:com.acme.shop.storefront.integration.AuditTrail#orderPlaced(long) message-send"
            + " audit.orders"
            + JMS_SEND
            + STOREFRONT
            + "integration/src/main/java/com/acme/shop/storefront/integration/"
            + "AuditTrail.java#19-19",
        "method:com.acme.shop.fulfilment.AuditListener#orderPlaced(java.lang.String)"
            + " message-listener audit.orders "
            + JMS_LISTENER
            + "}"
            + FULFILMENT
            + "AuditListener.java#14-14");
  }

  @Test
  void entry2MessageListenersAsEntryPoints() {
    for (List<String> facts : List.of(compiled, parsed)) {
      assertThat(facts.stream().filter(l -> l.contains(" message-listener ")))
          .extracting(l -> l.substring(0, l.indexOf(" message-listener ")))
          .containsExactly(
              "method:com.acme.shop.fulfilment.AuditListener#orderPlaced(java.lang.String)",
              "method:com.acme.shop.fulfilment.PaymentCompletedListener#paymentCompleted("
                  + "java.lang.String)",
              "method:com.acme.shop.payments.OrderEventsListener#orderEvent(java.lang.String)",
              "method:com.acme.shop.payments.OrderEventsListener#refund(java.lang.String)",
              "method:com.acme.shop.payments.RefundListener#refundRequested(java.lang.String)",
              "method:com.acme.shop.storefront.order.ReturnListener#returned(java.lang.String)",
              "method:com.acme.shop.storefront.order.ShipmentListener#dispatched("
                  + "java.lang.String)");
    }
  }

  @Test
  void bothWaysGiveTheSameFacts() {
    assertThat(parsed).isEqualTo(compiled);
  }
}
