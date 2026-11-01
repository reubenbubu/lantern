package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.link.Deployments.Container;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.LinkText.Url;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where an address leads, decided the same way for every linker (ADR 0042, amendment for #473): an
 * HTTP call, a gateway route, a frontend's URL, an identity provider's URL and a mail host.
 *
 * <ol>
 *   <li><b>Fill.</b> An address whose host the calling unit's config leaves to a placeholder is
 *       filled from the environment of each deployment container that runs the unit, as Spring
 *       Boot's relaxed binding reads it.
 *   <li><b>Classify</b> the host: this machine, a private address, a Kubernetes cluster name, a
 *       name without a domain, a public domain or a public IP address.
 *   <li><b>Match</b> in one order: {@code spring.application.name}; a name of a deployment
 *       container that runs a unit; the artifact ID. For this machine or a private address, by its
 *       port: the unit whose deployment container publishes it, else the units whose {@code
 *       server.port} it is (8080 when they set none).
 *   <li><b>Answer</b>: a unit, several (ambiguous), an external system (a public domain that names
 *       no unit, never a private address or a bare name), or unknown.
 * </ol>
 *
 * Protocol linkers keep their own work: paths and endpoints, load-balanced names, identity provider
 * containers.
 */
final class Addresses implements AddressAnswers {

  /** Confidence factors of the ways a unit is found. */
  static final double BY_SERVICE_NAME = 1.0;

  static final double BY_HOST = 0.9;
  static final double BY_CONTAINER = 0.9;
  static final double BY_ARTIFACT_ID = 0.8;
  static final double BY_PUBLISHED_PORT = 0.8;
  static final double BY_PORT = 0.8;

  /** The port a unit that sets none listens on, as Spring Boot's embedded server does. */
  static final String DEFAULT_PORT = "8080";

  /** What a host is, before anything is matched. */
  enum Host {
    /** {@code localhost}, a loopback address. */
    THIS_MACHINE,
    /** An RFC 1918 address, {@code 0.0.0.0}, {@code host.docker.internal}, a private suffix. */
    PRIVATE,
    /** A Kubernetes name under {@code .svc}: the Service is its first label. */
    CLUSTER,
    /** A name without a domain: a compose service, a Kubernetes Service, a discovery name. */
    BARE,
    /** A name under a public domain. */
    PUBLIC_DOMAIN,
    /** An IP address on no private network. */
    PUBLIC_IP
  }

  private static final Pattern LOOPBACK =
      Pattern.compile("localhost|127\\.\\d+\\.\\d+\\.\\d+|\\[::1]|\\[0:0:0:0:0:0:0:1]");

  private static final Pattern PRIVATE_ADDRESS =
      Pattern.compile(
          "10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"
              + "|0\\.0\\.0\\.0|host\\.docker\\.internal|gateway\\.docker\\.internal"
              + "|\\[f[cd][0-9a-f:]*]");

  /** Suffixes that are never on the internet. */
  private static final List<String> PRIVATE_SUFFIXES =
      List.of(".local", ".localhost", ".internal", ".lan", ".home.arpa");

  /** Classifies a host as written in a URL ({@code [::1]} with its brackets). */
  static Host classify(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (LOOPBACK.matcher(h).matches()) {
      return Host.THIS_MACHINE;
    }
    if (PRIVATE_ADDRESS.matcher(h).matches()) {
      return Host.PRIVATE;
    }
    if (h.endsWith(".svc") || h.contains(".svc.")) {
      return Host.CLUSTER;
    }
    if (h.startsWith("[") || h.matches("[0-9.]+")) {
      return Host.PUBLIC_IP;
    }
    if (!h.contains(".")) {
      return Host.BARE;
    }
    for (String suffix : PRIVATE_SUFFIXES) {
      if (h.endsWith(suffix)) {
        return Host.PRIVATE;
      }
    }
    return Host.PUBLIC_DOMAIN;
  }

  /** A unit an address leads to, how, and the fact it was found by (null for a default port). */
  record Target(Unit unit, Fact fact, String matchedBy, double factor) {}

  /** What kind of answer an address has. */
  enum Kind {
    UNIT,
    AMBIGUOUS,
    EXTERNAL,
    UNKNOWN
  }

  /**
   * Where an address leads.
   *
   * @param targets the unit (one, for {@link Kind#UNIT}) or the candidates ({@link Kind#AMBIGUOUS})
   * @param external the external system, for {@link Kind#EXTERNAL}
   * @param address the address as it was matched, filled in where a container's environment filled
   *     it
   * @param url the address taken apart
   * @param filledBy the container whose environment filled it in, or null
   * @param split whether the config and the environments disagree, so no one URL stands for it
   */
  record Answer(
      Kind kind,
      List<Target> targets,
      EntityId external,
      String address,
      Url url,
      Container filledBy,
      boolean split) {

    /** The one unit, for {@link Kind#UNIT}. */
    Target target() {
      return targets.getFirst();
    }

    /** The URL's host classified, or null when it has none. */
    Host host() {
      return url.host() == null ? null : classify(url.host());
    }

    /** Whether the URL is this machine or a private address, reached by its port. */
    boolean local() {
      if (split) {
        return false;
      }
      Host h = host();
      return h == Host.THIS_MACHINE || h == Host.PRIVATE;
    }

    private String key() {
      return switch (kind) {
        case UNIT -> target().unit().id().toString();
        case EXTERNAL -> external.toString();
        default -> null;
      };
    }
  }

  private final LinkIndex index;
  private final Deployments deployments;

  Addresses(LinkIndex index, Deployments deployments) {
    this.index = index;
    this.deployments = deployments;
  }

  @Override
  public List<Deployments.Placed> placed(Unit unit, Fact store, Deployments.Place place) {
    return deployments.place(unit, place);
  }

  Deployments deployments() {
    return deployments;
  }

  /**
   * Where an address leads in a view: as the unit's config gives it, and as the environment of each
   * deployment container that runs the unit fills it in.
   *
   * @param from the unit that uses it, whose containers' environments fill it in and which is never
   *     reached by its port; null for a frontend
   * @param text the address as the unit's config resolved it, a placeholder nothing resolved kept
   *     as written ({@code ${ORDERS_URL}/orders})
   * @param written the address as written, when config changed it; a placeholder that config does
   *     not set takes the environment's value before its default, as Spring Boot reads it
   */
  @Override
  public Answer resolve(Unit from, String view, String text, String written) {
    return combined(answers(from, view, text, written));
  }

  /**
   * Each answer an address has, the config's first: as the unit's config gives it (when it has a
   * host, or nothing fills it), then as each deployment container that runs the unit fills it in.
   * Never empty. Parameters as for {@link #resolve}.
   */
  @Override
  public List<Answer> answers(Unit from, String view, String text, String written) {
    Answer config = match(from, view, text, null);
    if (from == null) {
      return List.of(config);
    }
    List<Answer> answers = new ArrayList<>();
    if (config.url().host() != null) {
      answers.add(config);
    }
    for (Container c : deployments.running(from)) {
      String filled = filled(from, view, c, text, written);
      if (filled != null && !filled.equals(text) && LinkText.parse(filled).host() != null) {
        answers.add(match(from, view, filled, c));
      }
    }
    return answers.isEmpty() ? List.of(config) : answers;
  }

  /**
   * The address with what a container's environment sets filled in, as value resolution gives it
   * ({@link EnvironmentValues#filled}) over the unit's config in the view. Null when the
   * environment sets nothing the address uses, or a secret goes into it.
   */
  private String filled(Unit unit, String view, Container c, String text, String written) {
    EnvironmentValues.Filled filled =
        EnvironmentValues.filled(
            c,
            written != null ? written : text,
            key -> index.config(unit, view, ConfigExtractor.CONFIG_VALUE, key));
    return filled == null ? null : filled.value();
  }

  /**
   * One answer of the environments' several: the one target they agree on; ambiguous, with every
   * unit, when they lead to different ones; else the first.
   */
  static Answer combined(List<Answer> answers) {
    Map<String, Answer> decided = new LinkedHashMap<>();
    for (Answer a : answers) {
      if (a.key() != null) {
        decided.putIfAbsent(a.key(), a);
      }
    }
    if (decided.size() == 1) {
      return decided.values().iterator().next();
    }
    if (decided.isEmpty()) {
      return answers.getFirst();
    }
    List<Target> candidates = new ArrayList<>();
    for (Answer a : answers) {
      if (a.kind() == Kind.UNIT || a.kind() == Kind.AMBIGUOUS) {
        candidates.addAll(a.targets());
      }
    }
    Answer first = decided.values().iterator().next();
    return new Answer(
        Kind.AMBIGUOUS, candidates, null, first.address(), first.url(), first.filledBy(), true);
  }

  /** Matches a URL with a host; {@code in} is the container that filled it, if any. */
  private Answer match(Unit from, String view, String text, Container in) {
    Url url = LinkText.parse(text);
    String host = url.host();
    if (host == null) {
      return new Answer(Kind.UNKNOWN, List.of(), null, text, url, in, false);
    }
    Host kind = classify(host);
    if (kind == Host.THIS_MACHINE || kind == Host.PRIVATE) {
      String port = url.port() != null ? url.port() : defaultPort(url.scheme());
      List<Target> targets = byPublishedPort(from, port, in);
      if (targets.isEmpty()) {
        targets = byPort(from, port, view);
      }
      return answer(targets, text, url, in);
    }
    List<Target> targets = byName(from, host, view, in);
    if (targets.isEmpty() && kind == Host.CLUSTER) {
      targets = byName(from, host.substring(0, host.indexOf('.')), view, in);
    }
    if (targets.isEmpty() && kind == Host.PUBLIC_DOMAIN) {
      return new Answer(
          Kind.EXTERNAL,
          List.of(),
          new EntityId(CrossRepoLinks.EXTERNAL_SYSTEM, host),
          text,
          url,
          in,
          false);
    }
    return answer(targets, text, url, in);
  }

  private static Answer answer(List<Target> targets, String text, Url url, Container in) {
    Kind kind = targets.isEmpty() ? Kind.UNKNOWN : targets.size() == 1 ? Kind.UNIT : Kind.AMBIGUOUS;
    return new Answer(kind, targets, null, text, url, in, false);
  }

  private static String defaultPort(String scheme) {
    return "https".equals(scheme) || "wss".equals(scheme) ? "443" : "80";
  }

  /**
   * The units a host names, at the first step that names any: application name, a name of a
   * deployment container running the unit (in the filling container's environment, when one filled
   * the address), artifact ID.
   */
  private List<Target> byName(Unit from, String name, String view, Container in) {
    List<Target> targets = byApplicationName(name, view, "host", BY_HOST);
    if (targets.isEmpty()) {
      for (Unit unit : index.units.values()) {
        for (Container c : deployments.running(unit)) {
          if ((in == null || c.environment().equals(in.environment())) && c.answers(name)) {
            targets.add(new Target(unit, c.fact(), "container-host", BY_CONTAINER));
            break;
          }
        }
      }
    }
    if (targets.isEmpty()) {
      for (Unit unit : index.units.values()) {
        if (unit.artifactId().equalsIgnoreCase(name)) {
          targets.add(new Target(unit, null, "artifact-id", BY_ARTIFACT_ID));
        }
      }
    }
    return targets;
  }

  /** The units whose {@code spring.application.name} in the view is the name. */
  @Override
  public List<Target> byApplicationName(String name, String view, String matchedBy, double factor) {
    List<Target> targets = new ArrayList<>();
    for (Unit unit : index.units.values()) {
      Fact fact = index.name(unit.id(), view);
      if (fact != null && fact.object().value().equalsIgnoreCase(name)) {
        targets.add(new Target(unit, fact, matchedBy, factor));
      }
    }
    return targets;
  }

  /**
   * The other units a compose container running them publishes the port of the host for (in the
   * filling container's environment, when one filled the address), since what calls this machine or
   * the host's private address reaches a published port, not a container's own.
   */
  private List<Target> byPublishedPort(Unit from, String port, Container in) {
    List<Target> targets = new ArrayList<>();
    for (Unit unit : index.units.values()) {
      if (unit.equals(from)) {
        continue;
      }
      for (Container c : deployments.running(unit)) {
        if ((in == null || c.environment().equals(in.environment()))
            && FrontendLinker.publishes(c.fact(), port)) {
          targets.add(new Target(unit, c.fact(), "published-port", BY_PUBLISHED_PORT));
          break;
        }
      }
    }
    return targets;
  }

  /**
   * The other units listening on the port in the view: their {@code server.port}, or {@value
   * #DEFAULT_PORT} when they set none (such a target has no fact).
   */
  private List<Target> byPort(Unit from, String port, String view) {
    List<Target> targets = new ArrayList<>();
    for (Unit unit : index.units.values()) {
      if (unit.equals(from)) {
        continue;
      }
      Fact fact = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, "server.port");
      if (fact != null && Claims.masked(fact)) {
        continue;
      }
      String value = fact == null ? DEFAULT_PORT : fact.object().value();
      if (value.equals(port)) {
        targets.add(new Target(unit, fact, "port", BY_PORT));
      }
    }
    return targets;
  }
}
