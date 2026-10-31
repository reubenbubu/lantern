package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A link rule of one protocol (ADR 0095, amendment for #575): it indexes the facts it reads, runs
 * its matchers on them in every view, and gives each link once if it holds in the default view,
 * else once per profile (ADR 0042). The rules of the cross-repo links step, each with its own
 * origin: HTTP (with gateway routes and frontend calls, which reuse its matching), messaging, data
 * stores, Redis, mail, identity, and the deployment containers units run in.
 *
 * <p>Until views are explicit (#522), each reads what the step read, every extracted fact and the
 * values' and units' facts but the units' technology, since the profiles it links in are those
 * every fact names.
 */
final class ProtocolLinks implements LinkRule {

  /** What a rule's matchers do: find links in the index, given the rule's origin. */
  @FunctionalInterface
  interface Matchers {
    void link(LinkIndex index, FoundLinks found, Origin origin, Validity validity);
  }

  /**
   * Every extracted fact and the values' and units' facts, but not {@code technology}: the units
   * give it and several of these rules do too (of brokers, databases, frontends), and no matcher
   * reads it, so reading it would only make the rules wait on each other in a cycle.
   */
  private static final Set<String> READS = readSet();

  private static Set<String> readSet() {
    Set<String> reads =
        new java.util.HashSet<>(
            Rules.union(
                Set.of(EXTRACTED), ValueResolution.WRITES, DeployableUnits.vocabularyPredicates()));
    reads.remove(DeployableUnits.TECHNOLOGY);
    return Set.copyOf(reads);
  }

  private final Origin origin;
  private final Set<String> writes;
  private final Matchers matchers;

  private ProtocolLinks(String name, String version, Set<String> writes, Matchers matchers) {
    this.origin = new Origin(Origin.Kind.STATIC, name, version);
    this.writes = Set.copyOf(writes);
    this.matchers = Objects.requireNonNull(matchers, "matchers");
  }

  /** HTTP calls, gateway routes and the frontends' calls and proxies. */
  static ProtocolLinks http() {
    return new ProtocolLinks(
        "http-links",
        "1",
        Set.of(
            CrossRepoLinks.CALLS_HTTP,
            CrossRepoLinks.ROUTED_ENDPOINT,
            CrossRepoLinks.UNROUTED_ENDPOINT,
            CrossRepoLinks.UNRESOLVED_LINK,
            CrossRepoLinks.USES_INFRASTRUCTURE,
            DataLinker.DISPLAY_NAME,
            Deployments.DEPLOYED_IN,
            DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) -> {
          Addresses addresses = new Addresses(index, new Deployments(index));
          // HTTP calls read where their addresses lead from address resolution's claims (#589).
          HttpLinker http = new HttpLinker(index, found, new AddressClaims(index));
          http.link();
          RouteLinker routes = new RouteLinker(index, found, addresses);
          routes.link();
          new FrontendLinker(index, found, addresses, http, routes).link(origin, validity);
        });
  }

  /** Messages sent and listened to, through topics, queues and exchanges. */
  static ProtocolLinks messaging() {
    return new ProtocolLinks(
        "messaging-links",
        "1",
        Set.of(
            CrossRepoLinks.PUBLISHES,
            CrossRepoLinks.CONSUMES,
            CrossRepoLinks.ROUTES_TO,
            CrossRepoLinks.SENDS_MESSAGES_TO,
            CrossRepoLinks.USES_BROKER,
            CrossRepoLinks.UNRESOLVED_LINK,
            DataLinker.DISPLAY_NAME,
            DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) -> new MessageLinker(index, found).link());
  }

  /** The deployment containers that run units and data stores. */
  static ProtocolLinks deployments() {
    return new ProtocolLinks(
        "deployment-links",
        "1",
        Set.of(Deployments.DEPLOYED_IN, DataLinker.DISPLAY_NAME, DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) ->
            new Deployments(index).link(index, found, origin, validity));
  }

  /** Databases and the data of other units' schemas. */
  static ProtocolLinks data() {
    return new ProtocolLinks(
        "data-links",
        "1",
        Set.of(
            CrossRepoLinks.USES_DATABASE,
            CrossRepoLinks.ACCESSES_DATA_OF,
            CrossRepoLinks.USES_BROKER,
            CrossRepoLinks.USES_INFRASTRUCTURE,
            CrossRepoLinks.UNRESOLVED_LINK,
            DataLinker.DISPLAY_NAME,
            Deployments.DEPLOYED_IN,
            DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) ->
            new DataLinker(index, found, new Deployments(index)).link(origin, validity));
  }

  /** Mail sent through mail servers. */
  static ProtocolLinks mail() {
    return new ProtocolLinks(
        "mail-links",
        "1",
        Set.of(
            CrossRepoLinks.SENDS_MAIL,
            CrossRepoLinks.UNRESOLVED_LINK,
            DataLinker.DISPLAY_NAME,
            DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) ->
            new MailLinker(index, found, new Addresses(index, new Deployments(index))).link());
  }

  /** Identity providers, and JWT secrets units share. */
  static ProtocolLinks identity() {
    return new ProtocolLinks(
        "identity-links",
        "1",
        Set.of(
            CrossRepoLinks.USES_IDENTITY_PROVIDER,
            CrossRepoLinks.SHARED_SECRET,
            CrossRepoLinks.UNRESOLVED_LINK,
            DataLinker.DISPLAY_NAME,
            Deployments.DEPLOYED_IN,
            DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) ->
            new IdentityLinker(index, found, new Addresses(index, new Deployments(index)))
                .link(origin, validity));
  }

  /** Redis servers units share. */
  static ProtocolLinks redis() {
    return new ProtocolLinks(
        "redis-links",
        "1",
        Set.of(
            CrossRepoLinks.USES_DATABASE,
            CrossRepoLinks.UNRESOLVED_LINK,
            DataLinker.DISPLAY_NAME,
            Deployments.DEPLOYED_IN,
            DeployableUnits.TECHNOLOGY),
        (index, found, origin, validity) ->
            new RedisLinker(index, found, new Deployments(index)).link(origin, validity));
  }

  /** The rules, in the order the cross-repo links step ran their matchers. */
  static List<ProtocolLinks> all() {
    return List.of(http(), messaging(), deployments(), data(), mail(), identity(), redis());
  }

  @Override
  public RuleInfo info() {
    return RuleInfo.of(origin);
  }

  @Override
  public Set<String> reads() {
    return READS;
  }

  @Override
  public Set<String> writes() {
    return writes;
  }

  @Override
  public void derive(ClaimQuery in, ClaimSink out, View view) {
    LinkIndex index = new LinkIndex(in.facts());
    Validity validity = Validity.from(in.snapshot());
    FoundLinks found = new FoundLinks();
    matchers.link(index, found, origin, validity);
    Map<String, Fact> derived = new LinkedHashMap<>();
    found.facts(index.profiles, origin, validity).forEach(f -> derived.putIfAbsent(f.id(), f));
    derived.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .forEach(out::add);
  }
}
