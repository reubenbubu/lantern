package io.lantern.extract.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spring Cloud Stream bindings, without Docker: golden facts for the {@code messaging-stream}
 * fixture parsed, the same facts read through javac with stand-ins for Spring Cloud Stream, and how
 * the binder is told when the config names none.
 */
class StreamBindingsTest {

  private static final long SNAPSHOT = 5;

  private static final String STREAM = "method:com.example.stream.";

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = Resolved.facts(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        MessagingExtractor.NAME,
        MessagingExtractor.VERSION);
    assertThat(Resolved.facts(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("messaging-stream", temp.resolve("stream"), "stream");
  }

  private static List<String> lines(List<Fact> facts) {
    return Fixtures.render(facts).lines().toList();
  }

  @Test
  void parsed() throws IOException {
    Repo repo = fixture();
    List<Fact> facts = extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    Fixtures.assertGolden("messaging/stream-parsed.txt", facts);
    // The extractor itself records each value as written, resolving none (ADR 0094, #559).
    List<Fact> written =
        new MessagingExtractor()
            .extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)), SNAPSHOT);
    Fixtures.checkEnvelope(
        written, List.of(repo), SNAPSHOT, MessagingExtractor.NAME, MessagingExtractor.VERSION);
    Fixtures.assertGolden("messaging/stream-written.txt", written);
    assertThat(Fixtures.render(written)).doesNotContain("s3cret").doesNotContain("\"profile\"");
    assertThat(Fixtures.render(facts)).doesNotContain("s3cret");
  }

  @Test
  void cases() throws IOException {
    Repo repo = fixture();
    List<String> facts = lines(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))));

    assertThat(facts)
        // A function's input: a Kafka topic with the binding's group, per profile.
        .anyMatch(
            l ->
                l.startsWith(STREAM + "StreamFunctions#process() message-listener orders ")
                    && l.contains("\"binding\":\"process-in-0\"")
                    && l.contains("\"group\":\"payments\"")
                    && !l.contains("profile"))
        .anyMatch(
            l ->
                l.startsWith(STREAM + "StreamFunctions#process() message-listener cloud.orders ")
                    && l.contains("\"profile\":\"cloud\""))
        // Its output, the destination's placeholder resolved per profile.
        .anyMatch(
            l ->
                l.startsWith(STREAM + "StreamFunctions#process() message-send payments.done ")
                    && l.contains("\"binding\":\"process-out-0\"")
                    && l.contains("\"written\":\"${stream.payments-topic}\""))
        .anyMatch(
            l ->
                l.startsWith(STREAM + "StreamFunctions#process() message-send cloud.payments.done ")
                    && l.contains("\"profile\":\"cloud\""))
        // A consumer of two destinations.
        .anyMatch(l -> l.startsWith(STREAM + "StreamFunctions#audit() message-listener audit.a "))
        .anyMatch(l -> l.startsWith(STREAM + "StreamFunctions#audit() message-listener audit.b "))
        // A supplier through the RabbitMQ binder: the exchange, keyed by the destination's name.
        .anyMatch(
            l ->
                l.startsWith(STREAM + "StreamFunctions#publishShipment() message-send shipments ")
                    && l.contains("\"broker\":\"rabbitmq\"")
                    && l.contains("\"destinationKind\":\"exchange\"")
                    && l.contains("\"routingKey\":\"shipments\""))
        // A RabbitMQ consumer under an alias: the group's queue, bound to the exchange.
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM
                            + "StreamFunctions#receiveShipment() message-listener"
                            + " shipments.warehouse ")
                    && l.contains("\"binding\":\"shipmentsIn\"")
                    && l.contains("\"destinationKind\":\"queue\""))
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM
                            + "StreamFunctions#receiveShipment() message-binding"
                            + " shipments.warehouse ")
                    && l.contains("\"exchange\":\"shipments\"")
                    && l.contains("\"exchangeType\":\"topic\"")
                    && l.contains("\"routingKey\":\"shipment.*\""))
        // A composition: the first function's input, the last one's (a component's) output.
        .anyMatch(
            l -> l.startsWith(STREAM + "StreamFunctions#uppercase() message-listener raw.text "))
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM + "Wrap#apply(java.lang.String) message-send uppercasewrap-out-0 ")
                    && l.contains("\"broker\":\"kafka\""))
        // StreamBridge: a configured binding, a binder named in code, a fixed routing key.
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM + "Notifier#refund(java.lang.String) message-send refunds.topic ")
                    && l.contains("\"binding\":\"refunds\"")
                    && l.contains(
                        "\"template\":\"org.springframework.cloud.stream.function.StreamBridge\""))
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM
                            + "Notifier#refundThroughRabbit(java.lang.String) message-send"
                            + " refunds.topic ")
                    && l.contains("\"broker\":\"rabbitmq\""))
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM + "Notifier#notify(java.lang.String) message-send notifications ")
                    && l.contains("\"routingKey\":\"notify.email\""))
        // A name no binding is configured for is the destination, followed from @Value.
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM + "Notifier#dynamic(java.lang.String) message-send dynamic.topic ")
                    && l.contains(
                        "\"followed\":[\"field:com.example.stream.Notifier#dynamicTopic\"]"))
        // A destination a secret was substituted into stays as written.
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM
                            + "Notifier#secret(java.lang.String) message-send"
                            + " tenant-${stream.api-token} ")
                    && l.contains("\"masked\":true"))
        // The legacy model: @StreamListener with its @SendTo, Source.output() and an @Output.
        .anyMatch(
            l ->
                l.startsWith(
                        STREAM
                            + "LegacyHandler#handle(java.lang.String) message-listener legacy.in ")
                    && l.contains(
                        "\"annotation\":\"org.springframework.cloud.stream.annotation.StreamListener\""))
        .anyMatch(
            l ->
                l.startsWith(
                    STREAM + "LegacyHandler#handle(java.lang.String) message-send legacy.out "))
        .anyMatch(
            l ->
                l.startsWith(
                    STREAM
                        + "LegacyHandler#forward(org.springframework.messaging.Message)"
                        + " message-send legacy.out "));
    assertThat(facts)
        // A supplier bean the definition leaves out is not bound, nor a binding the code chooses.
        .noneMatch(l -> l.startsWith(STREAM + "StreamFunctions#clock()"))
        .noneMatch(l -> l.startsWith(STREAM + "Notifier#chosen("));
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);
    RepoBuild built =
        new RepoBuild(
            repo,
            commit,
            MessagingExtractorTest.build(
                temp, repo, commit, "com.example.stream", "stream", List.of()));

    JavaAnalysis code = JavaExtractor.analyze(List.of(built));
    List<Fact> compiled = Resolved.facts(code, SNAPSHOT);
    List<Fact> parsed = Resolved.facts(List.of(new RepoBuild(repo, commit, null)), SNAPSHOT);

    assertThat(code.modules()).singleElement().matches(JavaAnalysis.Module::compiled);
    List<String> lines = withoutConfidence(compiled);
    // A call to a binding interface's @Output channel is seen only through javac: parsed, the
    // channel's type does not resolve, so the call is not recorded.
    String order =
        STREAM
            + "LegacyHandler#order(org.springframework.messaging.Message) message-send"
            + " legacy.orders ";
    assertThat(lines).anyMatch(l -> l.startsWith(order) && l.contains("\"broker\":\"rabbitmq\""));
    assertThat(lines.stream().filter(l -> !l.startsWith(order)).toList())
        .isNotEmpty()
        .isEqualTo(withoutConfidence(parsed));
  }

  private static final String POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>orders</artifactId>
        <version>1.0.0</version>
      </project>
      """;

  private static final String CONSUMER =
      """
      package com.example.orders;

      import java.util.function.Consumer;
      import org.springframework.context.annotation.Bean;
      import org.springframework.context.annotation.Configuration;

      @Configuration
      public class OrdersConfig {
        @Bean
        public Consumer<String> orders() {
          return order -> {};
        }
      }
      """;

  @Test
  void theOnlyFunctionIsBoundAndTheBinderToldFromTheConfig() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("hints"),
            "orders",
            Map.of(
                "pom.xml",
                POM,
                "src/main/resources/application.properties",
                """
                spring.rabbitmq.host=localhost
                spring.cloud.stream.bindings.orders-in-0.group=shop
                """,
                "src/main/java/com/example/orders/OrdersConfig.java",
                CONSUMER));

    List<String> facts = lines(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))));

    // Without a destination the binding's name is the destination: the group's queue on it.
    assertThat(facts)
        .anyMatch(
            l ->
                l.startsWith(
                        "method:com.example.orders.OrdersConfig#orders() message-listener"
                            + " orders-in-0.shop ")
                    && l.contains("\"broker\":\"rabbitmq\""))
        .anyMatch(
            l ->
                l.startsWith(
                        "method:com.example.orders.OrdersConfig#orders() message-binding"
                            + " orders-in-0.shop ")
                    && l.contains("\"exchange\":\"orders-in-0\"")
                    && l.contains("\"routingKey\":\"#\""));
  }

  @Test
  void theBinderIsToldFromTheClasspathWhenBuilt() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("classpath"),
            "orders",
            Map.of(
                "pom.xml",
                POM,
                "src/main/resources/application.properties",
                "spring.cloud.stream.bindings.orders-in-0.destination=orders\n",
                "src/main/java/com/example/orders/OrdersConfig.java",
                CONSUMER));
    String commit = Fixtures.head(repo);

    List<String> built =
        lines(
            extract(
                List.of(
                    new RepoBuild(
                        repo,
                        commit,
                        MessagingExtractorTest.build(
                            temp,
                            repo,
                            commit,
                            "com.example",
                            "orders",
                            List.of("lib/spring-cloud-stream-binder-kafka-4.1.0.jar"))))));
    List<String> parsed = lines(extract(List.of(new RepoBuild(repo, commit, null))));

    assertThat(built)
        .anyMatch(
            l ->
                l.startsWith(
                        "method:com.example.orders.OrdersConfig#orders() message-listener orders ")
                    && l.contains("\"broker\":\"kafka\""));
    // Parsed, nothing tells the binder: no facts rather than a guess.
    assertThat(parsed).noneMatch(l -> l.contains("OrdersConfig#orders()"));
  }

  @Test
  void aFunctionBeanWithoutSpringCloudStreamIsNotBound() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("plain"),
            "orders",
            Map.of(
                "pom.xml",
                POM,
                "src/main/resources/application.properties",
                "spring.kafka.bootstrap-servers=localhost:9092\n",
                "src/main/java/com/example/orders/OrdersConfig.java",
                CONSUMER));

    assertThat(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)))).isEmpty();
  }

  @Test
  void aCommaComposesAndAnAliasKeyMayLookSecret() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("definition"),
            "orders",
            Map.of(
                "pom.xml",
                POM,
                "src/main/resources/application.properties",
                """
                spring.cloud.stream.binders.k.type=kafka
                spring.cloud.function.definition=passwordReset;orders,wrap
                spring.cloud.stream.function.bindings.passwordReset-in-0=resets
                spring.cloud.stream.bindings.resets.destination=password.resets
                """,
                "src/main/java/com/example/orders/Functions.java",
                """
                package com.example.orders;

                import java.util.function.Consumer;
                import java.util.function.Function;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class Functions {
                  @Bean
                  public Consumer<String> passwordReset() {
                    return reset -> {};
                  }

                  @Bean
                  public Function<String, String> orders() {
                    return order -> order;
                  }

                  @Bean
                  public Function<String, String> wrap() {
                    return text -> text;
                  }
                }
                """));

    List<String> facts = lines(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))));

    String functions = "method:com.example.orders.Functions#";
    assertThat(facts)
        // The alias is read though its key names a password.
        .anyMatch(
            l ->
                l.startsWith(functions + "passwordReset() message-listener password.resets ")
                    && l.contains("\"binding\":\"resets\""))
        // ',' composes as '|' does: one binding, orders' input and wrap's output.
        .anyMatch(l -> l.startsWith(functions + "orders() message-listener orderswrap-in-0 "))
        .anyMatch(l -> l.startsWith(functions + "wrap() message-send orderswrap-out-0 "))
        .noneMatch(
            l ->
                l.contains("orders-in-0")
                    || l.contains("wrap-out-0 ") && !l.contains("orderswrap"));
  }

  @Test
  void aDestinationAKnownSecretKeyWasSubstitutedIntoStaysAsWritten() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("jwt"),
            "orders",
            Map.of(
                "pom.xml",
                POM,
                "src/main/resources/application.properties",
                """
                spring.cloud.stream.binders.k.type=kafka
                spring.cloud.function.definition=orders
                jwt.key=literal-test-value-0123456789abcdef
                spring.cloud.stream.bindings.orders-in-0.destination=tenant-${jwt.key}
                """,
                "src/main/java/com/example/orders/Functions.java",
                """
                package com.example.orders;

                import java.util.function.Consumer;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class Functions {
                  @Bean
                  public Consumer<String> orders() {
                    return order -> {};
                  }
                }
                """));
    JavaAnalysis code =
        JavaExtractor.analyze(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    // Without the code's keys the name alone does not show jwt.key is a secret.
    assertThat(Fixtures.render(Resolved.facts(code, SNAPSHOT))).contains("literal-test-value");
    List<Fact> masked = Resolved.facts(code, SNAPSHOT, java.util.Set.of("jwt.key"));
    assertThat(Fixtures.render(masked))
        .doesNotContain("literal-test-value")
        .contains("tenant-${jwt.key}");
  }

  @Test
  void theBinderIsToldFromThePomsWithoutABuild() throws IOException {
    Repo repo =
        Fixtures.repo(
            temp.resolve("poms"),
            "orders",
            Map.of(
                "pom.xml",
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>orders-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>app</module>
                  </modules>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.cloud</groupId>
                        <artifactId>spring-cloud-stream-binder-kafka</artifactId>
                        <version>4.1.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-stream-rabbit</artifactId>
                      <version>4.1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """,
                "app/pom.xml",
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>orders-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>orders</artifactId>
                </project>
                """,
                "app/src/main/resources/application.properties",
                "spring.cloud.stream.bindings.orders-in-0.group=shop\n",
                "app/src/main/java/com/example/orders/OrdersConfig.java",
                CONSUMER));

    List<String> facts = lines(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))));

    // Inherited from the parent; the managed Kafka binder is only a version, not a dependency.
    assertThat(facts)
        .anyMatch(
            l ->
                l.startsWith(
                        "method:com.example.orders.OrdersConfig#orders() message-listener"
                            + " orders-in-0.shop ")
                    && l.contains("\"broker\":\"rabbitmq\""))
        .noneMatch(l -> l.contains("\"broker\":\"kafka\""));
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }
}
