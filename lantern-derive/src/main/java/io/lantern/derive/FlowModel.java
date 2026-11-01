package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The flows of the architecture model as the composer reads them back from the stored facts (#618):
 * each traced from an entry point, with its steps in order, as {@link Flows} stores them.
 */
public final class FlowModel {

  // What a step is. A message goes from one participant to another; a note is over some.

  /** A person uses a frontend's screens. */
  public static final String USES_SCREENS = "uses-screens";

  /** A frontend calls (or opens a socket to) the entry point, or the gateway in front of it. */
  public static final String FRONTEND_CALL = "frontend-call";

  /** The gateway the frontend called routes the call on to the entry point's unit. */
  public static final String ROUTE = "route";

  /** A gateway routes a client's request to the entry point (the flow starts at the gateway). */
  public static final String GATEWAY_ROUTE = "gateway-route";

  /** An HTTP call to another container, following its link. */
  public static final String HTTP_CALL = "http-call";

  /** Mail sent through a mail server, following its link. */
  public static final String MAIL = "mail";

  /** A message sent, following its link. */
  public static final String SEND = "send";

  /** A note: nothing in the code listens for the message sent. */
  public static final String NO_LISTENER = "no-listener";

  /** A note: the sender does not wait; the listeners take the message later. */
  public static final String HAND_OFF = "hand-off";

  /** A message delivered to a listener, following its link. */
  public static final String DELIVER = "deliver";

  /** A background job scheduled. */
  public static final String SCHEDULE = "schedule";

  /** A note: the scheduling code does not wait; the job runs later. */
  public static final String JOB_LATER = "job-later";

  /** A note: the job scheduled earlier runs. */
  public static final String JOB_RUNS = "job-runs";

  /** A note: each STOMP frame passes an inbound channel interceptor. */
  public static final String INTERCEPTOR = "interceptor";

  /** A frame: one of several implementations runs a call; one operand each. */
  public static final String ALTERNATIVES = "alternatives";

  /** A note: more implementations than are drawn may run a call. */
  public static final String TOO_MANY = "too-many-alternatives";

  /** A note: a message pushed to STOMP subscribers. */
  public static final String STOMP_PUSH = "stomp-push";

  /** A note: a call Lantern could not link, where the trace stops following it. */
  public static final String UNRESOLVED = "unresolved";

  /** Data access in the unit's database: a run of reads, writes and repository calls. */
  public static final String DATA = "data";

  /** A note: data access in a database that is not known. */
  public static final String UNKNOWN_DATABASE = "unknown-database";

  /** A note: the trace stopped at its bound. */
  public static final String TRUNCATED = "truncated";

  /** How a step is drawn: a synchronous or asynchronous message, a note or a frame. */
  public static final String SYNC = "sync";

  public static final String ASYNC = "async";
  public static final String NOTE = "note";
  public static final String FRAME = "frame";

  private FlowModel() {}

  /**
   * How a flow starts in the browser: a person using a frontend (when the frontend has screens for
   * one), the frontend's call that reaches the entry point, and the gateway and route it goes
   * through, if any (ADR 0047, amendment for #331).
   *
   * @param person null when no person has screens on the frontend
   * @param via the gateway the call goes to, which routes it on; null when it reaches the unit
   * @param route the gateway's route link to the unit; null when there is none or no gateway
   */
  public record Start(EntityId person, EntityId frontend, Fact link, EntityId via, Fact route) {}

  /**
   * How a flow starts at a gateway that routes a client's request to its endpoint (ADR 0047,
   * amendment for #351).
   *
   * @param routed the gateway's {@code routed-endpoint} fact: the request as the gateway receives
   *     it, and as the unit serves it
   * @param route the gateway's route link to the unit; null for the discovery locator
   */
  public record GatewayStart(EntityId gateway, Fact routed, Fact route) {}

  /** One data access of a run: what it does ({@code Reads}, {@code Writes} or {@code Calls}). */
  public record Part(String verb, List<String> what) {}

  /** One operand of an alternatives frame: the implementation it runs and its steps. */
  public record Operand(EntityId body, List<Step> steps) {}

  /**
   * One step of a flow.
   *
   * @param action what it is, one of this class's constants
   * @param from where a message starts; for a note, the unit it is about, if any
   * @param to where a message ends; for a note, the other element it is about, if any
   * @param kind {@link #SYNC}, {@link #ASYNC}, {@link #NOTE} or {@link #FRAME}
   * @param profiles the Spring profiles under which alone it happens; empty for every one
   * @param over the participants a note stands over
   * @param facts the facts it rests on, in order: the link it follows first, where it follows one
   * @param detail what the action needs beyond its facts, such as the methods an interceptor or
   *     frame names, the databases a data access could be in, or the bound a trace stopped at
   * @param parts for data access, each access of the run in order
   * @param operands for a frame, its operands in order
   */
  public record Step(
      String action,
      EntityId from,
      EntityId to,
      String kind,
      List<String> profiles,
      List<EntityId> over,
      List<Fact> facts,
      List<String> detail,
      List<Part> parts,
      List<Operand> operands) {

    public Step {
      profiles = List.copyOf(profiles);
      over = List.copyOf(over);
      facts = List.copyOf(facts);
      detail = List.copyOf(detail);
      parts = List.copyOf(parts);
      operands = List.copyOf(operands);
    }

    /** Whether it is a message, which counts toward the trace's bound. */
    public boolean message() {
      return kind.equals(SYNC) || kind.equals(ASYNC);
    }
  }

  /**
   * A flow traced from an entry point.
   *
   * @param id its entity, {@code flow:<unit>/<the entry point's claim>}
   * @param unit the unit whose entry point starts it
   * @param method the entry point's method
   * @param trigger the fact that makes the method an entry point (an endpoint, a listener ...)
   * @param start how the browser starts it; null when it does not
   * @param gateway how a gateway starts it, when the browser does not; null when none does
   * @param steps its steps, in order
   * @param containers the participants, in the order the flow reaches them
   * @param asyncDestinations the topics, queues and exchanges it hands work on through
   * @param asyncJobs the background jobs it hands work on to, by their job facts
   * @param reaches the entry points it reaches, as unit and method IDs separated by a space
   * @param unresolved what it calls that Lantern could not link, in order
   * @param pushes how many pushes to STOMP subscribers it makes
   * @param messages how many messages it has
   * @param truncated whether the trace stopped at its bound
   */
  public record Flow(
      EntityId id,
      EntityId unit,
      EntityId method,
      Fact trigger,
      Start start,
      GatewayStart gateway,
      List<Step> steps,
      List<EntityId> containers,
      List<EntityId> asyncDestinations,
      List<Fact> asyncJobs,
      List<String> reaches,
      List<String> unresolved,
      int pushes,
      int messages,
      boolean truncated) {

    /**
     * Whether a document may show it: its own steps (the gateway's routing to its entry point is
     * not one) reach another container, or it pushes to STOMP subscribers or calls what Lantern
     * could not link. A flow that stays in its unit is stored too, as its trace found it.
     */
    public boolean candidate() {
      int own = messages - (gateway == null ? 0 : 1);
      return own > 0 || pushes > 0 || !unresolved.isEmpty();
    }

    /** The entry point, as unit and method IDs separated by a space. */
    public String key() {
      return unit + " " + method;
    }
  }

  /** The flows stored in the knowledge, by their entry points' unit and method. */
  public static List<Flow> read(Knowledge knowledge) {
    Map<EntityId, List<Fact>> stepsByFlow = new HashMap<>();
    for (Fact step : knowledge.withPredicate(Flows.STEP_OF)) {
      if (Knowledge.isEntity(step)) {
        stepsByFlow.computeIfAbsent(Knowledge.object(step), f -> new ArrayList<>()).add(step);
      }
    }
    List<Flow> flows = new ArrayList<>();
    for (Fact triggered : knowledge.withPredicate(Flows.TRIGGERED_BY)) {
      if (!triggered.subject().type().equals(Flows.FLOW) || !Knowledge.isEntity(triggered)) {
        continue;
      }
      Flow flow =
          flow(knowledge, triggered, stepsByFlow.getOrDefault(triggered.subject(), List.of()));
      if (flow != null) {
        flows.add(flow);
      }
    }
    flows.sort(Comparator.comparing(Flow::key).thenComparing(f -> f.id().toString()));
    return flows;
  }

  private static Flow flow(Knowledge knowledge, Fact triggered, List<Fact> stepFacts) {
    Fact trigger = knowledge.fact(Screens.attribute(triggered, "trigger", null));
    EntityId unit = Flows.parse(Screens.attribute(triggered, "unit", null));
    Fact shape =
        knowledge.attributes(triggered.subject()).stream()
            .filter(f -> f.predicate().equals(Flows.FLOW_SHAPE))
            .findFirst()
            .orElse(null);
    if (trigger == null || unit == null || shape == null) {
      return null;
    }
    // A flow is read whole or not at all: a document composed from part of the snapshot's facts
    // (a repository's, in a run that carries others forward) cannot cite what it does not hold.
    List<Fact> all = new ArrayList<>(stepFacts);
    all.add(triggered);
    all.add(shape);
    for (Fact fact : all) {
      for (String name : List.of("facts", "asyncJobs")) {
        for (String id : Screens.strings(fact, name)) {
          if (knowledge.fact(id) == null) {
            return null;
          }
        }
      }
    }
    Start start = null;
    GatewayStart gateway = null;
    String starts = Screens.attribute(triggered, "start", null);
    if ("browser".equals(starts)) {
      start =
          new Start(
              Flows.parse(Screens.attribute(triggered, "person", null)),
              Flows.parse(Screens.attribute(triggered, "frontend", null)),
              knowledge.fact(Screens.attribute(triggered, "link", null)),
              Flows.parse(Screens.attribute(triggered, "through", null)),
              fact(knowledge, Screens.attribute(triggered, "route", null)));
    } else if ("gateway".equals(starts)) {
      gateway =
          new GatewayStart(
              Flows.parse(Screens.attribute(triggered, "gateway", null)),
              knowledge.fact(Screens.attribute(triggered, "routed", null)),
              fact(knowledge, Screens.attribute(triggered, "route", null)));
    }
    // Steps by order; those inside a frame's operand under it.
    List<Fact> ordered = new ArrayList<>(stepFacts);
    ordered.sort(Comparator.comparingInt(f -> f.attributes().path("order").asInt()));
    Map<String, List<Fact>> inside = new HashMap<>();
    List<Fact> top = new ArrayList<>();
    for (Fact fact : ordered) {
      String in = Screens.attribute(fact, "fragment", null);
      if (in == null) {
        top.add(fact);
      } else {
        inside.computeIfAbsent(in, k -> new ArrayList<>()).add(fact);
      }
    }
    List<Step> steps = steps(knowledge, top, inside);
    return new Flow(
        triggered.subject(),
        unit,
        Knowledge.object(triggered),
        trigger,
        start,
        gateway,
        steps,
        Screens.strings(shape, "containers").stream().map(Flows::parse).toList(),
        Screens.strings(shape, "asyncDestinations").stream().map(Flows::parse).toList(),
        Screens.strings(shape, "asyncJobs").stream().map(knowledge::fact).toList(),
        Screens.strings(shape, "reaches"),
        Screens.strings(shape, "unresolved"),
        shape.attributes().path("pushes").asInt(),
        shape.attributes().path("messages").asInt(),
        shape.attributes().path("truncated").asBoolean());
  }

  private static Fact fact(Knowledge knowledge, String id) {
    return id == null ? null : knowledge.fact(id);
  }

  /** The steps as stored, each frame with its operands' steps. */
  private static List<Step> steps(
      Knowledge knowledge, List<Fact> stored, Map<String, List<Fact>> inside) {
    List<Step> steps = new ArrayList<>();
    for (Fact fact : stored) {
      steps.add(step(knowledge, fact, inside));
    }
    return steps;
  }

  private static Step step(Knowledge knowledge, Fact fact, Map<String, List<Fact>> inside) {
    List<Fact> facts = new ArrayList<>();
    for (String id : Screens.strings(fact, "facts")) {
      Fact cited = knowledge.fact(id);
      if (cited != null) {
        facts.add(cited);
      }
    }
    List<Part> parts = new ArrayList<>();
    JsonNode stored = fact.attributes().get("parts");
    if (stored != null && stored.isArray()) {
      for (JsonNode part : stored) {
        List<String> what = new ArrayList<>();
        part.path("what").forEach(w -> what.add(w.asString()));
        parts.add(new Part(part.path("verb").asString(), what));
      }
    }
    String kind = Screens.attribute(fact, "kind", SYNC);
    List<String> detail = Screens.strings(fact, "detail");
    List<Operand> operands = new ArrayList<>();
    if (kind.equals(FRAME)) {
      List<Fact> children = inside.getOrDefault(fact.subject().toString(), List.of());
      for (int i = 0; i < detail.size(); i++) {
        int operand = i;
        List<Fact> mine =
            children.stream()
                .filter(c -> c.attributes().path("operand").asInt() == operand)
                .toList();
        operands.add(new Operand(Flows.parse(detail.get(i)), steps(knowledge, mine, inside)));
      }
    }
    return new Step(
        Screens.attribute(fact, "action", null),
        Flows.parse(Screens.attribute(fact, "from", null)),
        Flows.parse(Screens.attribute(fact, "to", null)),
        kind,
        Screens.strings(fact, "condition"),
        Screens.strings(fact, "over").stream().map(Flows::parse).toList(),
        facts,
        detail,
        parts,
        operands);
  }
}
