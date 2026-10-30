package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Placeholders, URLs, path templates and routing keys as the linker matches them. */
class LinkTextTest {

  @Test
  void amqpTopicWildcardsMatchAsTheBrokerMatchesThem() {
    assertThat(LinkText.topicMatches("order.*", "order.created")).isTrue();
    assertThat(LinkText.topicMatches("order.*", "order.created.eu")).isFalse();
    assertThat(LinkText.topicMatches("order.*", "order")).isFalse();
    assertThat(LinkText.topicMatches("order.#", "order")).isTrue();
    assertThat(LinkText.topicMatches("order.#", "order.created.eu")).isTrue();
    assertThat(LinkText.topicMatches("#.eu", "order.created.eu")).isTrue();
    assertThat(LinkText.topicMatches("*.created.#", "order.created")).isTrue();
    assertThat(LinkText.topicMatches("refund.#", "order.created")).isFalse();
    assertThat(LinkText.topicMatches("#", "anything.at.all")).isTrue();
    assertThat(LinkText.topicMatches("order.created", "order.created")).isTrue();
  }

  @Test
  void pathsMatchTemplatesSegmentBySegmentPreferringLiterals() {
    assertThat(LinkText.pathMatch("/api/products/{id}", "/api/products/{id}")).isEqualTo(2);
    assertThat(LinkText.pathMatch("/api/products/42", "/api/products/{id:\\d+}")).isEqualTo(2);
    assertThat(LinkText.pathMatch("/api/products/{productId}/stock", "/api/products/{id}/stock"))
        .isEqualTo(3);
    assertThat(LinkText.pathMatch("/api/products/7/stock", "/api/products/{id}")).isEqualTo(-1);
    assertThat(LinkText.pathMatch("/api/products/", "/api/products")).isEqualTo(2);
    assertThat(LinkText.pathMatch("/static/a/b.css", "/static/**")).isEqualTo(1);
    assertThat(LinkText.pathMatch("/api/orders", "/api/products")).isEqualTo(-1);
    assertThat(LinkText.join("/shop/", "/api")).isEqualTo("/shop/api");
    assertThat(LinkText.join("", "/api")).isEqualTo("/api");
  }

  @Test
  void urlsAreTakenApart() {
    LinkText.Url url = LinkText.parse("HTTP://Payments:8080/api/payments?x=1");
    assertThat(url.scheme()).isEqualTo("http");
    assertThat(url.host()).isEqualTo("payments");
    assertThat(url.port()).isEqualTo("8080");
    assertThat(url.path()).isEqualTo("/api/payments");
    assertThat(LinkText.parse("http://localhost:8082").local()).isTrue();
    assertThat(LinkText.parse("http://localhost:8082").path()).isEmpty();
    assertThat(LinkText.parse("${TAX_SERVICE_URL}/v2/quotes").host()).isNull();
    assertThat(LinkText.parse("${TAX_SERVICE_URL}/v2/quotes").path()).isEqualTo("/v2/quotes");
    assertThat(LinkText.parse("{url}").path()).isNull();
    assertThat(LinkText.parse("http://${HOST}/x").host()).isNull();
    // An expression concatenated onto the host is the path, even with slashes and a ? inside it.
    LinkText.Url concatenated =
        LinkText.parse(
            "http://127.0.0.1:8888{uri.substring(uri.indexOf(\"/api\") + 4)}{q ? x : y}");
    assertThat(concatenated.host()).isEqualTo("127.0.0.1");
    assertThat(concatenated.port()).isEqualTo("8888");
    assertThat(concatenated.path())
        .isEqualTo("{uri.substring(uri.indexOf(\"/api\") + 4)}{q ? x : y}");
    assertThat(LinkText.parse("http://host:${PORT}/x").host()).isNull();
  }

  @Test
  void anExpressionInAPathIsOneSegment() {
    assertThat(LinkText.pathMatch("/{a.indexOf(\"/api\")}/x", "/{id}/x")).isEqualTo(1);
    assertThat(LinkText.pathMatch("{a.indexOf(\"/api\")}", "/{id}")).isEqualTo(0);
  }

  @Test
  void placeholdersResolveAgainstConfigWithDefaultsAndNesting() {
    Map<String, Fact> config =
        Map.of(
            "base", value("base", "http://orders:8080", null),
            "secret", value("secret", "******", "{\"masked\":true}"),
            "tax", value("tax", "${TAX_URL}", "{\"unresolved\":[\"TAX_URL\"]}"));
    LinkText.Resolved resolved = LinkText.resolve("${base}/api", config::get);
    assertThat(resolved.text()).isEqualTo("http://orders:8080/api");
    assertThat(resolved.unresolved()).isEmpty();
    assertThat(resolved.used()).hasSize(1);
    assertThat(LinkText.resolve("${missing:${base}}/x", config::get).text())
        .isEqualTo("http://orders:8080/x");
    assertThat(LinkText.resolve("${missing}/x", config::get).unresolved())
        .containsExactly("missing");
    assertThat(LinkText.resolve("${tax}/q", config::get).unresolved()).containsExactly("TAX_URL");
    assertThat(LinkText.resolve("${secret}", config::get).masked()).isTrue();
    assertThat(LinkText.resolve("${missing:}x", config::get).text()).isEqualTo("x");
  }

  @Test
  void addressesLoseSchemesPathsAndCredentials() {
    assertThat(DataLinker.address("tcp://artemis:61616?x=1")).isEqualTo("artemis:61616");
    assertThat(DataLinker.address("optional:configserver:http://config:8888".substring(22)))
        .isEqualTo("config:8888");
    assertThat(DataLinker.address("http://user:pw@eureka:8761/eureka/")).isEqualTo("eureka:8761");
    assertThat(DataLinker.address(" Kafka:9092 ")).isEqualTo("kafka:9092");
    assertThat(DataLinker.address("failover:(tcp://a:61616,tcp://b:61616)")).isEqualTo("a:61616");
  }

  private static Fact value(String key, String value, String attributes) {
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    json.put("key", key);
    return Fact.create(
        EntityId.parse("module:g:a"),
        "config-value",
        FactObject.string(value),
        json,
        new Origin(Origin.Kind.STATIC, "config", "1"),
        new Evidence("r", "a".repeat(40), "src/main/resources/application.yml", null, null),
        1.0,
        Validity.from(1));
  }
}
