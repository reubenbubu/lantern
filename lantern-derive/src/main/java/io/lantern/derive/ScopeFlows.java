package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The flows a scope may draw, as queries on the stored flows (ADR 0115, amendment for #718): each
 * stored flow ({@link FlowModel}) as the scope traces it, its steps in words, what starts it, the
 * candidates the significance role is shown, their rank (ADR 0047) and the flows a scope shows (ADR
 * 0070, ADR 0108), so the composer's sequence diagrams and the flow roles ask about the same flows.
 *
 * <p>A step is a neutral message, note or alternatives frame: who sends it to whom, its label as
 * the facts give it, its code, what it adds and the facts it rests on, and the link whose intent
 * phrase (ADR 0050) may word it instead; the composer draws it, applying the phrase. Each step is
 * also given as the line the roles are shown, by its facts only, without intent phrases.
 */
public final class ScopeFlows {

  /** The most flows a scope shows (docs/design.md: three to seven). */
  public static final int MAX_FLOWS = 7;

  /** The fewest flows a scope shows; flows that reach only databases fill up to it. */
  public static final int MIN_FLOWS = 3;

  /** The most tables or methods a data access label names for each verb before saying how many. */
  private static final int NAMED = 4;

  /**
   * The path segments of a request that signs a person in, as ADR 0074 reads them: the same words
   * as {@code People.SIGN_IN} in lantern-link.
   */
  private static final Set<String> SIGN_IN =
      Set.of("sign-in", "signin", "sign_in", "login", "log-in", "authenticate");

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

  private final Knowledge knowledge;
  private final Elements elements;
  private final Set<EntityId> scope;
  private final Function<EntityId, String> names;

  /** Every stored flow as traced here, in the order stored, once read. */
  private List<Trace> traces;

  /** The deployable units, once read. */
  private Set<EntityId> units;

  /** As the other constructor, with the names the composer gives elements. */
  public ScopeFlows(
      ArchitectureModel model,
      Elements elements,
      Set<EntityId> scope,
      Function<EntityId, String> names) {
    this(model.index(), elements, scope, names);
  }

  /**
   * @param elements the elements the scope's diagrams show, for the people who use a frontend and
   *     the links that are another's alternative under a profile
   * @param scope the deployable units in scope; flows that start in or pass through one are shown
   * @param names names an element as the document does; a composer also adds it to its document
   */
  public ScopeFlows(
      Knowledge knowledge,
      Elements elements,
      Set<EntityId> scope,
      Function<EntityId, String> names) {
    this.knowledge = knowledge;
    this.elements = elements;
    this.scope = Set.copyOf(scope);
    this.names = names;
  }

  /** The flows of the model's scope, traced from the model's facts (ADR 0115). */
  public ScopeFlows(ArchitectureModel model, Elements elements, Set<EntityId> scope) {
    this(model.index(), elements, scope);
  }

  /** As the other constructor, naming elements as {@link Elements#names} does. */
  public ScopeFlows(Knowledge knowledge, Elements elements, Set<EntityId> scope) {
    this(knowledge, elements, scope, elements.names()::name);
  }

  // Entry points and starts.

  /** What starts a flow: an endpoint, a listener or a scheduled job of a unit. */
  public record Entry(EntityId unit, EntityId method, Fact fact) {

    /** The unit and method IDs separated by a space, as {@link FlowModel.Flow#key}. */
    public String key() {
      return unit + " " + method;
    }
  }

  /**
   * How a flow starts in the browser, in the scope: a person using a frontend (when the frontend
   * has screens for one in the scope), the frontend's call that reaches the entry point, and the
   * gateway and route it goes through, if any (ADR 0047, amendment for #331).
   *
   * @param person null when no person of the scope has screens on the frontend
   * @param via the gateway the call goes to, which routes it on; null when it reaches the unit
   * @param route the gateway's route link to the unit; null when there is none or no gateway
   */
  public record Start(
      EntityId person,
      List<Fact> personFacts,
      EntityId frontend,
      Fact link,
      EntityId via,
      Fact route) {}

  /** A flow's title, its trigger as the overview names it, and the clause saying what starts it. */
  public record Heading(String title, String trigger, String starts) {}

  // Steps.

  /** A step of a traced flow, which happens only under its Spring profiles when it names any. */
  public sealed interface Step permits Message, Note, Alternatives {

    /** The Spring profiles under which alone it happens; empty for every one. */
    List<String> profiles();
  }

  /**
   * A message from one participant to another.
   *
   * @param plain its label as the facts alone give it
   * @param code its code, such as the endpoint or protocol, or null
   * @param async whether the sender does not wait for it
   * @param info what it adds, in sentences, or null
   * @param facts the facts it rests on
   * @param phrased the link whose intent phrase (ADR 0050) labels it instead, where there is one,
   *     the phrase's cited facts then added to its own; null when it has none
   */
  public record Message(
      EntityId from,
      EntityId to,
      String plain,
      String code,
      boolean async,
      List<String> profiles,
      String info,
      List<Fact> facts,
      Fact phrased)
      implements Step {}

  /** A note over participants, none for the whole flow. */
  public record Note(List<EntityId> over, String text, List<String> profiles, List<Fact> facts)
      implements Step {}

  /**
   * A call one of several implementations runs, each an operand, since the code does not say which
   * one runs.
   *
   * @param call the fact of the call
   */
  public record Alternatives(Fact call, List<Operand> operands, List<String> profiles)
      implements Step {}

  /**
   * An operand of {@link Alternatives}: the simple name of the type that runs it, and its steps.
   */
  public record Operand(String name, List<Step> steps) {}

  /**
   * A step as the roles are shown it: by its facts, without intent phrases.
   *
   * @param depth how deep in frames it is, 0 at the top
   * @param text what it does, such as {@code shop-app to orders, async: Sends order.created}
   * @param facts the facts it rests on
   */
  public record StepLine(int depth, String text, List<Fact> facts) {}

  // Tracing the stored flows.

  /** One stored flow as the scope traces it. */
  public final class Trace {
    private final FlowModel.Flow flow;
    private final Entry entry;
    private final Start start;
    private final FlowModel.GatewayStart gateway;
    private List<Step> steps = new ArrayList<>();
    private final Set<EntityId> participants = new LinkedHashSet<>();
    private final Set<String> reached;
    private final SortedSet<String> asyncThrough = new TreeSet<>();
    private final List<String> unresolved;
    private List<StepLine> outline = new ArrayList<>();
    private int depth;
    private final int pushes;
    private Heading heading;

    private Trace(FlowModel.Flow flow) {
      this.flow = flow;
      this.entry = new Entry(flow.unit(), flow.method(), flow.trigger());
      this.gateway = flow.gateway();
      this.reached = new HashSet<>(flow.reaches());
      this.unresolved = flow.unresolved();
      this.pushes = flow.pushes();
      FlowModel.Start stored = flow.start();
      if (stored == null) {
        this.start = null;
      } else {
        // The person who uses the frontend among the scope's people: its only one, else the one
        // with most screens on it, by ID among equals.
        EntityId person = null;
        List<Fact> personFacts = List.of();
        for (Map.Entry<EntityId, List<Fact>> e : elements.screensOn(stored.frontend()).entrySet()) {
          if (person == null || e.getValue().size() > personFacts.size()) {
            person = e.getKey();
            personFacts = e.getValue();
          }
        }
        this.start =
            new Start(
                person,
                personFacts,
                stored.frontend(),
                stored.link(),
                stored.via(),
                stored.route());
      }
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
      flow.asyncDestinations().forEach(d -> asyncThrough.add(nameOf(d)));
      flow.asyncJobs().stream()
          .filter(Objects::nonNull)
          .forEach(j -> asyncThrough.add("the " + jobName(j)));
      if (start != null && start.person != null) {
        message(
            start.person,
            start.frontend,
            "Uses the screens",
            null,
            false,
            List.of(),
            "In a web browser.",
            start.personFacts,
            null);
      }
      steps(flow.steps());
    }

    /** The stored flow. */
    public FlowModel.Flow flow() {
      return flow;
    }

    /** Its entry point. */
    public Entry entry() {
      return entry;
    }

    /** How the browser starts the flow; null when it starts at its entry point. */
    public Start start() {
      return start;
    }

    /** How a gateway starts the flow, when the browser does not; null when none does. */
    public FlowModel.GatewayStart gateway() {
      return gateway;
    }

    /** Its steps, in order. */
    public List<Step> steps() {
      return List.copyOf(steps);
    }

    /** Its participants, in the order the flow reaches them. */
    public List<EntityId> participants() {
      return List.copyOf(participants);
    }

    /** The destinations and jobs it hands work on through, by name, sorted. */
    public List<String> asyncThrough() {
      return List.copyOf(asyncThrough);
    }

    /** What it calls that Lantern could not link, in order. */
    public List<String> unresolved() {
      return unresolved;
    }

    /** Its steps as the roles are shown them. */
    public List<StepLine> outline() {
      return List.copyOf(outline);
    }

    /** The units, stores, queues and external systems the flow touches. */
    public int touches() {
      return participants.size() + (pushes > 0 ? 1 : 0);
    }

    /** Its title, its trigger and what starts it. */
    public Heading heading() {
      if (heading == null) {
        heading = headingOf(this);
      }
      return heading;
    }

    private void message(
        EntityId from,
        EntityId to,
        String plain,
        String code,
        boolean async,
        List<String> profiles,
        String info,
        List<Fact> facts,
        Fact phrased) {
      outline.add(line(line(from, to, async, plain, code, info), profiles, facts));
      if (from != null) {
        participants.add(from);
      }
      if (to != null) {
        participants.add(to);
      }
      steps.add(
          new Message(from, to, plain, code, async, profiles, info, List.copyOf(facts), phrased));
    }

    private void note(List<EntityId> over, String text, List<String> profiles, List<Fact> facts) {
      participants.addAll(over);
      outline.add(line(new StringBuilder("Note: ").append(text), profiles, facts));
      steps.add(
          new Note(List.copyOf(new LinkedHashSet<>(over)), text, profiles, List.copyOf(facts)));
    }

    private StringBuilder line(
        EntityId from, EntityId to, boolean async, String plain, String code, String info) {
      StringBuilder line =
          new StringBuilder(nameOf(from))
              .append(" to ")
              .append(nameOf(to))
              .append(" (")
              .append(ElementNames.kind(to))
              .append("), ")
              .append(async ? "async" : "sync")
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

    private StepLine line(StringBuilder line, List<String> profiles, List<Fact> facts) {
      if (!profiles.isEmpty()) {
        line.append(line.toString().endsWith(".") ? " " : ". ")
            .append("Only under the Spring profile")
            .append(profiles.size() > 1 ? "s " : " ")
            .append(list(profiles))
            .append('.');
      }
      return new StepLine(depth, line.toString(), List.copyOf(facts));
    }

    private void steps(List<FlowModel.Step> stored) {
      for (FlowModel.Step step : stored) {
        trace(step);
      }
    }

    /** A stored step as the trace that found it did. */
    private void trace(FlowModel.Step step) {
      List<String> profiles = step.profiles();
      List<Fact> facts = step.facts();
      Fact first = facts.isEmpty() ? null : facts.getFirst();
      switch (step.action()) {
        case FlowModel.USES_SCREENS -> {
          // Traced for the scope's person, before the steps.
        }
        case Types.FRONTEND_CALL -> frontendCall(step);
        case FlowModel.ROUTE -> route(step);
        case Types.GATEWAY_ROUTE -> gatewayRoute(step);
        case Types.HTTP_CALL -> httpCall(step, first);
        case FlowModel.MAIL ->
            message(
                step.from(),
                step.to(),
                "Sends an email",
                attribute(first, "protocol"),
                false,
                profiles,
                null,
                facts,
                first);
        case FlowModel.SEND -> {
          String key = attribute(first, "routingKey");
          message(
              step.from(),
              step.to(),
              key == null ? "Sends a message" : "Sends " + key,
              attribute(first, "protocol"),
              true,
              profiles,
              Elements.resolution(first) == null
                  ? null
                  : capitalize(Elements.resolution(first)) + ".",
              facts,
              first);
        }
        case FlowModel.NO_LISTENER ->
            note(step.over(), "No listener for it was found in the code.", profiles, facts);
        case FlowModel.HAND_OFF -> {
          SortedSet<String> receivers = new TreeSet<>();
          facts.forEach(d -> receivers.add(nameOf(Knowledge.object(d))));
          EntityId unit = step.over().getFirst();
          note(
              step.over(),
              nameOf(unit)
                  + " does not wait: "
                  + String.join(" and ", receivers)
                  + (receivers.size() == 1 ? " takes" : " take")
                  + " the message later.",
              profiles,
              facts);
        }
        case FlowModel.DELIVER -> {
          EntityId handler = parse(attribute(first, "target"));
          String queue = attribute(first, "queue");
          String protocol = attribute(first, "protocol");
          String through = queue == null ? null : nameOf(parse(queue));
          message(
              step.from(),
              step.to(),
              handler == null ? "Delivers the message" : "Delivers to " + methodName(handler),
              through == null ? protocol : join(protocol, through),
              true,
              profiles,
              deliveryInfo(first),
              facts,
              null);
        }
        case FlowModel.SCHEDULE ->
            message(
                step.from(),
                step.to(),
                "Schedules " + simpleName(first.object().value()),
                Components.jobKind(first),
                true,
                profiles,
                null,
                facts,
                null);
        case FlowModel.JOB_LATER -> {
          EntityId unit = step.over().getFirst();
          note(
              step.over(),
              nameOf(unit)
                  + " does not wait: the "
                  + Components.jobKind(first)
                  + " runs later in "
                  + nameOf(parse(step.detail().getFirst()))
                  + ".",
              profiles,
              facts);
        }
        case FlowModel.JOB_RUNS ->
            note(
                step.over(),
                "Later: the " + jobName(first) + " runs (" + methodName(first.subject()) + ").",
                profiles,
                facts);
        case FlowModel.INTERCEPTOR ->
            note(
                step.over(),
                "Each STOMP frame the client sends passes "
                    + methodName(parse(step.detail().getFirst()))
                    + ".",
                profiles,
                facts);
        case FlowModel.ALTERNATIVES -> alternatives(step, first);
        case FlowModel.TOO_MANY -> {
          List<String> types = step.detail().stream().map(d -> typeName(parse(d))).toList();
          note(
              step.over(),
              "One of "
                  + list(types)
                  + " runs "
                  + methodName(Knowledge.object(first))
                  + " here; the trace does not follow them.",
              profiles,
              facts);
        }
        case FlowModel.STOMP_PUSH -> stompPush(step, first);
        case FlowModel.UNRESOLVED -> unresolved(step, first);
        case FlowModel.DATA -> data(step);
        case FlowModel.UNKNOWN_DATABASE -> unknownDatabase(step);
        case FlowModel.TRUNCATED ->
            note(
                List.of(),
                "The trace stops here, after "
                    + ("messages".equals(step.detail().getFirst())
                        ? Flows.MAX_MESSAGES + " messages"
                        : Flows.MAX_STEPS + " steps")
                    + ".",
                List.of(),
                List.of());
        default -> throw new IllegalStateException("Unknown flow step " + step.action());
      }
    }

    /** The frontend's call that starts the flow in the browser. */
    private void frontendCall(FlowModel.Step step) {
      Fact link = start.link;
      boolean socket = socket(link);
      List<String> info = new ArrayList<>();
      String protocol = attribute(link, "protocol");
      if (protocol != null) {
        info.add("Over " + protocol + ".");
      }
      String proxy = attribute(link, "proxy");
      if (proxy != null) {
        info.add("Through the proxy " + proxy + " of its server.");
      }
      message(
          step.from(),
          step.to(),
          socket
              ? "Opens a socket"
              : ("true".equals(attribute(link, "navigation"))
                      ? "Navigates the browser to "
                      : "Calls ")
                  + methodName(entry.method),
          request(link),
          false,
          List.of(),
          info.isEmpty() ? null : String.join(" ", info),
          step.facts(),
          null);
    }

    /** The gateway the frontend called routing the call on to the entry point's unit. */
    private void route(FlowModel.Step step) {
      boolean socket = socket(start.link);
      Fact route = start.route;
      StringBuilder routeInfo =
          new StringBuilder("Route ")
              .append(Elements.attribute(start.link, "throughRoute", "of " + nameOf(start.via)));
      if (route != null && attribute(route, "url") != null) {
        routeInfo.append(" to ").append(attribute(route, "url"));
      }
      if (route != null && attribute(route, "protocol") != null) {
        routeInfo.append(", over ").append(attribute(route, "protocol"));
      }
      routeInfo.append('.');
      message(
          step.from(),
          step.to(),
          socket ? "Relays the socket" : "Routes to " + methodName(entry.method),
          attribute(start.link, "reachesEndpoint"),
          false,
          step.profiles(),
          routeInfo.toString(),
          step.facts(),
          route);
    }

    /** A gateway routing the request it receives to the unit, with the request as it serves it. */
    private void gatewayRoute(FlowModel.Step step) {
      StringBuilder info = new StringBuilder();
      String url = attribute(gateway.routed(), "url");
      if (attribute(gateway.routed(), "route") != null) {
        info.append("Route ").append(attribute(gateway.routed(), "route"));
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
      message(
          step.from(),
          step.to(),
          "Routes to " + methodName(entry.method),
          attribute(gateway.routed(), "served"),
          false,
          step.profiles(),
          info.toString(),
          step.facts(),
          gateway.route());
    }

    private void httpCall(FlowModel.Step step, Fact link) {
      EntityId to = step.to();
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
      String resolution = Elements.resolution(link);
      if (resolution != null) {
        info.add(capitalize(resolution) + ".");
      }
      for (Map.Entry<Fact, Fact> alternative : elements.alternativeLinks().entrySet()) {
        if (alternative.getValue().equals(link)) {
          Fact other = alternative.getKey();
          info.add(
              "Under the Spring profile "
                  + Knowledge.view(other)
                  + " it calls "
                  + nameOf(Knowledge.object(other))
                  + " instead.");
        }
      }
      String plain = "Calls " + (handler == null ? nameOf(to) : methodName(handler));
      message(
          step.from(),
          to,
          plain,
          endpoint,
          false,
          step.profiles(),
          info.isEmpty() ? null : String.join(" ", info),
          step.facts(),
          link);
    }

    /** A call one of several implementations runs, each traced as an operand. */
    private void alternatives(FlowModel.Step step, Fact call) {
      List<Step> outer = steps;
      List<StepLine> outerOutline = outline;
      List<Operand> operands = new ArrayList<>();
      List<StepLine> alternatives = new ArrayList<>();
      depth += 2;
      for (FlowModel.Operand operand : step.operands()) {
        String name = typeName(operand.body());
        steps = new ArrayList<>();
        outline = new ArrayList<>();
        steps(operand.steps());
        operands.add(new Operand(name, List.copyOf(steps)));
        alternatives.add(new StepLine(depth - 1, "As " + name + ":", List.of()));
        alternatives.addAll(outline);
      }
      depth -= 2;
      steps = outer;
      outline = outerOutline;
      outline.add(
          new StepLine(
              depth,
              "One of these runs "
                  + methodName(Knowledge.object(call))
                  + " here; the code does not say which:",
              List.of(call)));
      outline.addAll(alternatives);
      steps.add(new Alternatives(call, List.copyOf(operands), step.profiles()));
    }

    /**
     * A message pushed to STOMP subscribers: a note over the unit, since the subscribers are
     * clients (a browser) that no fact of the code names; the WebSocket endpoints of the unit say
     * where they connect (ADR 0080).
     */
    private void stompPush(FlowModel.Step step, Fact push) {
      EntityId unit = step.over().getFirst();
      SortedSet<String> paths = new TreeSet<>();
      step.facts().stream().skip(1).forEach(e -> paths.add(e.object().value()));
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
      note(step.over(), text.toString(), step.profiles(), step.facts());
    }

    private void unresolved(FlowModel.Step step, Fact fact) {
      String reason = attribute(fact, "reason");
      StringBuilder text =
          new StringBuilder(nameOf(parse(step.detail().getFirst())))
              .append(" calls ")
              .append(fact.object().value());
      text.append(", which Lantern could not link");
      if (reason != null) {
        text.append(" (").append(reason.replace('-', ' '));
        List<String> keys = Elements.strings(fact, "unresolved");
        if (!keys.isEmpty()) {
          text.append(" ").append(String.join(", ", keys));
        }
        text.append(")");
      }
      text.append("; the trace does not follow it.");
      note(step.over(), text.toString(), step.profiles(), step.facts());
    }

    /**
     * A run of the unit's data access in its database as one message (ADR 0047, amendment for
     * #272): each verb with what it names, in order.
     */
    private void data(FlowModel.Step step) {
      EntityId unit = step.from();
      Map<String, Set<String>> parts = new LinkedHashMap<>();
      Set<String> info = new LinkedHashSet<>();
      for (FlowModel.Part part : step.parts()) {
        parts.computeIfAbsent(part.verb(), v -> new LinkedHashSet<>()).addAll(part.what());
        String text = sharedTables(unit, part);
        if (text != null) {
          info.add(text);
        }
      }
      String code = step.detail().isEmpty() ? null : step.detail().getFirst();
      message(
          unit,
          step.to(),
          label(parts),
          code,
          false,
          step.profiles(),
          info.isEmpty() ? null : String.join(" ", info),
          step.facts(),
          null);
    }

    /** Which of the tables a unit reads or writes are another unit's, or null for none. */
    private String sharedTables(EntityId unit, FlowModel.Part part) {
      if (part.verb().equals("Calls")) {
        return null;
      }
      Set<String> tables = new TreeSet<>(part.what());
      List<String> info = new ArrayList<>();
      for (Fact shared : knowledge.relations(unit, Types.ACCESSES_DATA_OF)) {
        if (elements.alternativeLinks().containsKey(shared)) {
          continue;
        }
        SortedSet<String> theirs = new TreeSet<>();
        Elements.strings(shared, part.verb().equals("Reads") ? "reads" : "writes")
            .forEach(t -> theirs.add(tableName(t)));
        theirs.retainAll(tables);
        if (!theirs.isEmpty()) {
          info.add(
              String.join(", ", theirs)
                  + (theirs.size() == 1 ? " is a table of " : " are tables of ")
                  + nameOf(Knowledge.object(shared))
                  + ".");
        }
      }
      return info.isEmpty() ? null : String.join(" ", info);
    }

    /** Data access in a database that is not known: none, or one of several. */
    private void unknownDatabase(FlowModel.Step step) {
      EntityId unit = step.over().getFirst();
      FlowModel.Part part = step.parts().getFirst();
      String unknown;
      if (step.detail().isEmpty()) {
        unknown = "; no datasource of it was found, so which database is not known.";
      } else {
        List<String> databases = step.detail().stream().map(d -> nameOf(parse(d))).toList();
        unknown = " in one of " + list(databases) + "; the code does not say which.";
      }
      String text =
          part.verb().equals("Calls")
              ? nameOf(unit) + " calls " + part.what().getFirst() + unknown
              : nameOf(unit)
                  + " "
                  + part.verb().toLowerCase(Locale.ROOT)
                  + " "
                  + String.join(", ", part.what())
                  + unknown;
      note(step.over(), text, step.profiles(), step.facts());
    }
  }

  /** Every stored flow as traced here, in the order stored. */
  public List<Trace> traces() {
    if (traces == null) {
      List<Trace> read = new ArrayList<>();
      for (FlowModel.Flow flow : FlowModel.read(knowledge)) {
        read.add(new Trace(flow));
      }
      traces = List.copyOf(read);
    }
    return traces;
  }

  // Choosing the flows.

  /** The rank of ADR 0047: the containers touched, then a hand-off, then the entry point. */
  private static final Comparator<Trace> RANK =
      Comparator.comparingInt(Trace::touches)
          .reversed()
          .thenComparing(t -> t.asyncThrough.isEmpty())
          .thenComparing(t -> t.entry.key());

  /** The candidates in the rank order of ADR 0047, as a scope no model judged shows them. */
  public List<Candidate> byRank(List<Candidate> candidates) {
    Map<EntityId, Trace> byFlow = new HashMap<>();
    for (Trace trace : traces()) {
      byFlow.put(trace.flow.id(), trace);
    }
    List<Candidate> ranked = new ArrayList<>(candidates);
    ranked.sort(
        Comparator.comparing((Candidate c) -> byFlow.get(c.flow()), Comparator.nullsLast(RANK)));
    return ranked;
  }

  /**
   * The flows the scope shows, in the order shown: none part of another or of the shape of another.
   *
   * @param judged the flows the model judged significant for the scope, in its order, by ID; null
   *     when it did not judge them, so the highest ranked are shown (ADR 0047, ADR 0070)
   */
  public List<Trace> chosen(List<String> judged) {
    List<Trace> candidates = new ArrayList<>();
    for (Trace trace : traces()) {
      // The candidates are those that leave their unit.
      if (trace.flow.candidate()) {
        candidates.add(trace);
      }
    }
    candidates.sort(RANK);
    candidates.removeIf(t -> t.participants.stream().noneMatch(scope::contains));
    if (judged != null) {
      // The model's choice, in its order: every flow it judged significant that is in scope.
      Map<String, Trace> byId = new HashMap<>();
      candidates.forEach(t -> byId.put(t.flow.id().toString(), t));
      List<Trace> kept = new ArrayList<>();
      for (String id : judged) {
        Trace trace = byId.get(id);
        if (trace != null && !kept.contains(trace)) {
          kept.add(trace);
        }
      }
      // The model is shown one flow for each stored shape, which tells flows apart by the messages
      // they follow; two it keeps may still be one picture with other labels (a PUT and a DELETE of
      // the same resource), so only its first of each drawn shape is drawn (#701).
      return firstOfEachShape(kept, ScopeFlows::shape);
    }
    List<Trace> first = new ArrayList<>();
    // Business weight (ADR 0070): how a person signs in, and the socket a frontend opens for the
    // system to push to it, come before the ranked rest, the best ranked of each.
    for (Predicate<Trace> weighty :
        List.<Predicate<Trace>>of(ScopeFlows::signsIn, ScopeFlows::opensSocket)) {
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
    candidates.sort(Comparator.comparing(ScopeFlows::crud).thenComparingInt(ScopeFlows::tier));
    for (Trace trace : candidates) {
      consider(kept, trace, !crud(trace) || kept.size() < MIN_FLOWS);
    }
    return kept;
  }

  /**
   * Whether the flow only reads or writes its unit's own data: it starts at its entry point, not in
   * a browser, and reaches nothing but databases.
   */
  private static boolean crud(Trace trace) {
    EntityId gateway = trace.gateway == null ? null : trace.gateway.gateway();
    return trace.start == null
        && trace.pushes == 0
        && trace.unresolved.isEmpty()
        && trace.participants.stream()
            .allMatch(
                p ->
                    p.equals(trace.entry.unit)
                        || p.equals(gateway)
                        || p.type().equals(Types.DATABASE));
  }

  /**
   * Shows the flow unless it is part of one shown or of the shape of one shown; a flow that holds
   * flows shown and touches as many takes the first one's place.
   *
   * @param add whether the flow is added when it holds none shown
   */
  private static void consider(List<Trace> kept, Trace trace, boolean add) {
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
      case Types.ENDPOINT, Types.WEBSOCKET_ENDPOINT, Types.STOMP_MAPPING -> 0;
      case Types.MESSAGE_LISTENER -> 1;
      default -> 2;
    };
  }

  /** The first of each shape, in the order given. */
  public static <T> List<T> firstOfEachShape(List<T> ordered, Function<T, ?> shape) {
    Set<Object> seen = new HashSet<>();
    List<T> first = new ArrayList<>();
    for (T item : ordered) {
      if (seen.add(shape.apply(item))) {
        first.add(item);
      }
    }
    return first;
  }

  /** What makes two flows look the same: how they start and their lifelines, in order. */
  private static List<Object> shape(Trace trace) {
    return List.of(trace.entry.fact.predicate(), List.copyOf(trace.participants));
  }

  /**
   * Whether the flow is a request that signs a person in: the request a frontend sends, when the
   * flow starts in the browser with a person, else the endpoint's own request.
   */
  private static boolean signsIn(Trace trace) {
    String path;
    if (trace.start != null) {
      if (trace.start.person == null || socket(trace.start.link)) {
        return false;
      }
      path = attribute(trace.start.link, "path");
    } else if (trace.entry.fact.predicate().equals(Types.ENDPOINT)) {
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
  private static boolean opensSocket(Trace trace) {
    return trace.start != null && socket(trace.start.link);
  }

  // What the roles are shown (knowledge-build epic, section 11.5).

  /**
   * A flow the significance role is shown: one for each shape the scope's candidate flows have.
   *
   * @param flow the flow's ID
   * @param unit the unit it starts in
   * @param starts what starts it, with its handler
   * @param entry the fact that makes its handler an entry point
   * @param containers its participants in order, by name
   * @param steps its steps, each with the facts behind it
   * @param async the destinations and jobs it hands work on through, by name
   * @param crossed the deployable units it crosses
   */
  public record Candidate(
      EntityId flow,
      EntityId unit,
      String starts,
      Fact entry,
      List<String> containers,
      List<StepLine> steps,
      List<String> async,
      List<EntityId> crossed) {

    /** Whether it crosses a unit boundary: more than one deployable unit takes part. */
    public boolean crossesUnits() {
      return crossed.size() > 1;
    }
  }

  /**
   * The scope's candidate flows, one for each stored shape: the one with the smallest ID, as {@code
   * SAME_SHAPE_AS} points to it, listed by what starts them, so the same facts always show the same
   * flows in the same order.
   */
  public List<Candidate> candidates() {
    SortedMap<String, Trace> byShape = new TreeMap<>();
    for (Trace trace : traces()) {
      FlowModel.Flow flow = trace.flow;
      if (!flow.candidate() || trace.participants.stream().noneMatch(scope::contains)) {
        continue;
      }
      String shape = knowledge.attribute(flow.id(), Types.FLOW_SHAPE).orElse("flow " + flow.id());
      Trace held = byShape.get(shape);
      if (held == null || flow.id().toString().compareTo(held.flow.id().toString()) < 0) {
        byShape.put(shape, trace);
      }
    }
    List<Candidate> result = new ArrayList<>();
    for (Trace trace : byShape.values()) {
      result.add(candidate(trace));
    }
    result.sort(CANDIDATE_ORDER);
    return result;
  }

  /** Candidates by what starts them, then by the unit and the flow. */
  private static final Comparator<Candidate> CANDIDATE_ORDER =
      Comparator.comparing(Candidate::starts)
          .thenComparing(c -> c.unit().toString())
          .thenComparing(c -> c.flow().toString());

  /** The flow as the roles are shown it. */
  public Candidate candidate(Trace trace) {
    if (units == null) {
      units = Elements.units(knowledge);
    }
    Heading heading = trace.heading();
    List<String> containers = new ArrayList<>();
    List<EntityId> crossed = new ArrayList<>();
    for (EntityId participant : trace.participants) {
      containers.add(nameOf(participant));
      if (units.contains(participant)) {
        crossed.add(participant);
      }
    }
    if (!crossed.contains(trace.entry.unit)) {
      crossed.addFirst(trace.entry.unit);
    }
    return new Candidate(
        trace.flow.id(),
        trace.entry.unit,
        heading.starts() + " (" + methodName(trace.entry.method) + ")",
        trace.entry.fact,
        containers,
        List.copyOf(trace.outline),
        List.copyOf(trace.asyncThrough),
        crossed);
  }

  private Heading headingOf(Trace trace) {
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
      case Types.ENDPOINT ->
          new Heading(
              value + " on " + nameOf(entry.unit),
              value + " (" + handler + ")",
              nameOf(entry.unit) + " receives " + value);
      case Types.MESSAGE_LISTENER ->
          new Heading(
              "Message on " + value + " to " + nameOf(entry.unit),
              "A message on " + value + " (" + handler + ")",
              "a message arrives on " + value + " for " + nameOf(entry.unit));
      case Types.STOMP_MAPPING -> {
        boolean subscribe = "subscribe".equals(attribute(entry.fact, "kind"));
        String what = subscribe ? "STOMP subscription to " : "STOMP message to ";
        yield new Heading(
            what + value + " on " + nameOf(entry.unit),
            "A " + what + value + " (" + handler + ")",
            subscribe
                ? "a client subscribes to " + value + " on " + nameOf(entry.unit)
                : "a client sends a STOMP message to " + value + " on " + nameOf(entry.unit));
      }
      case Types.WEBSOCKET_ENDPOINT ->
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
          String job = Components.jobKind(entry.fact);
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

  /**
   * The scenario a flow is, as the name of its entry point's method says it, such as "Create
   * organization" for {@code createOrganization}; for a method of one word, such as {@code
   * Status.get}, with the words of its type that do not say what kind of class it is ("Get
   * status"). The LLM words a better one where it can (ADR 0047, amendment for #272).
   */
  public static String scenario(Entry entry) {
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

  // Helpers.

  private String nameOf(EntityId entity) {
    return names.apply(entity);
  }

  private String deliveryInfo(Fact link) {
    String binding = bindingInfo(link);
    String resolution = Elements.resolution(link);
    if (resolution == null) {
      return binding;
    }
    return binding == null
        ? capitalize(resolution) + "."
        : binding + " " + capitalize(resolution) + ".";
  }

  private static String bindingInfo(Fact link) {
    String key = attribute(link, "routingKey");
    String binding = attribute(link, "bindingKey");
    if (key == null || binding == null) {
      return null;
    }
    return "Routing key " + key + ", bound with " + binding + ".";
  }

  /** "Reads orders, payment; writes payment": each verb with what it names, in order. */
  private static String label(Map<String, Set<String>> parts) {
    List<String> clauses = new ArrayList<>();
    for (Map.Entry<String, Set<String>> part : parts.entrySet()) {
      List<String> named = new ArrayList<>(part.getValue());
      String shown =
          named.size() <= NAMED
              ? String.join(", ", named)
              : String.join(", ", named.subList(0, NAMED))
                  + " and "
                  + (named.size() - NAMED)
                  + " more";
      String verb = clauses.isEmpty() ? part.getKey() : part.getKey().toLowerCase(Locale.ROOT);
      clauses.add(verb + " " + shown);
    }
    return String.join("; ", clauses);
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

  /** "JobRunr job TaskReminder": the kind of a job and its name. */
  private static String jobName(Fact job) {
    return Components.jobKind(job) + " " + simpleName(job.object().value());
  }

  /** A class's simple name when the value is a binary class name, else the value. */
  private static String simpleName(String value) {
    if (!value.matches("[\\w$]+(\\.[\\w$]+)+")) {
      return value;
    }
    return value.substring(value.lastIndexOf('.') + 1).replace('$', '.');
  }

  private static String capitalize(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  /** {@code Type.method} of a method ID, without parameters or package. */
  public static String methodName(EntityId method) {
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

  private static String join(String a, String b) {
    return a == null ? b : a + " · " + b;
  }

  /** "a, b and c": the names joined as a sentence lists them. */
  public static String list(List<String> names) {
    if (names.size() == 1) {
      return names.getFirst();
    }
    return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }

  private static String attribute(Fact fact, String name) {
    return Elements.attribute(fact, name, null);
  }
}
