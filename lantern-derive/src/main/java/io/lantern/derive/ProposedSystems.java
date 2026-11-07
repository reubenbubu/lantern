package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.types.Types;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * An external system or database Investigate proposes, read as the one already in the model that it
 * names (#976): one system is one box, under the name static analysis gave it.
 *
 * <p>A connection Investigate found may end at a new {@code external-system:<name>} or {@code
 * database:<name>}, named by the model. Before it is drawn, it is matched against the external
 * systems and databases facts other than the LLM's know, in this order, each match counting only
 * when it is the one candidate (of several, the one the proposing unit links to in the default
 * view, then in any, then the one anything links to in the default view):
 *
 * <ol>
 *   <li><b>by host</b>: a host the proposal names (as a host or in a URL) is the candidate's host;
 *   <li><b>by name</b>, of the same type: the distinctive words of one's name all occur in the
 *       other's ({@code smsly-api} and {@code api.smsly.example} share {@code smsly}), words such
 *       as {@code api}, {@code service}, {@code example} or a port not counting;
 *   <li><b>by technology and unit</b>, only for a proposal whose name has no distinctive word (such
 *       as {@code object-storage}): the one candidate the proposing unit already uses whose
 *       technology or name holds a distinctive word of the proposal's technology ({@code S3}).
 * </ol>
 *
 * A matched connection whose unit already has a link of its own to the match repeats it and is left
 * out, since static analysis wins (design, Investigate's guard rails); any other is redrawn to the
 * match, keeping its claim, so its wording stays its own. An unmatched one is left as it is.
 */
final class ProposedSystems {

  private ProposedSystems() {}

  /** Words of a name that say what kind of thing it is, not which one. */
  static final Set<String> GENERIC_NAME_WORDS =
      Set.of(
          "api",
          "apis",
          "rest",
          "http",
          "https",
          "www",
          "web",
          "ws",
          "soap",
          "grpc",
          "service",
          "services",
          "svc",
          "server",
          "servers",
          "system",
          "systems",
          "external",
          "remote",
          "client",
          "endpoint",
          "endpoints",
          "gateway",
          "provider",
          "host",
          "localhost",
          "com",
          "org",
          "net",
          "io",
          "example",
          "local",
          "internal",
          "prod",
          "production",
          "sandbox",
          "staging",
          "stage",
          "dev",
          "test",
          "db",
          "database",
          "databases",
          "data",
          "store",
          "storage",
          "object",
          "objects",
          "blob",
          "bucket",
          "file",
          "files",
          "mail",
          "email",
          "smtp",
          "sftp",
          "ftp",
          "the",
          "and",
          "of");

  /** Words of a technology that say how a unit talks, not to what. */
  static final Set<String> GENERIC_TECHNOLOGY_WORDS =
      Set.of(
          "via",
          "over",
          "using",
          "with",
          "and",
          "the",
          "api",
          "apis",
          "sdk",
          "client",
          "clients",
          "http",
          "https",
          "rest",
          "json",
          "xml",
          "protocol",
          "driver",
          "jdbc",
          "r2dbc",
          "reactive",
          "spring",
          "data",
          "template",
          "aws",
          "library",
          "java",
          "javax",
          "jakarta",
          "apache",
          "okhttp",
          "feign",
          "openfeign",
          "webclient",
          "resttemplate",
          "restclient",
          "httpclient",
          "javamailsender",
          "integration",
          "async",
          "sync",
          "calls",
          "call");

  private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9]+");

  private static final Pattern HOST = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");

  /** The attributes of a link and an element that hold its technology. */
  private static final List<String> TECHNOLOGY_ATTRIBUTES = List.of("protocol", "technology");

  /**
   * The facts with each matched connection Investigate found redrawn to the system it names, or
   * left out where its unit already links to it; the same facts when no connection matches.
   */
  static Collection<Fact> matched(Collection<Fact> facts) {
    List<Fact> proposed = new ArrayList<>();
    for (Fact fact : facts) {
      if (fact.predicate().equals(Types.INVESTIGATED_CONNECTION) && Knowledge.isEntity(fact)) {
        proposed.add(fact);
      }
    }
    if (proposed.isEmpty()) {
      return facts;
    }
    // What facts other than the LLM's know, and what each unit links to by any other fact.
    Set<EntityId> known = new HashSet<>();
    Map<EntityId, Set<EntityId>> linked = new HashMap<>();
    Map<EntityId, Set<EntityId>> linkedByDefault = new HashMap<>();
    Set<EntityId> drawnByDefault = new HashSet<>();
    Map<EntityId, Set<String>> technologies = new HashMap<>();
    for (Fact fact : facts) {
      if (fact.predicate().equals(Types.INVESTIGATED_CONNECTION)) {
        continue;
      }
      boolean llm = fact.origin().kind() == Origin.Kind.LLM;
      if (Knowledge.isEntity(fact)) {
        linked.computeIfAbsent(fact.subject(), s -> new HashSet<>()).add(Knowledge.object(fact));
        if (Knowledge.view(fact) == null) {
          linkedByDefault
              .computeIfAbsent(fact.subject(), s -> new HashSet<>())
              .add(Knowledge.object(fact));
          if (!llm) {
            drawnByDefault.add(Knowledge.object(fact));
          }
        }
        if (!llm) {
          known.add(fact.subject());
          known.add(Knowledge.object(fact));
          for (String name : TECHNOLOGY_ATTRIBUTES) {
            JsonNode value = fact.attributes().get(name);
            if (value != null && value.isString()) {
              technologies
                  .computeIfAbsent(Knowledge.object(fact), e -> new HashSet<>())
                  .addAll(words(value.asString(), Set.of()));
            }
          }
        }
      } else if (!llm) {
        known.add(fact.subject());
        if (fact.predicate().equals(Types.TECHNOLOGY)) {
          technologies
              .computeIfAbsent(fact.subject(), e -> new HashSet<>())
              .addAll(words(fact.object().value(), Set.of()));
        }
      }
    }
    List<EntityId> candidates =
        known.stream().filter(ProposedSystems::isSystemOrStore).sorted(Knowledge.BY_ID).toList();
    Map<Fact, EntityId> matches = new java.util.IdentityHashMap<>();
    for (Fact fact : proposed) {
      EntityId to = Knowledge.object(fact);
      if (!known.contains(to) && isSystemOrStore(to)) {
        EntityId match =
            match(
                fact,
                candidates,
                new Links(
                    linked.getOrDefault(fact.subject(), Set.of()),
                    linkedByDefault.getOrDefault(fact.subject(), Set.of()),
                    drawnByDefault),
                technologies);
        if (match != null) {
          matches.put(fact, match);
        }
      }
    }
    if (matches.isEmpty()) {
      return facts;
    }
    List<Fact> result = new ArrayList<>(facts.size());
    Set<String> drawn = new HashSet<>();
    for (Fact fact : facts) {
      EntityId match = matches.get(fact);
      if (match == null) {
        result.add(fact);
      } else if (!linked.getOrDefault(fact.subject(), Set.of()).contains(match)
          && drawn.add(
              fact.subject() + " " + match + " " + fact.attributes().path("interaction"))) {
        result.add(redrawn(fact, match));
      }
    }
    return result;
  }

  /** The one candidate the proposal names, by host, name, or technology and unit; else null. */
  private static EntityId match(
      Fact fact, List<EntityId> candidates, Links links, Map<EntityId, Set<String>> technologies) {
    EntityId to = Knowledge.object(fact);
    // By host: a host the proposal names, or its own key read as one.
    Set<String> hosts = new LinkedHashSet<>();
    for (JsonNode name : fact.attributes().path("names")) {
      if (name.isString()) {
        String host = host(name.asString());
        if (host != null) {
          hosts.add(host);
        }
      }
    }
    String own = host(to.key());
    if (own != null) {
      hosts.add(own);
    }
    hosts.remove("localhost");
    hosts.remove("127.0.0.1");
    EntityId byHost =
        one(candidates.stream().filter(c -> hosts.contains(hostOf(c))).toList(), links);
    if (byHost != null) {
      return byHost;
    }
    // By name, among those of its type.
    Set<String> words = words(to.key(), GENERIC_NAME_WORDS);
    if (!words.isEmpty()) {
      return one(
          candidates.stream()
              .filter(c -> c.type().equals(to.type()))
              .filter(
                  c -> {
                    Set<String> theirs = words(c.key(), GENERIC_NAME_WORDS);
                    return !theirs.isEmpty()
                        && (theirs.containsAll(words) || words.containsAll(theirs));
                  })
              .toList(),
          links);
    }
    // By technology, among those its unit already uses.
    Set<String> technology =
        words(fact.attributes().path("technology").asString(""), GENERIC_TECHNOLOGY_WORDS);
    if (technology.isEmpty()) {
      return null;
    }
    List<EntityId> used = new ArrayList<>();
    for (EntityId candidate : candidates) {
      if (!links.any().contains(candidate)) {
        continue;
      }
      Set<String> theirs = new HashSet<>(words(candidate.key(), Set.of()));
      theirs.addAll(technologies.getOrDefault(candidate, Set.of()));
      if (technology.stream().anyMatch(theirs::contains)) {
        used.add(candidate);
      }
    }
    return used.size() == 1 ? used.getFirst() : null;
  }

  /**
   * What the proposing unit links to by facts other than Investigate's, in any view and in the
   * default one, and what any fact of static analysis links to in the default view.
   */
  private record Links(Set<EntityId> any, Set<EntityId> byDefault, Set<EntityId> drawn) {}

  /**
   * The one match; of several, the one its unit links to in the default view, as the diagrams draw
   * the default and only note a profile's other host of the same system; else the one its unit
   * links to at all; else the one anything links to in the default view; else null.
   */
  private static EntityId one(List<EntityId> matches, Links links) {
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    for (Set<EntityId> linked : List.of(links.byDefault(), links.any(), links.drawn())) {
      List<EntityId> theirs = matches.stream().filter(linked::contains).toList();
      if (theirs.size() == 1) {
        return theirs.getFirst();
      }
    }
    return null;
  }

  /** Whether the entity is an external system or a database, what a proposal may add. */
  private static boolean isSystemOrStore(EntityId id) {
    return id.type().equals(Types.EXTERNAL_SYSTEM) || id.type().equals(Types.DATABASE);
  }

  /**
   * An element's host: an external system's key as it is, a database's server before its port and
   * name ({@code shop-db:5432/shop}).
   */
  private static String hostOf(EntityId id) {
    String key = id.key().toLowerCase(Locale.ROOT);
    if (id.type().equals(Types.DATABASE)) {
      int end = key.length();
      for (char c : new char[] {':', '/'}) {
        int at = key.indexOf(c);
        if (at >= 0) {
          end = Math.min(end, at);
        }
      }
      key = key.substring(0, end);
    }
    return key;
  }

  /** The host a text names, as a URL's or as a dotted host name, or null. */
  static String host(String text) {
    String value = text.strip().toLowerCase(Locale.ROOT);
    if (value.contains("://")) {
      try {
        String host = URI.create(value).getHost();
        return host == null || host.isEmpty() ? null : host;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return HOST.matcher(value).matches() ? value : null;
  }

  /** A text's words, lowercased, without numbers, single letters and the words given. */
  static Set<String> words(String text, Set<String> generic) {
    Set<String> words = new LinkedHashSet<>();
    for (String word : WORD_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
      if (word.length() > 1
          && !word.chars().allMatch(Character::isDigit)
          && !generic.contains(word)) {
        words.add(word);
      }
    }
    return words;
  }

  /** The connection redrawn to the match: its claim kept, its ID that of its new content. */
  private static Fact redrawn(Fact fact, EntityId match) {
    FactObject object = FactObject.entity(match);
    String id =
        FactJson.factId(
            fact.subject(),
            fact.predicate(),
            object,
            fact.attributes(),
            fact.lineage(),
            fact.origin(),
            fact.evidence());
    return new Fact(
        id,
        fact.claimId(),
        fact.subject(),
        fact.predicate(),
        object,
        fact.attributes(),
        fact.lineage(),
        fact.origin(),
        fact.evidence(),
        fact.confidence(),
        fact.validity());
  }
}
