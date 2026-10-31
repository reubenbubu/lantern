package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.attributes.BindsAttributes;
import io.lantern.extract.attributes.DeclaresAttributes;
import io.lantern.extract.attributes.ListensOnAttributes;
import io.lantern.extract.attributes.SendsToAttributes;
import io.lantern.extract.attributes.SentByAttributes;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.sql.JdbcUrl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code RESOLVES_TO} facts of value resolution (ADR 0094, amendment for #587): each
 * destination and address keyed by its module and the expression its code writes ({@code
 * destination:<module>:<broker>:<kind>:<expression>}, {@code address:<module>:<expression>}) to the
 * literal one its module's config resolves the expression to, in each view.
 *
 * <ul>
 *   <li>A destination's literal is {@code destination:<broker>:<kind>:<name>}, as the messaging
 *       extractor keys a destination whose name is written out.
 *   <li>An address a request goes to is the host and port of the URL resolved, {@code
 *       address:<host>}, as the HTTP client extractor keys a URL whose host is written out; a Feign
 *       client's URL without a scheme gets {@code http://} first, as Spring Cloud OpenFeign adds
 *       it.
 *   <li>An address a datasource connects to is the server of the JDBC URL resolved, as the
 *       persistence extractor keys a server written out.
 * </ul>
 *
 * One fact for the default view and one for each profile whose config gives another literal, the
 * profile named; none in a view where a placeholder stays unresolved, a secret is used, config
 * changes nothing or no literal host results. Each cites the {@code config-value} facts used.
 */
final class Literals {

  private static final Origin ORIGIN =
      new Origin(Origin.Kind.STATIC, ValueResolution.NAME, ValueResolution.VERSION);

  private Literals() {}

  /** An expression-keyed entity, the module whose config resolves it, and how to resolve it. */
  private record Scoped(
      EntityId entity, EntityId module, String repo, Kind kind, String client, Fact source) {}

  private enum Kind {
    DESTINATION,
    REQUEST_ADDRESS,
    DATASOURCE_ADDRESS
  }

  /** What an expression resolves to in one view: the literal entity and the text resolved. */
  private record Literal(EntityId entity, String resolved, SortedSet<String> facts) {}

  static List<Fact> of(List<Fact> facts, CodeValues values, Validity validity) {
    Objects.requireNonNull(facts, "facts");
    Map<EntityId, Scoped> scoped = new LinkedHashMap<>();
    Map<EntityId, Fact> sentBy = new HashMap<>();
    Map<EntityId, Fact> datasourceOf = new HashMap<>();
    Map<String, Fact> byId = new HashMap<>();
    for (Fact f : facts) {
      byId.putIfAbsent(f.id(), f);
      if (f.predicate().equals(HttpClientExtractor.SENT_BY)
          && f.object().kind() == FactObject.Kind.ENTITY) {
        sentBy.putIfAbsent(f.subject(), f);
      } else if (f.predicate().equals(PersistenceExtractor.HAS_DATASOURCE)
          && f.object().kind() == FactObject.Kind.ENTITY) {
        datasourceOf.putIfAbsent(f.object().entityId(), f);
      }
    }
    for (Fact f : facts) {
      if (f.object().kind() != FactObject.Kind.ENTITY) {
        continue;
      }
      EntityId object = f.object().entityId();
      switch (f.predicate()) {
        case MessagingExtractor.SENDS_TO,
            MessagingExtractor.LISTENS_ON,
            MessagingExtractor.BINDS,
            MessagingExtractor.DECLARES -> {
          if (!object.type().equals(MessagingExtractor.DESTINATION_ENTITY)) {
            continue;
          }
          String[] config = configOf(f);
          if (config[0] != null && within(object, EntityId.parse(config[0]))) {
            scoped.putIfAbsent(
                object,
                new Scoped(
                    object, EntityId.parse(config[0]), config[1], Kind.DESTINATION, null, f));
          }
        }
        case HttpClientExtractor.ADDRESSED_TO -> {
          Fact sent = sentBy.get(f.subject());
          if (sent == null) {
            continue;
          }
          SentByAttributes a = Claims.of(sent, SentByAttributes.class);
          if (a.configModule() == null) {
            continue;
          }
          EntityId module = EntityId.parse(a.configModule());
          if (within(object, module)) {
            scoped.putIfAbsent(
                object,
                new Scoped(object, module, a.configRepo(), Kind.REQUEST_ADDRESS, a.client(), sent));
          }
        }
        case PersistenceExtractor.CONNECTS_TO -> {
          Fact has = datasourceOf.get(f.subject());
          if (has == null || !object.type().equals(HttpClientExtractor.ADDRESS)) {
            continue;
          }
          EntityId module = has.subject();
          if (within(object, module)) {
            scoped.putIfAbsent(
                object,
                new Scoped(
                    object, module, has.evidence().repo(), Kind.DATASOURCE_ADDRESS, null, f));
          }
        }
        default -> {}
      }
    }
    List<Fact> out = new ArrayList<>();
    for (Scoped s : scoped.values()) {
      CodeValues.View config = values.forModule(s.module(), s.repo());
      Literal byDefault = null;
      for (String profile : config.views()) {
        Literal literal = literal(s, config, profile);
        if (profile == null) {
          byDefault = literal;
        }
        if (literal == null
            || (profile != null
                && byDefault != null
                && byDefault.entity().equals(literal.entity()))) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        if (profile != null) {
          attributes.put("profile", profile);
        }
        if (!literal.resolved().equals(literal.entity().key())) {
          attributes.put("resolved", literal.resolved());
        }
        ArrayNode cited = attributes.putArray("facts");
        literal.facts().forEach(cited::add);
        out.add(
            Fact.create(
                s.entity(),
                ValueResolution.RESOLVES_TO,
                FactObject.entity(literal.entity()),
                attributes,
                ORIGIN,
                evidence(s.source(), literal.facts(), byId),
                1.0,
                validity));
      }
    }
    return out;
  }

  /** The literal an expression-keyed entity resolves to in a view, or null. */
  private static Literal literal(Scoped s, CodeValues.View config, String profile) {
    String expression = s.entity().key().substring(s.module().key().length() + 1);
    switch (s.kind()) {
      case DESTINATION -> {
        // <broker>:<kind>:<expression>, the broker and kind having no colon.
        int broker = expression.indexOf(':');
        int kind = broker < 0 ? -1 : expression.indexOf(':', broker + 1);
        if (kind < 0) {
          return null;
        }
        CodeValues.Value v = config.value(profile, expression.substring(kind + 1));
        if (!resolved(v) || v.value().isEmpty()) {
          return null;
        }
        String key = expression.substring(0, kind + 1) + v.value();
        return new Literal(
            new EntityId(MessagingExtractor.DESTINATION_ENTITY, key), key, v.facts());
      }
      case REQUEST_ADDRESS -> {
        if (expression.equals("*")) {
          return null;
        }
        CodeValues.Value v = config.value(profile, expression);
        if (!resolved(v)) {
          return null;
        }
        String url = HttpClientExtractor.clean(v.value());
        if ("feign".equals(s.client())) {
          url = HttpClientExtractor.withScheme(url);
        }
        HttpClientExtractor.Split split = HttpClientExtractor.split(url);
        if (!split.absolute() || split.base() == null) {
          return null;
        }
        String authority = split.base().substring(split.base().indexOf("://") + 3);
        return host(authority, url, v);
      }
      case DATASOURCE_ADDRESS -> {
        CodeValues.Value v = config.value(profile, expression);
        if (!resolved(v)) {
          return null;
        }
        String text = v.value().strip();
        String server = text.contains("://") ? JdbcUrl.of(text, null).server() : text;
        return server == null ? null : host(server, text, v);
      }
    }
    return null;
  }

  /**
   * Where a resolution is seen: where its expression is written, then the config values it used.
   */
  private static Evidence evidence(Fact source, SortedSet<String> used, Map<String, Fact> byId) {
    List<Evidence.Location> locations = new ArrayList<>();
    locations.add(source.evidence().declared());
    for (String id : used) {
      Fact config = byId.get(id);
      if (config != null) {
        Evidence.Location at = config.evidence().declared();
        Evidence.Location located =
            new Evidence.Location(
                at.repo(),
                at.path(),
                at.lines(),
                at.blob(),
                at.contentHash(),
                Evidence.Role.CONFIG);
        if (!locations.contains(located)) {
          locations.add(located);
        }
      }
    }
    return new Evidence(locations);
  }

  private static Literal host(String host, String resolved, CodeValues.Value v) {
    if (host.isEmpty() || host.contains("$") || host.contains("{")) {
      return null;
    }
    return new Literal(
        new EntityId(HttpClientExtractor.ADDRESS, host.toLowerCase(Locale.ROOT)),
        resolved,
        v.facts());
  }

  /**
   * Whether config resolved a value: every placeholder, no secret, and something changed, as an
   * expression that is not literal for another reason (a STOMP destination's {@code {}}) is not
   * config's to resolve.
   */
  private static boolean resolved(CodeValues.Value v) {
    return !v.masked() && v.unresolved().isEmpty() && !v.value().equals(v.written());
  }

  /** Whether an entity is keyed by a module: its key starts with the module's. */
  private static boolean within(EntityId entity, EntityId module) {
    return entity.key().startsWith(module.key() + ":");
  }

  /** The module and repository whose config resolves a messaging relation's values. */
  private static String[] configOf(Fact f) {
    return switch (f.predicate()) {
      case MessagingExtractor.SENDS_TO -> {
        SendsToAttributes a = Claims.of(f, SendsToAttributes.class);
        yield new String[] {a.configModule(), a.configRepo()};
      }
      case MessagingExtractor.LISTENS_ON -> {
        ListensOnAttributes a = Claims.of(f, ListensOnAttributes.class);
        yield new String[] {a.configModule(), a.configRepo()};
      }
      case MessagingExtractor.BINDS -> {
        BindsAttributes a = Claims.of(f, BindsAttributes.class);
        yield new String[] {a.configModule(), a.configRepo()};
      }
      default -> {
        DeclaresAttributes a = Claims.of(f, DeclaresAttributes.class);
        yield new String[] {a.configModule(), a.configRepo()};
      }
    };
  }
}
