package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.extract.attributes.BindsAttributes;
import io.lantern.extract.attributes.DeclaresAttributes;
import io.lantern.extract.attributes.ListensOnAttributes;
import io.lantern.extract.attributes.MessageBindingAttributes;
import io.lantern.extract.attributes.MessageListenerAttributes;
import io.lantern.extract.attributes.MessageSendAttributes;
import io.lantern.extract.attributes.SendsToAttributes;
import io.lantern.extract.attributes.StreamFunctionAttributes;
import io.lantern.extract.messaging.MessagingExtractor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sends, listeners and bindings as value resolution resolves them in each view, as relations to
 * the destinations they join on (ADR 0094, amendment for #597): each {@code message-send}, {@code
 * message-listener} and {@code message-binding} fact the messaging extractor's resolving gives
 * becomes a {@code SENDS_TO}, {@code LISTENS_ON} or {@code BINDS} from the same method, with the
 * same attributes, evidence and confidence, and the module whose config resolved it.
 *
 * <ul>
 *   <li>The object is the literal destination, {@code destination:<broker>:<kind>:<name>}, as the
 *       messaging extractor keys one whose name is written out and value resolution's {@code
 *       RESOLVES_TO} names one resolved; a binding's is its queue.
 *   <li>Where a placeholder stays unresolved or names a secret, it is the destination as written,
 *       keyed by the module, {@code destination:<module>:<broker>:<kind>:<written>}, so no literal
 *       destination is named that config does not give.
 *   <li>{@code name} is the destination's name as resolved, and {@code configModule} and {@code
 *       configRepo} the module whose config resolved it, as on the relation written in code.
 * </ul>
 */
final class MessageRelations {

  /** The relation each resolved message fact becomes. */
  static final Map<String, String> RELATION =
      Map.of(
          MessagingExtractor.SEND, MessagingExtractor.SENDS_TO,
          MessagingExtractor.LISTENER, MessagingExtractor.LISTENS_ON,
          MessagingExtractor.BINDING, MessagingExtractor.BINDS);

  /** The module whose config resolves a method's values, and its repository. */
  record Config(String module, String repo) {}

  private MessageRelations() {}

  /**
   * The relations of the resolved message facts.
   *
   * @param written the facts written in code, whose {@code configModule} and {@code configRepo}
   *     name the module that resolves each method's values
   * @param resolved the facts value resolution gives; only the message facts are read
   */
  static List<Fact> of(List<Fact> written, List<Fact> resolved) {
    Map<EntityId, Config> config = new HashMap<>();
    for (Fact f : written) {
      Config c = configOf(f);
      if (c != null && c.module() != null && c.repo() != null) {
        config.putIfAbsent(f.subject(), c);
      }
    }
    return of(resolved, config::get);
  }

  /**
   * The relations of message facts, the module that resolves each method's values given.
   *
   * @param resolved the facts; only the message facts are read
   * @param config the module and repository whose config resolves a method's values, or null
   */
  static List<Fact> of(List<Fact> resolved, java.util.function.Function<EntityId, Config> config) {
    List<Fact> relations = new ArrayList<>();
    for (Fact f : resolved) {
      String relation = RELATION.get(f.predicate());
      Config module = relation == null ? null : config.apply(f.subject());
      if (module == null) {
        continue;
      }
      relations.add(relation(f, relation, module));
    }
    return relations;
  }

  /** The module whose config resolves a messaging fact written in code, or null. */
  private static Config configOf(Fact f) {
    if (f.object().kind() != FactObject.Kind.ENTITY
        && !f.predicate().equals(MessagingExtractor.STREAM_FUNCTION)) {
      return null;
    }
    return switch (f.predicate()) {
      case MessagingExtractor.SENDS_TO -> {
        SendsToAttributes a = Claims.of(f, SendsToAttributes.class);
        yield new Config(a.configModule(), a.configRepo());
      }
      case MessagingExtractor.LISTENS_ON -> {
        ListensOnAttributes a = Claims.of(f, ListensOnAttributes.class);
        yield new Config(a.configModule(), a.configRepo());
      }
      case MessagingExtractor.BINDS -> {
        BindsAttributes a = Claims.of(f, BindsAttributes.class);
        yield new Config(a.configModule(), a.configRepo());
      }
      case MessagingExtractor.DECLARES -> {
        DeclaresAttributes a = Claims.of(f, DeclaresAttributes.class);
        yield new Config(a.configModule(), a.configRepo());
      }
      case MessagingExtractor.STREAM_FUNCTION -> {
        StreamFunctionAttributes a = Claims.of(f, StreamFunctionAttributes.class);
        yield new Config(a.configModule(), a.configRepo());
      }
      default -> null;
    };
  }

  private static Fact relation(Fact message, String relation, Config config) {
    // The message fact's attributes carry over whole.
    ObjectNode attributes = Claims.json(message);
    String broker;
    String kind;
    switch (message.predicate()) {
      case MessagingExtractor.SEND -> {
        MessageSendAttributes a = Claims.of(message, MessageSendAttributes.class);
        broker = a.broker();
        kind = a.destinationKind();
      }
      case MessagingExtractor.LISTENER -> {
        MessageListenerAttributes a = Claims.of(message, MessageListenerAttributes.class);
        broker = a.broker();
        kind = a.destinationKind();
      }
      default -> {
        broker = Claims.of(message, MessageBindingAttributes.class).broker();
        kind = "queue";
      }
    }
    String name = message.object().value();
    attributes.put("name", name);
    attributes.put("configModule", config.module());
    attributes.put("configRepo", config.repo());
    boolean literal = !Claims.masked(message) && Claims.unresolved(message).isEmpty();
    String written = Claims.written(message);
    String key =
        literal
            ? broker + ":" + kind + ":" + name
            : EntityId.parse(config.module()).key()
                + ":"
                + broker
                + ":"
                + kind
                + ":"
                + (written != null ? written : name);
    return Fact.create(
        message.subject(),
        relation,
        FactObject.entity(new EntityId(MessagingExtractor.DESTINATION_ENTITY, key)),
        attributes,
        message.origin(),
        message.evidence(),
        message.confidence(),
        message.validity());
  }
}
