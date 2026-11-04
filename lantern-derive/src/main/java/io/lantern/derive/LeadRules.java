package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The rules of the Investigate lead catalog (knowledge-build epic, section 11.2; #524), one per
 * kind, each a query over the facts. The catalog itself, what each kind's trigger is and what the
 * model judges, is data: {@code leads.json}.
 */
public final class LeadRules {

  private LeadRules() {}

  /** Every lead rule, in the catalog's order. */
  public static List<LeadRule> all() {
    return List.of(
        new IdentityFromRequest(),
        new SendBeforeCommit(),
        new ContractMismatch(),
        new WeakAccessCheck(),
        new SharedTypeSkew(),
        new SingleInstance(),
        new PlaintextSecret(),
        new EnvironmentBaked(),
        new OpenCors(),
        new SchemaUnmanaged());
  }

  /**
   * What the model judges for each kind of lead, by kind, in the catalog's order ({@code
   * leads.json}): Investigate shows it with each lead it asks about.
   */
  public static Map<String, String> judges() {
    try (java.io.InputStream in = LeadRules.class.getResourceAsStream("/leads.json")) {
      if (in == null) {
        throw new IllegalStateException("Missing resource /leads.json");
      }
      Map<String, String> judges = new java.util.LinkedHashMap<>();
      for (tools.jackson.databind.JsonNode kind :
          tools.jackson.databind.json.JsonMapper.builder().build().readTree(in)) {
        judges.put(kind.path("kind").asString(), kind.path("judge").asString());
      }
      return java.util.Collections.unmodifiableMap(judges);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("Cannot read /leads.json", e);
    }
  }

  private static final String HTTP_METHOD = "httpMethod";

  // --- identity-from-request -------------------------------------------------------------------

  /**
   * A header, cookie or parameter read in a unit's code whose name says it carries who the caller
   * is (user, account, member, role, permission, tenant or organization, with an optional id, ids
   * or s, and an optional x- before): one lead per unit and value, citing every read.
   */
  static final class IdentityFromRequest extends LeadRule {
    private static final Pattern IDENTITY =
        Pattern.compile(
            "x?(user|account|member|role|permission|tenant|org|organization)(id|ids|s)?");

    IdentityFromRequest() {
      super("identity-from-request", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      for (Fact read : knowledge.withPredicate(Types.READS_REQUEST_VALUE)) {
        String name = read.object().value().toString();
        String plain = name.toLowerCase(Locale.ROOT).replaceAll("[-_ ]", "");
        if (!IDENTITY.matcher(plain).matches()) {
          continue;
        }
        String source = attribute(read, "source");
        for (EntityId unit : leads.unitsOf(read.subject())) {
          leads
              .lead(
                  unit + "/" + source + ":" + name,
                  "The " + source + " " + name + " is read by the code of " + unit.key())
              .unit(unit)
              .code(read.subject())
              .cite(read);
        }
      }
    }
  }

  // --- send-before-commit ----------------------------------------------------------------------

  /**
   * A send, request or mail sent by code a transactional method reaches through the call graph,
   * with no transactional event listener, after-commit callback or outbox on the way: one lead per
   * unit and sending method.
   */
  static final class SendBeforeCommit extends LeadRule {
    // A list, not a set: each method's sends are kept in this order, the same in every run (#682).
    private static final List<String> SENDS =
        List.of(Types.MESSAGE_SEND, Types.HTTP_CALL, Types.MAIL_SEND, Types.STOMP_SEND);

    /** Propagations that run the method outside a transaction, or join one only if it exists. */
    private static final Set<String> NO_TRANSACTION = Set.of("NOT_SUPPORTED", "NEVER", "SUPPORTS");

    SendBeforeCommit() {
      super("send-before-commit", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      Map<EntityId, List<Fact>> sends = new TreeMap<>(Knowledge.BY_ID);
      for (String predicate : SENDS) {
        for (Fact send : knowledge.withPredicate(predicate)) {
          if (send.subject().type().equals(Types.METHOD) && !isOutbox(send.subject())) {
            sends.computeIfAbsent(send.subject(), m -> new ArrayList<>()).add(send);
          }
        }
      }
      if (sends.isEmpty()) {
        return;
      }
      Set<EntityId> barriers = new HashSet<>();
      // Whether each method runs in a read-write transaction: a class's annotation first, then the
      // method's own, which overrides it, as Spring reads them.
      Map<EntityId, Fact> declared = new TreeMap<>(Knowledge.BY_ID);
      List<Fact> onMethods = new ArrayList<>();
      for (Fact fact : knowledge.withPredicate(Types.TRANSACTIONAL)) {
        if ("event-listener".equals(attribute(fact, "kind"))) {
          barriers.add(fact.subject());
        } else if (fact.subject().type().equals(Types.METHOD)) {
          onMethods.add(fact);
        } else {
          knowledge.parts(fact.subject(), Types.METHOD).forEach(m -> declared.put(m, fact));
        }
      }
      onMethods.forEach(f -> declared.put(f.subject(), f));
      Map<EntityId, Fact> transactions = new TreeMap<>(Knowledge.BY_ID);
      declared.forEach(
          (method, fact) -> {
            if (!"true".equals(attribute(fact, "readOnly"))
                && !NO_TRANSACTION.contains(fact.object().value().toString())
                && !method.key().contains("#<init>")) {
              transactions.put(method, fact);
            }
          });
      Map<EntityId, Set<EntityId>> calls = callGraph(knowledge);
      Set<EntityId> reaching = reachingSends(calls, sends.keySet(), barriers);
      for (Map.Entry<EntityId, Fact> start : transactions.entrySet()) {
        if (!reaching.contains(start.getKey())) {
          continue;
        }
        Set<EntityId> seen = new HashSet<>(Set.of(start.getKey()));
        Deque<EntityId> todo = new ArrayDeque<>(List.of(start.getKey()));
        while (!todo.isEmpty()) {
          EntityId method = todo.removeFirst();
          List<Fact> sent = sends.get(method);
          if (sent != null) {
            for (EntityId unit : leads.unitsOf(method)) {
              Lead lead =
                  leads
                      .lead(
                          unit + "/" + method,
                          "Code a transaction runs sends before the transaction commits")
                      .unit(unit)
                      .code(method)
                      .code(start.getKey())
                      .cite(start.getValue());
              sent.forEach(lead::cite);
            }
          }
          for (EntityId next : calls.getOrDefault(method, Set.of())) {
            if (reaching.contains(next)
                && !barriers.contains(next)
                && !next.key().toLowerCase(Locale.ROOT).contains("aftercommit")
                && !isOutbox(next)
                && seen.add(next)) {
              todo.add(next);
            }
          }
        }
      }
    }

    /** The methods from which a send can be reached without passing a barrier, sends included. */
    private static Set<EntityId> reachingSends(
        Map<EntityId, Set<EntityId>> calls, Set<EntityId> sends, Set<EntityId> barriers) {
      Map<EntityId, Set<EntityId>> callers = new HashMap<>();
      calls.forEach(
          (caller, callees) ->
              callees.forEach(c -> callers.computeIfAbsent(c, k -> new HashSet<>()).add(caller)));
      Set<EntityId> reaching = new HashSet<>(sends);
      Deque<EntityId> todo = new ArrayDeque<>(sends);
      while (!todo.isEmpty()) {
        EntityId method = todo.removeFirst();
        if (barriers.contains(method)) {
          continue;
        }
        for (EntityId caller : callers.getOrDefault(method, Set.of())) {
          if (reaching.add(caller)) {
            todo.add(caller);
          }
        }
      }
      return reaching;
    }

    private static boolean isOutbox(EntityId method) {
      String key = method.key();
      int hash = key.indexOf('#');
      return (hash < 0 ? key : key.substring(0, hash)).toLowerCase(Locale.ROOT).contains("outbox");
    }

    /**
     * Method to method calls, a call to an interface's method also reaching its implementations.
     */
    private static Map<EntityId, Set<EntityId>> callGraph(Knowledge knowledge) {
      Map<EntityId, Set<EntityId>> implementers = new HashMap<>();
      for (Fact fact : knowledge.withPredicate(Types.IMPLEMENTS)) {
        if (Knowledge.isEntity(fact)) {
          implementers
              .computeIfAbsent(Knowledge.object(fact), t -> new HashSet<>())
              .add(fact.subject());
        }
      }
      Map<EntityId, Set<EntityId>> calls = new HashMap<>();
      for (Fact call : knowledge.withPredicate(Types.CALLS)) {
        if (!Knowledge.isEntity(call) || !call.subject().type().equals(Types.METHOD)) {
          continue;
        }
        EntityId callee = Knowledge.object(call);
        Set<EntityId> targets = calls.computeIfAbsent(call.subject(), m -> new HashSet<>());
        targets.add(callee);
        String key = callee.key();
        int hash = key.indexOf('#');
        if (hash > 0) {
          EntityId owner = new EntityId(Types.TYPE, key.substring(0, hash));
          for (EntityId impl : implementers.getOrDefault(owner, Set.of())) {
            targets.add(new EntityId(Types.METHOD, impl.key() + key.substring(hash)));
          }
        }
      }
      return calls;
    }
  }

  // --- contract-mismatch -----------------------------------------------------------------------

  /**
   * A call between units whose method and path match no endpoint of the unit it reaches (a typo, a
   * missing prefix, GET for POST); or a frontend's call that reaches a gateway no route of which
   * leads on to an endpoint for it.
   */
  static final class ContractMismatch extends LeadRule {
    ContractMismatch() {
      super("contract-mismatch", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      SortedSet<EntityId> units = leads.units();
      Set<EntityId> gateways = new HashSet<>();
      // A gateway: a unit with links its routes make, or endpoints its routes do or do not reach.
      Map<EntityId, List<Pattern>> routes = new HashMap<>();
      for (Fact link : knowledge.withPredicate(Types.CALLS_HTTP)) {
        if (attribute(link, "route") != null) {
          gateways.add(link.subject());
          for (String path : strings(link, "paths")) {
            routes.computeIfAbsent(link.subject(), g -> new ArrayList<>()).add(antPattern(path));
          }
        }
      }
      knowledge.withPredicate(Types.ROUTED_ENDPOINT).forEach(f -> gateways.add(f.subject()));
      knowledge.withPredicate(Types.UNROUTED_ENDPOINT).forEach(f -> gateways.add(f.subject()));
      Map<EntityId, Boolean> hasEndpoints = new HashMap<>();
      Map<String, Fact> unrouted = new HashMap<>();
      for (Fact fact : knowledge.withPredicate(Types.UNROUTED_ENDPOINT)) {
        unrouted.putIfAbsent(fact.subject() + " " + plain(fact.object().value().toString()), fact);
      }
      for (Fact link : knowledge.withPredicate(Types.CALLS_HTTP)) {
        String path = attribute(link, "path");
        if (path == null
            || !Knowledge.isEntity(link)
            || attribute(link, "route") != null
            || attribute(link, "target") != null) {
          continue;
        }
        if (path.contains("${")) {
          // A path still holding a placeholder matches nothing yet; that is not a mismatch.
          continue;
        }
        EntityId to = Knowledge.object(link);
        String method = attribute(link, HTTP_METHOD);
        String call = (method == null ? "" : method + " ") + path;
        if (link.subject().type().equals(Types.FRONTEND) && gateways.contains(to)) {
          // A frontend's call to a gateway: does a route lead on to an endpoint for it?
          Fact missed = unrouted.get(to + " " + plain(call));
          if (missed == null
              && attribute(link, "reaches") == null
              && attribute(link, "throughRoute") == null
              && !routed(routes.getOrDefault(to, List.of()), path)
              && !ownEndpoint(knowledge, leads, to, plain(call))) {
            leads
                .lead(
                    link.subject() + "/" + call,
                    "The frontend calls "
                        + call
                        + ", which no route or endpoint of the gateway matches")
                .unit(to)
                .cite(link);
            continue;
          }
          if (missed != null) {
            EntityId endpoint = parse(attribute(missed, "endpoint"));
            Lead lead =
                leads
                    .lead(
                        link.subject() + "/" + call,
                        "The frontend calls " + call + ", which no route of the gateway reaches")
                    .unit(to)
                    .cite(link)
                    .cite(missed);
            if (endpoint != null) {
              lead.code(endpoint);
            }
          }
          continue;
        }
        if (!units.contains(link.subject())
            || !units.contains(to)
            || gateways.contains(to)
            || !hasEndpoints.computeIfAbsent(to, u -> endpoints(knowledge, leads, u))) {
          continue;
        }
        EntityId source = parse(attribute(link, "source"));
        Lead lead =
            leads
                .lead(
                    link.subject() + "/" + call,
                    "A call to " + call + " matches no endpoint of " + to.key())
                .unit(link.subject())
                .unit(to)
                .cite(link);
        if (source != null) {
          lead.code(source);
        }
      }
    }

    /** Whether any method of the unit's code is an endpoint. */
    private static boolean endpoints(Knowledge knowledge, Leads leads, EntityId unit) {
      for (Fact endpoint : knowledge.withPredicate(Types.ENDPOINT)) {
        if (leads.unitsOf(endpoint.subject()).contains(unit)) {
          return true;
        }
      }
      return false;
    }

    /** A route's path pattern, Spring's way: ** any path, * one segment, {x} one segment. */
    private static Pattern antPattern(String path) {
      StringBuilder regex = new StringBuilder();
      for (int i = 0; i < path.length(); i++) {
        char c = path.charAt(i);
        if (path.startsWith("/**", i)) {
          regex.append("(/.*)?");
          i += 2;
        } else if (path.startsWith("**", i)) {
          regex.append(".*");
          i++;
        } else if (c == '*') {
          regex.append("[^/]*");
        } else if (c == '{') {
          int end = path.indexOf('}', i);
          regex.append("[^/]+");
          i = end < 0 ? path.length() : end;
        } else {
          regex.append(Pattern.quote(String.valueOf(c)));
        }
      }
      return Pattern.compile(regex.toString());
    }

    /** Whether a route of the gateway takes the path, its variables standing for any segment. */
    private static boolean routed(List<Pattern> routes, String path) {
      String concrete = path.replaceAll("\\{[^}]*}", "x").replaceAll("\\?.*$", "");
      return routes.stream().anyMatch(r -> r.matcher(concrete).matches());
    }

    /** Whether the gateway's own code has an endpoint for the call. */
    private static boolean ownEndpoint(
        Knowledge knowledge, Leads leads, EntityId gateway, String call) {
      for (Fact endpoint : knowledge.withPredicate(Types.ENDPOINT)) {
        if (plain(endpoint.object().value().toString()).equals(call)
            && leads.unitsOf(endpoint.subject()).contains(gateway)) {
          return true;
        }
      }
      return false;
    }

    /** A method and path with every path variable written the same way. */
    private static String plain(String call) {
      return call.replaceAll("\\{[^}]*}", "{}");
    }
  }

  // --- weak-access-check -----------------------------------------------------------------------

  /**
   * A POST, PUT, PATCH or DELETE endpoint whose access check names a permission ending in READ or
   * VIEW, or that has no check in a unit whose other endpoints have one.
   */
  static final class WeakAccessCheck extends LeadRule {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern READ_ONLY = Pattern.compile("(?i)(?:.*_)?(READ|VIEW)");

    WeakAccessCheck() {
      super("weak-access-check", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      Map<EntityId, List<Fact>> endpoints = new TreeMap<>(Knowledge.BY_ID);
      for (Fact endpoint : knowledge.withPredicate(Types.ENDPOINT)) {
        endpoints.computeIfAbsent(endpoint.subject(), m -> new ArrayList<>()).add(endpoint);
      }
      Set<EntityId> checking = new HashSet<>();
      Map<EntityId, List<Fact>> checks = new HashMap<>();
      for (EntityId method : endpoints.keySet()) {
        List<Fact> found = new ArrayList<>();
        for (EntityId on : List.of(method, Components.ownerType(knowledge, method))) {
          for (Fact fact : knowledge.attributes(on)) {
            if (fact.predicate().equals(Types.ACCESS_CHECK)
                || fact.predicate().equals(Types.CUSTOM_ACCESS_CHECK)) {
              found.add(fact);
            }
          }
        }
        checks.put(method, found);
        if (!found.isEmpty()) {
          checking.addAll(leads.unitsOf(method));
        }
      }
      for (Map.Entry<EntityId, List<Fact>> entry : endpoints.entrySet()) {
        EntityId method = entry.getKey();
        Fact mutating = null;
        for (Fact endpoint : entry.getValue()) {
          String verb = attribute(endpoint, HTTP_METHOD);
          if (verb == null) {
            verb = endpoint.object().value().toString().split(" ", 2)[0];
          }
          if (MUTATING.contains(verb.toUpperCase(Locale.ROOT))) {
            mutating = endpoint;
          }
        }
        if (mutating == null) {
          continue;
        }
        List<Fact> found = checks.get(method);
        for (EntityId unit : leads.unitsOf(method)) {
          if (found.isEmpty()) {
            if (checking.contains(unit)) {
              leads
                  .lead(
                      unit + "/" + method,
                      mutating.object().value() + " has no access check, unlike other endpoints")
                  .unit(unit)
                  .code(method)
                  .cite(mutating);
            }
            continue;
          }
          for (Fact check : found) {
            if (namesReadPermission(check.object().value().toString())) {
              leads
                  .lead(
                      unit + "/" + method,
                      mutating.object().value()
                          + " changes data but its check names "
                          + check.object().value())
                  .unit(unit)
                  .code(method)
                  .cite(mutating)
                  .cite(check);
            }
          }
        }
      }
    }

    static boolean namesReadPermission(String check) {
      for (String token : check.split("[^A-Za-z0-9_]+")) {
        if (READ_ONLY.matcher(token).matches()) {
          return true;
        }
      }
      return false;
    }
  }

  // --- shared-type-skew ------------------------------------------------------------------------

  /**
   * A type passed in messages or HTTP client calls, declared in an in-set library that the units
   * passing it bundle at different versions.
   */
  static final class SharedTypeSkew extends LeadRule {
    SharedTypeSkew() {
      super("shared-type-skew", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      Map<String, Fact> skewOfType = new HashMap<>();
      for (Fact skew : knowledge.withPredicate(Types.VERSION_SKEW)) {
        // The library's module: a library entity and its module share their key.
        for (EntityId type : leads.typesOf(new EntityId(Types.MODULE, skew.subject().key()))) {
          skewOfType.put(type.key(), skew);
        }
      }
      if (skewOfType.isEmpty()) {
        return;
      }
      Map<String, List<Fact>> uses = new TreeMap<>();
      for (Fact payload : knowledge.withPredicate(Types.PAYLOAD_TYPE)) {
        List<String> types = new ArrayList<>(List.of(payload.object().value().toString()));
        types.addAll(strings(payload, "typeArguments"));
        for (String type : types) {
          if (skewOfType.containsKey(type)) {
            uses.computeIfAbsent(type, t -> new ArrayList<>()).add(payload);
          }
        }
      }
      for (Map.Entry<String, List<Fact>> entry : uses.entrySet()) {
        Fact skew = skewOfType.get(entry.getKey());
        SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
        entry.getValue().forEach(p -> units.addAll(leads.unitsOf(p.subject())));
        Set<String> versions = new TreeSet<>();
        var pins = skew.attributes().get("pins");
        for (EntityId unit : units) {
          if (pins != null && pins.has(unit.toString())) {
            versions.add(pins.get(unit.toString()).asString());
          }
        }
        if (versions.size() < 2) {
          continue;
        }
        Lead lead =
            leads
                .lead(
                    skew.subject() + "/" + entry.getKey(),
                    entry.getKey()
                        + " is passed between units that bundle "
                        + skew.subject().key()
                        + " at versions "
                        + String.join(", ", versions))
                .units(units)
                .code(new EntityId(Types.TYPE, entry.getKey()))
                .cite(skew);
        for (Fact payload : entry.getValue()) {
          lead.code(payload.subject()).cite(payload);
        }
      }
    }
  }

  // --- single-instance -------------------------------------------------------------------------

  /**
   * A unit or data store that every deployment runs once (replicas 1 or unset), and that two or
   * more units interact with; or a unit whose STOMP broker is the simple in-memory one, with no
   * relay.
   */
  static final class SingleInstance extends LeadRule {
    // A list, not a set: the first link of a unit to a container is the one cited, so the order
    // must be the same in every run (#682); Set.of iterates in an order that changes per JVM.
    private static final List<String> RUNTIME =
        List.of(
            Types.CALLS_HTTP,
            Types.USES_DATABASE,
            Types.PUBLISHES,
            Types.CONSUMES,
            Types.SENDS_MESSAGES_TO,
            Types.USES_BROKER,
            Types.USES_INFRASTRUCTURE,
            Types.USES_IDENTITY_PROVIDER,
            Types.SENDS_MAIL);

    SingleInstance() {
      super("single-instance", "1");
    }

    @Override
    protected Set<String> alsoReads() {
      return Set.of(Types.DEPLOYED_AS, Types.RUNS);
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      // What each instance runs, and whether every container it rests on runs once.
      Map<EntityId, List<Fact>> runs = new TreeMap<>(Knowledge.BY_ID);
      Map<EntityId, Boolean> once = new TreeMap<>(Knowledge.BY_ID);
      for (Fact instance : knowledge.withPredicate(Types.DEPLOYED_AS)) {
        for (String id : strings(instance, "facts")) {
          Fact container = knowledge.fact(id);
          if (container == null) {
            continue;
          }
          String replicas = attribute(container, "replicas");
          boolean single = replicas == null || replicas.equals("1");
          for (Fact run : knowledge.relations(instance.subject(), Types.RUNS)) {
            EntityId entity = Knowledge.object(run);
            once.merge(entity, single, Boolean::logicalAnd);
            runs.computeIfAbsent(entity, e -> new ArrayList<>()).add(run);
            runs.get(entity).add(container);
          }
        }
      }
      Map<EntityId, Map<EntityId, Fact>> users = new TreeMap<>(Knowledge.BY_ID);
      SortedSet<EntityId> units = leads.units();
      for (String predicate : RUNTIME) {
        for (Fact link : knowledge.withPredicate(predicate)) {
          if (Knowledge.isEntity(link)
              && (units.contains(link.subject()) || link.subject().type().equals(Types.FRONTEND))
              && !link.subject().equals(Knowledge.object(link))) {
            users
                .computeIfAbsent(Knowledge.object(link), e -> new TreeMap<>(Knowledge.BY_ID))
                .putIfAbsent(link.subject(), link);
          }
        }
      }
      for (Map.Entry<EntityId, Boolean> entry : once.entrySet()) {
        Map<EntityId, Fact> using = users.getOrDefault(entry.getKey(), Map.of());
        if (!entry.getValue() || using.size() < 2) {
          continue;
        }
        Lead lead =
            leads
                .lead(
                    entry.getKey().toString(),
                    entry.getKey().key()
                        + " runs as one instance in every deployment, and "
                        + using.size()
                        + " units use it")
                .code(entry.getKey());
        if (units.contains(entry.getKey())) {
          lead.unit(entry.getKey());
        }
        runs.get(entry.getKey()).forEach(lead::cite);
        using.values().forEach(lead::cite);
      }
      // A STOMP simple broker keeps its subscriptions in one instance's memory.
      Map<EntityId, List<Fact>> stomp = new TreeMap<>(Knowledge.BY_ID);
      Set<EntityId> relayed = new HashSet<>();
      for (Fact prefix : knowledge.withPredicate(Types.STOMP_PREFIX)) {
        for (EntityId unit : leads.unitsOf(prefix.subject())) {
          if ("simple-broker".equals(attribute(prefix, "kind"))) {
            stomp.computeIfAbsent(unit, u -> new ArrayList<>()).add(prefix);
          }
          if (attribute(prefix, "relayHost") != null
              || "broker-relay".equals(attribute(prefix, "kind"))) {
            relayed.add(unit);
          }
        }
      }
      for (Map.Entry<EntityId, List<Fact>> entry : stomp.entrySet()) {
        if (relayed.contains(entry.getKey())) {
          continue;
        }
        Lead lead =
            leads
                .lead(
                    entry.getKey() + "/stomp-simple-broker",
                    entry.getKey().key() + " keeps STOMP subscriptions in its own memory")
                .unit(entry.getKey());
        for (Fact prefix : entry.getValue()) {
          lead.code(prefix.subject()).cite(prefix);
        }
      }
    }
  }

  // --- plaintext-secret ------------------------------------------------------------------------

  /**
   * A secret written in plain text: a config value a key names as secret written as a literal, a
   * signing secret several units share, or the keys of a Kubernetes Secret written as stringData.
   */
  static final class PlaintextSecret extends LeadRule {
    PlaintextSecret() {
      super("plaintext-secret", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      for (Fact value : knowledge.withPredicate(Types.CONFIG_VALUE)) {
        // Masked and written as a literal: a value masked only for a placeholder's secret, or
        // encrypted ({cipher}), keeps what was written in written.
        if ("true".equals(attribute(value, "masked")) && attribute(value, "written") == null) {
          String key = attribute(value, "key");
          String profile = attribute(value, "profile");
          leads
              .lead(
                  value.subject() + "/" + key + (profile == null ? "" : "@" + profile),
                  "The secret " + key + " is written in a config file")
              .units(leads.unitsOf(value.subject()))
              .code(value.subject())
              .cite(value);
        }
      }
      for (Fact shared : knowledge.withPredicate(Types.SHARED_SECRET)) {
        String value = shared.object().value().toString();
        leads
            .lead("shared/" + value, "Units sign or check tokens with one shared secret, " + value)
            .unit(shared.subject())
            .units(
                strings(shared, "with").stream()
                    .map(LeadRule::parse)
                    .filter(e -> e != null)
                    .toList())
            .code(shared.subject())
            .cite(shared);
      }
      for (Fact secret : knowledge.withPredicate(Types.KUBERNETES_SECRET)) {
        List<String> keys = strings(secret, "stringData");
        if (keys.isEmpty()) {
          continue;
        }
        String environment = attribute(secret, "environment");
        leads
            .lead(
                secret.subject()
                    + "/"
                    + attribute(secret, "platform")
                    + ":"
                    + (environment == null ? "-" : environment)
                    + "/"
                    + secret.object().value(),
                "The Kubernetes Secret "
                    + secret.object().value()
                    + " holds "
                    + keys.size()
                    + " values in plain text (stringData)")
            .code(secret.subject())
            .cite(secret);
      }
    }
  }

  // --- environment-baked -----------------------------------------------------------------------

  /**
   * A frontend's base URL whose host is localhost or a private address, in the build the frontend's
   * default build uses or in its code.
   */
  static final class EnvironmentBaked extends LeadRule {
    private static final Pattern LOCAL =
        Pattern.compile(
            "(?i)^[a-z][a-z0-9+.-]*://(localhost|127\\.\\d+\\.\\d+\\.\\d+|0\\.0\\.0\\.0|10\\.\\d+\\.\\d+\\.\\d+"
                + "|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)([:/].*)?$");

    EnvironmentBaked() {
      super("environment-baked", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      for (Fact url : knowledge.withPredicate(Types.FRONTEND_URL)) {
        String value = url.object().value().toString();
        boolean built =
            !"environment".equals(attribute(url, "source"))
                || "true".equals(attribute(url, "defaultBuild"));
        if (!built || !LOCAL.matcher(value).matches()) {
          continue;
        }
        leads
            .lead(
                url.subject() + "/" + attribute(url, "name"),
                "The default build of "
                    + url.subject().key()
                    + " calls "
                    + value
                    + ", a local or private address")
            .code(url.subject())
            .cite(url);
      }
    }
  }

  // --- open-cors -------------------------------------------------------------------------------

  /** A CORS policy that lets every origin call. */
  static final class OpenCors extends LeadRule {
    OpenCors() {
      super("open-cors", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      for (Fact policy : knowledge.withPredicate(Types.CORS_POLICY)) {
        List<String> origins = new ArrayList<>(strings(policy, "origins"));
        origins.add(policy.object().value().toString());
        boolean open =
            origins.stream()
                .anyMatch(o -> o.strip().equals("*") || o.strip().matches("\\$\\{[^}]*:\\*}"));
        if (!open) {
          continue;
        }
        String where =
            firstOf(
                attribute(policy, "path"),
                attribute(policy, "key"),
                attribute(policy, "name"),
                attribute(policy, "source"));
        leads
            .lead(
                policy.subject() + "/" + (where == null ? "-" : where),
                "A CORS policy of " + policy.subject().key() + " allows every origin")
            .units(leads.unitsOf(policy.subject()))
            .code(policy.subject())
            .cite(policy);
      }
    }

    private static String firstOf(String... values) {
      for (String value : values) {
        if (value != null) {
          return value;
        }
      }
      return null;
    }
  }

  // --- schema-unmanaged ------------------------------------------------------------------------

  /**
   * Hibernate creating or updating a unit's schema ({@code spring.jpa.hibernate.ddl-auto} update,
   * create or create-drop) outside a test profile, in a module with no Flyway or Liquibase
   * migrations.
   */
  static final class SchemaUnmanaged extends LeadRule {
    private static final Set<String> GENERATES = Set.of("update", "create", "create-drop");

    SchemaUnmanaged() {
      super("schema-unmanaged", "1");
    }

    @Override
    protected void find(Knowledge knowledge, Leads leads) {
      for (Fact value : knowledge.withPredicate(Types.CONFIG_VALUE)) {
        String profile = attribute(value, "profile");
        if (!"spring.jpa.hibernate.ddl-auto".equals(attribute(value, "key"))
            || !GENERATES.contains(
                value.object().value().toString().strip().toLowerCase(Locale.ROOT))
            || (profile != null && profile.toLowerCase(Locale.ROOT).contains("test"))
            || migrated(knowledge, value.subject())) {
          continue;
        }
        leads
            .lead(
                value.subject() + "/" + (profile == null ? "default" : profile),
                "Hibernate "
                    + value.object().value()
                    + "s the schema of "
                    + value.subject().key()
                    + ", which has no migrations")
            .units(leads.unitsOf(value.subject()))
            .code(value.subject())
            .cite(value);
      }
    }

    /** Whether the module's schema is built by Flyway or Liquibase migrations. */
    private static boolean migrated(Knowledge knowledge, EntityId module) {
      EntityId schema = new EntityId("schema", module.key());
      for (Fact kind : knowledge.attributes(schema)) {
        if (kind.predicate().equals(Types.KIND)
            && Set.of("flyway", "liquibase").contains(kind.object().value().toString())) {
          return true;
        }
      }
      return false;
    }
  }
}
