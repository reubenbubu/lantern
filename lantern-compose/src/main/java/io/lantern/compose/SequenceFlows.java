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
import io.lantern.derive.FlowModel;
import io.lantern.derive.Flows;
import io.lantern.derive.Knowledge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
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

  /** The flows shown, once chosen. */
  private List<Trace> chosen;

  /**
   * How a flow starts in the browser, in the scope: a person using a frontend (when the frontend
   * has screens for one in the scope), the frontend's call that reaches the entry point, and the
   * gateway and route it goes through, if any (ADR 0047, amendment for #331).
   *
   * @param person null when no person of the scope has screens on the frontend
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

  /**
   * @param units the deployable units in scope; flows that start in or pass through one are shown
   */
  SequenceFlows(Knowledge knowledge, Assembler assembler, C4Views c4, Set<EntityId> units) {
    this.knowledge = knowledge;
    this.assembler = assembler;
    this.c4 = c4;
    this.scope = units;
  }

  // Entry points.

  /** What starts a flow: an endpoint, a listener or a scheduled job of a unit. */
  record Entry(EntityId unit, EntityId method, Fact fact) {

    String key() {
      return unit + " " + method;
    }
  }

  // Drawing the stored flows.

  /** A step with the Spring profiles under which alone it happens. */
  private record Item(SequenceDiagram.Step step, List<String> profiles) {}

  /** One stored flow as the scope draws it. */
  private final class Trace {
    final FlowModel.Flow flow;
    final Entry entry;

    /** How the browser starts the flow; null when it starts at its entry point. */
    final Start start;

    /** How a gateway starts the flow, when the browser does not; null when none does. */
    final FlowModel.GatewayStart gateway;

    List<Item> items = new ArrayList<>();
    final Set<EntityId> participants = new LinkedHashSet<>();
    final Set<String> reached;
    final SortedSet<String> asyncThrough = new TreeSet<>();
    final List<String> unresolved;

    /** The steps as the LLM's prompt shows them: by their facts, without intent phrases. */
    List<WordingPrompt.FlowStep> outline = new ArrayList<>();

    int depth;

    /** How many pushes to STOMP subscribers the flow shows, which leave the unit as notes. */
    final int pushes;

    Trace(FlowModel.Flow flow) {
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
        for (Map.Entry<EntityId, List<Fact>> e : c4.screensOn(stored.frontend()).entrySet()) {
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
            null,
            List.of(),
            "In a web browser.",
            start.personFacts);
      }
      steps(flow.steps());
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
      outline.add(step(line(from, to, kind, plain, code, info), profiles, plainFacts));
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
      participants.addAll(over);
      outline.add(step(new StringBuilder("Note: ").append(text), profiles, facts));
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

    /** The units, stores, queues and external systems the flow touches. */
    int touches() {
      return participants.size() + (pushes > 0 ? 1 : 0);
    }

    private void steps(List<FlowModel.Step> steps) {
      for (FlowModel.Step step : steps) {
        draw(step);
      }
    }

    /** Draws a stored step as the trace that found it did. */
    private void draw(FlowModel.Step step) {
      List<String> profiles = step.profiles();
      List<Fact> facts = step.facts();
      Fact first = facts.isEmpty() ? null : facts.getFirst();
      switch (step.action()) {
        case FlowModel.USES_SCREENS -> {
          // Drawn for the scope's person, before the steps.
        }
        case FlowModel.FRONTEND_CALL -> frontendCall(step);
        case FlowModel.ROUTE -> route(step);
        case FlowModel.GATEWAY_ROUTE -> gatewayRoute(step);
        case FlowModel.HTTP_CALL -> httpCall(step, first);
        case FlowModel.MAIL -> {
          Assembler.Phrased phrase = assembler.phrase(first);
          List<Fact> all = new ArrayList<>(facts);
          if (phrase != null) {
            all.addAll(phrase.facts());
          }
          String plain = "Sends an email";
          message(
              step.from(),
              step.to(),
              phrase != null ? phrase.text() : plain,
              attribute(first, "protocol"),
              null,
              profiles,
              null,
              all,
              plain,
              facts);
        }
        case FlowModel.SEND -> {
          String key = attribute(first, "routingKey");
          Assembler.Phrased phrase = assembler.phrase(first);
          List<Fact> all = new ArrayList<>(facts);
          if (phrase != null) {
            all.addAll(phrase.facts());
          }
          String plain = key == null ? "Sends a message" : "Sends " + key;
          message(
              step.from(),
              step.to(),
              phrase != null ? phrase.text() : plain,
              attribute(first, "protocol"),
              SequenceDiagram.MessageKind.ASYNC,
              profiles,
              C4Views.resolution(first) == null
                  ? null
                  : capitalize(C4Views.resolution(first)) + ".",
              all,
              plain,
              facts);
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
              SequenceDiagram.MessageKind.ASYNC,
              profiles,
              deliveryInfo(first),
              facts);
        }
        case FlowModel.SCHEDULE ->
            message(
                step.from(),
                step.to(),
                "Schedules " + simpleName(first.object().value()),
                ExplorerSections.jobKind(first),
                SequenceDiagram.MessageKind.ASYNC,
                profiles,
                null,
                facts);
        case FlowModel.JOB_LATER -> {
          EntityId unit = step.over().getFirst();
          note(
              step.over(),
              nameOf(unit)
                  + " does not wait: the "
                  + ExplorerSections.jobKind(first)
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
          List<String> names = step.detail().stream().map(d -> typeName(parse(d))).toList();
          note(
              step.over(),
              "One of "
                  + list(names)
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
          null,
          List.of(),
          info.isEmpty() ? null : String.join(" ", info),
          step.facts());
    }

    /** The gateway the frontend called routing the call on to the entry point's unit. */
    private void route(FlowModel.Step step) {
      boolean socket = socket(start.link);
      Fact route = start.route;
      List<Fact> facts = new ArrayList<>(step.facts());
      List<Fact> plainFacts = List.copyOf(facts);
      String plain = socket ? "Relays the socket" : "Routes to " + methodName(entry.method);
      StringBuilder routeInfo =
          new StringBuilder("Route ")
              .append(
                  RepoSections.attribute(start.link, "throughRoute", "of " + nameOf(start.via)));
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
      message(
          step.from(),
          step.to(),
          phrase != null ? phrase.text() : plain,
          attribute(start.link, "reachesEndpoint"),
          null,
          step.profiles(),
          routeInfo.toString(),
          facts,
          plain,
          plainFacts);
    }

    /** A gateway routing the request it receives to the unit, with the request as it serves it. */
    private void gatewayRoute(FlowModel.Step step) {
      List<Fact> facts = new ArrayList<>(step.facts());
      List<Fact> plainFacts = List.copyOf(facts);
      String plain = "Routes to " + methodName(entry.method);
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
      Assembler.Phrased phrase = gateway.route() == null ? null : assembler.phrase(gateway.route());
      if (phrase != null) {
        facts.addAll(phrase.facts());
      }
      message(
          step.from(),
          step.to(),
          phrase != null ? phrase.text() : plain,
          attribute(gateway.routed(), "served"),
          null,
          step.profiles(),
          info.toString(),
          facts,
          plain,
          plainFacts);
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
      String resolution = C4Views.resolution(link);
      if (resolution != null) {
        info.add(capitalize(resolution) + ".");
      }
      for (Map.Entry<Fact, Fact> alternative : c4.alternativeLinks().entrySet()) {
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
      List<Fact> facts = new ArrayList<>(step.facts());
      List<Fact> plainFacts = List.copyOf(facts);
      String plain = "Calls " + (handler == null ? nameOf(to) : methodName(handler));
      Assembler.Phrased phrase = assembler.phrase(link);
      if (phrase != null) {
        facts.addAll(phrase.facts());
      }
      message(
          step.from(),
          to,
          phrase != null ? phrase.text() : plain,
          endpoint,
          null,
          step.profiles(),
          info.isEmpty() ? null : String.join(" ", info),
          facts,
          plain,
          plainFacts);
    }

    /**
     * A call one of several implementations runs, each as an operand of an {@code alt} frame, since
     * the code does not say which one runs.
     */
    private void alternatives(FlowModel.Step step, Fact call) {
      List<Item> outer = items;
      List<WordingPrompt.FlowStep> outerOutline = outline;
      List<SequenceDiagram.Operand> operands = new ArrayList<>();
      List<WordingPrompt.FlowStep> alternatives = new ArrayList<>();
      depth += 2;
      for (FlowModel.Operand operand : step.operands()) {
        String name = typeName(operand.body());
        items = new ArrayList<>();
        outline = new ArrayList<>();
        steps(operand.steps());
        operands.add(new SequenceDiagram.Operand("as " + name, frames(items), List.of()));
        alternatives.add(new WordingPrompt.FlowStep(depth - 1, "As " + name + ":", List.of()));
        alternatives.addAll(outline);
      }
      depth -= 2;
      items = outer;
      outline = outerOutline;
      outline.add(
          new WordingPrompt.FlowStep(
              depth,
              "One of these runs "
                  + methodName(Knowledge.object(call))
                  + " here; the code does not say which:",
              List.of(call)));
      outline.addAll(alternatives);
      items.add(
          new Item(
              new SequenceDiagram.Frame(
                  SequenceDiagram.Operator.ALT, operands, assembler.facts(List.of(call))),
              step.profiles()));
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
        List<String> keys = C4Views.strings(fact, "unresolved");
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
          null,
          step.profiles(),
          info.isEmpty() ? null : String.join(" ", info),
          step.facts());
    }

    /** Which of the tables a unit reads or writes are another unit's, or null for none. */
    private String sharedTables(EntityId unit, FlowModel.Part part) {
      if (part.verb().equals("Calls")) {
        return null;
      }
      Set<String> tables = new TreeSet<>(part.what());
      List<String> info = new ArrayList<>();
      for (Fact shared : knowledge.relations(unit, C4Views.ACCESSES_DATA_OF)) {
        if (c4.alternativeLinks().containsKey(shared)) {
          continue;
        }
        SortedSet<String> theirs = new TreeSet<>();
        C4Views.strings(shared, part.verb().equals("Reads") ? "reads" : "writes")
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
        List<String> names = step.detail().stream().map(d -> nameOf(parse(d))).toList();
        unknown = " in one of " + list(names) + "; the code does not say which.";
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

  /** The most tables or methods a data access label names for each verb before saying how many. */
  private static final int NAMED = 4;

  /** "Reads orders, payment; writes payment": each verb with what it names, in order. */
  private static String label(Map<String, Set<String>> parts) {
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
    return ExplorerSections.jobKind(job) + " " + simpleName(job.object().value());
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
    List<Trace> candidates = new ArrayList<>();
    for (FlowModel.Flow flow : FlowModel.read(knowledge)) {
      // Every stored flow is drawn, so the document holds the facts of all; the candidates are
      // those that leave their unit.
      Trace trace = new Trace(flow);
      if (flow.candidate()) {
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
   * as {@code People.SIGN_IN} in lantern-link, which this module does not depend on.
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
