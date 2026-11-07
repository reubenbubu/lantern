package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.types.Types;
import java.util.Objects;
import java.util.Optional;

/**
 * The names a reader sees for the model's elements (#717): a unit's application name, a database's
 * display name or its database and server, a mail or identity provider's product, a type's simple
 * name, and so on, read from the facts.
 */
public final class ElementNames {

  private final Knowledge knowledge;
  private final String systemName;

  /** The names of the model's entities, as the documents name them. */
  public ElementNames(ArchitectureModel model, String systemName) {
    this(model.index(), systemName);
  }

  /**
   * @param systemName the name of the documented system, which its {@code system} entity takes
   */
  public ElementNames(Knowledge knowledge, String systemName) {
    this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    this.systemName = systemName;
  }

  /** What an element is, with an article, such as {@code a deployable unit}. */
  public static String kind(EntityId element) {
    return switch (element.type()) {
      case Types.SERVICE -> "a deployable unit";
      case Types.DATABASE -> "a database";
      case Types.TOPIC -> "a topic";
      case Types.QUEUE -> "a queue";
      case Types.EXCHANGE -> "an exchange";
      case Types.EXTERNAL_SYSTEM -> "an external system";
      case Types.IDENTITY_PROVIDER -> "an identity provider";
      default -> "a " + element.type();
    };
  }

  /** The name a reader sees for the entity. */
  public String name(EntityId id) {
    String key = id.key();
    return switch (id.type()) {
      case Types.SERVICE -> {
        Optional<String> name = Elements.applicationName(knowledge, id);
        yield name.orElse(key.substring(key.lastIndexOf(':') + 1));
      }
      case Types.SYSTEM -> systemName;
      case Types.ACTOR ->
          id.equals(ScopeFlows.SUBSCRIBERS)
              ? ScopeFlows.SUBSCRIBERS_NAME
              : id.equals(UnknownCallers.CALLER) ? UnknownCallers.CALLER_NAME : key;
      case Types.DATABASE -> {
        Optional<String> display = knowledge.attribute(id, Types.DISPLAY_NAME);
        if (display.isPresent()) {
          // A unit's own database is named by the linker (#291).
          yield display.get();
        }
        // server/database, the server host:port or the unit an embedded database belongs to.
        int slash = key.lastIndexOf('/');
        if (slash < 0) {
          yield key;
        }
        String server = key.substring(0, slash);
        int colon = server.lastIndexOf(':');
        String host =
            colon > 0 && server.substring(colon + 1).matches("[0-9]+")
                ? server.substring(0, colon)
                : server;
        yield key.substring(slash + 1) + " on " + host;
      }
      case Types.TOPIC, Types.QUEUE, Types.EXCHANGE -> key.substring(key.indexOf(':') + 1);
      case Types.EXTERNAL_SYSTEM ->
          knowledge
              .attribute(id, Types.DISPLAY_NAME)
              .or(() -> MailProviders.name(key))
              .or(() -> IdentityProviders.name(key))
              .orElse(key);
      // An identity provider the deployment runs is named after its product (#979).
      case Types.IDENTITY_PROVIDER -> knowledge.attribute(id, Types.DISPLAY_NAME).orElse(key);
      case Types.DECISION -> knowledge.attribute(id, Types.TITLE).orElse(key);
      // A group no role named is named by its signal, without the kind of signal before it.
      case Types.GROUP -> {
        String signal = key.substring(key.lastIndexOf('/') + 1);
        yield signal.substring(signal.indexOf('-') + 1);
      }
      case Types.PERSON -> knowledge.attribute(id, Types.DISPLAY_NAME).orElse(key);
      case Types.FRONTEND ->
          knowledge
              .attribute(id, Types.DISPLAY_NAME)
              .or(
                  () ->
                      Elements.frontendApp(knowledge, id)
                          .map(f -> Elements.attribute(f, "name", key)))
              .orElse(key);
      case Types.MODULE, Types.LIBRARY, Types.PACKAGE, Types.ARTIFACT ->
          key.substring(key.lastIndexOf(':') + 1);
      case Types.TYPE -> key.substring(key.lastIndexOf('.') + 1).replace('$', '.');
      case Types.METHOD, Types.FIELD -> key.substring(key.indexOf('#') + 1);
      default -> key;
    };
  }
}
