package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Links the deployable units of a snapshot at runtime, matching what one exposes with what another
 * uses: HTTP calls with endpoints, messages sent with listeners, datasources with databases, and
 * config with brokers and infrastructure. See {@code docs/adr/0041-cross-repo-links.md}.
 *
 * <p>It runs after {@link DeployableUnits} and reads its facts with the extractors'. Each link is
 * found in every view (the default one and each profile the facts name, one profile active at a
 * time) and recorded once if it holds in the default view, else once per profile it holds under,
 * marked with that {@code profile}. Every link names the facts on both ends ({@code facts}), the
 * code on each end where there is code ({@code source}, {@code target}), its protocol and a link ID
 * that stays the same across snapshots ({@code link}). What static matching cannot decide is
 * recorded as {@code unresolved-link} on the unit, with its candidates, for the LLM resolver.
 */
public final class CrossRepoLinks implements LinkStep {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "cross-repo-links";

  /** The step's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this step's types. */
  public static final String VOCABULARY_SOURCE = "cross-repo-links";

  /** Entity: a Kafka topic. */
  public static final String TOPIC = "topic";

  /** Entity: a RabbitMQ or JMS queue. */
  public static final String QUEUE = "queue";

  /** Entity: a RabbitMQ exchange. */
  public static final String EXCHANGE = "exchange";

  /** Entity: a database on a database server. */
  public static final String DATABASE = "database";

  /** Entity: a message broker a unit's config connects to. */
  public static final String MESSAGE_BROKER = "message-broker";

  /** Entity: a system outside the snapshot that a unit calls. */
  public static final String EXTERNAL_SYSTEM = "external-system";

  /** Entity: a service discovery, config or tracing server outside the snapshot. */
  public static final String INFRASTRUCTURE = "infrastructure";

  /** Relationship: a unit calls another unit or an external system over HTTP. */
  public static final String CALLS_HTTP = "CALLS_HTTP";

  /** Relationship: a unit sends messages to a topic, queue or exchange. */
  public static final String PUBLISHES = "PUBLISHES";

  /** Relationship: a unit receives messages from a topic or queue. */
  public static final String CONSUMES = "CONSUMES";

  /** Relationship: an exchange routes messages to a queue bound to it. */
  public static final String ROUTES_TO = "ROUTES_TO";

  /** Relationship: messages a unit sends reach a listener of another unit. */
  public static final String SENDS_MESSAGES_TO = "SENDS_MESSAGES_TO";

  /** Relationship: a unit connects to a database. */
  public static final String USES_DATABASE = "USES_DATABASE";

  /** Relationship: a unit reads or writes tables of another unit's schema. */
  public static final String ACCESSES_DATA_OF = "ACCESSES_DATA_OF";

  /** Relationship: a unit connects to a message broker. */
  public static final String USES_BROKER = "USES_BROKER";

  /** Relationship: a unit uses service discovery, a config server or tracing. */
  public static final String USES_INFRASTRUCTURE = "USES_INFRASTRUCTURE";

  /** Attribute: a call, send or datasource of a unit that static matching cannot place. */
  public static final String UNRESOLVED_LINK = "unresolved-link";

  private static final String RESOURCE = "/vocabulary/cross-repo-links-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  @Override
  public Origin origin() {
    return ORIGIN;
  }

  /** The vocabulary types this step's facts use besides the built-in and earlier ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = CrossRepoLinks.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Derives the links of a snapshot from its facts.
   *
   * @param facts the facts that hold in the snapshot, the deployable units' included
   * @param snapshot the snapshot number the facts hold from
   * @return the derived facts, without duplicates, ordered by subject, predicate, object and ID
   */
  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    LinkIndex index = new LinkIndex(facts);
    Validity validity = Validity.from(snapshot);
    FoundLinks found = new FoundLinks();
    new HttpLinker(index, found).link();
    new MessageLinker(index, found).link();
    new DataLinker(index, found).link(ORIGIN, validity);
    Map<String, Fact> derived = new LinkedHashMap<>();
    found.facts(index.profiles, ORIGIN, validity).forEach(f -> derived.putIfAbsent(f.id(), f));
    return derived.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  /** Rounded, so a stored confidence reads as it was meant: 0.56, not 0.5599... */
  static double round(double confidence) {
    return Math.round(confidence * 100) / 100.0;
  }
}
