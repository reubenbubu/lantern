package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links what units send to what units listen on (ADR 0041): Kafka topics by name or a listener's
 * topic pattern, JMS destinations by name, and RabbitMQ exchanges through the bindings declared for
 * them to queues, routing keys matched as the exchange's type routes them, AMQP topic wildcards
 * included. Records the topics, queues and exchanges as entities with what each unit publishes to
 * and consumes from them, and one link from sender to listener for each match.
 */
final class MessageLinker {

  /** Confidence factors: a topic matched through a listener's pattern, a headers exchange. */
  static final double BY_PATTERN = 0.9;

  static final double HEADERS_EXCHANGE = 0.5;

  /** A message sent, received or a binding declared, as it is in one view (by one unit). */
  private record Use(
      Fact fact, Unit unit, String broker, String kind, String destination, List<Fact> used) {}

  private final LinkIndex index;
  private final FoundLinks out;

  MessageLinker(LinkIndex index, FoundLinks out) {
    this.index = index;
    this.out = out;
  }

  void link() {
    Map<String, List<Fact>> sends = LinkIndex.codeSites(index.of(MessagingExtractor.SEND));
    Map<String, List<Fact>> listeners = LinkIndex.codeSites(index.of(MessagingExtractor.LISTENER));
    Map<String, List<Fact>> bindings = LinkIndex.codeSites(index.of(MessagingExtractor.BINDING));
    for (String view : index.views()) {
      List<Use> sent = uses(sends, view, true);
      List<Use> received = uses(listeners, view, true);
      List<Use> bound = uses(bindings, view, false);
      for (Use binding : bound) {
        routes(binding, view);
      }
      for (Use send : sent) {
        publish(send, view, received, bound);
      }
      for (Use listener : received) {
        consume(listener, view, sent);
      }
    }
  }

  /**
   * The uses of each site in a view, one per unit running its code (or one without a unit for a
   * binding, which belongs to the broker once any unit declares it). A destination written with
   * placeholders in a library's code is resolved again with the config of each unit bundling it.
   */
  private List<Use> uses(Map<String, List<Fact>> sites, String view, boolean perUnit) {
    List<Use> uses = new ArrayList<>();
    for (List<Fact> site : sites.values()) {
      Fact fact = LinkIndex.inView(Map.of("", site), view).get("");
      if (fact == null || !index.active(fact.subject(), view)) {
        continue;
      }
      List<Unit> units = index.unitsOf(fact.subject());
      if (units.isEmpty()) {
        continue;
      }
      String broker = LinkIndex.text(fact, "broker");
      String kind = LinkIndex.text(fact, "destinationKind");
      if (!perUnit) {
        if (!LinkIndex.flag(fact, "masked")) {
          uses.add(new Use(fact, null, broker, kind, fact.object().value(), List.of()));
        }
        continue;
      }
      String written = LinkIndex.text(fact, "written");
      for (Unit unit : units) {
        String destination = fact.object().value();
        List<Fact> used = List.of();
        boolean masked = LinkIndex.flag(fact, "masked");
        Set<String> unresolved = new LinkedHashSet<>(LinkIndex.texts(fact, "unresolved"));
        if (written != null
            && !unit.modules().isEmpty()
            && !index.modulesOf(fact.subject()).contains(unit.modules().getFirst())) {
          LinkText.Resolved resolved =
              LinkText.resolve(
                  written, key -> index.config(unit, view, ConfigExtractor.CONFIG_VALUE, key));
          destination = resolved.text();
          used = resolved.used();
          masked = resolved.masked();
          unresolved = resolved.unresolved();
        }
        if (masked) {
          continue;
        }
        Use use = new Use(fact, unit, broker, kind, destination, used);
        if (!unresolved.isEmpty()) {
          unresolved(use, view, unresolved, "unresolved-placeholder", List.of());
        } else {
          uses.add(use);
        }
      }
    }
    return uses;
  }

  /** The topic, queue or exchange a use names, or null for a broker the linker does not know. */
  private static EntityId destination(Use use) {
    String broker = use.broker();
    if (broker == null) {
      return null;
    }
    return switch (broker) {
      case MessagingExtractor.KAFKA ->
          new EntityId(CrossRepoLinks.TOPIC, broker + ":" + use.destination());
      case MessagingExtractor.JMS ->
          new EntityId(CrossRepoLinks.QUEUE, broker + ":" + use.destination());
      case MessagingExtractor.RABBITMQ ->
          "exchange".equals(use.kind())
              ? new EntityId(CrossRepoLinks.EXCHANGE, broker + ":" + use.destination())
              : new EntityId(CrossRepoLinks.QUEUE, broker + ":" + use.destination());
      default -> null;
    };
  }

  private static String protocol(String broker) {
    return switch (broker) {
      case MessagingExtractor.KAFKA -> "Kafka";
      case MessagingExtractor.JMS -> "JMS";
      default -> "AMQP";
    };
  }

  /** A binding: the exchange routes to the queue. */
  private void routes(Use binding, String view) {
    Fact fact = binding.fact();
    String exchange = LinkIndex.text(fact, "exchange");
    if (exchange == null || !MessagingExtractor.RABBITMQ.equals(binding.broker())) {
      return;
    }
    EntityId from = new EntityId(CrossRepoLinks.EXCHANGE, binding.broker() + ":" + exchange);
    EntityId to =
        new EntityId(CrossRepoLinks.QUEUE, binding.broker() + ":" + binding.destination());
    ObjectNode attributes = FactJson.newObject();
    String key = LinkIndex.text(fact, "routingKey");
    if (key != null) {
      attributes.put("bindingKey", key);
    }
    String type = LinkIndex.text(fact, "exchangeType");
    if (type != null) {
      attributes.put("exchangeType", type);
    }
    attributes.put("protocol", "AMQP");
    attributes.put("source", fact.subject().toString());
    out.add(
        view,
        new Found(
            CrossRepoLinks.ROUTES_TO + "|" + from + "|" + to + "|" + fact.subject() + "|" + key,
            from,
            CrossRepoLinks.ROUTES_TO,
            FactObject.entity(to),
            attributes,
            fact.evidence(),
            CrossRepoLinks.round(fact.confidence()),
            List.of(fact.id())));
  }

  /** What a send publishes to, and a link to each unit listening where it arrives. */
  private void publish(Use send, String view, List<Use> received, List<Use> bound) {
    EntityId destination = destination(send);
    if (destination == null) {
      return;
    }
    Fact fact = send.fact();
    String routingKey = LinkIndex.text(fact, "routingKey");
    boolean keyUnknown = LinkIndex.texts(fact, "unknown").contains("routingKey");
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", protocol(send.broker()));
    if (routingKey != null && !keyUnknown) {
      attributes.put("routingKey", routingKey);
    }
    attributes.put("source", fact.subject().toString());
    out.add(
        view,
        new Found(
            CrossRepoLinks.PUBLISHES
                + "|"
                + send.unit().id()
                + "|"
                + destination
                + "|"
                + fact.subject(),
            send.unit().id(),
            CrossRepoLinks.PUBLISHES,
            FactObject.entity(destination),
            attributes,
            fact.evidence(),
            CrossRepoLinks.round(fact.confidence()),
            ids(send, null)));

    if (MessagingExtractor.RABBITMQ.equals(send.broker()) && "exchange".equals(send.kind())) {
      List<Use> undecided = new ArrayList<>();
      for (Use binding : bound) {
        if (!MessagingExtractor.RABBITMQ.equals(binding.broker())
            || !send.destination().equals(LinkIndex.text(binding.fact(), "exchange"))) {
          continue;
        }
        String type = LinkIndex.text(binding.fact(), "exchangeType");
        String pattern = LinkIndex.text(binding.fact(), "routingKey");
        if (keyUnknown && !"fanout".equals(type)) {
          undecided.add(binding);
          continue;
        }
        double factor = routes(type, pattern, routingKey);
        if (factor == 0) {
          continue;
        }
        for (Use listener : received) {
          if (MessagingExtractor.RABBITMQ.equals(listener.broker())
              && !"exchange".equals(listener.kind())
              && listener.destination().equals(binding.destination())) {
            link(send, view, destination, binding, listener, factor);
          }
        }
      }
      if (!undecided.isEmpty()) {
        List<Unit> candidates = new ArrayList<>();
        for (Use binding : undecided) {
          for (Use listener : received) {
            if (MessagingExtractor.RABBITMQ.equals(listener.broker())
                && listener.destination().equals(binding.destination())
                && !candidates.contains(listener.unit())) {
              candidates.add(listener.unit());
            }
          }
        }
        unresolved(send, view, Set.of(), "unknown-routing-key", candidates);
      }
      return;
    }
    for (Use listener : received) {
      if (!send.broker().equals(listener.broker())) {
        continue;
      }
      if (MessagingExtractor.KAFKA.equals(send.broker())) {
        if ("topic-pattern".equals(listener.kind())) {
          if (LinkText.regexMatches(listener.destination(), send.destination())) {
            link(send, view, destination, null, listener, BY_PATTERN);
          }
        } else if (listener.destination().equals(send.destination())) {
          link(send, view, destination, null, listener, 1.0);
        }
      } else if (listener.destination().equals(send.destination())
          && !"exchange".equals(listener.kind())) {
        link(send, view, destination, null, listener, 1.0);
      }
    }
  }

  /**
   * How a binding routes a routing key, as a confidence factor, 0 if it does not: by the exchange's
   * type, an unknown type read as topic if the pattern has wildcards, else direct.
   */
  private static double routes(String type, String pattern, String key) {
    if ("fanout".equals(type)) {
      return 1.0;
    }
    if ("headers".equals(type)) {
      return HEADERS_EXCHANGE;
    }
    if (pattern == null || key == null) {
      return 0;
    }
    boolean topic =
        "topic".equals(type) || (type == null && (pattern.contains("*") || pattern.contains("#")));
    return (topic ? LinkText.topicMatches(pattern, key) : pattern.equals(key)) ? 1.0 : 0;
  }

  /** The link from the unit sending to the unit listening, unless they are the same. */
  private void link(
      Use send, String view, EntityId destination, Use binding, Use listener, double factor) {
    if (send.unit().equals(listener.unit())) {
      return;
    }
    Fact s = send.fact();
    Fact l = listener.fact();
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", protocol(send.broker()));
    attributes.put("destination", destination.toString());
    String routingKey = LinkIndex.text(s, "routingKey");
    if (routingKey != null) {
      attributes.put("routingKey", routingKey);
    }
    EntityId queue = destination(listener);
    if (binding != null) {
      attributes.put("queue", queue.toString());
      String pattern = LinkIndex.text(binding.fact(), "routingKey");
      if (pattern != null) {
        attributes.put("bindingKey", pattern);
      }
    } else if (!queue.equals(destination)) {
      // A topic pattern: the listener names the pattern, not the topic.
      attributes.put("pattern", listener.destination());
    }
    attributes.put("source", s.subject().toString());
    attributes.put("target", l.subject().toString());
    Set<String> facts = new TreeSet<>(ids(send, listener));
    double confidence = Math.min(s.confidence(), l.confidence());
    if (binding != null) {
      facts.add(binding.fact().id());
      confidence = Math.min(confidence, binding.fact().confidence());
    }
    out.add(
        view,
        new Found(
            CrossRepoLinks.SENDS_MESSAGES_TO
                + "|"
                + send.unit().id()
                + "|"
                + listener.unit().id()
                + "|"
                + s.subject()
                + "|"
                + l.subject()
                + "|"
                + destination,
            send.unit().id(),
            CrossRepoLinks.SENDS_MESSAGES_TO,
            FactObject.entity(listener.unit().id()),
            attributes,
            s.evidence(),
            CrossRepoLinks.round(confidence * factor),
            facts));
  }

  /**
   * What a listener consumes from: its topic or queue, or for a Kafka topic pattern each topic sent
   * to that it matches.
   */
  private void consume(Use listener, String view, List<Use> sent) {
    Fact fact = listener.fact();
    List<EntityId> topics = new ArrayList<>();
    if ("exchange".equals(listener.kind())) {
      return;
    }
    if ("topic-pattern".equals(listener.kind())) {
      Set<String> names = new TreeSet<>();
      for (Use send : sent) {
        if (MessagingExtractor.KAFKA.equals(send.broker())
            && LinkText.regexMatches(listener.destination(), send.destination())) {
          names.add(send.destination());
        }
      }
      names.forEach(
          n -> topics.add(new EntityId(CrossRepoLinks.TOPIC, listener.broker() + ":" + n)));
    } else {
      EntityId destination = destination(listener);
      if (destination != null) {
        topics.add(destination);
      }
    }
    for (EntityId topic : topics) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("protocol", protocol(listener.broker()));
      String group = LinkIndex.text(fact, "group");
      if (group != null) {
        attributes.put("group", group);
      }
      if ("topic-pattern".equals(listener.kind())) {
        attributes.put("pattern", listener.destination());
      }
      attributes.put("source", fact.subject().toString());
      out.add(
          view,
          new Found(
              CrossRepoLinks.CONSUMES
                  + "|"
                  + listener.unit().id()
                  + "|"
                  + topic
                  + "|"
                  + fact.subject(),
              listener.unit().id(),
              CrossRepoLinks.CONSUMES,
              FactObject.entity(topic),
              attributes,
              fact.evidence(),
              CrossRepoLinks.round(
                  fact.confidence() * ("topic-pattern".equals(listener.kind()) ? BY_PATTERN : 1.0)),
              ids(listener, null)));
    }
  }

  /** Records a send or listener static matching cannot decide. */
  private void unresolved(
      Use use, String view, Set<String> unresolved, String reason, List<Unit> candidates) {
    Fact fact = use.fact();
    ObjectNode attributes = FactJson.newObject();
    attributes.put("kind", "message");
    attributes.put("reason", reason);
    attributes.put("source", fact.subject().toString());
    if (!unresolved.isEmpty()) {
      ArrayNode keys = attributes.putArray("unresolved");
      unresolved.forEach(keys::add);
    }
    if (!candidates.isEmpty()) {
      ArrayNode units = attributes.putArray("candidates");
      candidates.forEach(u -> units.add(u.id().toString()));
    }
    out.add(
        view,
        new Found(
            CrossRepoLinks.UNRESOLVED_LINK
                + "|"
                + use.unit().id()
                + "|"
                + fact.subject()
                + "|"
                + fact.predicate()
                + "|"
                + (LinkIndex.text(fact, "written") != null
                    ? LinkIndex.text(fact, "written")
                    : fact.object().value()),
            use.unit().id(),
            CrossRepoLinks.UNRESOLVED_LINK,
            FactObject.string(use.destination()),
            attributes,
            fact.evidence(),
            CrossRepoLinks.round(fact.confidence()),
            ids(use, null)));
  }

  private static Set<String> ids(Use a, Use b) {
    Set<String> ids = new TreeSet<>();
    ids.add(a.fact().id());
    a.used().forEach(u -> ids.add(u.id()));
    if (b != null) {
      ids.add(b.fact().id());
      b.used().forEach(u -> ids.add(u.id()));
    }
    return ids;
  }
}
