package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.attributes.IdentityAdminClientAttributes;
import io.lantern.extract.attributes.JwtSecretKeyAttributes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.UrlCredentials;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Kind;
import io.lantern.link.Deployments.Container;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.LinkText.Url;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links units to the identity providers their config and code name (ADR 0085): the issuers, JWK
 * sets and OAuth2 providers of {@code spring.security.oauth2.*} and keys like them, Keycloak's
 * adapter and admin client. A provider that is a unit of the snapshot (an auth service serving its
 * JWKS) is linked to that unit; one a deployment runs from an identity provider's image (Keycloak
 * in compose) is an external system named after the product; one on a host with a domain is the
 * external system of that host. Each link is a {@code USES_IDENTITY_PROVIDER} with what the unit
 * does with the provider ({@code purpose}).
 *
 * <p>It also records the JWT secrets units share ({@code shared-secret}, for the findings page):
 * units whose HMAC keys for JWTs come from the same unresolved placeholder, the same config value
 * or, when the value is masked or unset, the same config key. The secret's value is never recorded.
 */
final class IdentityLinker {

  /** Attribute: a unit signs or checks JWTs with a secret another unit has too. */
  static final String SHARED_SECRET = "shared-secret";

  static final String VALIDATES_TOKENS = "validates-tokens";
  static final String SIGNS_IN_USERS = "signs-in-users";
  static final String GETS_TOKENS = "gets-tokens";
  static final String MANAGES_USERS = "manages-users";

  private static final double BY_NAME = 1.0;
  private static final double BY_CONTAINER = 0.8;
  private static final double BY_DEFAULT_PORT = 0.6;

  private static final String CLIENT = "spring.security.oauth2.client.";
  private static final String PROVIDER = CLIENT + "provider.";
  private static final String REGISTRATION = CLIENT + "registration.";

  /** The last parts of keys, relaxed, that hold a provider's address for checking tokens. */
  private static final Set<String> TOKEN_KEYS =
      Set.of(
          "issueruri",
          "issuerurl",
          "jwkseturi",
          "jwkseturl",
          "jwksuri",
          "jwksurl",
          "introspectionuri");

  /** The last parts of a client provider's keys, relaxed, that hold its address. */
  private static final Set<String> PROVIDER_KEYS =
      Set.of("issueruri", "authorizationuri", "tokenuri", "jwkseturi", "userinfouri");

  /** The providers Spring Security knows without a provider block (CommonOAuth2Provider). */
  private static final Map<String, String> COMMON_PROVIDERS =
      Map.of(
          "google", "accounts.google.com", "github", "github.com", "facebook", "www.facebook.com");

  private final LinkIndex index;
  private final FoundLinks out;
  private final AddressAnswers addresses;
  private final Deployments deployments;

  /** The identity provider containers each external system found from them is deployed in. */
  private final Map<EntityId, Map<String, Container>> deployed = new TreeMap<>(LinkIndex.BY_TEXT);

  IdentityLinker(
      LinkIndex index, FoundLinks out, AddressAnswers addresses, Deployments deployments) {
    this.index = index;
    this.out = out;
    this.addresses = addresses;
    this.deployments = deployments;
  }

  void link(Origin origin, Validity validity) {
    for (String view : index.views()) {
      for (Unit unit : index.units.values()) {
        config(unit, view);
        adminClients(unit, view);
      }
      secrets(view);
    }
    deployed.forEach(
        (system, containers) -> {
          Container first = containers.values().iterator().next();
          out.add(
              Fact.create(
                  system,
                  DataLinker.DISPLAY_NAME,
                  FactObject.string(first.product()),
                  FactJson.newObject(),
                  origin,
                  first.fact().evidence(),
                  first.fact().confidence(),
                  validity));
          containers
              .values()
              .forEach(c -> out.add(Deployments.deployedIn(system, c, origin, validity)));
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Providers named in config

  /** What a unit does with a provider, and the config keys that say so. */
  private record Use(String purpose, String protocol, List<Fact> values) {}

  private void config(Unit unit, String view) {
    Map<String, List<String>> byProvider = new TreeMap<>();
    Map<String, List<String>> registrations = new TreeMap<>();
    List<String> tokenKeys = new ArrayList<>();
    String keycloak = null;
    for (String key : index.configKeys(unit, ConfigExtractor.CONFIG_VALUE)) {
      String relaxed = relaxed(key);
      String last = relaxed.substring(relaxed.lastIndexOf('.') + 1);
      if (relaxed.startsWith(relaxed(PROVIDER))) {
        String[] parts = key.substring(PROVIDER.length()).split("\\.");
        if (parts.length == 2 && PROVIDER_KEYS.contains(last)) {
          byProvider.computeIfAbsent(parts[0], p -> new ArrayList<>()).add(key);
        }
      } else if (relaxed.startsWith(relaxed(REGISTRATION))) {
        String[] parts = key.substring(REGISTRATION.length()).split("\\.");
        if (parts.length == 2) {
          registrations.computeIfAbsent(parts[0], r -> new ArrayList<>()).add(key);
        }
      } else if (relaxed.equals("keycloak.authserverurl")) {
        keycloak = key;
      } else if (TOKEN_KEYS.contains(last) && !relaxed.startsWith(relaxed(CLIENT))) {
        tokenKeys.add(key);
      }
    }
    // Resource servers and keys like theirs: the unit checks the tokens it is sent.
    link(
        unit,
        view,
        new Use(VALIDATES_TOKENS, tokenProtocol(tokenKeys), values(unit, view, tokenKeys)));
    // OAuth2 clients: each provider with what its registrations do with it.
    Map<String, Set<String>> grants = new TreeMap<>();
    for (Map.Entry<String, List<String>> r : registrations.entrySet()) {
      String provider = setting(unit, view, REGISTRATION + r.getKey() + ".provider");
      String grant = setting(unit, view, REGISTRATION + r.getKey() + ".authorization-grant-type");
      String name = provider != null ? provider : r.getKey();
      grants
          .computeIfAbsent(name, n -> new TreeSet<>())
          .add(grant == null ? "authorization_code" : grant);
    }
    for (Map.Entry<String, List<String>> p : byProvider.entrySet()) {
      Set<String> used = grants.getOrDefault(p.getKey(), Set.of("authorization_code"));
      List<Fact> values = values(unit, view, p.getValue());
      boolean oidc = p.getValue().stream().anyMatch(k -> relaxed(k).endsWith(".issueruri"));
      for (String purpose : purposes(used)) {
        link(unit, view, new Use(purpose, clientProtocol(purpose, oidc), values));
      }
    }
    // A provider Spring Security knows by its name, registered without a provider block.
    for (Map.Entry<String, Set<String>> g : grants.entrySet()) {
      String host = COMMON_PROVIDERS.get(g.getKey().toLowerCase(Locale.ROOT));
      if (host == null || byProvider.containsKey(g.getKey())) {
        continue;
      }
      List<Fact> values = values(unit, view, registrations.getOrDefault(g.getKey(), List.of()));
      if (values.isEmpty()) {
        continue;
      }
      EntityId system = new EntityId(CrossRepoLinks.EXTERNAL_SYSTEM, host);
      for (String purpose : purposes(g.getValue())) {
        add(
            unit,
            view,
            new Use(purpose, clientProtocol(purpose, true), values),
            new Reached(system, "provider-name", BY_NAME, null, null, null),
            values.getFirst(),
            "https://" + host,
            null,
            null);
      }
    }
    if (keycloak != null) {
      String bearer = setting(unit, view, "keycloak.bearer-only");
      String purpose = "true".equalsIgnoreCase(bearer) ? VALIDATES_TOKENS : SIGNS_IN_USERS;
      link(unit, view, new Use(purpose, "OIDC", values(unit, view, List.of(keycloak))));
    }
  }

  /** What a client does with its provider, by its grant types. */
  private static Set<String> purposes(Set<String> grants) {
    Set<String> purposes = new TreeSet<>();
    for (String grant : grants) {
      purposes.add(grant.equals("authorization_code") ? SIGNS_IN_USERS : GETS_TOKENS);
    }
    return purposes;
  }

  private static String tokenProtocol(List<String> keys) {
    return keys.stream().anyMatch(k -> relaxed(k).contains("issuer")) ? "OIDC" : "JWKS";
  }

  private static String clientProtocol(String purpose, boolean oidc) {
    return purpose.equals(SIGNS_IN_USERS) && oidc ? "OIDC" : "OAuth2";
  }

  private void adminClients(Unit unit, String view) {
    for (Fact client : index.of(IdentityExtractor.IDENTITY_ADMIN_CLIENT)) {
      if (!index.unitsOf(client.subject()).contains(unit)
          || !index.active(client.subject(), view)) {
        continue;
      }
      LinkText.Resolved resolved =
          LinkText.resolve(
              client.object().value(),
              key -> index.config(unit, view, ConfigExtractor.CONFIG_VALUE, key));
      if (resolved.masked()) {
        continue;
      }
      List<Fact> used = new ArrayList<>(resolved.used());
      String written = used.isEmpty() ? null : Claims.written(used.getFirst());
      Use use =
          new Use(
              MANAGES_USERS,
              Claims.of(client, IdentityAdminClientAttributes.class).product() + " Admin REST",
              List.of(client));
      Reached reached = reach(client, unit, view, resolved.text(), written);
      if (reached != null) {
        Set<String> facts = new TreeSet<>();
        used.forEach(u -> facts.add(u.id()));
        add(unit, view, use, reached, client, resolved.text(), facts, client.subject());
      }
    }
  }

  /** The config values of keys a unit sees in a view: not masked, a URL each. */
  private List<Fact> values(Unit unit, String view, List<String> keys) {
    List<Fact> values = new ArrayList<>();
    for (String key : keys) {
      Fact value = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, key);
      if (value != null && !Claims.masked(value)) {
        values.add(value);
      }
    }
    return values;
  }

  private String setting(Unit unit, String view, String key) {
    for (String k : index.configKeys(unit, ConfigExtractor.CONFIG_VALUE)) {
      if (relaxed(k).equals(relaxed(key))) {
        Fact value = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, k);
        if (value != null && !Claims.masked(value)) {
          return value.object().value().strip();
        }
      }
    }
    return null;
  }

  /** Links a unit to the provider the first of its values that reaches one names. */
  private void link(Unit unit, String view, Use use) {
    for (Fact value : use.values()) {
      Reached reached = reach(value, unit, view, value.object().value(), Claims.written(value));
      if (reached != null) {
        add(unit, view, use, reached, value, value.object().value(), null, null);
        return;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Where a provider's URL leads

  /** What a URL reaches: a unit's or external system's entity, how, and the fact it is by. */
  private record Reached(
      EntityId target, String matchedBy, double factor, Fact by, String path, String url) {

    Reached at(String text) {
      return new Reached(target, matchedBy, factor, by, path, text);
    }
  }

  /**
   * What a provider's URL reaches in a view, as address resolution answers it (filled in by the
   * environment of a container that runs the unit where config leaves the host to it): a unit of
   * the system; else an identity provider container a deployment runs, by its host or, for this
   * machine, the port it publishes; else an external system. Null when it names nothing known.
   *
   * @param asking the config value or admin client whose URL it is
   * @param value the URL as config resolved it, placeholders nothing resolved kept as written
   * @param written the URL as written, when config changed it
   */
  private Reached reach(Fact asking, Unit unit, String view, String value, String written) {
    // What a deployment that runs the unit fills in comes before the config's own value, as in
    // Spring Boot; each is tried until one reaches something, since only this linker knows
    // identity provider containers.
    List<Answer> answers = new ArrayList<>(addresses.answers(asking, unit, view, value, written));
    answers.sort(Comparator.comparing((Answer a) -> a.filledBy() == null));
    for (Answer answer : answers) {
      Reached reached = reach(unit, view, answer);
      if (reached != null) {
        return reached;
      }
    }
    return null;
  }

  private Reached reach(Unit unit, String view, Answer answer) {
    Url url = answer.url();
    String host = url.host();
    if (host == null || !("http".equals(url.scheme()) || "https".equals(url.scheme()))) {
      return null;
    }
    Container from = answer.filledBy();
    Reached reached = null;
    if (answer.kind() == Kind.UNIT) {
      Addresses.Target t = answer.target();
      if (answer.local() && t.fact() == null) {
        // Only when the URL's path reaches one of its endpoints: 8080 is also Keycloak's port.
        Reached byDefault = unit(unit, t.unit(), "port", BY_DEFAULT_PORT, null, url, view);
        reached = byDefault != null && byDefault.path() != null ? byDefault : null;
      } else {
        reached = unit(unit, t.unit(), t.matchedBy(), t.factor(), t.fact(), url, view);
        if (reached == null) {
          return null;
        }
      }
    }
    if (reached == null && answer.local()) {
      if (url.port() != null) {
        reached = byPublishedPort(url.port());
      }
    } else if (reached == null && answer.kind() != Kind.AMBIGUOUS) {
      for (Container c : deployments.all()) {
        if ("identity".equals(Deployments.container(c.fact()).role())
            && c.product() != null
            && c.answers(host)
            && (from == null || sameEnvironment(c, from))) {
          reached = provider(c, "container");
          break;
        }
      }
    }
    if (reached == null && answer.kind() == Kind.EXTERNAL) {
      reached = new Reached(answer.external(), "external", 1.0, null, null, null);
    }
    return reached == null || from == null ? reached : reached.at(answer.address());
  }

  private static boolean sameEnvironment(Container a, Container b) {
    return a.environment().equals(b.environment());
  }

  /** An identity provider container that publishes a port of this machine. */
  private Reached byPublishedPort(String port) {
    List<Container> found = new ArrayList<>();
    for (Container c : deployments.all()) {
      if (!"identity".equals(Deployments.container(c.fact()).role()) || c.product() == null) {
        continue;
      }
      for (String published : Claims.list(Deployments.container(c.fact()).ports())) {
        String p =
            published.contains("/") ? published.substring(0, published.indexOf('/')) : published;
        String[] parts = p.split(":");
        if (parts.length >= 2 && parts[parts.length - 2].equals(port)) {
          found.add(c);
        }
      }
    }
    Set<String> products = new TreeSet<>();
    found.forEach(c -> products.add(c.product()));
    return products.size() == 1 ? provider(found.getFirst(), "published-port") : null;
  }

  /** The external system of an identity provider a deployment runs, named after its product. */
  private Reached provider(Container c, String matchedBy) {
    EntityId system =
        new EntityId(CrossRepoLinks.EXTERNAL_SYSTEM, Deployments.normalized(c.product()));
    deployed.computeIfAbsent(system, s -> new TreeMap<>()).putIfAbsent(c.fact().id(), c);
    return new Reached(system, matchedBy, BY_CONTAINER, c.fact(), null, null);
  }

  /**
   * Another unit, with the endpoint of it the URL's path reaches, if any; null for the unit itself.
   */
  private Reached unit(
      Unit from, Unit to, String matchedBy, double factor, Fact by, Url url, String view) {
    if (to.equals(from)) {
      return null;
    }
    String endpoint = null;
    if (url.path() != null && !url.path().isEmpty()) {
      String prefix = index.servletPrefix(to, view);
      int best = -1;
      for (Fact e : index.of(SpringExtractor.ENDPOINT)) {
        String controller = Endpoints.of(e).controller();
        EntityId code =
            controller == null ? e.subject() : new EntityId(BuiltInTypes.TYPE, controller);
        String template = Endpoints.of(e).path();
        String method = Endpoints.of(e).httpMethod();
        if (template == null
            || (method != null
                && !method.equalsIgnoreCase("GET")
                && !method.equalsIgnoreCase("POST"))
            || !index.unitsOf(code).contains(to)
            || !index.active(e.subject(), view)) {
          continue;
        }
        int score = LinkText.pathMatch(url.path(), LinkText.join(prefix, template));
        if (score > best) {
          best = score;
          endpoint = e.subject().toString();
        }
      }
    }
    return new Reached(to.id(), matchedBy, factor, by, endpoint, null);
  }

  private void add(
      Unit unit,
      String view,
      Use use,
      Reached reached,
      Fact evidence,
      String url,
      Set<String> more,
      EntityId source) {
    if (reached.url() != null) {
      url = reached.url();
    }
    url = UrlCredentials.withoutCredentials(url);
    Url parsed = LinkText.parse(url);
    ObjectNode attributes = FactJson.newObject();
    attributes.put("purpose", use.purpose());
    attributes.put(
        "protocol",
        use.protocol() + " over " + ("https".equals(parsed.scheme()) ? "HTTPS" : "HTTP"));
    attributes.put("url", url);
    String key = Claims.key(evidence);
    if (key != null) {
      attributes.put("key", key);
    }
    if (source != null) {
      attributes.put("source", source.toString());
    }
    if (reached.path() != null) {
      attributes.put("target", reached.path());
    }
    attributes.put("matchedBy", reached.matchedBy());
    Set<String> facts = new TreeSet<>();
    use.values().forEach(v -> facts.add(v.id()));
    facts.add(evidence.id());
    if (reached.by() != null) {
      facts.add(reached.by().id());
    }
    if (more != null) {
      facts.addAll(more);
    }
    double confidence = evidence.confidence() * reached.factor() * unit.fact().confidence();
    out.add(
        view,
        new Found(
            CrossRepoLinks.USES_IDENTITY_PROVIDER
                + "|"
                + unit.id()
                + "|"
                + reached.target()
                + "|"
                + use.purpose(),
            unit.id(),
            CrossRepoLinks.USES_IDENTITY_PROVIDER,
            FactObject.entity(reached.target()),
            attributes,
            evidence.evidence(),
            CrossRepoLinks.round(confidence),
            facts));
  }

  // ---------------------------------------------------------------------------------------------
  // Shared secrets

  /** A unit's HMAC key for JWTs in a view, and where its secret comes from. */
  private record Secret(
      Unit unit, Fact fact, String basis, String origin, List<String> keys, List<Fact> used) {}

  private void secrets(String view) {
    Map<String, List<Secret>> byOrigin = new LinkedHashMap<>();
    for (Fact fact : index.of(IdentityExtractor.JWT_SECRET_KEY)) {
      if (!index.active(fact.subject(), view)) {
        continue;
      }
      for (Unit unit : index.unitsOf(fact.subject())) {
        Secret secret = secret(unit, fact, view);
        byOrigin.computeIfAbsent(secret.origin(), o -> new ArrayList<>()).add(secret);
      }
    }
    for (List<Secret> sharing : byOrigin.values()) {
      Set<EntityId> units = new TreeSet<>(LinkIndex.BY_TEXT);
      sharing.forEach(s -> units.add(s.unit().id()));
      if (units.size() < 2) {
        continue;
      }
      for (Secret s : sharing) {
        ObjectNode attributes = FactJson.newObject();
        attributes.put("algorithm", "HMAC");
        attributes.put("basis", s.basis());
        attributes.put("source", s.fact().subject().toString());
        attributes.put("library", Claims.of(s.fact(), JwtSecretKeyAttributes.class).library());
        ArrayNode with = attributes.putArray("with");
        units.stream().filter(u -> !u.equals(s.unit().id())).forEach(u -> with.add(u.toString()));
        List<String> uses = Claims.list(Claims.of(s.fact(), JwtSecretKeyAttributes.class).uses());
        if (!uses.isEmpty()) {
          ArrayNode array = attributes.putArray("uses");
          uses.forEach(array::add);
        }
        if (s.basis().equals("placeholder")) {
          ArrayNode array = attributes.putArray("placeholders");
          s.keys().forEach(array::add);
        }
        Set<String> facts = new TreeSet<>();
        sharing.forEach(o -> facts.add(o.fact().id()));
        s.used().forEach(u -> facts.add(u.id()));
        out.add(
            view,
            new Found(
                SHARED_SECRET + "|" + s.unit().id() + "|" + s.fact().subject(),
                s.unit().id(),
                SHARED_SECRET,
                FactObject.string(
                    String.join(
                        ", ",
                        Claims.list(Claims.of(s.fact(), JwtSecretKeyAttributes.class).keys()))),
                attributes,
                s.fact().evidence(),
                CrossRepoLinks.round(
                    s.fact().confidence()
                        * (s.basis().equals("key") ? 0.7 : 0.9)
                        * s.unit().fact().confidence()),
                facts));
      }
    }
  }

  /**
   * Where a unit's secret comes from in a view: the placeholders nothing resolves (the environment
   * fills one secret in for each), else a digest of the value config gives it, else, when the value
   * is masked or no config sets it, the key itself. The value never leaves this method.
   */
  private Secret secret(Unit unit, Fact fact, String view) {
    List<String> keys = Claims.list(Claims.of(fact, JwtSecretKeyAttributes.class).keys());
    Set<String> placeholders = new TreeSet<>();
    List<String> values = new ArrayList<>();
    List<Fact> used = new ArrayList<>();
    boolean known = true;
    for (String key : keys) {
      Fact value = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, key);
      if (value == null) {
        placeholders.add(environmental(key));
        continue;
      }
      used.add(value);
      List<String> unresolved = Claims.unresolved(value);
      if (!unresolved.isEmpty()) {
        unresolved.forEach(u -> placeholders.add(environmental(u)));
      } else if (Claims.masked(value)) {
        known = false;
      } else {
        values.add(value.object().value());
      }
    }
    if (!placeholders.isEmpty() && values.isEmpty() && known) {
      return new Secret(
          unit,
          fact,
          "placeholder",
          "placeholder:" + placeholders,
          List.copyOf(placeholders),
          used);
    }
    if (!values.isEmpty() && placeholders.isEmpty() && known) {
      return new Secret(
          unit, fact, "value", "value:" + digest(String.join("\n", values)), List.of(), used);
    }
    Set<String> relaxedKeys = new TreeSet<>();
    keys.forEach(k -> relaxedKeys.add(relaxed(k)));
    return new Secret(unit, fact, "key", "key:" + relaxedKeys, List.of(), used);
  }

  private static String digest(String text) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** A key as Spring's relaxed binding compares it: lower case, without dashes and underscores. */
  private static String relaxed(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }

  /**
   * A key as an environment variable sets it, so {@code AUTH_TOKEN_SIGNINGKEY} and {@code
   * auth.token.signing-key} are one: lower case, {@code _} as {@code .}, without dashes.
   */
  private static String environmental(String key) {
    return key.toLowerCase(Locale.ROOT).replace("-", "").replace('_', '.');
  }
}
