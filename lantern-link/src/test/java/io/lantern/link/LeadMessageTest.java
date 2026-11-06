package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.Toml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * A lead's first message (#860): for one lead of each kind, in a small made-up shop, the message
 * equals its golden TOML in {@code src/test/resources/lead-messages/}, and reads back as TOML. With
 * {@code -Dlantern.test.golden.write=true} the golden files are written from the messages instead,
 * to be read before they are committed.
 */
class LeadMessageTest {

  private static final Path GOLDEN = Path.of("src/test/resources/lead-messages");

  private static final String REPO = "shop";
  private static final String ORDERS = "service:shop:orders";
  private static final String BILLING = "service:shop:billing";
  private static final String GATEWAY = "service:shop:gateway";
  private static final String WEB = "frontend:web";
  private static final String CONTROLLER = "orders/src/main/java/shop/orders/OrderController.java";
  private static final String SERVICE = "orders/src/main/java/shop/orders/OrderService.java";
  private static final String CORS = "orders/src/main/java/shop/orders/CorsConfig.java";
  private static final String ORDERS_CONFIG = "orders/src/main/resources/application.yml";
  private static final String BILLING_CLIENT =
      "billing/src/main/java/shop/billing/OrdersClient.java";
  private static final String WEB_ENV = "web/src/environments/environment.prod.ts";
  private static final String WEB_API = "web/src/app/orders.service.ts";
  private static final String COMPOSE = "compose.yaml";
  private static final String EVENT = "events/src/main/java/shop/events/OrderPlaced.java";

  /** The files as the tools show them, a secret masked. */
  private static final Map<String, String> FILES =
      Map.of(
          CONTROLLER,
          """
          package shop.orders;

          @RestController
          @RequestMapping("/orders")
          class OrderController {

            @PostMapping
            Order place(@RequestHeader("X-User-Id") String userId, @RequestBody Order order) {
              return service.place(userId, order);
            }

            @PreAuthorize("isAuthenticated()")
            @DeleteMapping("/{id}")
            void delete(@PathVariable long id) {
              service.delete(id);
            }
          }
          """,
          SERVICE,
          """
          package shop.orders;

          @Service
          class OrderService {

            @Transactional
            Order place(String userId, Order order) {
              Order saved = orders.save(order.ownedBy(userId));
              rabbit.convertAndSend("orders", "order.placed", new OrderPlaced(saved.id()));
              stock.reserve(saved);
              return saved;
            }
          }
          """,
          CORS,
          """
          package shop.orders;

          @Configuration
          class CorsConfig implements WebMvcConfigurer {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
              registry.addMapping("/**").allowedOrigins("*").allowedMethods("*");
            }
          }
          """,
          ORDERS_CONFIG,
          """
          spring:
            datasource:
              url: jdbc:postgresql://db:5432/orders
              username: orders
              password: ******
            jpa:
              hibernate:
                ddl-auto: update
          """,
          BILLING_CLIENT,
          """
          package shop.billing;

          class OrdersClient {
            Invoice invoice(long id) {
              return rest.get().uri("http://orders/invoices/{id}", id).retrieve().body(Invoice.class);
            }
          }
          """,
          WEB_ENV,
          """
          export const environment = {
            production: true,
            apiUrl: 'http://localhost:8080'
          };
          """,
          WEB_API,
          """
          @Injectable({ providedIn: 'root' })
          export class OrdersService {
            place(order: Order) {
              return this.http.post(`${environment.apiUrl}/api/orders`, order);
            }
          }
          """,
          COMPOSE,
          """
          services:
            redis:
              image: redis:7
            orders:
              build: orders
              depends_on: [redis]
          """,
          EVENT,
          """
          package shop.events;

          public record OrderPlaced(long orderId, String currency) {}
          """);

  /** A fact of static analysis about {@code subject}, read at {@code path}:{@code line}. */
  private static Fact fact(
      String subject,
      String predicate,
      FactObject object,
      String attributes,
      String path,
      int line) {
    ObjectNode json =
        attributes == null ? FactJson.newObject() : (ObjectNode) FactJson.parse(attributes);
    return Fact.create(
        EntityId.parse(subject),
        predicate,
        object,
        json,
        new Origin(Origin.Kind.STATIC, "test", "1"),
        Evidence.of(REPO, path, line > 0 ? new Evidence.LineRange(line, line) : null, null),
        1.0,
        Validity.from(1));
  }

  private static Investigator.Lead lead(
      String kind,
      String key,
      String trigger,
      List<String> units,
      List<String> code,
      String path,
      int line,
      Fact... facts) {
    String id = "lead:" + kind + "/" + key;
    return new Investigator.Lead(
        fact(id, "lead-trigger", FactObject.string(kind), null, path, line),
        id,
        kind,
        trigger,
        units,
        code,
        List.of(facts));
  }

  private static final String PLACE =
      "method:shop.orders.OrderController#place(java.lang.String,shop.orders.Order)";
  private static final String SERVICE_PLACE =
      "method:shop.orders.OrderService#place(java.lang.String,shop.orders.Order)";
  private static final String DELETE = "method:shop.orders.OrderController#delete(long)";
  private static final String CORS_METHOD =
      "method:shop.orders.CorsConfig#addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry)";

  /** Where the code the leads point at is declared. */
  private static final Map<String, Evidence.Location> DECLARED =
      Map.of(
          PLACE,
          location(CONTROLLER, 8, 10),
          SERVICE_PLACE,
          location(SERVICE, 6, 12),
          DELETE,
          location(CONTROLLER, 12, 16),
          CORS_METHOD,
          location(CORS, 5, 8),
          "type:shop.events.OrderPlaced",
          location(EVENT, 3, 3));

  private static Evidence.Location location(String path, int from, int to) {
    return Evidence.of(REPO, path, new Evidence.LineRange(from, to), null).locations().getFirst();
  }

  /** One lead of each kind, by the name of its golden file. */
  static Map<String, Investigator.Lead> leads() {
    Map<String, Investigator.Lead> leads = new LinkedHashMap<>();
    leads.put(
        "identity-from-request",
        lead(
            "identity-from-request",
            ORDERS + "/header:X-User-Id",
            "The header X-User-Id is read by the code of orders",
            List.of(ORDERS),
            List.of(PLACE),
            CONTROLLER,
            9,
            fact(
                PLACE,
                "reads-request-value",
                FactObject.string("X-User-Id"),
                "{\"source\":\"header\"}",
                CONTROLLER,
                9)));
    leads.put(
        "send-before-commit",
        lead(
            "send-before-commit",
            ORDERS + "/" + SERVICE_PLACE,
            SERVICE_PLACE + " sends a message inside its transaction",
            List.of(ORDERS),
            List.of(SERVICE_PLACE),
            SERVICE,
            9,
            fact(
                SERVICE_PLACE,
                "message-send",
                FactObject.string("exchange:orders"),
                "{\"routingKey\":\"order.placed\"}",
                SERVICE,
                9),
            fact(SERVICE_PLACE, "transactional", FactObject.string("REQUIRED"), null, SERVICE, 6)));
    leads.put(
        "contract-mismatch",
        lead(
            "contract-mismatch",
            BILLING + "/GET%20/invoices/{id}",
            "billing calls GET /invoices/{id} on orders, which has no such endpoint",
            List.of(ORDERS),
            List.of("method:shop.billing.OrdersClient#invoice(long)"),
            BILLING_CLIENT,
            5,
            fact(
                "method:shop.billing.OrdersClient#invoice(long)",
                "http-call",
                FactObject.entity(EntityId.parse(ORDERS)),
                "{\"httpMethod\":\"GET\",\"path\":\"/invoices/{id}\"}",
                BILLING_CLIENT,
                5)));
    leads.put(
        "contract-mismatch-frontend",
        lead(
            "contract-mismatch",
            WEB + "/POST%20/api/orders",
            "The frontend web sends POST /api/orders, which no gateway route takes",
            List.of(GATEWAY),
            List.of(),
            WEB_API,
            4,
            fact(
                WEB,
                "http-call",
                FactObject.string("POST /api/orders"),
                "{\"httpMethod\":\"POST\",\"path\":\"/api/orders\"}",
                WEB_API,
                4)));
    leads.put(
        "weak-access-check",
        lead(
            "weak-access-check",
            ORDERS + "/" + DELETE,
            "The endpoint DELETE /orders/{id} of orders checks only that the caller is signed in",
            List.of(ORDERS),
            List.of(DELETE),
            CONTROLLER,
            14,
            fact(
                DELETE,
                "endpoint",
                FactObject.string("DELETE /orders/{id}"),
                "{\"httpMethod\":\"DELETE\",\"path\":\"/orders/{id}\"}",
                CONTROLLER,
                14)));
    leads.put(
        "shared-type-skew",
        lead(
            "shared-type-skew",
            "library:shop:events/shop.events.OrderPlaced",
            "orders and billing use shop.events.OrderPlaced from library:shop:events at different"
                + " versions",
            List.of(ORDERS, BILLING),
            List.of("type:shop.events.OrderPlaced"),
            EVENT,
            3,
            fact(
                "library:shop:events",
                "version-skew",
                FactObject.string("1.4.0"),
                "{\"pins\":{\"service:shop:orders\":\"1.4.0\",\"service:shop:billing\":\"1.2.0\"}}",
                "orders/pom.xml",
                0)));
    leads.put(
        "single-instance",
        lead(
            "single-instance",
            "database:redis",
            "database:redis runs as one instance in every deployment, and 2 units use it",
            List.of(),
            List.of(),
            COMPOSE,
            2,
            fact(
                ORDERS,
                "USES_DATABASE",
                FactObject.entity(EntityId.parse("database:redis")),
                null,
                COMPOSE,
                6),
            fact(
                "deployment-instance:compose:default/redis",
                "RUNS",
                FactObject.entity(EntityId.parse("database:redis")),
                null,
                COMPOSE,
                2)));
    leads.put(
        "plaintext-secret",
        lead(
            "plaintext-secret",
            "module:shop:orders/spring.datasource.password",
            "The setting spring.datasource.password of orders is written in its configuration",
            List.of(ORDERS),
            List.of(),
            ORDERS_CONFIG,
            5,
            fact(
                "module:shop:orders",
                "config-value",
                FactObject.string("spring.datasource.password"),
                "{\"profile\":\"default\"}",
                ORDERS_CONFIG,
                5)));
    leads.put(
        "environment-baked",
        lead(
            "environment-baked",
            WEB + "/apiUrl",
            "The frontend web calls http://localhost:8080, written as apiUrl in its production"
                + " environment",
            List.of(),
            List.of(),
            WEB_ENV,
            3,
            fact(
                WEB,
                "config-value",
                FactObject.string("apiUrl"),
                "{\"environment\":\"production\"}",
                WEB_ENV,
                3)));
    leads.put(
        "open-cors",
        lead(
            "open-cors",
            CORS_METHOD + "//**",
            CORS_METHOD + " lets any origin call /** with any method",
            List.of(ORDERS),
            List.of(CORS_METHOD),
            CORS,
            7));
    leads.put(
        "schema-unmanaged",
        lead(
            "schema-unmanaged",
            "module:shop:orders/default",
            "orders sets spring.jpa.hibernate.ddl-auto to update in its default profile",
            List.of(ORDERS),
            List.of(),
            ORDERS_CONFIG,
            8,
            fact(
                "module:shop:orders",
                "config-value",
                FactObject.string("spring.jpa.hibernate.ddl-auto"),
                "{\"profile\":\"default\"}",
                ORDERS_CONFIG,
                8)));
    return leads;
  }

  private static String message(Investigator.Lead lead) {
    Map<String, Set<String>> repos =
        Map.of(ORDERS, Set.of(REPO), BILLING, Set.of(REPO), GATEWAY, Set.of(REPO));
    Set<String> known = Set.of(ORDERS, BILLING, GATEWAY, WEB, "database:redis");
    return LeadMessage.of(
        lead,
        LeadMessage.questions(),
        repos,
        entity -> Optional.ofNullable(DECLARED.get(entity)),
        (repo, path) ->
            Optional.ofNullable(FILES.get(path)).map(text -> List.of(text.split("\n", -1))),
        known);
  }

  static List<String> kinds() {
    return List.copyOf(leads().keySet());
  }

  @ParameterizedTest
  @MethodSource("kinds")
  void eachKindsFirstMessageIsItsGoldenToml(String kind) throws IOException {
    String message = message(leads().get(kind));
    Path golden = GOLDEN.resolve(kind + ".toml");
    if (Boolean.getBoolean("lantern.test.golden.write")) {
      Files.createDirectories(GOLDEN);
      Files.writeString(golden, message);
    }

    Toml.read(message);
    assertThat(message).isEqualTo(Files.readString(golden));
    assertThat(message.length()).isLessThanOrEqualTo(LeadMessage.MAX_CHARS);
  }

  @Test
  void everyKindOfTheQuestionFileHasAGoldenMessage() {
    Set<String> asked = LeadMessage.questions().keySet();
    assertThat(leads().keySet()).containsExactlyInAnyOrderElementsOf(asked);
  }

  /** An entity ID as Lantern writes it, which a reader of a sentence should never see. */
  private static final Pattern FULL_ID =
      Pattern.compile(
          "\\b(?:service|module|library|frontend|method|type|field|database|deployment-instance"
              + "|exchange|queue|topic):\\S");

  @Test
  void noFactSentenceHoldsAnInternalFieldNameOrAFullEntityId() {
    for (Investigator.Lead lead : leads().values()) {
      for (Fact fact : lead.facts()) {
        String sentence = LeadMessage.sentence(fact);
        assertThat(sentence).as("the sentence of %s", fact).doesNotContainPattern(FULL_ID);
        assertThat(sentence)
            .doesNotContain(fact.predicate().contains("-") ? fact.predicate() : "\0");
        List<String> keys = new ArrayList<>();
        fact.attributes().properties().forEach(p -> keys.add(p.getKey()));
        for (String key : keys) {
          if (!key.equals("path") && !key.equals("profile") && !key.equals("environment")) {
            assertThat(sentence).as("the sentence of %s", fact).doesNotContain(key);
          }
        }
      }
    }
  }

  @Test
  void aMessageOverItsSizeDropsItsLastPiecesFirst() {
    Investigator.Lead lead = leads().get("identity-from-request");
    List<Fact> many = new ArrayList<>(lead.facts());
    for (int i = 0; i < 40; i++) {
      many.add(
          fact(
              PLACE,
              "reads-request-value",
              FactObject.string("X-Value-" + i + "-" + "x".repeat(150)),
              "{\"source\":\"header\"}",
              CONTROLLER,
              9));
    }
    Investigator.Lead big =
        new Investigator.Lead(
            lead.fact(), lead.id(), lead.kind(), lead.trigger(), lead.units(), lead.code(), many);

    String message = message(big);

    Toml.read(message);
    assertThat(message.length()).isLessThanOrEqualTo(LeadMessage.MAX_CHARS);
    assertThat(message).contains("X-User-Id").contains("8    Order place(");
  }
}
