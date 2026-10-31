package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.config.ConfigExtractor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A deployment's environment over a unit's config, as Spring Boot's relaxed binding reads it. */
class EnvironmentValuesTest {

  private static final Deployments.Container ORDERS =
      new Deployments.Container(
          fact("deployment-container:compose/orders", "orders", "{}"),
          "deploy|compose|default|docker-compose.yml",
          "orders",
          Set.of("orders"),
          Map.of("PAYMENTS_BASEURL", "http://payments:8080", "DB_HOST", "postgres"),
          null,
          null,
          List.of());

  @Test
  void anEnvironmentVariableSetsTheKeyItBindsToBeforeConfigAndDefault() {
    Fact config =
        fact("module:g:orders", "http://localhost:8081", "{\"key\":\"payments.base-url\"}");

    EnvironmentValues.Filled filled =
        EnvironmentValues.filled(
            ORDERS,
            "${payments.base-url:http://localhost:9000}/api",
            key -> key.equals("payments.base-url") ? config : null);

    assertThat(filled.value()).isEqualTo("http://payments:8080/api");
    assertThat(EnvironmentValues.env(ORDERS, "db.host")).isEqualTo("postgres");
    assertThat(EnvironmentValues.fill(ORDERS, "${DB_HOST}:${DB_PORT:5432}"))
        .isEqualTo("postgres:5432");
  }

  @Test
  void aValueTheEnvironmentDoesNotTouchOrThatTakesASecretIsNotFilled() {
    Fact masked =
        fact(
            "module:g:orders",
            ConfigExtractor.MASK,
            "{\"key\":\"payments.token\",\"masked\":true}");

    assertThat(EnvironmentValues.filled(ORDERS, "${catalog.url}/items", key -> null)).isNull();
    assertThat(
            EnvironmentValues.filled(
                ORDERS,
                "${payments.base-url}?token=${payments.token}",
                key -> key.equals("payments.token") ? masked : null))
        .isNull();
  }

  private static Fact fact(String subject, String value, String attributes) {
    return Fact.create(
        EntityId.parse(subject),
        subject.startsWith("module:") ? ConfigExtractor.CONFIG_VALUE : "deployment-container",
        FactObject.string(value),
        (tools.jackson.databind.node.ObjectNode) FactJson.parse(attributes),
        new Origin(Origin.Kind.STATIC, "config", "1"),
        Evidence.of("deploy", "docker-compose.yml", null, null),
        1.0,
        Validity.from(1));
  }
}
