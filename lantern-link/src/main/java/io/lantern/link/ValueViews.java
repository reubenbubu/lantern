package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.http.HttpClientExtractor;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The first link step: the values extractors keep as written, resolved in each view against the
 * config of the module they name ({@link CodeValues}), as the facts each extractor recorded before
 * it kept them as written (ADR 0094). So every later step reads the facts it read before, one kind
 * at a time as the slices of #519 land:
 *
 * <ul>
 *   <li>a {@code request} sent by code ({@code SENT_BY}) gives the code's {@code http-call} facts,
 *       one for the default view and one for each profile whose URL differs (#557).
 * </ul>
 *
 * <p>The value resolution rule of #522 replaces this step with {@code RESOLVES_TO} claims.
 */
public final class ValueViews implements LinkStep {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "values";

  /** The step's version. */
  public static final String VERSION = "1";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final Pattern ABSOLUTE = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/]*)");

  private static final String FEIGN = "feign";

  @Override
  public Origin origin() {
    return ORIGIN;
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    CodeValues values = CodeValues.of(facts);
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> derived = new LinkedHashMap<>();
    for (Fact f : facts) {
      if (f.predicate().equals(HttpClientExtractor.SENT_BY)
          && f.subject().type().equals(HttpClientExtractor.REQUEST)
          && f.object().kind() == FactObject.Kind.ENTITY) {
        for (Fact call : calls(f, values, validity)) {
          derived.putIfAbsent(call.id(), call);
        }
      }
    }
    return derived.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP requests

  /** A request's URL and service name resolved in one view, as they may be recorded. */
  private record Recorded(
      String url,
      String written,
      String service,
      SortedSet<String> unresolved,
      boolean masked,
      boolean loadBalanced) {}

  /**
   * The {@code http-call} facts of one request: the default view's and each differing profile's.
   */
  private static List<Fact> calls(Fact sent, CodeValues values, Validity validity) {
    JsonNode a = sent.attributes();
    String client = text(a, "client");
    String written = text(a, "url");
    String service = text(a, "service");
    String method = text(a, "httpMethod");
    boolean loadBalanced = a.path("loadBalanced").asBoolean(false);
    CodeValues.View config =
        values.forModule(EntityId.parse(text(a, "configModule")), text(a, "configRepo"));
    Recorded byDefault = recorded(config, null, client, written, service, loadBalanced);
    List<Fact> calls = new java.util.ArrayList<>();
    for (String profile : config.views()) {
      Recorded value =
          profile == null
              ? byDefault
              : recorded(config, profile, client, written, service, loadBalanced);
      if (profile != null && value.equals(byDefault)) {
        continue;
      }
      ObjectNode attributes = FactJson.newObject();
      if (profile != null) {
        attributes.put("profile", profile);
      }
      if (method != null) {
        attributes.put("httpMethod", method);
      }
      attributes.put("client", client);
      copy(a, attributes, "clientType");
      copy(a, attributes, "annotation");
      String object;
      if (value.masked()) {
        attributes.put("masked", true);
        object = value.written();
      } else {
        HttpClientExtractor.Split split = HttpClientExtractor.split(value.url());
        if (split.path() != null) {
          attributes.put("path", split.path());
        }
        if (split.absolute()) {
          attributes.put("url", value.url());
          attributes.put("baseUrl", split.base());
        }
        if (value.service() != null) {
          attributes.put("service", value.service());
        }
        if (value.loadBalanced()) {
          attributes.put("loadBalanced", true);
        }
        if (!value.written().equals(value.url())) {
          attributes.put("written", value.written());
        }
        if (!value.unresolved().isEmpty()) {
          ArrayNode keys = attributes.putArray("unresolved");
          value.unresolved().forEach(keys::add);
        }
        object = value.url();
      }
      if (a.has("expressions")) {
        attributes.set("expressions", a.get("expressions").deepCopy());
      }
      calls.add(
          Fact.create(
              sent.object().entityId(),
              HttpClientExtractor.HTTP_CALL,
              FactObject.string(method == null ? object : method + " " + object),
              attributes,
              ORIGIN,
              sent.evidence(),
              sent.confidence(),
              validity));
    }
    return calls;
  }

  private static Recorded recorded(
      CodeValues.View config,
      String profile,
      String client,
      String written,
      String service,
      boolean callLoadBalanced) {
    CodeValues.Value url = config.value(profile, written);
    CodeValues.Value name = service == null ? null : config.value(profile, service);
    if (url.masked() || (name != null && name.masked())) {
      return new Recorded(written, written, null, new TreeSet<>(), true, false);
    }
    String resolved = HttpClientExtractor.clean(url.value());
    if (client.equals(FEIGN)) {
      // Spring Cloud OpenFeign adds http:// to a url without a scheme, once resolved.
      resolved = HttpClientExtractor.withScheme(resolved);
    }
    SortedSet<String> unresolved = new TreeSet<>(url.unresolved());
    String resolvedService = null;
    if (name != null) {
      unresolved.addAll(name.unresolved());
      resolvedService = name.value();
    }
    // A load-balanced client's host, and an lb:// URL's, is a service name, not a DNS name.
    boolean loadBalanced = callLoadBalanced || resolved.startsWith("lb://");
    if (resolvedService == null && loadBalanced) {
      resolvedService = host(resolved);
    }
    return new Recorded(resolved, written, resolvedService, unresolved, false, loadBalanced);
  }

  /** The host of an absolute URL without placeholders in it, or null. */
  private static String host(String url) {
    Matcher m = ABSOLUTE.matcher(url);
    if (!m.find()) {
      return null;
    }
    String host = m.group(2);
    int at = host.lastIndexOf('@');
    host = at < 0 ? host : host.substring(at + 1);
    int colon = host.indexOf(':');
    host = colon < 0 ? host : host.substring(0, colon);
    return host.isEmpty() || host.startsWith("[") || host.contains("{") || host.contains("$")
        ? null
        : host;
  }

  private static void copy(JsonNode from, ObjectNode to, String name) {
    if (from.has(name)) {
      to.set(name, from.get(name).deepCopy());
    }
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? null : value.stringValue();
  }
}
