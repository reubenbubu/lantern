package io.lantern.extract.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.repo.Repo;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.extract.Fixtures;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.sandbox.BuildModule;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildStatus;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that need no Docker, on the {@code messaging} fixture: golden facts with it parsed
 * (degraded mode), and the same facts with it read through javac, with Lantern's own test classpath
 * (Spring's context and beans annotations) and stand-ins for Spring Kafka, JMS, AMQP and Messaging.
 * {@link Project0MessagingIT} reads the synthetic test set built in the sandbox.
 */
class MessagingExtractorTest {

  private static final long SNAPSHOT = 5;

  @TempDir Path temp;

  private static List<Fact> extract(List<RepoBuild> inputs) throws IOException {
    List<Fact> facts = new MessagingExtractor().extract(inputs, SNAPSHOT);
    Fixtures.checkEnvelope(
        facts,
        inputs.stream().map(RepoBuild::repo).toList(),
        SNAPSHOT,
        MessagingExtractor.NAME,
        MessagingExtractor.VERSION);
    // Running again on the same input gives the same facts.
    assertThat(new MessagingExtractor().extract(inputs, SNAPSHOT)).isEqualTo(facts);
    return facts;
  }

  private Repo fixture() throws IOException {
    return Fixtures.repo("messaging", temp.resolve("messaging"), "messaging");
  }

  @Test
  void parsed() throws IOException {
    Repo repo = fixture();
    List<Fact> facts = extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null)));

    Fixtures.assertGolden("messaging/messaging-parsed.txt", facts);
    // A secret from the config never reaches a fact.
    assertThat(Fixtures.render(facts)).doesNotContain("s3cret");
  }

  @Test
  void cases() throws IOException {
    Repo repo = fixture();
    List<String> facts =
        Fixtures.render(extract(List.of(new RepoBuild(repo, Fixtures.head(repo), null))))
            .lines()
            .toList();
    String events = "method:com.example.events.";

    assertThat(facts)
        // A @Value field followed one hop, resolved per profile.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "PaymentPublisher#publish(java.lang.String) message-send"
                            + " payments.completed ")
                    && l.contains(
                        "\"followed\":[\"field:com.example.events.PaymentPublisher#topic\"]")
                    && l.contains("\"written\":\"${events.kafka.payments-topic}\""))
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "PaymentPublisher#publish(java.lang.String) message-send"
                            + " cloud.payments.completed ")
                    && l.contains("\"profile\":\"cloud\""))
        // A constructor's @Value parameter assigned to a field, with its default.
        .anyMatch(
            l ->
                l.startsWith(
                    events + "RefundPublisher#refund(java.lang.String) message-send refunds "))
        // A topic from a ProducerRecord, folded from a concatenation of constants.
        .anyMatch(
            l ->
                l.startsWith(
                    events + "PaymentPublisher#audit(java.lang.String) message-send audit.events "))
        // A value a secret was substituted into stays as written.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "SecretTopicPublisher#publish(java.lang.String) message-send"
                            + " ${events.kafka.secret-topic} ")
                    && l.contains("\"masked\":true"))
        // A listener carried by a custom annotation.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderEventsHandler#onOrder(java.lang.String) message-listener"
                            + " orders ")
                    && l.contains("\"annotation\":\"com.example.events.OrderEvents\"")
                    && l.contains("\"group\":\"orders\""))
        // A listener on the class, its handlers the entry points.
        .anyMatch(
            l ->
                l.startsWith(
                    events + "AuditHandlers#number(java.lang.Long) message-listener audit.events "))
        // A RabbitMQ send, the routing key folded; one through the default exchange to a queue.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderPublisher#created(java.lang.String) message-send"
                            + " shop.orders ")
                    && l.contains("\"routingKey\":\"order.created\""))
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderPublisher#direct(java.lang.String) message-send"
                            + " direct.queue ")
                    && l.contains("\"destinationKind\":\"queue\""))
        // Bindings: through bean parameters, a bean method called, a builder, and a fanout.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "RabbitConfig#orderEventsBinding(org.springframework.amqp.core.Queue,"
                            + "org.springframework.amqp.core.TopicExchange) message-binding"
                            + " payments.order-events ")
                    && l.contains("\"exchange\":\"shop.orders\"")
                    && l.contains("\"exchangeType\":\"topic\"")
                    && l.contains("\"routingKey\":\"order.*\""))
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "RabbitConfig#refundsBinding(org.springframework.amqp.core.Queue)"
                            + " message-binding payments.refunds ")
                    && l.contains("\"routingKey\":\"refund.#\""))
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "RabbitConfig#broadcastBinding(org.springframework.amqp.core.Queue,"
                            + "org.springframework.amqp.core.FanoutExchange) message-binding"
                            + " payments.refunds ")
                    && l.contains("\"exchangeType\":\"fanout\"")
                    && !l.contains("routingKey"))
        // A @QueueBinding: the queue listened on, and bound with each key.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderEventsListener#audit(java.lang.String) message-binding"
                            + " audit.queue ")
                    && l.contains("\"routingKey\":\"audit.special\""))
        // A reply to an exchange with a routing key.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderEventsListener#orderEvent(java.lang.String) message-send"
                            + " replies ")
                    && l.contains("\"routingKey\":\"reply.key\""))
        // An unresolved placeholder keeps its key.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "ShipmentListener#returned(java.lang.String) message-listener"
                            + " ${events.jms.returns} ")
                    && l.contains("\"unresolved\":[\"events.jms.returns\"]"));
    assertThat(facts)
        // An exchange from @Value that resolves to "" is the default exchange: a send to the queue
        // the key names; a profile naming an exchange sends to it.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderPublisher#routed(java.lang.String) message-send"
                            + " default.routed ")
                    && l.contains("\"destinationKind\":\"queue\""))
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderPublisher#routed(java.lang.String) message-send"
                            + " cloud.exchange ")
                    && l.contains("\"profile\":\"cloud\"")
                    && l.contains("\"routingKey\":\"default.routed\""))
        // A routing key that cannot be followed is marked unknown; the send is kept.
        .anyMatch(
            l ->
                l.startsWith(
                        events
                            + "OrderPublisher#keyed(java.lang.String,java.lang.String)"
                            + " message-send shop.orders ")
                    && l.contains("\"unknown\":[\"routingKey\"]"))
        // @Exchange's type from ExchangeTypes.TOPIC, folded by name when parsed.
        .anyMatch(
            l ->
                l.contains("OrderEventsListener#audit(java.lang.String) message-binding")
                    && l.contains("\"exchangeType\":\"topic\""))
        // An exchange-to-exchange binding is not a queue's.
        .noneMatch(l -> l.contains("downstream"));
    // A topic the caller chooses is not guessed, and a method without a handler annotation is not
    // a listener.
    assertThat(facts)
        .noneMatch(l -> l.startsWith(events + "PaymentPublisher#unknown("))
        .noneMatch(l -> l.startsWith(events + "AuditHandlers#notAHandler("));
  }

  @Test
  void compiledGivesTheSameFactsAsParsed() throws IOException {
    Repo repo = fixture();
    String commit = Fixtures.head(repo);
    RepoBuild built =
        new RepoBuild(
            repo, commit, build(temp, repo, commit, "com.example.events", "events", List.of()));

    JavaAnalysis code = JavaExtractor.analyze(List.of(built));
    List<Fact> compiled = new MessagingExtractor().extract(code, SNAPSHOT);
    List<Fact> parsed =
        new MessagingExtractor().extract(List.of(new RepoBuild(repo, commit, null)), SNAPSHOT);

    assertThat(code.modules()).singleElement().matches(JavaAnalysis.Module::compiled);
    List<String> lines = withoutConfidence(compiled);
    assertThat(lines).isNotEmpty().isEqualTo(withoutConfidence(parsed));
    assertThat(Fixtures.render(compiled).lines()).allMatch(line -> line.endsWith(" 1.0"));
  }

  @Test
  void vocabularyFitsOnTheSpringTypes() {
    Vocabulary vocabulary = Fixtures.vocabulary();

    // A listener is an entry point, as #96 finds them.
    assertThat(vocabulary.isA(MessagingExtractor.LISTENER, "entry-point")).isTrue();
  }

  private static List<String> withoutConfidence(List<Fact> facts) {
    return Fixtures.render(facts).lines().map(l -> l.replaceAll(" [01]\\.[07]$", "")).toList();
  }

  /**
   * A successful build of a one-module fixture, with Lantern's test classpath and, for what that
   * lacks, stand-ins for Spring Kafka, JMS, AMQP, Messaging and Cloud Stream compiled here.
   *
   * @param jars further classpath entries, such as a binder's jar name
   */
  static BuildResult build(
      Path temp, Repo repo, String commit, String groupId, String artifactId, List<String> jars)
      throws IOException {
    List<String> classpath =
        new ArrayList<>(
            Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList());
    classpath.add(compileStubs(temp).toString());
    classpath.addAll(jars);
    BuildModule module =
        new BuildModule(
            "", groupId, artifactId, "jar", "1.0.0", List.of(), classpath, null, null, null);
    Path directory = Files.createDirectories(temp.resolve("builds").resolve(repo.name()));
    return new BuildResult(
        repo.name(),
        commit,
        BuildStatus.SUCCEEDED,
        null,
        21,
        "test",
        "test",
        0,
        Instant.EPOCH,
        Duration.ZERO,
        null,
        List.of(module),
        directory);
  }

  private static Path compileStubs(Path temp) throws IOException {
    List<Path> sources;
    try (Stream<Path> files = Files.walk(Fixtures.fixture("messaging-stubs"))) {
      sources = files.filter(f -> f.toString().endsWith(".java")).toList();
    }
    Path classes = Files.createDirectories(temp.resolve("stub-classes"));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      boolean ok =
          compiler
              .getTask(
                  null,
                  files,
                  null,
                  List.of("-d", classes.toString()),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertThat(ok).as("the stand-ins compile").isTrue();
    }
    return classes;
  }
}
