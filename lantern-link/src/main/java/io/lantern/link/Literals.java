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
import java.util.TreeSet;
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
 * profile named, the default view's listing in {@code exceptProfiles} the profiles it does not hold
 * under; a placeholder's own default resolves too, citing no config value; none in a view where a
 * placeholder stays unresolved, a secret is used, config changes nothing or no literal host
 * results. Each cites the {@code config-value} facts used.
 */
final class Literals {

  private static final Origin ORIGIN =
      new Origin(Origin.Kind.STATIC, ValueResolution.NAME, ValueResolution.VERSION);

  private static final String FEIGN = "feign";

  private Literals() {}

  /** An expression-keyed entity, the module whose config resolves it, and how to resolve it. */
  private record Scoped(EntityId entity, EntityId module, String repo, Kind kind, Fact source) {}

  private enum Kind {
    DESTINATION,
    REQUEST_ADDRESS,
    DATASOURCE_ADDRESS
  }

  /** What an expression resolves to in one view: the literal entity and the text resolved. */
  private record Literal(EntityId entity, String resolved, SortedSet<String> facts) {}

  static List<Fact> of(List<Fact> facts, CodeValues values, Validity validity) {
    LinkIndex index = new LinkIndex(facts);
    Deployments deployments = index.units.isEmpty() ? null : new Deployments(index);
    return of(facts, values, index.units.isEmpty() ? null : index, deployments, validity);
  }

  private static List<Fact> of(
      List<Fact> facts,
      CodeValues values,
      LinkIndex index,
      Deployments deployments,
      Validity validity) {
    Objects.requireNonNull(facts, "facts");
    Map<EntityId, Scoped> scoped = new LinkedHashMap<>();
    Map<EntityId, Fact> sentBy = new HashMap<>();
    Map<EntityId, Fact> datasourceOf = new HashMap<>();
    Map<String, Fact> byId = new HashMap<>();
    java.util.Set<EntityId> feign = new java.util.HashSet<>();
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
                new Scoped(object, EntityId.parse(config[0]), config[1], Kind.DESTINATION, f));
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
                object, new Scoped(object, module, a.configRepo(), Kind.REQUEST_ADDRESS, sent));
            if (FEIGN.equals(a.client())) {
              // OpenFeign adds a scheme: an address any Feign client sends to gets one.
              feign.add(object);
            }
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
                new Scoped(object, module, has.evidence().repo(), Kind.DATASOURCE_ADDRESS, f));
          }
        }
        default -> {}
      }
    }
    List<Fact> out = new ArrayList<>();
    for (Scoped s : scoped.values()) {
      CodeValues.View config = values.forModule(s.module(), s.repo());
      boolean scheme = feign.contains(s.entity());
      String written = written(s);
      if (written == null) {
        continue;
      }
      // The views: the default (null), each profile, then each deployment that runs the unit.
      Map<String, Literal> byView = new LinkedHashMap<>();
      for (String profile : config.views()) {
        CodeValues.Value v = config.value(profile, written);
        byView.put(profile, resolved(v) ? literal(s, v.value(), v.facts(), scheme) : null);
      }
      Map<String, Literal> byDeployment = new LinkedHashMap<>();
      for (Deployments.Container c : running(s.module(), index, deployments)) {
        String base = activeProfile(c, config);
        CodeValues.Value inBase = config.value(base, written);
        EnvironmentValues.Filled filled =
            EnvironmentValues.filled(
                c, written, key -> config.facts(base).get(key), config::isSecret);
        Literal literal;
        if (filled == null) {
          // The environment sets nothing the value uses: as in the profile it activates.
          SortedSet<String> used = new TreeSet<>(inBase.facts());
          used.add(c.fact().id());
          literal = resolved(inBase) ? literal(s, inBase.value(), used, scheme) : null;
        } else if (filled.value().contains("${") || filled.value().equals(written)) {
          literal = null;
        } else {
          SortedSet<String> used = new TreeSet<>();
          filled.config().forEach(f -> used.add(f.id()));
          used.add(c.fact().id());
          literal = literal(s, filled.value(), used, scheme);
        }
        byDeployment.put(view(c), literal);
      }
      Literal byDefault = byView.get(null);
      List<InView> all = new ArrayList<>();
      byView.forEach((profile, literal) -> all.add(new InView(profile, null, literal)));
      byDeployment.forEach((view, literal) -> all.add(new InView(null, view, literal)));
      for (InView in : all) {
        Literal literal = in.literal();
        boolean byDefaultView = in.profile() == null && in.deployment() == null;
        if (literal == null
            || (!byDefaultView
                && byDefault != null
                && byDefault.entity().equals(literal.entity()))) {
          continue;
        }
        ObjectNode attributes = FactJson.newObject();
        if (in.deployment() != null) {
          attributes.put("deployment", in.deployment());
        } else if (in.profile() != null) {
          attributes.put("profile", in.profile());
        } else {
          // The default view's literal does not hold in a view that gives another or none.
          except(attributes, "exceptProfiles", byView, literal);
          except(attributes, "exceptDeployments", byDeployment, literal);
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

  /** What an expression resolves to in one view: a profile's, a deployment's or the default. */
  private record InView(String profile, String deployment, Literal literal) {}

  /** Lists the views (not the default) whose literal is not the default view's. */
  private static void except(
      ObjectNode attributes, String name, Map<String, Literal> views, Literal byDefault) {
    List<String> except =
        views.entrySet().stream()
            .filter(v -> v.getKey() != null)
            .filter(v -> v.getValue() == null || !byDefault.entity().equals(v.getValue().entity()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    if (!except.isEmpty()) {
      except.forEach(attributes.putArray(name)::add);
    }
  }

  /**
   * A deployment's view: its environment and the container's name, as {@code
   * <repository>|<platform>|<environment>[|<compose file>]|<container>}.
   */
  static String view(Deployments.Container c) {
    return c.environment() + "|" + c.name();
  }

  /** The containers of the deployments that run a module's unit, if the units are known. */
  private static List<Deployments.Container> running(
      EntityId module, LinkIndex index, Deployments deployments) {
    List<Deployments.Container> running = new ArrayList<>();
    if (index == null) {
      return running;
    }
    for (LinkIndex.Unit unit : index.units.values()) {
      if (unit.modules().contains(module)) {
        deployments
            .running(unit)
            .forEach(
                c -> {
                  if (!running.contains(c)) {
                    running.add(c);
                  }
                });
      }
    }
    return running;
  }

  /**
   * The profile whose config a deployment's environment overlays: the one profile of the module it
   * makes active ({@code SPRING_PROFILES_ACTIVE}), else the default view (null).
   */
  private static String activeProfile(Deployments.Container c, CodeValues.View config) {
    List<String> active =
        Claims.list(Deployments.container(c.fact()).activeProfiles()).stream()
            .filter(p -> config.views().contains(p))
            .toList();
    return active.size() == 1 ? active.getFirst() : null;
  }

  /** The expression an entity's key holds, as written: for a destination, its name. */
  private static String written(Scoped s) {
    String expression = s.entity().key().substring(s.module().key().length() + 1);
    if (s.kind() == Kind.DESTINATION) {
      // <broker>:<kind>:<expression>, the broker and kind having no colon.
      int broker = expression.indexOf(':');
      int kind = broker < 0 ? -1 : expression.indexOf(':', broker + 1);
      return kind < 0 ? null : expression.substring(kind + 1);
    }
    return s.kind() == Kind.REQUEST_ADDRESS && expression.equals("*") ? null : expression;
  }

  /** The literal entity a resolved value names, or null. */
  private static Literal literal(Scoped s, String value, SortedSet<String> facts, boolean feign) {
    switch (s.kind()) {
      case DESTINATION -> {
        if (value.isEmpty()) {
          return null;
        }
        String expression = s.entity().key().substring(s.module().key().length() + 1);
        String key = expression.substring(0, expression.length() - written(s).length()) + value;
        return new Literal(new EntityId(MessagingExtractor.DESTINATION_ENTITY, key), key, facts);
      }
      case REQUEST_ADDRESS -> {
        String url = HttpClientExtractor.clean(value);
        if (feign) {
          url = HttpClientExtractor.withScheme(url);
        }
        HttpClientExtractor.Split split = HttpClientExtractor.split(url);
        if (!split.absolute() || split.base() == null) {
          return null;
        }
        String authority = split.base().substring(split.base().indexOf("://") + 3);
        return host(authority, url, facts);
      }
      case DATASOURCE_ADDRESS -> {
        String text = value.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        // A whole URL (jdbc:, r2dbc:, mongodb://) gives its server; else the text is the server.
        boolean url =
            text.contains("://") || lower.startsWith("jdbc:") || lower.startsWith("r2dbc:");
        String server = url ? JdbcUrl.of(text, null).server() : text;
        return server == null ? null : host(server, text, facts);
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

  private static Literal host(String host, String resolved, SortedSet<String> facts) {
    if (host.isEmpty() || host.contains("$") || host.contains("{")) {
      return null;
    }
    return new Literal(
        new EntityId(HttpClientExtractor.ADDRESS, host.toLowerCase(Locale.ROOT)), resolved, facts);
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
