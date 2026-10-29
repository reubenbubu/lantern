package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The check that a text names only what the inputs it rests on mention (ADR 0066). */
class GroundingTest {

  @Test
  void codeLikeNamesAndTechnologiesAreFound() {
    assertThat(
            Grounding.names(
                "OrderService's placeOrder() sends order.created to Kafka through `outbox`,"
                    + " writes order_items in PostgreSQL and serves /api/orders. [F1, F2]"))
        .containsExactly(
            "outbox",
            "Kafka",
            "PostgreSQL",
            "OrderService",
            "placeOrder",
            "order.created",
            "order_items",
            "/api/orders");
  }

  @Test
  void plainWordsAreNotNames() {
    assertThat(
            Grounding.names(
                "Takes orders from shop-core over HTTP, e.g. new ones, i.e. all of them; built-in"
                    + " checks run in spring. Version 3.2 [F1] [fact:"
                    + "a".repeat(64)
                    + "]"))
        .isEmpty();
  }

  @Test
  void aNameAppearsIgnoringCaseAndPunctuation() {
    List<String> support =
        List.of(
            "type:com.example.shop.OrderService has-method placeOrder",
            "service:shop PUBLISHES topic:kafka:order-created",
            "module uses org.postgresql:postgresql");

    assertThat(
            Grounding.unsupported(
                "OrderService's placeOrder() publishes order-created events to Kafka and stores"
                    + " them in PostgreSQL.",
                support))
        .isEmpty();
    assertThat(Grounding.unsupported("OrderServices keep orders.", support)).isEmpty();
  }

  @Test
  void namesNoSupportMentionsAreUnsupported() {
    assertThat(
            Grounding.unsupported(
                "Caches orders in Redis through OrderCache, and stores them in PostgreSQL.",
                List.of("type:com.example.OrderService stereotype service", "uses postgres")))
        .containsExactly("Redis", "OrderCache");
  }

  @Test
  void aTechnologyAppearsUnderItsOtherNames() {
    assertThat(Grounding.unsupported("Listens to RabbitMQ.", List.of("spring-rabbit"))).isEmpty();
    assertThat(Grounding.unsupported("Listens to RabbitMQ.", List.of("protocol AMQP"))).isEmpty();
    assertThat(Grounding.unsupported("Runs on Spring.", List.of("spring-boot-starter"))).isEmpty();
    assertThat(Grounding.unsupported("Runs on Spring.", List.of("quarkus")))
        .containsExactly("Spring");
  }
}
