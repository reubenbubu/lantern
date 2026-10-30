package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Paragraph;
import io.lantern.core.document.Section;
import io.lantern.core.document.SequenceDiagram;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The main flows of a scope as sequence diagrams, by {@code docs/design.md} (Composition, Sequence
 * diagrams): each traced from an entry point (an HTTP endpoint, a message listener or a scheduled
 * job) through the call graph inside its deployable unit, keeping only the steps that cross a
 * container boundary (HTTP calls, sends and data access), and stitched across units through the
 * linker's links (ADR 0042): an HTTP call goes on in the endpoint it reaches, a send in each
 * listener it reaches, after the sender's own steps, since it happens later.
 *
 * <p>Nothing is invented: a step is drawn only for a fact, and a call Lantern could not link ends
 * there with a note. Participants are containers: deployable units, databases, topics, queues and
 * exchanges, and external systems, named as on the container diagram. An HTTP call or a send is
 * labelled with the LLM's intent phrase for its link where there is one (ADR 0050), the endpoint or
 * protocol after it; other labels say what the facts say (the handler, the routing key). A flow's
 * walkthrough and pre- and post-conditions are the LLM's where it worded the flow as traced here
 * (ADR 0058), else the prose says what the trace found and the conditions are not known.
 */
final class SequenceFlows {

  /** The most flows a scope shows (docs/design.md: three to seven). */
  static final int MAX_FLOWS = 7;

  /** The fewest flows a scope shows; flows that reach only databases fill up to it. */
  static final int MIN_FLOWS = 3;

  /** The most implementations a call is drawn into, as the operands of one frame. */
  static final int MAX_ALTERNATIVES = 4;

  /** The most messages one flow shows before the trace stops. */
  static final int MAX_MESSAGES = 40;

  /** The most steps, notes included, one flow shows before the trace stops. */
  static final int MAX_STEPS = 2 * MAX_MESSAGES;

  private static final String METHOD = "method";

  /** The name of a constructor in a method's ID (ADR 0007). */
  private static final String CONSTRUCTOR = "<init>(";

  private static final String ENDPOINT = "endpoint";
  private static final String MESSAGE_LISTENER = "message-listener";
  private static final String SCHEDULED = "scheduled";
  private static final String STOMP_MAPPING = "stomp-mapping";
  private static final String STOMP_SEND = "stomp-send";
  private static final String WEBSOCKET_ENDPOINT = "websocket-endpoint";
  private static final String STOMP_INTERCEPTOR = "stomp-interceptor";
  private static final String ROUTED_ENDPOINT = "routed-endpoint";
  private static final String BACKGROUND_JOB = "background-job";
  private static final String PROFILE_GATE = "profile-gate";
  private static final String SENDS_MESSAGES_TO = "SENDS_MESSAGES_TO";
  private static final String REPOSITORY_FOR = "REPOSITORY_FOR";
  private static final String READS_TABLE = "READS_TABLE";
  private static final String WRITES_TABLE = "WRITES_TABLE";

  private static final String UNKNOWN = "Not known from the code.";

  private final Knowledge knowledge;
  private final Assembler assembler;
  private final C4Views c4;
  private final Set<EntityId> scope;
  private final Set<Fact> alternatives;

  /** The flows shown, once chosen. */
  private List<Trace> chosen;

  /** The links of each unit, by the code on the unit's end. */
  private final Map<String, List<Fact>> linksBySource = new HashMap<>();

  /** The background jobs a method schedules, by the method's ID (the jobs' {@code scheduledBy}). */
  private Map<String, List<Entry>> jobsByScheduler = Map.of();

  /**
   * How a flow starts in the browser: a person using a frontend (when the frontend has screens for
   * one), the frontend's call that reaches the entry point, and the gateway and route it goes
   * through, if any (ADR 0047, amendment for #331).
   *
   * @param person null when no person has screens on the frontend
   * @param via the gateway the call goes to, which routes it on; null when it reaches the unit
   * @param route the gateway's route link to the unit; null when there is none or no gateway
   */
  private record Start(
      EntityId person,
      List<Fact> personFacts,
      EntityId frontend,
      Fact link,
      EntityId via,
      Fact route) {}

  /** The browser starts of the entry points frontends' calls reach, by {@link Entry#key()}. */
  private final Map<String, Start> starts = new HashMap<>();

  /**
   * How a flow starts at a gateway that routes a client's request to its endpoint (ADR 0047,
   * amendment for #351).
   *
   * @param routed the gateway's {@code routed-endpoint} fact: the request as the gateway receives
   *     it, and as the unit serves it
   * @param route the gateway's route link to the unit; null for the discovery locator
   */
  private record GatewayStart(EntityId gateway, Fact routed, Fact route) {}

  /** The gateways' {@code routed-endpoint} facts, by the unit and handler method they reach. */
  private final Map<String, List<Fact>> routedEndpoints = new HashMap<>();

  /**
   * @param units the deployable units in scope; flows that start in or pass through one are shown
   */
  SequenceFlows(Knowledge knowledge, Assembler assembler, C4Views c4, Set<EntityId> units) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.c4 = c4;
    this.scope = units;
    this.alternatives = c4.alternativeLinks().keySet();
    for (EntityId unit : C4Views.units(knowledge)) {
      for (Fact fact : knowledge.about(unit)) {
        String source = attribute(fact, "source");
        if (source != null
            && !alternatives.contains(fact)
            && !(fact.predicate().equals(C4Views.UNRESOLVED_LINK) && knowledge.resolved(fact))) {
          linksBySource.computeIfAbsent(unit + " " + source, k -> new ArrayList<>()).add(fact);
        }
      }
    }
    for (EntityId frontend : C4Views.frontends(knowledge)) {
      starts(frontend);
    }
    for (EntityId gateway : C4Views.units(knowledge)) {
      for (Fact fact : knowledge.attributes(gateway)) {
        if (fact.predicate().equals(ROUTED_ENDPOINT)
            && attribute(fact, "profile") == null
            && attribute(fact, "target") != null
            && attribute(fact, "endpoint") != null) {
          routedEndpoints
              .computeIfAbsent(
                  attribute(fact, "target") + " " + attribute(fact, "endpoint"),
                  k -> new ArrayList<>())
              .add(fact);
        }
      }
    }
  }

  /**
   * How a flow that starts at an endpoint, not in a browser, starts at a gateway that routes a
   * client's request to it: through a route before the discovery locator, then by the gateway's ID
   * and the request; null when no gateway routes to it.
   */
  private GatewayStart gatewayStart(Entry entry) {
    if (!entry.fact.predicate().equals(ENDPOINT)) {
      return null;
    }
    Fact routed =
        routedEndpoints.getOrDefault(entry.key(), List.of()).stream()
            .filter(f -> C4Views.strings(f, "facts").contains(entry.fact.id()))
            .min(
                Comparator.comparing((Fact f) -> "true".equals(attribute(f, "discoveryLocator")))
                    .thenComparing(f -> f.subject().toString())
                    .thenComparing(f -> f.object().value()))
            .orElse(null);
    if (routed == null) {
      return null;
    }
    String id = attribute(routed, "route");
    Fact route =
        id == null
            ? null
            : knowledge.relations(routed.subject(), C4Views.CALLS_HTTP).stream()
                .filter(
                    f ->
                        !alternatives.contains(f)
                            && Knowledge.object(f).equals(entry.unit)
                            && id.equals(attribute(f, "route")))
                .findFirst()
                .orElse(null);
    return new GatewayStart(routed.subject(), routed, route);
  }

  /**
   * Records how the browser starts the flows of the entry points the frontend's calls reach, the
   * first call in the code for each, with the person who uses the frontend: its only one, else the
   * one with most screens on it, by ID among equals.
   */
  private void starts(EntityId frontend) {
    EntityId person = null;
    List<Fact> personFacts = List.of();
    for (Map.Entry<EntityId, List<Fact>> e : c4.screensOn(frontend).entrySet()) {
      if (person == null || e.getValue().size() > personFacts.size()) {
        person = e.getKey();
        personFacts = e.getValue();
      }
    }
    List<Fact> calls =
        knowledge.relations(frontend, C4Views.CALLS_HTTP).stream()
            .filter(f -> !alternatives.contains(f) && attribute(f, "reaches") != null)
            .sorted(
                Comparator.comparing((Fact f) -> f.evidence().path())
                    .thenComparingInt(SequenceFlows::line)
                    .thenComparing(Fact::id))
            .toList();
    for (Fact call : calls) {
      EntityId unit = parse(attribute(call, "reachesUnit"));
      EntityId handler = parse(attribute(call, "reaches"));
      if (unit == null || handler == null) {
        continue;
      }
      EntityId to = Knowledge.object(call);
      EntityId via = to.equals(unit) ? null : to;
      Fact route = null;
      if (via != null) {
        String id = attribute(call, "throughRoute");
        route =
            knowledge.relations(via, C4Views.CALLS_HTTP).stream()
                .filter(
                    f ->
                        !alternatives.contains(f)
                            && Knowledge.object(f).equals(unit)
                            && id != null
                            && id.equals(attribute(f, "route")))
                .findFirst()
                .orElse(null);
      }
      starts.putIfAbsent(
          unit + " " + handler, new Start(person, personFacts, frontend, call, via, route));
    }
  }

  // Entry points.

  /** What starts a flow: an endpoint, a listener or a scheduled job of a unit. */
  record Entry(EntityId unit, EntityId method, Fact fact) {

    String key() {
      return unit + " " + method;
    }
  }

  /** Every entry point of every unit, by unit and method ID. */
  private List<Entry> entries() {
    List<Entry> result = new ArrayList<>();
    for (EntityId unit : C4Views.units(knowledge)) {
      SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
      for (EntityId module : C4Views.codeModules(knowledge, unit)) {
        code.addAll(knowledge.typesWithin(module));
        for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
          code.addAll(knowledge.typesWithin(pkg));
        }
      }
      for (EntityId type : code) {
        for (EntityId method : knowledge.parts(type, METHOD)) {
          Start start = starts.get(unit + " " + method);
          String reached = start == null ? null : attribute(start.link, "reachesEndpoint");
          knowledge.attributes(method).stream()
              // Of a handler's endpoints, the one the browser's call reaches.
              .sorted(
                  Comparator.comparing(
                      (Fact f) ->
                          !(f.predicate().equals(ENDPOINT) && f.object().value().equals(reached))))
              .filter(
                  f ->
                      f.predicate().equals(ENDPOINT)
                          || f.predicate().equals(MESSAGE_LISTENER)
                          || f.predicate().equals(STOMP_MAPPING)
                          || f.predicate().equals(SCHEDULED)
                          || f.predicate().equals(BACKGROUND_JOB)
                          // A WebSocket endpoint starts a flow when a frontend opens it.
                          || (f.predicate().equals(WEBSOCKET_ENDPOINT)
                              && starts.containsKey(unit + " " + method)))
              .findFirst()
              .ifPresent(f -> result.add(new Entry(unit, method, f)));
        }
      }
    }
    return result;
  }

  // Tracing.

  /** A step with the Spring profiles under which alone it happens. */
  private record Item(SequenceDiagram.Step step, List<String> profiles) {}

  /**
   * A send's delivery to a listener, or a background job's run, traced after the steps of the code
   * that sent the message or scheduled the job.
   *
   * @param link the delivery's link; null for a job
   * @param job the job that runs; null for a delivery
   */
  private record Later(EntityId from, Fact link, List<String> profiles, Entry job) {}

  /** One flow being traced. */
  private final class Trace {
    final Entry entry;

    /** How the browser starts the flow; null when it starts at its entry point. */
    final Start start;

    /** How a gateway starts the flow, when the browser does not; null when none does. */
    final GatewayStart gateway;

    List<Item> items = new ArrayList<>();
    final Set<EntityId> participants = new LinkedHashSet<>();

    /**
     * The methods being traced, from the entry point or a delivery down: a call to one recurses.
     */
    final Set<String> stack = new HashSet<>();

    /** Every method traced in the flow. */
    final Set<String> traced = new HashSet<>();

    /** The methods whose code was traced and added nothing, so tracing them again adds nothing. */
    final Set<String> quiet = new HashSet<>();

    /** The deliveries traced, by their link's ID: each is traced once. */
    Set<String> delivered = new HashSet<>();

    /** How many steps, notes and later deliveries the flow has had, to tell quiet code. */
    int added;

    /** How many recursive calls were cut, so code that reached one is not taken as quiet. */
    int cut;

    /** The data access the last step drew, which the next one to the same database joins. */
    DataRun data;

    Deque<Later> later = new ArrayDeque<>();
    final Set<String> reached = new HashSet<>();
    final SortedSet<String> asyncThrough = new TreeSet<>();
    final List<String> unresolved = new ArrayList<>();

    /** The steps as the LLM's prompt shows them: by their facts, without intent phrases. */
    List<WordingPrompt.FlowStep> outline = new ArrayList<>();

    int depth;
    int messages;

    /** How many pushes to STOMP subscribers the flow shows, which leave the unit as notes. */
    int pushes;

    boolean truncated;

    Trace(Entry entry) {
      this.entry = entry;
      this.start = starts.get(entry.key());
      this.gateway = start == null ? gatewayStart(entry) : null;
      if (start != null) {
        if (start.person != null) {
          participants.add(start.person);
        }
        participants.add(start.frontend);
        if (start.via != null) {
          participants.add(start.via);
        }
      }
      if (gateway != null) {
        participants.add(gateway.gateway());
      }
      participants.add(entry.unit);
    }

    boolean full() {
      if (messages >= MAX_MESSAGES || added >= MAX_STEPS) {
        truncated = true;
      }
      return truncated;
    }

    void message(
        EntityId from,
        EntityId to,
        String label,
        String code,
        SequenceDiagram.MessageKind kind,
        List<String> profiles,
        String info,
        List<Fact> facts) {
      message(from, to, label, code, kind, profiles, info, facts, label, facts);
    }

    /**
     * Adds a message.
     *
     * @param plain the label the facts alone give, for the LLM's prompt
     * @param plainFacts the facts behind it, without those the LLM's intent phrase cites
     */
    void message(
        EntityId from,
        EntityId to,
        String label,
        String code,
        SequenceDiagram.MessageKind kind,
        List<String> profiles,
        String info,
        List<Fact> facts,
        String plain,
        List<Fact> plainFacts) {
      if (full()) {
        return;
      }
      messages++;
      added++;
      data = null;
      outline(line(from, to, kind, plain, code, info), profiles, plainFacts);
      if (from != null) {
        participants.add(from);
      }
      if (to != null) {
        participants.add(to);
      }
      items.add(
          new Item(
              new SequenceDiagram.Message(
                  from,
                  to,
                  label,
                  code,
                  kind,
                  false,
                  null,
                  profiles,
                  info == null ? List.of() : List.of(TextRun.of(info)),
                  assembler.facts(facts)),
              profiles));
    }

    void note(List<EntityId> over, String text, List<String> profiles, List<Fact> facts) {
      if (full()) {
        return;
      }
      added++;
      data = null;
      participants.addAll(over);
      outline(new StringBuilder("Note: ").append(text), profiles, facts);
      items.add(
          new Item(
              new SequenceDiagram.Note(
                  List.copyOf(new LinkedHashSet<>(over)),
                  List.of(TextRun.of(text)),
                  assembler.facts(facts)),
              profiles));
    }

    private StringBuilder line(
        EntityId from,
        EntityId to,
        SequenceDiagram.MessageKind kind,
        String plain,
        String code,
        String info) {
      StringBuilder line =
          new StringBuilder(nameOf(from))
              .append(" to ")
              .append(nameOf(to))
              .append(" (")
              .append(WordingPrompt.kind(to))
              .append("), ")
              .append(kind == SequenceDiagram.MessageKind.ASYNC ? "async" : "sync")
              .append(": ")
              .append(plain);
      if (code != null) {
        line.append(" [").append(code).append(']');
      }
      if (info != null) {
        line.append(". ").append(info);
      }
      return line;
    }

    private WordingPrompt.FlowStep step(
        StringBuilder line, List<String> profiles, List<Fact> facts) {
      if (!profiles.isEmpty()) {
        line.append(line.toString().endsWith(".") ? " " : ". ")
            .append("Only under the Spring profile")
            .append(profiles.size() > 1 ? "s " : " ")
            .append(list(profiles))
            .append('.');
      }
      return new WordingPrompt.FlowStep(depth, line.toString(), List.copyOf(facts));
    }

    private void outline(StringBuilder line, List<String> profiles, List<Fact> facts) {
      outline.add(step(line, profiles, facts));
    }

    /**
     * Draws data access of the unit in the database: a message, or, when the step before is the
     * unit's access to the same database under the same profiles, that message saying this too, so
     * a run of reads and writes is one step (ADR 0047, amendment for #272).
     *
     * @param verb what the unit does, such as {@code Reads} or {@code Calls}
     * @param what what it reads, writes or calls
     */
    void data(
        EntityId unit,
        EntityId database,
        String verb,
        List<String> what,
        String code,
        List<String> profiles,
        String info,
        List<Fact> facts) {
      DataRun run = data;
      boolean joins =
          run != null
              && !truncated
              && run.items == items
              && run.outline == outline
              && run.unit.equals(unit)
              && run.database.equals(database)
              && run.profiles.equals(profiles)
              && Objects.equals(run.code, code)
              && items.size() == run.item + 1
              && outline.size() == run.step + 1;
      if (!joins) {
        int before = messages;
        message(
            unit,
            database,
            DataRun.label(Map.of(verb, new LinkedHashSet<>(what))),
            code,
            null,
            profiles,
            info,
            facts);
        if (messages > before) {
          DataRun started =
              new DataRun(items, items.size() - 1, outline, outline.size() - 1, unit, database);
          started.profiles = profiles;
          started.code = code;
          started.add(verb, what, info, facts);
          data = started;
        }
        return;
      }
      run.add(verb, what, info, facts);
      added++;
      String label = DataRun.label(run.parts);
      String joined = run.info.isEmpty() ? null : String.join(" ", run.info);
      List<Fact> all = List.copyOf(run.facts);
      items.set(
          run.item,
          new Item(
              new SequenceDiagram.Message(
                  unit,
                  database,
                  label,
                  code,
                  null,
                  false,
                  null,
                  profiles,
                  joined == null ? List.of() : List.of(TextRun.of(joined)),
                  assembler.facts(all)),
              profiles));
      outline.set(run.step, step(line(unit, database, null, label, code, joined), profiles, all));
    }

    /** The units, stores, queues and external systems the flow touches. */
    int touches() {
      return participants.size() + (pushes > 0 ? 1 : 0);
    }
  }

  /** A run of one unit's data access in one database, drawn as one message. */
  private static final class DataRun {

    /** The most tables or methods a label names for each verb before saying how many more. */
    static final int NAMED = 4;

    final List<Item> items;
    final int item;
    final List<WordingPrompt.FlowStep> outline;
    final int step;
    final EntityId unit;
    final EntityId database;
    List<String> profiles = List.of();
    String code;
    final Map<String, Set<String>> parts = new LinkedHashMap<>();
    final Set<String> info = new LinkedHashSet<>();
    final Set<Fact> facts = new LinkedHashSet<>();

    DataRun(
        List<Item> items,
        int item,
        List<WordingPrompt.FlowStep> outline,
        int step,
        EntityId unit,
        EntityId database) {
      this.items = items;
      this.item = item;
      this.outline = outline;
      this.step = step;
      this.unit = unit;
      this.database = database;
    }

    void add(String verb, List<String> what, String text, List<Fact> more) {
      parts.computeIfAbsent(verb, v -> new LinkedHashSet<>()).addAll(what);
      if (text != null) {
        info.add(text);
      }
      facts.addAll(more);
    }

    /** "Reads orders, payment; writes payment": each verb with what it names, in order. */
    static String label(Map<String, Set<String>> parts) {
      List<String> clauses = new ArrayList<>();
      for (Map.Entry<String, Set<String>> part : parts.entrySet()) {
        List<String> names = new ArrayList<>(part.getValue());
        String shown =
            names.size() <= NAMED
                ? String.join(", ", names)
                : String.join(", ", names.subList(0, NAMED))
                    + " and "
                    + (names.size() - NAMED)
                    + " more";
        String verb = clauses.isEmpty() ? part.getKey() : part.getKey().toLowerCase(Locale.ROOT);
        clauses.add(verb + " " + shown);
      }
      return String.join("; ", clauses);
    }
  }

  private Trace trace(Entry entry) {
    Trace trace = new Trace(entry);
    if (trace.start != null) {
      browser(trace);
    } else if (trace.gateway != null) {
      gateway(trace);
    }
    sync(trace, entry.unit, entry.method, List.of());
    if (entry.fact.predicate().equals(WEBSOCKET_ENDPOINT)) {
      interceptors(trace, entry.unit);
    }
    deliver(trace);
    if (trace.truncated) {
      String after =
          trace.messages >= MAX_MESSAGES ? MAX_MESSAGES + " messages" : MAX_STEPS + " steps";
      // The closing note is drawn past the caps that stopped the trace.
      int added = trace.added;
      int messages = trace.messages;
      trace.truncated = false;
      trace.added = 0;
      trace.messages = 0;
      trace.note(List.of(), "The trace stops here, after " + after + ".", List.of(), List.of());
      trace.added = added + 1;
      trace.messages = messages;
      trace.truncated = true;
    }
    return trace;
  }

  /**
   * The steps before the entry point when the browser starts the flow: the person using the
   * frontend, the frontend's call, and the gateway routing it on, if it goes through one.
   */
  private void browser(Trace trace) {
    Start start = trace.start;
    Entry entry = trace.entry;
    boolean socket = socket(start.link);
    if (start.person != null) {
      trace.message(
          start.person,
          start.frontend,
          "Uses the screens",
          null,
          null,
          List.of(),
          "In a web browser.",
          start.personFacts);
    }
    List<String> info = new ArrayList<>();
    String protocol = attribute(start.link, "protocol");
    if (protocol != null) {
      info.add("Over " + protocol + ".");
    }
    String proxy = attribute(start.link, "proxy");
    if (proxy != null) {
      info.add("Through the proxy " + proxy + " of its server.");
    }
    trace.message(
        start.frontend,
        start.via == null ? entry.unit : start.via,
        socket
            ? "Opens a socket"
            : ("true".equals(attribute(start.link, "navigation"))
                    ? "Navigates the browser to "
                    : "Calls ")
                + methodName(entry.method),
        request(start.link),
        null,
        List.of(),
        info.isEmpty() ? null : String.join(" ", info),
        withEnds(start.link));
    if (start.via == null) {
      return;
    }
    Fact route = start.route;
    List<Fact> facts = route == null ? new ArrayList<>(List.of(start.link)) : withEnds(route);
    List<Fact> plainFacts = List.copyOf(facts);
    String plain = socket ? "Relays the socket" : "Routes to " + methodName(entry.method);
    StringBuilder routeInfo =
        new StringBuilder("Route ")
            .append(RepoSections.attribute(start.link, "throughRoute", "of " + nameOf(start.via)));
    if (route != null && attribute(route, "url") != null) {
      routeInfo.append(" to ").append(attribute(route, "url"));
    }
    if (route != null && attribute(route, "protocol") != null) {
      routeInfo.append(", over ").append(attribute(route, "protocol"));
    }
    routeInfo.append('.');
    Assembler.Phrased phrase = route == null ? null : assembler.phrase(route);
    if (phrase != null) {
      facts.addAll(phrase.facts());
    }
    trace.message(
        start.via,
        entry.unit,
        phrase != null ? phrase.text() : plain,
        attribute(start.link, "reachesEndpoint"),
        null,
        route == null ? List.of() : profiles(route, List.of()),
        routeInfo.toString(),
        facts,
        plain,
        plainFacts);
  }

  /**
   * The step before the entry point when a gateway starts the flow: the gateway routing the request
   * it receives to the unit, with the request as the unit serves it.
   */
  private void gateway(Trace trace) {
    GatewayStart start = trace.gateway;
    Entry entry = trace.entry;
    List<Fact> facts = new ArrayList<>(withEnds(start.routed()));
    if (start.route() != null) {
      facts.addAll(withEnds(start.route()));
    }
    List<Fact> plainFacts = List.copyOf(facts);
    String plain = "Routes to " + methodName(entry.method);
    StringBuilder info = new StringBuilder();
    String url = attribute(start.routed(), "url");
    if (attribute(start.routed(), "route") != null) {
      info.append("Route ").append(attribute(start.routed(), "route"));
      if (url != null) {
        info.append(" to ").append(url);
      }
    } else {
      info.append("Through the discovery locator");
      if (url != null) {
        info.append(", to ").append(url);
      }
    }
    info.append('.');
    Assembler.Phrased phrase = start.route() == null ? null : assembler.phrase(start.route());
    if (phrase != null) {
      facts.addAll(phrase.facts());
    }
    trace.message(
        start.gateway(),
        entry.unit,
        phrase != null ? phrase.text() : plain,
        attribute(start.routed(), "served"),
        null,
        start.route() == null ? List.of() : profiles(start.route(), List.of()),
        info.toString(),
        facts,
        plain,
        plainFacts);
  }

  private static boolean socket(Fact link) {
    return "WebSocket".equals(attribute(link, "protocol"));
  }

  /** The request a frontend's call sends, as it reaches its target: method and path. */
  private static String request(Fact link) {
    String path = attribute(link, "path");
    if (path == null) {
      path = attribute(link, "url");
    }
    String method = attribute(link, "httpMethod");
    return method == null || socket(link) ? path : method + " " + path;
  }

  /** The methods of an interceptor, in the order a channel calls them. */
  private static final List<String> INTERCEPTS =
      List.of(
          "preSend",
          "postSend",
          "afterSendCompletion",
          "preReceive",
          "postReceive",
          "afterReceiveCompletion");

  /**
   * After a client connects to a WebSocket endpoint, the interceptors of the unit's inbound STOMP
   * channel (ADR 0080), which every frame the client sends passes through: each method they
   * declare, in the order the channel calls them.
   */
  private void interceptors(Trace trace, EntityId unit) {
    for (EntityId method : unitMethods(unit)) {
      for (Fact fact : knowledge.attributes(method)) {
        if (!fact.predicate().equals(STOMP_INTERCEPTOR)
            || !"inbound".equals(attribute(fact, "channel"))) {
          continue;
        }
        EntityId type = new EntityId(BuiltInTypes.TYPE, fact.object().value());
        List<String> declared = C4Views.strings(fact, "intercepts");
        for (String name : INTERCEPTS) {
          if (!declared.contains(name)) {
            continue;
          }
          for (EntityId body : knowledge.parts(type, METHOD)) {
            if (!methodName(body).endsWith("." + name)) {
              continue;
            }
            trace.note(
                List.of(unit),
                "Each STOMP frame the client sends passes " + methodName(body) + ".",
                List.of(),
                List.of(fact));
            sync(trace, unit, body, List.of());
          }
        }
      }
    }
  }

  /** Traces the deliveries of the sends so far, after the steps of the code that sent them. */
  private void deliver(Trace trace) {
    while (!trace.later.isEmpty() && !trace.truncated) {
      Later later = trace.later.removeFirst();
      if (later.job != null) {
        Entry job = later.job;
        if (trace.traced.contains(job.key()) || !trace.delivered.add(job.key())) {
          continue;
        }
        trace.note(
            List.of(job.unit),
            "Later: the " + jobName(job) + " runs (" + methodName(job.method) + ").",
            later.profiles,
            List.of(job.fact));
        trace.reached.add(job.key());
        sync(trace, job.unit, job.method, later.profiles);
        continue;
      }
      Fact link = later.link;
      // A send the code makes again, or a listener sending on to itself, is delivered once.
      if (!trace.delivered.add(link.id())) {
        continue;
      }
      EntityId receiver = Knowledge.object(link);
      EntityId handler = parse(attribute(link, "target"));
      String queue = attribute(link, "queue");
      String protocol = attribute(link, "protocol");
      String through = queue == null ? null : nameOf(parse(queue));
      trace.message(
          later.from,
          receiver,
          handler == null ? "Delivers the message" : "Delivers to " + methodName(handler),
          through == null ? protocol : join(protocol, through),
          SequenceDiagram.MessageKind.ASYNC,
          later.profiles,
          deliveryInfo(link),
          withEnds(link));
      if (handler != null) {
        trace.reached.add(receiver + " " + handler);
        sync(trace, receiver, handler, later.profiles);
      }
    }
  }

  /** Something a method does, in the order of the code: a call, or a step out of its unit. */
  private record Event(int line, int order, String id, Runnable action) {}

  /**
   * Follows the method's code inside the unit, adding the steps that leave it. A method is traced
   * each time the code calls it, as the code makes the call each time, except from inside itself: a
   * recursive call is not followed again.
   */
  private void sync(Trace trace, EntityId unit, EntityId method, List<String> outer) {
    String key = unit + " " + method;
    if (trace.truncated || trace.quiet.contains(key)) {
      return;
    }
    if (!trace.stack.add(key)) {
      trace.cut++;
      return;
    }
    trace.traced.add(key);
    int before = trace.added;
    int cut = trace.cut;
    try {
      steps(trace, unit, method, outer);
    } finally {
      trace.stack.remove(key);
    }
    if (trace.added == before && trace.cut == cut) {
      trace.quiet.add(key);
    }
  }

  private void steps(Trace trace, EntityId unit, EntityId method, List<String> outer) {
    List<String> profiles = gate(method, outer);
    List<Event> events = new ArrayList<>();
    for (Fact call : knowledge.relations(method, BuiltInTypes.CALLS)) {
      EntityId callee = Knowledge.object(call);
      EntityId owner = PackageDependencies.ownerType(knowledge, callee);
      if (!knowledge.relations(owner, REPOSITORY_FOR).isEmpty()) {
        events.add(
            new Event(line(call), 0, call.id(), () -> repositoryCall(trace, unit, call, profiles)));
      } else {
        events.add(new Event(line(call), 0, call.id(), () -> call(trace, unit, call, profiles)));
      }
    }
    for (Fact link : linksBySource.getOrDefault(unit + " " + method, List.of())) {
      Runnable action =
          switch (link.predicate()) {
            case C4Views.CALLS_HTTP -> () -> httpCall(trace, unit, link, profiles);
            case C4Views.SENDS_MAIL -> () -> mail(trace, unit, link, profiles);
            case C4Views.PUBLISHES -> () -> send(trace, unit, method, link, profiles);
            case C4Views.UNRESOLVED_LINK -> () -> unresolved(trace, unit, link, profiles);
            default -> null;
          };
      if (action != null) {
        events.add(new Event(line(link), 1, link.id(), action));
      }
    }
    for (Fact push : knowledge.attributes(method)) {
      if (push.predicate().equals(STOMP_SEND)) {
        events.add(
            new Event(line(push), 1, push.id(), () -> stompPush(trace, unit, push, profiles)));
      }
    }
    List<Entry> jobs = jobsByScheduler.getOrDefault(method.toString(), List.of());
    List<Entry> here = jobs.stream().filter(j -> j.unit.equals(unit)).toList();
    for (Entry job : here.isEmpty() ? jobs : here) {
      events.add(
          new Event(
              schedulingLine(method),
              1,
              job.fact.id(),
              () -> schedule(trace, unit, job, profiles)));
    }
    List<Fact> reads = knowledge.relations(method, READS_TABLE);
    if (!reads.isEmpty()) {
      events.add(
          new Event(
              reads.stream().mapToInt(SequenceFlows::line).min().orElseThrow(),
              2,
              reads.getFirst().id(),
              () -> dataAccess(trace, unit, "Reads", reads, profiles)));
    }
    List<Fact> writes = knowledge.relations(method, WRITES_TABLE);
    if (!writes.isEmpty()) {
      events.add(
          new Event(
              writes.stream().mapToInt(SequenceFlows::line).min().orElseThrow(),
              3,
              writes.getFirst().id(),
              () -> dataAccess(trace, unit, "Writes", writes, profiles)));
    }
    events.sort(
        Comparator.comparingInt(Event::line)
            .thenComparingInt(Event::order)
            .thenComparing(Event::id));
    events.forEach(e -> e.action().run());
  }

  /**
   * Follows a call into the code that runs: the method called, or, when the types implementing or
   * extending its type declare it too, each of them as an operand of an {@code alt} frame, since
   * the code does not say which one runs; a note when there are more than {@link
   * #MAX_ALTERNATIVES}.
   */
  private void call(Trace trace, EntityId unit, Fact call, List<String> profiles) {
    List<EntityId> bodies = bodies(Knowledge.object(call));
    if (bodies.size() == 1) {
      sync(trace, unit, bodies.getFirst(), profiles);
      return;
    }
    List<String> names = bodies.stream().map(SequenceFlows::typeName).toList();
    if (bodies.size() > MAX_ALTERNATIVES) {
      trace.note(
          List.of(unit),
          "One of "
              + list(names)
              + " runs "
              + methodName(Knowledge.object(call))
              + " here; the trace does not follow them.",
          profiles,
          List.of(call));
      return;
    }
    List<Item> outer = trace.items;
    List<WordingPrompt.FlowStep> outerOutline = trace.outline;
    Deque<Later> outerLater = trace.later;
    Set<String> outerDelivered = trace.delivered;
    Set<String> delivered = new HashSet<>(outerDelivered);
    List<SequenceDiagram.Operand> operands = new ArrayList<>();
    List<WordingPrompt.FlowStep> alternatives = new ArrayList<>();
    boolean any = false;
    trace.depth += 2;
    for (int i = 0; i < bodies.size(); i++) {
      trace.items = new ArrayList<>();
      trace.outline = new ArrayList<>();
      trace.later = new ArrayDeque<>();
      trace.delivered = new HashSet<>(outerDelivered);
      sync(trace, unit, bodies.get(i), profiles);
      deliver(trace);
      delivered.addAll(trace.delivered);
      any |= !trace.items.isEmpty();
      operands.add(
          new SequenceDiagram.Operand("as " + names.get(i), frames(trace.items), List.of()));
      alternatives.add(
          new WordingPrompt.FlowStep(trace.depth - 1, "As " + names.get(i) + ":", List.of()));
      alternatives.addAll(trace.outline);
    }
    trace.depth -= 2;
    trace.items = outer;
    trace.outline = outerOutline;
    trace.later = outerLater;
    trace.delivered = delivered;
    if (any) {
      trace.outline.add(
          new WordingPrompt.FlowStep(
              trace.depth,
              "One of these runs "
                  + methodName(Knowledge.object(call))
                  + " here; the code does not say which:",
              List.of(call)));
      trace.outline.addAll(alternatives);
      trace.items.add(
          new Item(
              new SequenceDiagram.Frame(
                  SequenceDiagram.Operator.ALT, operands, assembler.facts(List.of(call))),
              profiles));
    }
  }

  /**
   * The methods that may run for a call: the method declared where the call points (or the one it
   * inherits), unless its type is an interface or of unknown kind, and the same method declared by
   * the types below it, through interfaces and abstract or other base classes; the called method if
   * none.
   */
  private List<EntityId> bodies(EntityId callee) {
    EntityId declared = declared(callee);
    EntityId owner = PackageDependencies.ownerType(knowledge, declared);
    int hash = declared.key().indexOf('#');
    // A constructor runs for the class the code names, never for one below it.
    if (owner.equals(declared) || hash < 0 || declared.key().startsWith(CONSTRUCTOR, hash + 1)) {
      return List.of(declared);
    }
    String signature = declared.key().substring(hash);
    List<EntityId> result = new ArrayList<>();
    // The declared method runs only when its type is known to be a class (or enum or record).
    String kind = knowledge.attribute(owner, "kind").orElse("interface");
    if (!kind.equals("interface") && !kind.equals("annotation")) {
      result.add(declared);
    }
    for (EntityId type : subtypes(owner)) {
      EntityId method = new EntityId(METHOD, type.key() + signature);
      if (!knowledge.wholes(method, BuiltInTypes.TYPE).isEmpty()) {
        result.add(method);
      }
    }
    return result.isEmpty() ? List.of(declared) : result;
  }

  /** The method, or the one its type inherits under the same signature, if it declares none. */
  private EntityId declared(EntityId method) {
    if (!knowledge.wholes(method, BuiltInTypes.TYPE).isEmpty()) {
      return method;
    }
    int hash = method.key().indexOf('#');
    if (hash < 0) {
      return method;
    }
    String signature = method.key().substring(hash);
    Deque<EntityId> todo =
        new ArrayDeque<>(List.of(new EntityId(BuiltInTypes.TYPE, method.key().substring(0, hash))));
    Set<EntityId> seen = new HashSet<>();
    while (!todo.isEmpty()) {
      EntityId type = todo.removeFirst();
      if (!seen.add(type)) {
        continue;
      }
      EntityId candidate = new EntityId(METHOD, type.key() + signature);
      if (!knowledge.wholes(candidate, BuiltInTypes.TYPE).isEmpty()) {
        return candidate;
      }
      for (String relation : List.of(BuiltInTypes.EXTENDS, BuiltInTypes.IMPLEMENTS)) {
        knowledge.relations(type, relation).forEach(f -> todo.add(Knowledge.object(f)));
      }
    }
    return method;
  }

  /** The types implementing or extending the type, directly or not, by ID. */
  private SortedSet<EntityId> subtypes(EntityId type) {
    SortedSet<EntityId> result = new TreeSet<>(Knowledge.BY_ID);
    Deque<EntityId> todo = new ArrayDeque<>(List.of(type));
    while (!todo.isEmpty()) {
      EntityId supertype = todo.removeFirst();
      for (String relation : List.of(BuiltInTypes.EXTENDS, BuiltInTypes.IMPLEMENTS)) {
        for (Fact fact : knowledge.withPredicate(relation)) {
          if (Knowledge.isEntity(fact)
              && Knowledge.object(fact).equals(supertype)
              && result.add(fact.subject())) {
            todo.add(fact.subject());
          }
        }
      }
    }
    result.remove(type);
    return result;
  }

  /**
   * The profiles under which the method's code runs: those of a {@code @Profile} gate on it or its
   * type naming profiles to be active, else those of the code calling it. A negated gate ({@code
   * !local}) is not a step's condition: the links it changes say which profiles they miss.
   */
  private List<String> gate(EntityId method, List<String> outer) {
    List<EntityId> gated = new ArrayList<>(List.of(method));
    gated.add(PackageDependencies.ownerType(knowledge, method));
    for (EntityId entity : gated) {
      for (Fact fact : knowledge.attributes(entity)) {
        if (fact.predicate().equals(PROFILE_GATE) && !fact.object().value().contains("!")) {
          List<String> profiles = C4Views.strings(fact, "profiles");
          if (!profiles.isEmpty()) {
            return new ArrayList<>(new TreeSet<>(profiles));
          }
        }
      }
    }
    return outer;
  }

  private List<String> profiles(Fact link, List<String> outer) {
    String profile = attribute(link, "profile");
    return profile == null ? outer : List.of(profile);
  }

  private void httpCall(Trace trace, EntityId unit, Fact link, List<String> outer) {
    EntityId to = Knowledge.object(link);
    List<String> profiles = profiles(link, outer);
    EntityId handler = parse(attribute(link, "target"));
    String endpoint = attribute(link, "endpoint");
    if (endpoint == null) {
      String method = attribute(link, "httpMethod");
      String path = attribute(link, "path");
      endpoint = path == null ? null : method == null ? path : method + " " + path;
    }
    List<String> info = new ArrayList<>();
    String protocol = attribute(link, "protocol");
    if (protocol != null) {
      info.add("Over " + protocol + ".");
    }
    String resolution = C4Views.resolution(link);
    if (resolution != null) {
      info.add(capitalize(resolution) + ".");
    }
    List<Fact> facts = withEnds(link);
    for (Map.Entry<Fact, Fact> alternative : c4.alternativeLinks().entrySet()) {
      if (alternative.getValue().equals(link)) {
        Fact other = alternative.getKey();
        info.add(
            "Under the Spring profile "
                + attribute(other, "profile")
                + " it calls "
                + nameOf(Knowledge.object(other))
                + " instead.");
        facts.add(other);
      }
    }
    List<Fact> plainFacts = List.copyOf(facts);
    String plain = "Calls " + (handler == null ? nameOf(to) : methodName(handler));
    Assembler.Phrased phrase = assembler.phrase(link);
    if (phrase != null) {
      facts.addAll(phrase.facts());
    }
    trace.message(
        unit,
        to,
        phrase != null ? phrase.text() : plain,
        endpoint,
        null,
        profiles,
        info.isEmpty() ? null : String.join(" ", info),
        facts,
        plain,
        plainFacts);
    if (handler != null && !to.type().equals(C4Views.EXTERNAL_SYSTEM)) {
      trace.reached.add(to + " " + handler);
      sync(trace, to, handler, profiles);
    }
  }

  /** A step that sends mail through the mail server: the flow ends in an email. */
  private void mail(Trace trace, EntityId unit, Fact link, List<String> outer) {
    EntityId server = Knowledge.object(link);
    List<String> profiles = profiles(link, outer);
    Assembler.Phrased phrase = assembler.phrase(link);
    List<Fact> facts = withEnds(link);
    List<Fact> plainFacts = List.copyOf(facts);
    String plain = "Sends an email";
    if (phrase != null) {
      facts.addAll(phrase.facts());
    }
    trace.message(
        unit,
        server,
        phrase != null ? phrase.text() : plain,
        attribute(link, "protocol"),
        null,
        profiles,
        null,
        facts,
        plain,
        plainFacts);
  }

  private void send(Trace trace, EntityId unit, EntityId method, Fact link, List<String> outer) {
    EntityId destination = Knowledge.object(link);
    List<String> profiles = profiles(link, outer);
    String key = attribute(link, "routingKey");
    Assembler.Phrased phrase = assembler.phrase(link);
    List<Fact> facts = withEnds(link);
    List<Fact> plainFacts = List.copyOf(facts);
    String plain = key == null ? "Sends a message" : "Sends " + key;
    if (phrase != null) {
      facts.addAll(phrase.facts());
    }
    trace.message(
        unit,
        destination,
        phrase != null ? phrase.text() : plain,
        attribute(link, "protocol"),
        SequenceDiagram.MessageKind.ASYNC,
        profiles,
        C4Views.resolution(link) == null ? null : capitalize(C4Views.resolution(link)) + ".",
        facts,
        plain,
        plainFacts);
    trace.asyncThrough.add(nameOf(destination));
    List<Fact> deliveries =
        linksBySource.getOrDefault(unit + " " + method, List.of()).stream()
            .filter(
                f ->
                    f.predicate().equals(SENDS_MESSAGES_TO)
                        && destination.toString().equals(attribute(f, "destination")))
            .toList();
    if (deliveries.isEmpty()) {
      trace.note(
          List.of(destination),
          "No listener for it was found in the code.",
          profiles,
          List.of(link));
      return;
    }
    SortedSet<String> receivers = new TreeSet<>();
    for (Fact delivery : deliveries) {
      receivers.add(nameOf(Knowledge.object(delivery)));
      trace.later.add(new Later(destination, delivery, profiles(delivery, profiles), null));
    }
    trace.note(
        List.of(unit, destination),
        nameOf(unit)
            + " does not wait: "
            + String.join(" and ", receivers)
            + (receivers.size() == 1 ? " takes" : " take")
            + " the message later.",
        profiles,
        deliveries);
  }

  /**
   * A method scheduling a background job: an async message to the unit that runs the job, a note
   * that the method does not wait, and the job's own steps after the method's, since it runs later.
   */
  private void schedule(Trace trace, EntityId unit, Entry job, List<String> profiles) {
    String kind = ExplorerSections.jobKind(job.fact);
    trace.message(
        unit,
        job.unit,
        "Schedules " + simpleName(job.fact.object().value()),
        kind,
        SequenceDiagram.MessageKind.ASYNC,
        profiles,
        null,
        List.of(job.fact));
    trace.asyncThrough.add("the " + jobName(job));
    trace.note(
        List.of(unit),
        nameOf(unit) + " does not wait: the " + kind + " runs later in " + nameOf(job.unit) + ".",
        profiles,
        List.of(job.fact));
    trace.later.add(new Later(unit, null, profiles, job));
  }

  /** The library calls that schedule a job. */
  private static final Set<String> SCHEDULING_CALLS =
      Set.of("enqueue", "schedule", "scheduleRecurrently", "create", "scheduleJob");

  /**
   * The line of the method's first call scheduling a job through JobRunr or Quartz, so the step
   * comes in the order of the code; after its other steps when there is none.
   */
  private int schedulingLine(EntityId method) {
    int result = Integer.MAX_VALUE;
    for (Fact call : knowledge.relations(method, BuiltInTypes.CALLS)) {
      String key = Knowledge.object(call).key();
      int hash = key.indexOf('#');
      if (hash < 0 || !(key.startsWith("org.jobrunr.") || key.startsWith("org.quartz."))) {
        continue;
      }
      int paren = key.indexOf('(', hash);
      String name = key.substring(hash + 1, paren < 0 ? key.length() : paren);
      if (SCHEDULING_CALLS.contains(name)) {
        result = Math.min(result, line(call));
      }
    }
    return result;
  }

  /** "JobRunr job TaskReminder": the kind of a job and its name. */
  private static String jobName(Entry job) {
    return ExplorerSections.jobKind(job.fact) + " " + simpleName(job.fact.object().value());
  }

  /** A class's simple name when the value is a binary class name, else the value. */
  private static String simpleName(String value) {
    if (!value.matches("[\\w$]+(\\.[\\w$]+)+")) {
      return value;
    }
    return value.substring(value.lastIndexOf('.') + 1).replace('$', '.');
  }

  private String deliveryInfo(Fact link) {
    String binding = bindingInfo(link);
    String resolution = C4Views.resolution(link);
    if (resolution == null) {
      return binding;
    }
    return binding == null
        ? capitalize(resolution) + "."
        : binding + " " + capitalize(resolution) + ".";
  }

  private static String capitalize(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  /**
   * A message pushed to STOMP subscribers: a note over the unit, since the subscribers are clients
   * (a browser) that no fact of the code names; the WebSocket endpoints of the unit say where they
   * connect (ADR 0080).
   */
  private void stompPush(Trace trace, EntityId unit, Fact push, List<String> profiles) {
    List<Fact> facts = new ArrayList<>(List.of(push));
    SortedSet<String> paths = new TreeSet<>();
    for (Fact endpoint : websocketEndpoints(unit)) {
      if ("stomp".equals(attribute(endpoint, "protocol"))) {
        paths.add(endpoint.object().value());
        facts.add(endpoint);
      }
    }
    boolean toUser = "true".equals(attribute(push, "toUser"));
    StringBuilder text =
        new StringBuilder(nameOf(unit))
            .append(
                toUser
                    ? " pushes to the user's STOMP subscribers of "
                    : " pushes to the STOMP subscribers of ")
            .append(push.object().value());
    if (!paths.isEmpty()) {
      text.append(", connected over ").append(String.join(" or ", paths));
    }
    text.append(" [STOMP over WebSocket]. It does not wait for them.");
    trace.note(List.of(unit), text.toString(), profiles, facts);
    trace.pushes++;
  }

  /** The WebSocket endpoints registered by the unit's code. */
  private List<Fact> websocketEndpoints(EntityId unit) {
    List<Fact> result = new ArrayList<>();
    for (EntityId method : unitMethods(unit)) {
      for (Fact fact : knowledge.attributes(method)) {
        if (fact.predicate().equals(WEBSOCKET_ENDPOINT)) {
          result.add(fact);
        }
      }
    }
    return result;
  }

  /** The methods of the unit's code (its modules and the libraries it bundles), by type ID. */
  private List<EntityId> unitMethods(EntityId unit) {
    SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId module : C4Views.codeModules(knowledge, unit)) {
      code.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        code.addAll(knowledge.typesWithin(pkg));
      }
    }
    List<EntityId> result = new ArrayList<>();
    for (EntityId type : code) {
      result.addAll(knowledge.parts(type, METHOD));
    }
    return result;
  }

  private void unresolved(Trace trace, EntityId unit, Fact fact, List<String> profiles) {
    String reason = attribute(fact, "reason");
    StringBuilder text =
        new StringBuilder(nameOf(unit)).append(" calls ").append(fact.object().value());
    text.append(", which Lantern could not link");
    if (reason != null) {
      text.append(" (").append(reason.replace('-', ' '));
      List<String> keys = C4Views.strings(fact, "unresolved");
      if (!keys.isEmpty()) {
        text.append(" ").append(String.join(", ", keys));
      }
      text.append(")");
    }
    text.append("; the trace does not follow it.");
    trace.unresolved.add(fact.object().value());
    trace.note(List.of(), text.toString(), profiles, List.of(fact));
  }

  private void dataAccess(
      Trace trace, EntityId unit, String verb, List<Fact> accesses, List<String> profiles) {
    SortedSet<String> tables = new TreeSet<>();
    accesses.forEach(f -> tables.add(tableName(Knowledge.object(f).toString())));
    List<Fact> facts = new ArrayList<>(accesses);
    List<String> info = new ArrayList<>();
    for (Fact shared : knowledge.relations(unit, C4Views.ACCESSES_DATA_OF)) {
      if (alternatives.contains(shared)) {
        continue;
      }
      SortedSet<String> theirs = new TreeSet<>();
      C4Views.strings(shared, verb.equals("Reads") ? "reads" : "writes")
          .forEach(t -> theirs.add(tableName(t)));
      theirs.retainAll(tables);
      if (!theirs.isEmpty()) {
        facts.add(shared);
        info.add(
            String.join(", ", theirs)
                + (theirs.size() == 1 ? " is a table of " : " are tables of ")
                + nameOf(Knowledge.object(shared))
                + ".");
      }
    }
    Database database = database(unit);
    facts.addAll(database.facts);
    if (database.entity == null) {
      trace.note(
          List.of(unit),
          nameOf(unit)
              + " "
              + verb.toLowerCase(Locale.ROOT)
              + " "
              + String.join(", ", tables)
              + database.unknown,
          profiles,
          facts);
      return;
    }
    trace.data(
        unit,
        database.entity,
        verb,
        List.copyOf(tables),
        database.protocol,
        profiles,
        info.isEmpty() ? null : String.join(" ", info),
        facts);
  }

  private void repositoryCall(Trace trace, EntityId unit, Fact call, List<String> profiles) {
    EntityId callee = Knowledge.object(call);
    Database database = database(unit);
    List<Fact> facts = new ArrayList<>(List.of(call));
    facts.addAll(
        knowledge.relations(PackageDependencies.ownerType(knowledge, callee), REPOSITORY_FOR));
    facts.addAll(database.facts);
    if (database.entity == null) {
      trace.note(
          List.of(unit),
          nameOf(unit) + " calls " + methodName(callee) + database.unknown,
          profiles,
          facts);
      return;
    }
    trace.data(
        unit,
        database.entity,
        "Calls",
        List.of(methodName(callee)),
        database.protocol,
        profiles,
        null,
        facts);
  }

  /**
   * The database a unit's data access reaches, or none when it has not exactly one by default, with
   * the end of a sentence saying why.
   */
  private record Database(EntityId entity, String protocol, List<Fact> facts, String unknown) {}

  private Database database(EntityId unit) {
    Map<EntityId, List<Fact>> databases = new TreeMap<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.relations(unit, C4Views.USES_DATABASE)) {
      if (attribute(fact, "profile") == null) {
        databases.computeIfAbsent(Knowledge.object(fact), d -> new ArrayList<>()).add(fact);
      }
    }
    if (databases.isEmpty()) {
      return new Database(
          null,
          null,
          List.of(),
          "; no datasource of it was found, so which database is not known.");
    }
    if (databases.size() > 1) {
      List<Fact> facts = new ArrayList<>();
      databases.values().forEach(facts::addAll);
      List<String> names = databases.keySet().stream().map(this::nameOf).toList();
      return new Database(
          null, null, facts, " in one of " + list(names) + "; the code does not say which.");
    }
    Map.Entry<EntityId, List<Fact>> only = databases.entrySet().iterator().next();
    return new Database(
        only.getKey(), attribute(only.getValue().getFirst(), "protocol"), only.getValue(), null);
  }

  // Choosing the flows.

  /** The entry points of the flows shown, in the order they are shown. */
  List<Entry> mainEntries() {
    return chosen().stream().map(t -> t.entry).toList();
  }

  /** The flows shown: the highest ranked candidates in scope, none part of another. */
  private List<Trace> chosen() {
    if (chosen == null) {
      chosen = choose();
    }
    return chosen;
  }

  private List<Trace> choose() {
    List<Entry> entries = entries();
    Map<String, List<Entry>> jobs = new HashMap<>();
    for (Entry entry : entries) {
      if (entry.fact.predicate().equals(BACKGROUND_JOB)) {
        for (String by : C4Views.strings(entry.fact, "scheduledBy")) {
          jobs.computeIfAbsent(by, k -> new ArrayList<>()).add(entry);
        }
      }
    }
    jobsByScheduler = jobs;
    List<Trace> candidates = new ArrayList<>();
    for (Entry entry : entries) {
      Trace trace = trace(entry);
      // The gateway's routing to the entry point is no step of the flow's own.
      int own = trace.messages - (trace.gateway == null ? 0 : 1);
      if (own > 0 || trace.pushes > 0 || !trace.unresolved.isEmpty()) {
        candidates.add(trace);
      }
    }
    candidates.sort(
        Comparator.comparingInt(Trace::touches)
            .reversed()
            .thenComparing(t -> t.asyncThrough.isEmpty())
            .thenComparing(t -> t.entry.key()));
    candidates.removeIf(t -> t.participants.stream().noneMatch(scope::contains));
    List<Trace> first = new ArrayList<>();
    // Business weight (ADR 0070): how a person signs in, and the socket a frontend opens for the
    // system to push to it, come before the ranked rest, the best ranked of each.
    for (Predicate<Trace> weighty : List.<Predicate<Trace>>of(this::signsIn, this::opensSocket)) {
      candidates.stream()
          .filter(t -> weighty.test(t) && !first.contains(t))
          .findFirst()
          .ifPresent(first::add);
    }
    List<Trace> kept = new ArrayList<>();
    for (Trace trace : first) {
      candidates.remove(trace);
      consider(kept, trace, true);
    }
    // Then the rest in rank order, what someone asks for before what a listener or a schedule
    // starts; a flow that reaches nothing but databases shows nothing the container
    // diagram does not, so it comes last and is shown only to reach the three a scope shows at
    // least (ADR 0047, amendment for #272).
    candidates.sort(
        Comparator.comparing((Trace t) -> crud(t)).thenComparingInt(SequenceFlows::tier));
    for (Trace trace : candidates) {
      consider(kept, trace, !crud(trace) || kept.size() < MIN_FLOWS);
    }
    return kept;
  }

  /**
   * Whether the flow only reads or writes its unit's own data: it starts at its entry point, not in
   * a browser, and reaches nothing but databases.
   */
  private boolean crud(Trace trace) {
    EntityId gateway = trace.gateway == null ? null : trace.gateway.gateway();
    return trace.start == null
        && trace.pushes == 0
        && trace.unresolved.isEmpty()
        && trace.participants.stream()
            .allMatch(
                p ->
                    p.equals(trace.entry.unit)
                        || p.equals(gateway)
                        || p.type().equals(C4Views.DATABASE));
  }

  /**
   * Shows the flow unless it is part of one shown or of the shape of one shown; a flow that holds
   * flows shown and touches as many takes the first one's place.
   *
   * @param add whether the flow is added when it holds none shown
   */
  private void consider(List<Trace> kept, Trace trace, boolean add) {
    String key = trace.entry.key();
    if (kept.stream().anyMatch(k -> k.reached.contains(key))) {
      return;
    }
    List<Trace> held =
        kept.stream()
            .filter(k -> trace.reached.contains(k.entry.key()) && trace.touches() >= k.touches())
            .toList();
    // A flow started the same way as one already kept (by a request, a message, a schedule...),
    // with its lifelines in the same order, is of its shape: the same picture with other labels,
    // so it is shown once (ADR 0070).
    boolean sameShape = kept.stream().anyMatch(k -> shape(k).equals(shape(trace)));
    // A containing flow takes the place of the flows it holds, unless another kept flow, not one
    // it holds, already has its shape.
    boolean shapeElsewhere =
        kept.stream().anyMatch(k -> !held.contains(k) && shape(k).equals(shape(trace)));
    if (!held.isEmpty() && !shapeElsewhere) {
      kept.set(kept.indexOf(held.getFirst()), trace);
      kept.removeAll(held.subList(1, held.size()));
    } else if (held.isEmpty() && !sameShape && add && kept.size() < MAX_FLOWS) {
      kept.add(trace);
    }
  }

  /**
   * How much the flow is a business scenario: 0 for what someone asks for (a request, a socket or a
   * STOMP message from a client), 1 for a message a listener takes, 2 for a schedule or a job that
   * runs apart from any request.
   */
  private static int tier(Trace trace) {
    return switch (trace.entry.fact.predicate()) {
      case ENDPOINT, WEBSOCKET_ENDPOINT, STOMP_MAPPING -> 0;
      case MESSAGE_LISTENER -> 1;
      default -> 2;
    };
  }

  /** What makes two flows look the same: how they start and their lifelines, in order. */
  private static List<Object> shape(Trace trace) {
    return List.of(trace.entry.fact.predicate(), List.copyOf(trace.participants));
  }

  /**
   * The path segments of a request that signs a person in, as ADR 0074 reads them: the same words
   * as {@code PeopleLinker.SIGN_IN} in lantern-link, which this module does not depend on.
   */
  private static final Set<String> SIGN_IN =
      Set.of("sign-in", "signin", "sign_in", "login", "log-in", "authenticate");

  /**
   * Whether the flow is a request that signs a person in: the request a frontend sends, when the
   * flow starts in the browser with a person, else the endpoint's own request.
   */
  private boolean signsIn(Trace trace) {
    String path;
    if (trace.start != null) {
      if (trace.start.person == null || socket(trace.start.link)) {
        return false;
      }
      path = attribute(trace.start.link, "path");
    } else if (trace.entry.fact.predicate().equals(ENDPOINT)) {
      String request = trace.entry.fact.object().value();
      path = request.substring(request.indexOf(' ') + 1);
    } else {
      return false;
    }
    if (path == null) {
      return false;
    }
    for (String segment : path.toLowerCase(Locale.ROOT).split("[/?]")) {
      if (SIGN_IN.contains(segment)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the flow starts with a socket a frontend opens. */
  private boolean opensSocket(Trace trace) {
    return trace.start != null && socket(trace.start.link);
  }

  /**
   * One subsection per flow shown, each holding its prose, its trigger and its sequence diagram;
   * none when no entry point reaches outside its unit.
   *
   * @param section the section's ID
   * @param blocks receives the section's own blocks: a paragraph when there is no flow
   */
  List<Section> flows(String section, List<Block> blocks) {
    List<Trace> kept = chosen();
    if (kept.isEmpty()) {
      // A note: what the code does not show cites no fact (Quality bar, ADR 0046).
      blocks.add(
          new Callout(
              assembler.id(section, "none"),
              Callout.Variant.NOTE,
              null,
              null,
              List.of(
                  TextRun.of(
                      "No entry point (HTTP endpoint, message listener, STOMP handler, scheduled"
                          + " or background job) was found"
                          + " whose code reaches another container.")),
              List.of()));
      return List.of();
    }
    List<Section> result = new ArrayList<>();
    for (Trace trace : kept) {
      result.add(flowSection(section, trace));
    }
    return result;
  }

  /** A flow's title, its trigger as the overview names it, and the clause saying what starts it. */
  private record Heading(String title, String trigger, String starts) {}

  private Heading heading(Trace trace) {
    Heading heading = heading(trace.entry);
    Start start = trace.start;
    Entry entry = trace.entry;
    if (start == null && trace.gateway != null) {
      String request = trace.gateway.routed().object().value();
      String gateway = nameOf(trace.gateway.gateway());
      return new Heading(
          request + " on " + gateway,
          request + " to " + gateway + " (" + methodName(entry.method) + ")",
          gateway + " receives " + request + " and routes it to " + nameOf(entry.unit));
    }
    if (start == null) {
      return heading;
    }
    boolean socket = socket(start.link);
    String request = request(start.link);
    StringBuilder starts = new StringBuilder();
    if (start.person != null) {
      starts
          .append(nameOf(start.person))
          .append(", in ")
          .append(nameOf(start.frontend))
          .append(',');
    } else {
      starts.append(nameOf(start.frontend));
    }
    starts.append(socket ? " opens a socket to " : " sends ").append(request);
    if (start.via != null) {
      starts
          .append(", which ")
          .append(nameOf(start.via))
          .append(socket ? " relays to " : " routes to ")
          .append(nameOf(entry.unit));
    } else {
      starts.append(" to ").append(nameOf(entry.unit));
    }
    return new Heading(
        heading.title(),
        request + " from " + nameOf(start.frontend) + " (" + methodName(entry.method) + ")",
        starts.toString());
  }

  private Heading heading(Entry entry) {
    String handler = methodName(entry.method);
    String value = entry.fact.object().value();
    return switch (entry.fact.predicate()) {
      case ENDPOINT ->
          new Heading(
              value + " on " + nameOf(entry.unit),
              value + " (" + handler + ")",
              nameOf(entry.unit) + " receives " + value);
      case MESSAGE_LISTENER ->
          new Heading(
              "Message on " + value + " to " + nameOf(entry.unit),
              "A message on " + value + " (" + handler + ")",
              "a message arrives on " + value + " for " + nameOf(entry.unit));
      case STOMP_MAPPING -> {
        boolean subscribe = "subscribe".equals(attribute(entry.fact, "kind"));
        String what = subscribe ? "STOMP subscription to " : "STOMP message to ";
        yield new Heading(
            what + value + " on " + nameOf(entry.unit),
            "A " + what + value + " (" + handler + ")",
            subscribe
                ? "a client subscribes to " + value + " on " + nameOf(entry.unit)
                : "a client sends a STOMP message to " + value + " on " + nameOf(entry.unit));
      }
      case WEBSOCKET_ENDPOINT ->
          new Heading(
              "WebSocket connection to " + value + " on " + nameOf(entry.unit),
              "A WebSocket connection to " + value + " (" + handler + ")",
              "a client connects to " + value + " on " + nameOf(entry.unit));
      default -> {
        String kind = attribute(entry.fact, "trigger");
        if (kind == null) {
          kind = attribute(entry.fact, "kind");
        }
        String schedule = (kind == null ? "" : kind + " ") + value;
        String framework = attribute(entry.fact, "framework");
        if (framework != null) {
          // A JobRunr or Quartz job: named by its job, or by its cron when that is the value.
          String job = ExplorerSections.jobKind(entry.fact);
          boolean timed = "cron".equals(kind) || "interval".equals(kind);
          String named = timed ? job + " on the schedule " + schedule : job + " " + value;
          yield new Heading(
              handler + " on its schedule in " + nameOf(entry.unit),
              "The " + named + " (" + handler + ")",
              "the " + named + " runs in " + nameOf(entry.unit));
        }
        yield new Heading(
            handler + " on its schedule in " + nameOf(entry.unit),
            "The schedule " + schedule + " (" + handler + ")",
            "the schedule " + schedule + " fires in " + nameOf(entry.unit));
      }
    };
  }

  /** Words of a type's name that say what kind of class it is, not what it is about. */
  private static final Set<String> ROLE_WORDS =
      Set.of(
          "api",
          "consumer",
          "controller",
          "endpoint",
          "endpoints",
          "handler",
          "impl",
          "job",
          "jobs",
          "listener",
          "resource",
          "rest",
          "scheduler",
          "service",
          "task",
          "web");

  /**
   * The scenario a flow is, as the name of its entry point's method says it, such as "Create
   * organization" for {@code createOrganization}; for a method of one word, such as {@code
   * Status.get}, with the words of its type that do not say what kind of class it is ("Get
   * status"). The LLM words a better one where it can (ADR 0047, amendment for #272).
   */
  static String scenario(Entry entry) {
    String name = methodName(entry.method);
    int dot = name.lastIndexOf('.');
    List<String> words = words(name.substring(dot + 1));
    if (words.size() == 1 && dot > 0) {
      String type = name.substring(0, dot);
      words.addAll(
          words(type.substring(type.lastIndexOf('.') + 1)).stream()
              .filter(w -> !ROLE_WORDS.contains(w))
              .toList());
    }
    String text = String.join(" ", words);
    return text.isEmpty() ? name : Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  /** The words of a Java name in lower case: {@code sendHTTPRequest} as send, http, request. */
  private static List<String> words(String name) {
    List<String> result = new ArrayList<>();
    for (String word :
        name.replace('$', '_').split("_+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
      if (!word.isEmpty()) {
        result.add(word.toLowerCase(Locale.ROOT));
      }
    }
    return result;
  }

  /**
   * The prompt asking the LLM to word the flow (ADR 0058): what starts it and its steps by the
   * facts behind them, without the LLM's intent phrases, so it rests on facts only and does not
   * change with them.
   */
  private WordingPrompt.Question question(Trace trace) {
    Entry entry = trace.entry;
    Heading heading = heading(trace);
    return WordingPrompt.flow(
        entry.method,
        heading.title(),
        heading.starts() + " (" + methodName(entry.method) + ")",
        entry.fact,
        trace.outline);
  }

  /** The prompts asking the LLM to word the flows shown (ADR 0058), in the order shown. */
  List<WordingPrompt.Question> questions() {
    return chosen().stream().map(this::question).toList();
  }

  private Section flowSection(String section, Trace trace) {
    Entry entry = trace.entry;
    String handler = methodName(entry.method);
    String id = assembler.id(section, nameOf(entry.unit) + "." + handler);
    Heading heading = heading(trace);
    String starts = heading.starts();
    // The LLM's title, walkthrough and conditions, where it worded this flow as traced here (ADR
    // 0058); else the scenario as the entry point's name says it.
    Wordings.Flow worded = assembler.flow(Wordings.flowKey(question(trace).user()));
    String scenario = worded == null || worded.title() == null ? scenario(entry) : worded.title();
    String title = scenario + " (" + heading.title() + ")";
    Assembler.Text walkthrough =
        worded == null ? null : assembler.worded(entry.method, worded.walkthrough());
    Assembler.Text preconditions =
        worded == null ? null : assembler.worded(entry.method, worded.preconditions());
    Assembler.Text postconditions =
        worded == null ? null : assembler.worded(entry.method, worded.postconditions());
    List<Fact> rowFacts = new ArrayList<>(List.of(entry.fact));
    for (Assembler.Text text : Arrays.asList(preconditions, postconditions)) {
      if (text != null) {
        text.facts().forEach(f -> rowFacts.add(knowledge.fact(f)));
      }
    }
    List<Block> blocks = new ArrayList<>();
    String proseId = assembler.id(id, "prose");
    String prose = prose(trace, starts);
    blocks.add(
        new Table(
            assembler.id(id, "trigger"),
            "Overview",
            List.of(
                new Table.Column("Trigger"),
                new Table.Column("Pre-conditions"),
                new Table.Column("Post-conditions")),
            List.of(
                new Table.Row(
                    null,
                    List.of(
                        List.of(TextRun.of(heading.trigger())),
                        preconditions == null
                            ? List.<Inline>of(TextRun.of(UNKNOWN))
                            : preconditions.content(),
                        postconditions == null
                            ? List.<Inline>of(TextRun.of(UNKNOWN))
                            : postconditions.content()),
                    assembler.facts(rowFacts))),
            List.of()));
    List<SequenceDiagram.Step> steps = new ArrayList<>();
    steps.add(
        new SequenceDiagram.Note(
            List.of(),
            List.of(TextRun.of("Starts when " + starts + " (" + handler + ").")),
            assembler.facts(List.of(entry.fact))));
    steps.addAll(frames(trace.items));
    // The prose says what the steps show, so it cites their facts (Quality bar, ADR 0046).
    Set<String> proseFacts = new TreeSet<>();
    collectFacts(steps, proseFacts);
    if (walkthrough == null) {
      blocks.addFirst(new Paragraph(proseId, List.of(TextRun.of(prose)), List.copyOf(proseFacts)));
    } else {
      proseFacts.addAll(walkthrough.facts());
      blocks.addFirst(new Paragraph(proseId, walkthrough.content(), List.copyOf(proseFacts)));
    }
    List<SequenceDiagram.Participant> participants = new ArrayList<>();
    for (EntityId participant : trace.participants) {
      assembler.entity(participant);
      participants.add(
          new SequenceDiagram.Participant(
              participant,
              null,
              c4.technology(participant),
              null,
              assembler.facts(c4.containerFacts(participant))));
    }
    blocks.add(
        new SequenceDiagram(
            assembler.id(id, "diagram"),
            title,
            participants,
            steps,
            assembler.facts(List.of(entry.fact))));
    return new Section(
        id, title, SectionKind.SEQUENCES.levels(), null, List.of(), blocks, List.of());
  }

  private static void collectFacts(List<SequenceDiagram.Step> steps, Set<String> into) {
    for (SequenceDiagram.Step step : steps) {
      into.addAll(step.facts());
      if (step instanceof SequenceDiagram.Frame frame) {
        for (SequenceDiagram.Operand operand : frame.operands()) {
          into.addAll(operand.facts());
          collectFacts(operand.steps(), into);
        }
      }
    }
  }

  /** Two to four sentences of what the flow does, from what the trace found. */
  private String prose(Trace trace, String starts) {
    StringBuilder text =
        new StringBuilder("Starts when ")
            .append(starts)
            .append(", handled by ")
            .append(methodName(trace.entry.method))
            .append('.');
    List<String> others = new ArrayList<>();
    // What starts it in the browser is named already.
    Set<EntityId> named = new HashSet<>(List.of(trace.entry.unit));
    if (trace.gateway != null) {
      named.add(trace.gateway.gateway());
    }
    if (trace.start != null) {
      named.add(trace.start.frontend);
      if (trace.start.person != null) {
        named.add(trace.start.person);
      }
      if (trace.start.via != null) {
        named.add(trace.start.via);
      }
    }
    trace.participants.stream().filter(p -> !named.contains(p)).forEach(p -> others.add(nameOf(p)));
    if (!others.isEmpty()) {
      text.append(" It reaches ").append(list(others)).append('.');
    }
    if (!trace.asyncThrough.isEmpty()) {
      text.append(" It hands work on asynchronously through ")
          .append(list(new ArrayList<>(trace.asyncThrough)))
          .append('.');
    }
    if (!trace.unresolved.isEmpty()) {
      text.append(trace.unresolved.size() == 1 ? " One call" : " " + trace.unresolved.size())
          .append(trace.unresolved.size() == 1 ? " could" : " calls could")
          .append(" not be followed: ")
          .append(list(trace.unresolved))
          .append('.');
    }
    return text.toString();
  }

  /**
   * The steps, with each run of steps that happen only under the same profiles in an {@code opt}
   * frame naming them (one level of fragments, as the design asks).
   */
  private static List<SequenceDiagram.Step> frames(List<Item> items) {
    List<SequenceDiagram.Step> result = new ArrayList<>();
    int i = 0;
    while (i < items.size()) {
      List<String> profiles = items.get(i).profiles();
      if (profiles.isEmpty()) {
        result.add(items.get(i).step());
        i++;
        continue;
      }
      List<SequenceDiagram.Step> inside = new ArrayList<>();
      Set<String> facts = new TreeSet<>();
      while (i < items.size() && items.get(i).profiles().equals(profiles)) {
        inside.add(items.get(i).step());
        facts.addAll(items.get(i).step().facts());
        i++;
      }
      result.add(
          new SequenceDiagram.Frame(
              SequenceDiagram.Operator.OPT,
              List.of(
                  new SequenceDiagram.Operand(
                      "Spring profile" + (profiles.size() > 1 ? "s " : " ") + list(profiles),
                      inside,
                      List.of())),
              List.copyOf(facts)));
    }
    return result;
  }

  // Helpers.

  private String nameOf(EntityId entity) {
    assembler.entity(entity);
    return assembler.name(entity);
  }

  /** A link's fact with the facts on both its ends. */
  private List<Fact> withEnds(Fact link) {
    List<Fact> facts = new ArrayList<>(List.of(link));
    for (String id : C4Views.strings(link, "facts")) {
      Fact end = knowledge.fact(id);
      if (end != null) {
        facts.add(end);
      }
    }
    return facts;
  }

  private static String bindingInfo(Fact link) {
    String key = attribute(link, "routingKey");
    String binding = attribute(link, "bindingKey");
    if (key == null || binding == null) {
      return null;
    }
    return "Routing key " + key + ", bound with " + binding + ".";
  }

  /** {@code Type.method} of a method ID, without parameters or package. */
  static String methodName(EntityId method) {
    String key = method.key();
    int hash = key.indexOf('#');
    if (hash < 0) {
      return key.substring(key.lastIndexOf('.') + 1);
    }
    String type = key.substring(0, hash);
    String name = key.substring(hash + 1);
    int paren = name.indexOf('(');
    if (paren >= 0) {
      name = name.substring(0, paren);
    }
    return type.substring(type.lastIndexOf('.') + 1).replace('$', '.') + "." + name;
  }

  /** The simple name of the type declaring a method. */
  private static String typeName(EntityId method) {
    String key = method.key();
    String type = key.substring(0, Math.max(key.indexOf('#'), 0));
    return type.substring(type.lastIndexOf('.') + 1).replace('$', '.');
  }

  private static String tableName(String table) {
    return table.substring(table.lastIndexOf(':') + 1);
  }

  private static EntityId parse(String id) {
    if (id == null) {
      return null;
    }
    try {
      return EntityId.parse(id);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static int line(Fact fact) {
    return fact.evidence().lines() == null ? Integer.MAX_VALUE : fact.evidence().lines().start();
  }

  private static String join(String a, String b) {
    return a == null ? b : a + " · " + b;
  }

  private static String list(List<String> names) {
    if (names.size() == 1) {
      return names.getFirst();
    }
    return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }

  private static String attribute(Fact fact, String name) {
    return RepoSections.attribute(fact, name, null);
  }
}
