package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.derive.FlowModel.GatewayStart;
import io.lantern.derive.FlowModel.Operand;
import io.lantern.derive.FlowModel.Part;
import io.lantern.derive.FlowModel.Start;
import io.lantern.derive.FlowModel.Step;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Traces the flows of the architecture model (#618), by {@code docs/design.md} (Composition,
 * Sequence diagrams): each from an entry point (an HTTP endpoint, a message listener or a scheduled
 * job) through the call graph inside its deployable unit, keeping only the steps that cross a
 * container boundary (HTTP calls, sends and data access), and stitched across units through the
 * linker's links (ADR 0042): an HTTP call goes on in the endpoint it reaches, a send in each
 * listener it reaches, after the sender's own steps, since it happens later. Nothing is invented: a
 * step is traced only for a fact, and a call Lantern could not link ends there with a note.
 *
 * <p>It records what each step is and the facts it rests on; the composer words and draws it.
 */
final class FlowTracer {

  /** The most implementations a call is traced into, as the operands of one frame. */
  static final int MAX_ALTERNATIVES = 4;

  /** The most messages one flow has before the trace stops. */
  static final int MAX_MESSAGES = 40;

  /** The most steps, notes included, one flow has before the trace stops. */
  static final int MAX_STEPS = 2 * MAX_MESSAGES;

  private static final String METHOD = "method";

  /** The name of a constructor in a method's ID (ADR 0007). */
  private static final String CONSTRUCTOR = "<init>(";

  static final String ENDPOINT = "endpoint";
  static final String MESSAGE_LISTENER = "message-listener";
  static final String SCHEDULED = "scheduled";
  static final String STOMP_MAPPING = "stomp-mapping";
  private static final String STOMP_SEND = "stomp-send";
  static final String WEBSOCKET_ENDPOINT = "websocket-endpoint";
  private static final String STOMP_INTERCEPTOR = "stomp-interceptor";
  private static final String ROUTED_ENDPOINT = "routed-endpoint";
  static final String BACKGROUND_JOB = "background-job";
  private static final String PROFILE_GATE = "profile-gate";
  private static final String SENDS_MESSAGES_TO = "SENDS_MESSAGES_TO";
  private static final String REPOSITORY_FOR = "REPOSITORY_FOR";
  private static final String READS_TABLE = "READS_TABLE";
  private static final String WRITES_TABLE = "WRITES_TABLE";
  private static final String CALLS_HTTP = "CALLS_HTTP";
  private static final String SENDS_MAIL = "SENDS_MAIL";
  private static final String PUBLISHES = "PUBLISHES";
  private static final String UNRESOLVED_LINK = "unresolved-link";
  private static final String USES_DATABASE = "USES_DATABASE";
  private static final String ACCESSES_DATA_OF = "ACCESSES_DATA_OF";
  private static final String EXTERNAL_SYSTEM = "external-system";

  private final Knowledge knowledge;
  private final Set<Fact> alternatives;
  private final Map<Fact, Fact> alternativeLinks;

  /** The links of each unit, by the code on the unit's end. */
  private final Map<String, List<Fact>> linksBySource = new HashMap<>();

  /** The background jobs a method schedules, by the method's ID (the jobs' {@code scheduledBy}). */
  private Map<String, List<Entry>> jobsByScheduler = Map.of();

  /** The browser starts of the entry points frontends' calls reach, by {@link Entry#key()}. */
  private final Map<String, Start> starts = new HashMap<>();

  /** The person's facts of each browser start, by {@link Entry#key()}. */
  private final Map<String, List<Fact>> personFacts = new HashMap<>();

  /** The gateways' {@code routed-endpoint} facts, by the unit and handler method they reach. */
  private final Map<String, List<Fact>> routedEndpoints = new HashMap<>();

  FlowTracer(Knowledge knowledge) {
    this.knowledge = knowledge;
    this.alternativeLinks = Screens.alternatives(knowledge);
    this.alternatives = alternativeLinks.keySet();
    for (EntityId unit : Screens.units(knowledge)) {
      for (Fact fact : knowledge.about(unit)) {
        String source = attribute(fact, "source");
        if (source != null
            && !alternatives.contains(fact)
            && !(fact.predicate().equals(UNRESOLVED_LINK) && knowledge.resolved(fact))) {
          linksBySource.computeIfAbsent(unit + " " + source, k -> new ArrayList<>()).add(fact);
        }
      }
    }
    for (EntityId frontend : Screens.frontends(knowledge)) {
      starts(frontend);
    }
    for (EntityId gateway : Screens.units(knowledge)) {
      for (Fact fact : knowledge.attributes(gateway)) {
        if (fact.predicate().equals(ROUTED_ENDPOINT)
            && Knowledge.view(fact) == null
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
            .filter(f -> Screens.strings(f, "facts").contains(entry.fact.id()))
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
            : knowledge.relations(routed.subject(), CALLS_HTTP).stream()
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
   * first call in the code for each, with the person who uses the frontend ({@link Screens#user}).
   */
  private void starts(EntityId frontend) {
    Map.Entry<EntityId, List<Fact>> user = Screens.user(knowledge, frontend, Set.of());
    EntityId person = user == null ? null : user.getKey();
    List<Fact> facts = user == null ? List.of() : user.getValue();
    List<Fact> calls =
        knowledge.relations(frontend, CALLS_HTTP).stream()
            .filter(f -> !alternatives.contains(f) && attribute(f, "reaches") != null)
            .sorted(
                Comparator.comparing((Fact f) -> f.evidence().path())
                    .thenComparingInt(FlowTracer::line)
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
            knowledge.relations(via, CALLS_HTTP).stream()
                .filter(
                    f ->
                        !alternatives.contains(f)
                            && Knowledge.object(f).equals(unit)
                            && id != null
                            && id.equals(attribute(f, "route")))
                .findFirst()
                .orElse(null);
      }
      if (starts.putIfAbsent(unit + " " + handler, new Start(person, frontend, call, via, route))
          == null) {
        personFacts.put(unit + " " + handler, facts);
      }
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
    for (EntityId unit : Screens.units(knowledge)) {
      for (EntityId method : unitMethods(unit)) {
        Start start = starts.get(unit + " " + method);
        String reached = start == null ? null : attribute(start.link(), "reachesEndpoint");
        knowledge.attributes(method).stream()
            // Of a handler's endpoints, the one the browser's call reaches; else by the kind of
            // trigger, then its text, so the trigger that names a method's flow does not depend
            // on the facts' IDs (#545).
            .sorted(
                Comparator.comparing(
                        (Fact f) ->
                            !(f.predicate().equals(ENDPOINT) && f.object().value().equals(reached)))
                    .thenComparingInt(FlowTracer::triggerRank)
                    .thenComparing(f -> f.object().value())
                    .thenComparing(Fact::id))
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
    return result;
  }

  /**
   * Which trigger names a flow when one method has several: a request (an endpoint, a WebSocket
   * endpoint, a STOMP message), then a message, then a schedule or a background job.
   */
  private static int triggerRank(Fact trigger) {
    return switch (trigger.predicate()) {
      case ENDPOINT -> 0;
      case WEBSOCKET_ENDPOINT -> 1;
      case STOMP_MAPPING -> 2;
      case MESSAGE_LISTENER -> 3;
      case SCHEDULED -> 4;
      case BACKGROUND_JOB -> 5;
      default -> 6;
    };
  }

  /**
   * The flows of every entry point whose code reaches outside its unit (or pushes to STOMP
   * subscribers, or calls what Lantern could not link), each with its steps, in entry-point order.
   *
   * @param id the flow entity of an entry point's unit and trigger fact
   */
  List<FlowModel.Flow> trace(java.util.function.BiFunction<EntityId, Fact, EntityId> id) {
    List<Entry> entries = entries();
    Map<String, List<Entry>> jobs = new HashMap<>();
    for (Entry entry : entries) {
      if (entry.fact.predicate().equals(BACKGROUND_JOB)) {
        for (String by : Screens.strings(entry.fact, "scheduledBy")) {
          jobs.computeIfAbsent(by, k -> new ArrayList<>()).add(entry);
        }
      }
    }
    jobsByScheduler = jobs;
    List<FlowModel.Flow> flows = new ArrayList<>();
    for (Entry entry : entries) {
      Trace trace = trace(entry);
      // The gateway's routing to the entry point is no step of the flow's own.
      int own = trace.messages - (trace.gateway == null ? 0 : 1);
      if (own > 0 || trace.pushes > 0 || !trace.unresolved.isEmpty()) {
        flows.add(trace.flow(id.apply(entry.unit, entry.fact)));
      }
    }
    return flows;
  }

  // Tracing.

  /** A step being traced; a data access run grows while the next access joins it. */
  private static final class Node {
    final String action;
    final EntityId from;
    final EntityId to;
    final String kind;
    final List<String> profiles;
    final List<EntityId> over;
    final LinkedHashSet<Fact> facts;
    final List<String> detail;
    final List<Part> parts = new ArrayList<>();
    final List<OperandNode> operands = new ArrayList<>();

    Node(
        String action,
        EntityId from,
        EntityId to,
        String kind,
        List<String> profiles,
        List<EntityId> over,
        List<Fact> facts,
        List<String> detail) {
      this.action = action;
      this.from = from;
      this.to = to;
      this.kind = kind;
      this.profiles = List.copyOf(profiles);
      this.over = List.copyOf(over);
      this.facts = new LinkedHashSet<>(facts);
      this.detail = new ArrayList<>(detail);
    }

    Step step() {
      return new Step(
          action,
          from,
          to,
          kind,
          profiles,
          over,
          List.copyOf(facts),
          detail,
          parts,
          operands.stream().map(o -> new Operand(o.body, steps(o.steps))).toList());
    }

    static List<Step> steps(List<Node> nodes) {
      return nodes.stream().map(Node::step).toList();
    }
  }

  private record OperandNode(EntityId body, List<Node> steps) {}

  /**
   * A send's delivery to a listener, or a background job's run, traced after the steps of the code
   * that sent the message or scheduled the job.
   *
   * @param link the delivery's link; null for a job
   * @param job the job that runs; null for a delivery
   */
  private record Later(EntityId from, Fact link, List<String> profiles, Entry job) {}

  /** The data access the last step traced, which the next one to the same database joins. */
  private record DataRun(
      List<Node> items,
      int item,
      EntityId unit,
      EntityId database,
      List<String> profiles,
      String code) {}

  /** One flow being traced. */
  private final class Trace {
    final Entry entry;

    /** How the browser starts the flow; null when it starts at its entry point. */
    final Start start;

    /** How a gateway starts the flow, when the browser does not; null when none does. */
    final GatewayStart gateway;

    List<Node> items = new ArrayList<>();
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

    DataRun data;

    Deque<Later> later = new ArrayDeque<>();
    final Set<String> reached = new HashSet<>();
    final Set<EntityId> asyncDestinations = new TreeSet<>(Knowledge.BY_ID);
    final Set<Fact> asyncJobs = new LinkedHashSet<>();
    final List<String> unresolved = new ArrayList<>();

    int messages;

    /** How many pushes to STOMP subscribers the flow shows, which leave the unit as notes. */
    int pushes;

    boolean truncated;

    Trace(Entry entry) {
      this.entry = entry;
      this.start = starts.get(entry.key());
      this.gateway = start == null ? gatewayStart(entry) : null;
      if (start != null) {
        if (start.person() != null) {
          participants.add(start.person());
        }
        participants.add(start.frontend());
        if (start.via() != null) {
          participants.add(start.via());
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

    /** Adds a message; its kind is {@link FlowModel#SYNC} or {@link FlowModel#ASYNC}. */
    Node message(
        String action,
        EntityId from,
        EntityId to,
        String kind,
        List<String> profiles,
        List<Fact> facts,
        List<String> detail) {
      if (full()) {
        return null;
      }
      messages++;
      added++;
      data = null;
      if (from != null) {
        participants.add(from);
      }
      if (to != null) {
        participants.add(to);
      }
      Node node = new Node(action, from, to, kind, profiles, List.of(), facts, detail);
      items.add(node);
      return node;
    }

    Node note(
        String action,
        List<EntityId> over,
        List<String> profiles,
        List<Fact> facts,
        List<String> detail) {
      if (full()) {
        return null;
      }
      added++;
      data = null;
      participants.addAll(over);
      Node node =
          new Node(
              action,
              null,
              null,
              FlowModel.NOTE,
              profiles,
              List.copyOf(new LinkedHashSet<>(over)),
              facts,
              detail);
      items.add(node);
      return node;
    }

    /**
     * Traces data access of the unit in the database: a message, or, when the step before is the
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
        List<Fact> facts) {
      DataRun run = data;
      boolean joins =
          run != null
              && !truncated
              && run.items == items
              && run.unit.equals(unit)
              && run.database.equals(database)
              && run.profiles.equals(profiles)
              && Objects.equals(run.code, code)
              && items.size() == run.item + 1;
      if (!joins) {
        Node node =
            message(
                FlowModel.DATA,
                unit,
                database,
                FlowModel.SYNC,
                profiles,
                facts,
                code == null ? List.of() : List.of(code));
        if (node != null) {
          node.parts.add(new Part(verb, List.copyOf(what)));
          data = new DataRun(items, items.size() - 1, unit, database, profiles, code);
        }
        return;
      }
      Node node = items.get(run.item);
      node.parts.add(new Part(verb, List.copyOf(what)));
      node.facts.addAll(facts);
      added++;
    }

    FlowModel.Flow flow(EntityId id) {
      return new FlowModel.Flow(
          id,
          entry.unit,
          entry.method,
          entry.fact,
          start,
          gateway,
          Node.steps(items),
          List.copyOf(participants),
          List.copyOf(asyncDestinations),
          List.copyOf(asyncJobs),
          new ArrayList<>(new TreeSet<>(reached)),
          List.copyOf(unresolved),
          pushes,
          messages,
          truncated);
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
      // The closing note is traced past the bounds that stopped the trace.
      String after = trace.messages >= MAX_MESSAGES ? "messages" : "steps";
      trace.items.add(
          new Node(
              FlowModel.TRUNCATED,
              null,
              null,
              FlowModel.NOTE,
              List.of(),
              List.of(),
              List.of(),
              List.of(after)));
      trace.added++;
      trace.data = null;
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
    if (start.person() != null) {
      trace.message(
          FlowModel.USES_SCREENS,
          start.person(),
          start.frontend(),
          FlowModel.SYNC,
          List.of(),
          personFacts.get(entry.key()),
          List.of());
    }
    trace.message(
        FlowModel.FRONTEND_CALL,
        start.frontend(),
        start.via() == null ? entry.unit : start.via(),
        FlowModel.SYNC,
        List.of(),
        withEnds(start.link()),
        List.of());
    if (start.via() == null) {
      return;
    }
    Fact route = start.route();
    trace.message(
        FlowModel.ROUTE,
        start.via(),
        entry.unit,
        FlowModel.SYNC,
        route == null ? List.of() : profiles(route, List.of()),
        route == null ? List.of(start.link()) : withEnds(route),
        List.of());
  }

  /**
   * The step before the entry point when a gateway starts the flow: the gateway routing the request
   * it receives to the unit.
   */
  private void gateway(Trace trace) {
    GatewayStart start = trace.gateway;
    List<Fact> facts = new ArrayList<>(withEnds(start.routed()));
    if (start.route() != null) {
      facts.addAll(withEnds(start.route()));
    }
    trace.message(
        FlowModel.GATEWAY_ROUTE,
        start.gateway(),
        trace.entry.unit,
        FlowModel.SYNC,
        start.route() == null ? List.of() : profiles(start.route(), List.of()),
        facts,
        List.of());
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
        List<String> declared = Screens.strings(fact, "intercepts");
        for (String name : INTERCEPTS) {
          if (!declared.contains(name)) {
            continue;
          }
          for (EntityId body : knowledge.parts(type, METHOD)) {
            if (!methodName(body).endsWith("." + name)) {
              continue;
            }
            trace.note(
                FlowModel.INTERCEPTOR,
                List.of(unit),
                List.of(),
                List.of(fact),
                List.of(body.toString()));
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
            FlowModel.JOB_RUNS, List.of(job.unit), later.profiles, List.of(job.fact), List.of());
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
      trace.message(
          FlowModel.DELIVER,
          later.from,
          receiver,
          FlowModel.ASYNC,
          later.profiles,
          withEnds(link),
          List.of());
      if (handler != null) {
        trace.reached.add(receiver + " " + handler);
        sync(trace, receiver, handler, later.profiles);
      }
    }
  }

  /**
   * Something a method does, in the order of the code: a call, or a step out of its unit. Ordered
   * by line, then kind (calls; then links, STOMP pushes and jobs; then reads; then writes), then
   * what it reaches as its fact says it; the fact's ID only between steps the same in all of these
   * (#545).
   */
  private record Event(int line, int order, String reaches, String id, Runnable action) {

    static final Comparator<Event> ORDER =
        Comparator.comparingInt(Event::line)
            .thenComparingInt(Event::order)
            .thenComparing(Event::reaches)
            .thenComparing(Event::id);
  }

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
      EntityId owner = Components.ownerType(knowledge, callee);
      if (!knowledge.relations(owner, REPOSITORY_FOR).isEmpty()) {
        events.add(
            new Event(
                line(call),
                0,
                reaches(call),
                call.id(),
                () -> repositoryCall(trace, unit, call, profiles)));
      } else {
        events.add(
            new Event(
                line(call), 0, reaches(call), call.id(), () -> call(trace, unit, call, profiles)));
      }
    }
    for (Fact link : linksBySource.getOrDefault(unit + " " + method, List.of())) {
      Runnable action =
          switch (link.predicate()) {
            case CALLS_HTTP -> () -> httpCall(trace, unit, link, profiles);
            case SENDS_MAIL -> () -> mail(trace, unit, link, profiles);
            case PUBLISHES -> () -> send(trace, unit, method, link, profiles);
            case UNRESOLVED_LINK -> () -> unresolved(trace, unit, link, profiles);
            default -> null;
          };
      if (action != null) {
        events.add(new Event(line(link), 1, reaches(link), link.id(), action));
      }
    }
    for (Fact push : knowledge.attributes(method)) {
      if (push.predicate().equals(STOMP_SEND)) {
        events.add(
            new Event(
                line(push),
                1,
                reaches(push),
                push.id(),
                () -> stompPush(trace, unit, push, profiles)));
      }
    }
    List<Entry> jobs = jobsByScheduler.getOrDefault(method.toString(), List.of());
    List<Entry> here = jobs.stream().filter(j -> j.unit.equals(unit)).toList();
    for (Entry job : here.isEmpty() ? jobs : here) {
      events.add(
          new Event(
              schedulingLine(method),
              1,
              job.method + " " + job.fact.object().value(),
              job.fact.id(),
              () -> schedule(trace, unit, job, profiles)));
    }
    List<Fact> reads = knowledge.relations(method, READS_TABLE);
    if (!reads.isEmpty()) {
      events.add(
          new Event(
              reads.stream().mapToInt(FlowTracer::line).min().orElseThrow(),
              2,
              "",
              reads.getFirst().id(),
              () -> dataAccess(trace, unit, "Reads", reads, profiles)));
    }
    List<Fact> writes = knowledge.relations(method, WRITES_TABLE);
    if (!writes.isEmpty()) {
      events.add(
          new Event(
              writes.stream().mapToInt(FlowTracer::line).min().orElseThrow(),
              3,
              "",
              writes.getFirst().id(),
              () -> dataAccess(trace, unit, "Writes", writes, profiles)));
    }
    events.sort(Event.ORDER);
    events.forEach(e -> e.action().run());
  }

  /** What a step reaches, as its fact says: its predicate and its object's value. */
  private static String reaches(Fact fact) {
    return fact.predicate() + " " + fact.object().value();
  }

  /**
   * Follows a call into the code that runs: the method called, or, when the types implementing or
   * extending its type declare it too, each of them as an operand of a frame, since the code does
   * not say which one runs; a note when there are more than {@link #MAX_ALTERNATIVES}.
   */
  private void call(Trace trace, EntityId unit, Fact call, List<String> profiles) {
    List<EntityId> bodies = bodies(Knowledge.object(call));
    if (bodies.size() == 1) {
      sync(trace, unit, bodies.getFirst(), profiles);
      return;
    }
    List<String> ids = bodies.stream().map(EntityId::toString).toList();
    if (bodies.size() > MAX_ALTERNATIVES) {
      trace.note(FlowModel.TOO_MANY, List.of(unit), profiles, List.of(call), ids);
      return;
    }
    List<Node> outer = trace.items;
    Deque<Later> outerLater = trace.later;
    Set<String> outerDelivered = trace.delivered;
    Set<String> delivered = new HashSet<>(outerDelivered);
    List<OperandNode> operands = new ArrayList<>();
    boolean any = false;
    for (EntityId body : bodies) {
      trace.items = new ArrayList<>();
      trace.later = new ArrayDeque<>();
      trace.delivered = new HashSet<>(outerDelivered);
      sync(trace, unit, body, profiles);
      deliver(trace);
      delivered.addAll(trace.delivered);
      any |= !trace.items.isEmpty();
      operands.add(new OperandNode(body, trace.items));
    }
    trace.items = outer;
    trace.later = outerLater;
    trace.delivered = delivered;
    if (any) {
      Node frame =
          new Node(
              FlowModel.ALTERNATIVES,
              null,
              null,
              FlowModel.FRAME,
              profiles,
              List.of(),
              List.of(call),
              ids);
      frame.operands.addAll(operands);
      trace.items.add(frame);
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
    EntityId owner = Components.ownerType(knowledge, declared);
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
    gated.add(Components.ownerType(knowledge, method));
    for (EntityId entity : gated) {
      for (Fact fact : knowledge.attributes(entity)) {
        if (fact.predicate().equals(PROFILE_GATE) && !fact.object().value().contains("!")) {
          List<String> profiles = Screens.strings(fact, "profiles");
          if (!profiles.isEmpty()) {
            return new ArrayList<>(new TreeSet<>(profiles));
          }
        }
      }
    }
    return outer;
  }

  private static List<String> profiles(Fact link, List<String> outer) {
    String profile = Knowledge.view(link);
    return profile == null ? outer : List.of(profile);
  }

  private void httpCall(Trace trace, EntityId unit, Fact link, List<String> outer) {
    EntityId to = Knowledge.object(link);
    List<String> profiles = profiles(link, outer);
    EntityId handler = parse(attribute(link, "target"));
    List<Fact> facts = withEnds(link);
    for (Map.Entry<Fact, Fact> alternative : alternativeLinks.entrySet()) {
      if (alternative.getValue().equals(link)) {
        facts.add(alternative.getKey());
      }
    }
    trace.message(FlowModel.HTTP_CALL, unit, to, FlowModel.SYNC, profiles, facts, List.of());
    if (handler != null && !to.type().equals(EXTERNAL_SYSTEM)) {
      trace.reached.add(to + " " + handler);
      sync(trace, to, handler, profiles);
    }
  }

  /** A step that sends mail through the mail server: the flow ends in an email. */
  private void mail(Trace trace, EntityId unit, Fact link, List<String> outer) {
    trace.message(
        FlowModel.MAIL,
        unit,
        Knowledge.object(link),
        FlowModel.SYNC,
        profiles(link, outer),
        withEnds(link),
        List.of());
  }

  private void send(Trace trace, EntityId unit, EntityId method, Fact link, List<String> outer) {
    EntityId destination = Knowledge.object(link);
    List<String> profiles = profiles(link, outer);
    trace.message(
        FlowModel.SEND, unit, destination, FlowModel.ASYNC, profiles, withEnds(link), List.of());
    trace.asyncDestinations.add(destination);
    List<Fact> deliveries =
        linksBySource.getOrDefault(unit + " " + method, List.of()).stream()
            .filter(
                f ->
                    f.predicate().equals(SENDS_MESSAGES_TO)
                        && destination.toString().equals(attribute(f, "destination")))
            .toList();
    if (deliveries.isEmpty()) {
      trace.note(FlowModel.NO_LISTENER, List.of(destination), profiles, List.of(link), List.of());
      return;
    }
    for (Fact delivery : deliveries) {
      trace.later.add(new Later(destination, delivery, profiles(delivery, profiles), null));
    }
    trace.note(FlowModel.HAND_OFF, List.of(unit, destination), profiles, deliveries, List.of());
  }

  /**
   * A method scheduling a background job: an async message to the unit that runs the job, a note
   * that the method does not wait, and the job's own steps after the method's, since it runs later.
   */
  private void schedule(Trace trace, EntityId unit, Entry job, List<String> profiles) {
    trace.message(
        FlowModel.SCHEDULE,
        unit,
        job.unit,
        FlowModel.ASYNC,
        profiles,
        List.of(job.fact),
        List.of());
    trace.asyncJobs.add(job.fact);
    trace.note(
        FlowModel.JOB_LATER,
        List.of(unit),
        profiles,
        List.of(job.fact),
        List.of(job.unit.toString()));
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

  /**
   * A message pushed to STOMP subscribers: a note over the unit, since the subscribers are clients
   * (a browser) that no fact of the code names; the WebSocket endpoints of the unit say where they
   * connect (ADR 0080).
   */
  private void stompPush(Trace trace, EntityId unit, Fact push, List<String> profiles) {
    List<Fact> facts = new ArrayList<>(List.of(push));
    for (Fact endpoint : websocketEndpoints(unit)) {
      if ("stomp".equals(attribute(endpoint, "protocol"))) {
        facts.add(endpoint);
      }
    }
    trace.note(FlowModel.STOMP_PUSH, List.of(unit), profiles, facts, List.of());
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
    for (EntityId module : Components.codeModules(knowledge, unit)) {
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
    trace.unresolved.add(fact.object().value());
    trace.note(FlowModel.UNRESOLVED, List.of(), profiles, List.of(fact), List.of(unit.toString()));
  }

  private void dataAccess(
      Trace trace, EntityId unit, String verb, List<Fact> accesses, List<String> profiles) {
    SortedSet<String> tables = new TreeSet<>();
    accesses.forEach(f -> tables.add(tableName(Knowledge.object(f).toString())));
    List<Fact> facts = new ArrayList<>(accesses);
    for (Fact shared : knowledge.relations(unit, ACCESSES_DATA_OF)) {
      if (alternatives.contains(shared)) {
        continue;
      }
      SortedSet<String> theirs = new TreeSet<>();
      Screens.strings(shared, verb.equals("Reads") ? "reads" : "writes")
          .forEach(t -> theirs.add(tableName(t)));
      theirs.retainAll(tables);
      if (!theirs.isEmpty()) {
        facts.add(shared);
      }
    }
    Database database = database(unit);
    facts.addAll(database.facts);
    if (database.entity == null) {
      Node note =
          trace.note(
              FlowModel.UNKNOWN_DATABASE, List.of(unit), profiles, facts, database.candidates);
      if (note != null) {
        note.parts.add(new Part(verb, List.copyOf(tables)));
      }
      return;
    }
    trace.data(
        unit, database.entity, verb, List.copyOf(tables), database.protocol, profiles, facts);
  }

  private void repositoryCall(Trace trace, EntityId unit, Fact call, List<String> profiles) {
    EntityId callee = Knowledge.object(call);
    Database database = database(unit);
    List<Fact> facts = new ArrayList<>(List.of(call));
    facts.addAll(knowledge.relations(Components.ownerType(knowledge, callee), REPOSITORY_FOR));
    facts.addAll(database.facts);
    if (database.entity == null) {
      Node note =
          trace.note(
              FlowModel.UNKNOWN_DATABASE, List.of(unit), profiles, facts, database.candidates);
      if (note != null) {
        note.parts.add(new Part("Calls", List.of(methodName(callee))));
      }
      return;
    }
    trace.data(
        unit,
        database.entity,
        "Calls",
        List.of(methodName(callee)),
        database.protocol,
        profiles,
        facts);
  }

  /**
   * The database a unit's data access reaches, or none when it has not exactly one by default, with
   * the databases it could be (none, or several).
   */
  private record Database(
      EntityId entity, String protocol, List<Fact> facts, List<String> candidates) {}

  private Database database(EntityId unit) {
    Map<EntityId, List<Fact>> databases = new TreeMap<>(Knowledge.BY_ID);
    for (Fact fact : knowledge.relations(unit, USES_DATABASE)) {
      if (Knowledge.view(fact) == null) {
        databases.computeIfAbsent(Knowledge.object(fact), d -> new ArrayList<>()).add(fact);
      }
    }
    if (databases.size() != 1) {
      List<Fact> facts = new ArrayList<>();
      databases.values().forEach(facts::addAll);
      return new Database(
          null, null, facts, databases.keySet().stream().map(EntityId::toString).toList());
    }
    Map.Entry<EntityId, List<Fact>> only = databases.entrySet().iterator().next();
    return new Database(
        only.getKey(), attribute(only.getValue().getFirst(), "protocol"), only.getValue(), null);
  }

  // Helpers.

  /** A link's fact with the facts on both its ends. */
  private List<Fact> withEnds(Fact link) {
    List<Fact> facts = new ArrayList<>(List.of(link));
    for (String id : Screens.strings(link, "facts")) {
      Fact end = knowledge.fact(id);
      if (end != null) {
        facts.add(end);
      }
    }
    return facts;
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

  private static String tableName(String table) {
    return table.substring(table.lastIndexOf(':') + 1);
  }

  static EntityId parse(String id) {
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

  private static String attribute(Fact fact, String name) {
    return Screens.attribute(fact, name, null);
  }
}
