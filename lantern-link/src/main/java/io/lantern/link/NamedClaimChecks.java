package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.ClaimChecks.Contradiction;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The claim checks that read a name or number the claim states: the port the units listen on, the
 * endpoint paths they serve, the topics, queues and exchanges they publish to or consume from, and
 * the units they call or never call. See {@code docs/adr/0043-readme-claim-checks.md} (amendment
 * for #160).
 *
 * <p>Like the other checks, each reads only plain statements about the README's own units and
 * leaves a claim alone whenever the code's side is not known in full.
 */
final class NamedClaimChecks {

  private static final String SERVER_PORT = "server.port";
  private static final String CONTEXT_PATH = "server.servlet.context-path";
  private static final String SERVLET_PATH = "spring.mvc.servlet.path";

  // ---------------------------------------------------------------------------------------------
  // Ports

  /** "Runs on port 8081", "listens on port 8080", "the HTTP port is 8081", "server.port=8081". */
  private static final List<Pattern> PORTS =
      List.of(
          Pattern.compile(
              "\\b(?:runs?|running|listens?|listening|served|serves|serving|starts?|started"
                  + "|available|reachable|exposed|accessible|bound|binds|up)\\s+(?:on|at|to)"
                  + "\\s+(?:the\\s+)?(?:http\\s+)?port\\s+(\\d{2,5})\\b"),
          Pattern.compile(
              "\\b(?:http|server|web|application|service)\\s+port\\s+(?:is\\s+|:\\s*|=\\s*)?"
                  + "(\\d{2,5})\\b"),
          Pattern.compile("\\bserver\\.port\\s*(?:is\\s+|[:=]\\s*)(\\d{2,5})\\b"));

  /** Sentences that talk about another port than the units' own HTTP one. */
  private static final Pattern OTHER_PORT =
      Pattern.compile(
          "\\b(database|db|broker|management|actuator|debug|debugger|admin|metrics|grpc|jmx"
              + "|prometheus|redis|mongo|mongodb|elasticsearch|kafka|rabbitmq|rabbit|activemq"
              + "|artemis|zookeeper|eureka|postgres|postgresql|mysql|mariadb|oracle|docker"
              + "|container|host|proxy|gateway|load balancer|ingress|nginx|ssl|https|tls"
              + "|localhost)\\b");

  // ---------------------------------------------------------------------------------------------
  // Endpoints

  private static final Pattern SERVES =
      Pattern.compile(
          "\\b(exposes?|exposing|serves?|serving|provides?|providing|offers?|offering|handles?"
              + "|handling|accepts?|accepting|implements?|implementing)\\b");

  /** A path, after whitespace (not a URL's), with the HTTP method before it if one is written. */
  private static final Pattern PATH =
      Pattern.compile(
          "(?:^|[\\s(\\[\"'])(?:(get|post|put|patch|delete|head|options)\\s+)?"
              + "(/[a-z0-9{}_~*\\-]+(?:[./][a-z0-9{}_~*\\-]+)*/?)(?![\\w/:])");

  private static final Pattern ENDPOINT_WORDS =
      Pattern.compile(
          "\\b(endpoints?|apis?|rest-api|routes?|resources?|urls?|paths?|requests?|operations?)\\b");

  /** Paths a framework serves, not the units' own endpoints, and file paths. */
  private static final Pattern FRAMEWORK_PATH =
      Pattern.compile(
          "^/(actuator|swagger|swagger-ui|swagger-ui\\.html|v2|v3|api-docs|openapi|h2-console"
              + "|error|webjars|graphql|graphiql|static|public|resources|health|metrics|info"
              + "|prometheus|var|etc|tmp|opt|usr|home|dev|proc|bin|srv|mnt)(?:[/.]|$)");

  // ---------------------------------------------------------------------------------------------
  // Destinations

  private static final String PUBLISH =
      "publish|publishes|publishing|send|sends|sending|emit|emits|emitting|produce|produces"
          + "|producing|write|writes|writing|put|puts|putting";

  private static final String CONSUME =
      "consume|consumes|consuming|listen|listens|listening|subscribe|subscribes|subscribing"
          + "|read|reads|reading|receive|receives|receiving";

  private static final Pattern MESSAGING_VERB =
      Pattern.compile("\\b(" + PUBLISH + "|" + CONSUME + ")\\b([^,;:]*)");

  private static final String NAME = "([a-z0-9][a-z0-9_\\-]*(?:\\.[a-z0-9_\\-]+)*)";

  private static final String BROKER_WORD =
      "(?:kafka|rabbitmq|rabbit|jms|activemq|artemis|amqp)\\s+";

  /** "topic orders.created", "the topic exchange shop.orders", "the payments.completed topic". */
  private static final List<Pattern> DESTINATIONS =
      List.of(
          Pattern.compile(
              "\\b(?:"
                  + BROKER_WORD
                  + ")?(?:(?:topic|direct|fanout|headers)\\s+)?(?:topic|queue|exchange)\\s+"
                  + "(?:named\\s+|called\\s+)?"
                  + NAME),
          Pattern.compile(
              "\\b(?:the|a|an)\\s+"
                  + NAME
                  + "\\s+(?:"
                  + BROKER_WORD
                  + ")?(?:topic|queue|exchange)\\b"));

  /** Names of destinations by their role, not their name ("the dead-letter queue"). */
  private static final Pattern ROLE_NAME =
      Pattern.compile(
          "^(?:dead-letter|deadletter|dead-lettered|retry|reply|reply-to|error|errors|poison"
              + "|fallback|request-reply|work|default)$");

  // ---------------------------------------------------------------------------------------------
  // Named services

  /** Words after a named service that end the name: a qualifier or the end of the clause. */
  private static final String AFTER_NAME =
      "(?=\\s*$|\\s*[.!?]|\\s+(?:over|via|through|using|by|with|when|whenever|while|during"
          + "|directly|synchronously|asynchronously|for|to|on|at|in|or|and|nor|unless|if"
          + "|because|at all|anymore|either|itself|any more)\\b)";

  private static final String SERVICE_WORD = "(?:\\s+(?:service|application|app|api))?";

  /** A leading list of names: "payments", "the payments service or catalog". */
  private static final Pattern NAME_LIST =
      Pattern.compile(
          "^\\s*(?:to\\s+)?(?:the\\s+)?"
              + NAME
              + SERVICE_WORD
              + AFTER_NAME
              + "((?:\\s*(?:,|or|and|nor)\\s+(?:the\\s+)?[a-z0-9][a-z0-9._\\-]*"
              + SERVICE_WORD
              + AFTER_NAME
              + ")*)");

  private static final Pattern MORE_NAMES =
      Pattern.compile("(?:,|\\bor\\b|\\band\\b|\\bnor\\b)\\s+(?:the\\s+)?" + NAME + SERVICE_WORD);

  /** A positive call, after the sentence's subject: "calls payments", "only calls catalog". */
  private static final Pattern CALLS =
      Pattern.compile(
          "^(?:also\\s+)?(only\\s+)?(?:calls|invokes|talks to|sends requests to)\\s+(only\\s+)?(.*)$");

  /** Words that make the README's units the subject of what follows. */
  private static final Pattern OWN_SUBJECT =
      Pattern.compile(
          "\\b(it|its|this service|the service|this application|the application|this app|the app"
              + "|this api|the api|we|our)\\b");

  private static final Pattern PRONOUN_SUBJECT =
      Pattern.compile(
          "^(?:it|this service|this application|this app|the service|the application)\\b");

  private static final Pattern WORD = Pattern.compile("[a-z0-9][a-z0-9._\\-]*[a-z0-9]|[a-z0-9]");

  private final List<String> sentences;
  private final Set<EntityId> units;
  private final ClaimChecks.Index index;
  private final LinkIndex links;

  /** Each name a unit goes by, lowercase, mapped to the unit (ClaimChecks.Index#unitNames). */
  private final Map<String, EntityId> names;

  /** Sentences whose subject is "it" or "this service" after a sentence about another unit. */
  private final Set<Integer> aboutAnother = new HashSet<>();

  NamedClaimChecks(List<String> sentences, Set<EntityId> units, ClaimChecks.Index index) {
    this.sentences = sentences;
    this.units = units;
    this.index = index;
    this.links = index.links;
    this.names = index.unitNames;
    boolean another = false;
    for (int i = 0; i < sentences.size(); i++) {
      if (another && PRONOUN_SUBJECT.matcher(sentences.get(i)).find()) {
        aboutAnother.add(i);
      }
      another |= !namedUnits(sentences.get(i)).isEmpty();
    }
  }

  /**
   * Each name a unit goes by, lowercase: its artifact ID and application names, and each without a
   * {@code -service} or {@code -app} ending; a name two units share is left out.
   */
  static Map<String, EntityId> unitNames(LinkIndex links) {
    Map<String, Set<EntityId>> all = new HashMap<>();
    for (Unit unit : links.units.values()) {
      Set<String> own = new LinkedHashSet<>();
      own.add(unit.artifactId().toLowerCase(Locale.ROOT));
      for (String view : links.views()) {
        Fact name = links.name(unit.id(), view);
        if (name != null) {
          own.add(name.object().value().toLowerCase(Locale.ROOT));
        }
      }
      for (String name : List.copyOf(own)) {
        String bare = name.replaceAll("[-_](service|svc|app|application|api)$", "");
        if (!bare.isEmpty()) {
          own.add(bare);
        }
      }
      for (String name : own) {
        all.computeIfAbsent(name, n -> new TreeSet<>(LinkIndex.BY_TEXT)).add(unit.id());
      }
    }
    Map<String, EntityId> names = new HashMap<>();
    all.forEach(
        (name, ids) -> {
          if (ids.size() == 1) {
            names.put(name, ids.iterator().next());
          }
        });
    return Map.copyOf(names);
  }

  /** The units other than the README's that a text names. */
  Set<EntityId> namedUnits(String text) {
    Set<EntityId> named = new TreeSet<>(LinkIndex.BY_TEXT);
    Matcher m = WORD.matcher(text);
    while (m.find()) {
      EntityId unit = names.get(m.group());
      if (unit != null && !units.contains(unit)) {
        named.add(unit);
      }
    }
    return named;
  }

  /**
   * Whether a sentence is one the checks read: about what runs, not negated, and with one of the
   * README's units, "it" or nothing (a list item) as its subject, not another unit or anything else
   * ("Kafka", "PayGate").
   */
  private boolean readable(int i, int verbStart) {
    String sentence = sentences.get(i);
    String subject = sentence.substring(0, verbStart);
    return !aboutAnother.contains(i)
        && !ClaimChecks.NOT_RUNTIME.matcher(sentence).find()
        && !ClaimChecks.NEGATION_WORD.matcher(subject).find()
        && ownSubject(subject);
  }

  /** Whether a sentence's start before its verb is nothing, "it", or one of the README's units. */
  private boolean ownSubject(String subject) {
    return namedUnits(subject).isEmpty()
        && (subject.isBlank() || OWN_SUBJECT.matcher(subject).find() || namesOwnUnit(subject));
  }

  /** Whether a text names one of the README's units. */
  private boolean namesOwnUnit(String text) {
    Matcher m = WORD.matcher(text);
    while (m.find()) {
      EntityId unit = names.get(m.group());
      if (unit != null && units.contains(unit)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // port

  /**
   * "Runs on port 8081": every unit sets {@code server.port} to a number in every view, and none of
   * them is the one stated. A unit that leaves it unset, or sets it from a placeholder or a secret,
   * means the port is not known and nothing is flagged.
   */
  void port(List<Contradiction> out) {
    Set<String> stated = new TreeSet<>();
    for (int i = 0; i < sentences.size(); i++) {
      String s = sentences.get(i);
      for (Pattern pattern : PORTS) {
        Matcher m = pattern.matcher(s);
        while (m.find()) {
          if (OTHER_PORT.matcher(s).find() || !readable(i, m.start())) {
            return;
          }
          stated.add(String.valueOf(Integer.parseInt(m.group(1))));
        }
      }
    }
    if (stated.size() != 1 || units.isEmpty()) {
      return;
    }
    String port = stated.iterator().next();
    List<Fact> contradicting = new ArrayList<>();
    Set<String> found = new TreeSet<>();
    for (EntityId id : units) {
      Unit unit = links.units.get(id);
      if (unit == null
          || links.config(unit, null, ConfigExtractor.CONFIG_VALUE, SERVER_PORT) == null) {
        return;
      }
      for (String view : links.views()) {
        Fact value = links.config(unit, view, ConfigExtractor.CONFIG_VALUE, SERVER_PORT);
        if (value == null
            || LinkIndex.flag(value, "masked")
            || !value.object().value().strip().matches("\\d{1,5}")) {
          return;
        }
        String number = String.valueOf(Integer.parseInt(value.object().value().strip()));
        if (number.equals(port)) {
          return;
        }
        if (!contradicting.contains(value)) {
          contradicting.add(value);
          found.add("port " + number);
        }
      }
    }
    out.add(
        new Contradiction(
            ClaimChecks.PORT, "port " + port, found, ClaimChecks.sorted(contradicting)));
  }

  // ---------------------------------------------------------------------------------------------
  // endpoint

  /** An endpoint of one of the units, with the paths it is served at. */
  private record Served(Fact fact, String method, List<String> paths) {}

  /**
   * "Exposes {@code GET /orders/{id}}": the units' endpoints are known and none serves the path
   * (with the method, if one is written), with or without the unit's context and servlet paths, or
   * behind a prefix the README adds (a gateway's). Variables match any segment.
   */
  void endpoints(List<Contradiction> out) {
    Map<String, String[]> stated = new LinkedHashMap<>();
    for (int i = 0; i < sentences.size(); i++) {
      String s = sentences.get(i);
      Matcher verb = SERVES.matcher(s);
      if (!verb.find() || !readable(i, verb.start())) {
        continue;
      }
      Matcher m = PATH.matcher(s);
      m.region(verb.end(), s.length());
      while (m.find()) {
        String path = m.group(2);
        // A path with no method is read only after words about endpoints, not files ("/data").
        if (m.group(1) == null
            && !ENDPOINT_WORDS.matcher(s.substring(verb.end(), m.start())).find()) {
          continue;
        }
        if (path.length() > 1 && path.endsWith("/")) {
          path = path.substring(0, path.length() - 1);
        }
        if (FRAMEWORK_PATH.matcher(path).find()) {
          continue;
        }
        String method = m.group(1) == null ? null : m.group(1).toUpperCase(Locale.ROOT);
        stated.putIfAbsent(
            (method == null ? "" : method + " ") + path, new String[] {method, path});
      }
    }
    if (stated.isEmpty()) {
      return;
    }
    List<Served> served = served();
    if (served == null || served.isEmpty()) {
      return;
    }
    Set<String> missing = new LinkedHashSet<>();
    Set<Fact> contradicting = new LinkedHashSet<>();
    for (Map.Entry<String, String[]> e : stated.entrySet()) {
      String method = e.getValue()[0];
      String path = e.getValue()[1];
      if (served.stream().anyMatch(sv -> serves(sv, method, path))) {
        continue;
      }
      missing.add(e.getKey());
      contradicting.addAll(nearest(served, path));
    }
    if (missing.isEmpty()) {
      return;
    }
    Set<String> found = new TreeSet<>();
    contradicting.forEach(f -> found.add(f.object().value()));
    out.add(
        new Contradiction(
            ClaimChecks.ENDPOINT,
            String.join(", ", missing),
            found,
            ClaimChecks.sorted(new ArrayList<>(contradicting))));
  }

  /** The units' endpoints with the paths each is served at; null when one has no known path. */
  private List<Served> served() {
    List<Served> served = new ArrayList<>();
    for (Fact f : links.of(SpringExtractor.ENDPOINT)) {
      String controller = LinkIndex.text(f, "controller");
      EntityId code =
          controller == null ? f.subject() : new EntityId(BuiltInTypes.TYPE, controller);
      for (Unit unit : links.unitsOf(code)) {
        if (!units.contains(unit.id())) {
          continue;
        }
        String template = LinkIndex.text(f, "path");
        if (template == null) {
          return null;
        }
        Set<String> paths = new LinkedHashSet<>();
        paths.add(template);
        for (String view : links.views()) {
          String prefix = "";
          for (String key : List.of(CONTEXT_PATH, SERVLET_PATH)) {
            Fact value = links.config(unit, view, ConfigExtractor.CONFIG_VALUE, key);
            if (value != null && !LinkIndex.flag(value, "masked")) {
              prefix = LinkText.join(prefix, value.object().value());
            }
          }
          paths.add(LinkText.join(prefix, template));
        }
        List<String> lower = paths.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        served.add(new Served(f, LinkIndex.text(f, "httpMethod"), lower));
      }
    }
    return served;
  }

  /** Whether an endpoint serves a request, also behind leading segments the README adds. */
  private static boolean serves(Served served, String method, String path) {
    if (method != null && served.method() != null && !served.method().equals(method)) {
      return false;
    }
    String request = path;
    while (true) {
      for (String p : served.paths()) {
        if (LinkText.pathMatch(request, p) >= 0) {
          return true;
        }
      }
      int next = request.indexOf('/', 1);
      if (next < 0) {
        return false;
      }
      request = request.substring(next);
    }
  }

  /** The endpoints sharing the most leading segments with a path; all of them when none share. */
  private static List<Fact> nearest(List<Served> served, String path) {
    List<String> stated = segments(path);
    int best = 0;
    Map<Fact, Integer> shared = new LinkedHashMap<>();
    for (Served sv : served) {
      int most = 0;
      for (String p : sv.paths()) {
        List<String> segments = segments(p);
        int n = 0;
        while (n < stated.size() && n < segments.size() && stated.get(n).equals(segments.get(n))) {
          n++;
        }
        most = Math.max(most, n);
      }
      shared.merge(sv.fact(), most, Math::max);
      best = Math.max(best, most);
    }
    int threshold = best;
    return shared.entrySet().stream()
        .filter(e -> e.getValue() == threshold)
        .map(Map.Entry::getKey)
        .toList();
  }

  private static List<String> segments(String path) {
    List<String> segments = new ArrayList<>();
    for (String s : path.split("/")) {
      if (!s.isEmpty()) {
        segments.add(s);
      }
    }
    return segments;
  }

  // ---------------------------------------------------------------------------------------------
  // destination

  /**
   * "Publishes to the {@code orders.created} topic", "consumes from queue {@code shipments.ready}":
   * the units publish (or consume) only through destinations Lantern knows, and none of them is the
   * one named. Only names with a dot, dash or underscore are read, since a plain word ("the orders
   * topic") may describe a destination rather than name it.
   */
  void destinations(List<Contradiction> out) {
    Set<String> published = new LinkedHashSet<>();
    Set<String> consumed = new LinkedHashSet<>();
    for (int i = 0; i < sentences.size(); i++) {
      String s = sentences.get(i);
      Matcher verb = MESSAGING_VERB.matcher(s);
      while (verb.find()) {
        String clause = verb.group(2);
        Set<String> named = new LinkedHashSet<>();
        for (Pattern pattern : DESTINATIONS) {
          Matcher m = pattern.matcher(clause);
          while (m.find()) {
            String name = m.group(1);
            if (name.matches(".*[._\\-].*")
                && !ROLE_NAME.matcher(name).matches()
                && !name.startsWith("-")
                && !name.endsWith("-")) {
              named.add(name);
            }
          }
        }
        if (named.isEmpty() || !readable(i, verb.start())) {
          continue;
        }
        (verb.group(1).matches(PUBLISH) ? published : consumed).addAll(named);
      }
    }
    if (!published.isEmpty()) {
      destination(published, CrossRepoLinks.PUBLISHES, MessagingExtractor.SEND, out);
    }
    if (!consumed.isEmpty()) {
      destination(consumed, CrossRepoLinks.CONSUMES, MessagingExtractor.LISTENER, out);
    }
  }

  private void destination(
      Set<String> stated, String predicate, String raw, List<Contradiction> out) {
    List<Fact> uses = index.from(predicate, units);
    if (uses.isEmpty()) {
      return;
    }
    // A send or listener whose destination the linker could not know may be the named one.
    for (Fact f : index.from(CrossRepoLinks.UNRESOLVED_LINK, units)) {
      if ("message".equals(LinkIndex.text(f, "kind"))) {
        return;
      }
    }
    Set<String> known = new TreeSet<>();
    List<String> patterns = new ArrayList<>();
    for (Fact f : links.of(raw)) {
      if (links.unitsOf(f.subject()).stream().noneMatch(u -> units.contains(u.id()))) {
        continue;
      }
      if (LinkIndex.flag(f, "masked") || LinkIndex.text(f, "broker") == null) {
        return;
      }
      if ("topic-pattern".equals(LinkIndex.text(f, "destinationKind"))) {
        patterns.add(f.object().value());
      }
    }
    Set<EntityId> destinations = new TreeSet<>(LinkIndex.BY_TEXT);
    for (Fact f : uses) {
      if (f.object().kind() != FactObject.Kind.ENTITY) {
        continue;
      }
      destinations.add(f.object().entityId());
      String key = LinkIndex.text(f, "routingKey");
      if (key != null) {
        known.add(key.toLowerCase(Locale.ROOT));
      }
      String pattern = LinkIndex.text(f, "pattern");
      if (pattern != null) {
        patterns.add(pattern);
      }
    }
    // An exchange reaches the queues bound to it; a queue is reached from the exchanges bound to
    // it.
    for (Fact f : index.of(CrossRepoLinks.ROUTES_TO)) {
      if (f.object().kind() != FactObject.Kind.ENTITY) {
        continue;
      }
      if (destinations.contains(f.subject())) {
        known.add(name(f.object().entityId()));
      } else if (destinations.contains(f.object().entityId())) {
        known.add(name(f.subject()));
      }
    }
    destinations.forEach(d -> known.add(name(d)));
    Set<String> missing = new LinkedHashSet<>();
    for (String name : stated) {
      boolean matches =
          known.contains(name)
              || patterns.stream().anyMatch(p -> LinkText.regexMatches(p, name))
              || known.stream().anyMatch(k -> LinkText.topicMatches(k, name));
      if (!matches) {
        missing.add(name);
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    Set<String> found = new TreeSet<>();
    destinations.forEach(d -> found.add(name(d)));
    out.add(
        new Contradiction(
            ClaimChecks.DESTINATION, String.join(", ", missing), found, ClaimChecks.sorted(uses)));
  }

  /** A topic's, queue's or exchange's name, lowercase: its key after the broker. */
  private static String name(EntityId destination) {
    String key = destination.key();
    return key.substring(key.indexOf(':') + 1).toLowerCase(Locale.ROOT);
  }

  // ---------------------------------------------------------------------------------------------
  // named-services

  /**
   * "Never calls payments", "does not call the catalog service over HTTP": the units' HTTP calls to
   * a unit the claim names. "Calls payments" or "only calls catalog", with the units or "it" as the
   * subject: the units' HTTP calls are all linked and none reaches the named unit, or, for "only",
   * some reach another unit or system.
   */
  void services(List<Contradiction> out) {
    Set<EntityId> never = new TreeSet<>(LinkIndex.BY_TEXT);
    Set<EntityId> calls = new TreeSet<>(LinkIndex.BY_TEXT);
    Set<EntityId> only = new TreeSet<>(LinkIndex.BY_TEXT);
    for (int i = 0; i < sentences.size(); i++) {
      String s = sentences.get(i);
      if (ClaimChecks.NOT_RUNTIME.matcher(s).find() || aboutAnother.contains(i)) {
        continue;
      }
      Matcher no = ClaimChecks.NO_CALLS.matcher(s);
      while (no.find()) {
        if (ownSubject(s.substring(0, no.start()))) {
          never.addAll(nameList(no.group(2)));
        }
      }
      String rest = afterSubject(s);
      if (rest == null) {
        continue;
      }
      Matcher m = CALLS.matcher(rest);
      if (m.find()) {
        Set<EntityId> named = nameList(m.group(3));
        if (named.isEmpty()) {
          continue;
        }
        (m.group(1) != null || m.group(2) != null ? only : calls).addAll(named);
      }
    }
    List<Fact> http = index.from(CrossRepoLinks.CALLS_HTTP, units);
    Set<Fact> contradicting = new LinkedHashSet<>();
    List<String> stated = new ArrayList<>();
    for (EntityId named : never) {
      List<Fact> to = to(http, named);
      if (!to.isEmpty()) {
        contradicting.addAll(to);
        stated.add("no calls to " + named(named));
      }
    }
    Set<EntityId> called = new TreeSet<>(LinkIndex.BY_TEXT);
    called.addAll(calls);
    called.addAll(only);
    boolean unresolved =
        index.from(CrossRepoLinks.UNRESOLVED_LINK, units).stream()
            .anyMatch(f -> "http".equals(LinkIndex.text(f, "kind")));
    Set<String> found = new TreeSet<>();
    if (!http.isEmpty() && !unresolved) {
      for (EntityId named : called) {
        if (to(http, named).isEmpty()) {
          contradicting.addAll(http);
          stated.add("calls " + named(named));
        }
      }
    }
    if (!only.isEmpty()) {
      List<Fact> others =
          http.stream()
              .filter(f -> f.object().kind() == FactObject.Kind.ENTITY)
              .filter(f -> !only.contains(f.object().entityId()))
              .toList();
      if (!others.isEmpty()) {
        contradicting.addAll(others);
        stated.add("calls only " + String.join(", ", only.stream().map(this::named).toList()));
      }
    }
    if (contradicting.isEmpty()) {
      return;
    }
    if (!called.isEmpty()) {
      for (Fact f : contradicting) {
        if (f.object().kind() == FactObject.Kind.ENTITY) {
          found.add("calls " + named(f.object().entityId()));
        }
      }
    }
    out.add(
        new Contradiction(
            ClaimChecks.NAMED_SERVICES,
            String.join("; ", new LinkedHashSet<>(stated)),
            found,
            ClaimChecks.sorted(new ArrayList<>(contradicting))));
  }

  /** The units' calls that reach a unit. */
  private static List<Fact> to(List<Fact> http, EntityId unit) {
    return http.stream()
        .filter(f -> f.object().kind() == FactObject.Kind.ENTITY)
        .filter(f -> f.object().entityId().equals(unit))
        .toList();
  }

  /** How a unit is named: its default application name, else its artifact ID. */
  private String named(EntityId unit) {
    Fact name = links.name(unit, null);
    if (name != null) {
      return name.object().value();
    }
    Unit u = links.units.get(unit);
    return u == null ? unit.key() : u.artifactId();
  }

  /**
   * What follows the sentence's subject when the subject is one of the README's units or "it"; null
   * for any other subject.
   */
  private String afterSubject(String sentence) {
    Matcher pronoun = PRONOUN_SUBJECT.matcher(sentence);
    if (pronoun.find()) {
      return sentence.substring(pronoun.end()).strip();
    }
    Matcher word =
        Pattern.compile("^(?:the\\s+)?([a-z0-9][a-z0-9._\\-]*)" + SERVICE_WORD).matcher(sentence);
    EntityId subject = word.find() ? names.get(word.group(1)) : null;
    if (subject != null && units.contains(subject)) {
      return sentence.substring(word.end()).strip();
    }
    return null;
  }

  /** The units other than the README's that a leading list of names names, all of them known. */
  private Set<EntityId> nameList(String text) {
    Matcher m = NAME_LIST.matcher(text);
    if (!m.find()) {
      return Set.of();
    }
    List<String> words = new ArrayList<>();
    words.add(m.group(1));
    Matcher more = MORE_NAMES.matcher(m.group(2));
    while (more.find()) {
      words.add(more.group(1));
    }
    Set<EntityId> named = new TreeSet<>(LinkIndex.BY_TEXT);
    for (String w : words) {
      EntityId unit = names.get(w);
      if (unit == null || units.contains(unit)) {
        return Set.of();
      }
      named.add(unit);
    }
    return named;
  }
}
