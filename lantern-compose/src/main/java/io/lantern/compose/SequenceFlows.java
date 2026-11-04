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
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.FlowModel;
import io.lantern.derive.ScopeFlows;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The main flows of a scope as sequence diagrams, by {@code docs/design.md} (Composition, Sequence
 * diagrams): each traced from an entry point (an HTTP endpoint, a message listener or a scheduled
 * job) through the call graph inside its deployable unit, keeping only the steps that cross a
 * container boundary (HTTP calls, sends and data access), and stitched across units through the
 * linker's links (ADR 0042): an HTTP call goes on in the endpoint it reaches, a send in each
 * listener it reaches, after the sender's own steps, since it happens later.
 *
 * <p>Which flows a scope shows, their steps and what starts them are queries on the stored model
 * ({@link ScopeFlows}, ADR 0115); this class draws them. Nothing is invented: a step is drawn only
 * for a fact, and a call Lantern could not link ends there with a note. Participants are
 * containers: deployable units, databases, topics, queues and exchanges, and external systems,
 * named as on the container diagram. An HTTP call or a send is labelled with the LLM's intent
 * phrase for its link where there is one (ADR 0050), the endpoint or protocol after it; other
 * labels say what the facts say (the handler, the routing key). A flow's walkthrough and pre- and
 * post-conditions are the LLM's where it worded the flow as traced here (ADR 0058), else the prose
 * says what the trace found and the conditions are not known.
 */
final class SequenceFlows {

  private static final String UNKNOWN = "Not known from the code.";

  private final ArchitectureModel model;
  private final Assembler assembler;
  private final C4Views c4;
  private final ScopeFlows flows;

  /**
   * The flows the model judged significant for this scope, in the order it gave them (knowledge-
   * build epic, section 11.5), by the flow's ID; {@code null} when it did not judge them.
   */
  private final List<String> judged;

  /** The flows shown, once chosen. */
  private List<ScopeFlows.Trace> chosen;

  /** Each flow's steps as drawn, once drawn. */
  private final Map<ScopeFlows.Trace, List<Item>> drawn = new IdentityHashMap<>();

  /**
   * @param units the deployable units in scope; flows that start in or pass through one are shown
   */
  SequenceFlows(ArchitectureModel model, Assembler assembler, C4Views c4, Set<EntityId> units) {
    this(model, assembler, c4, units, null);
  }

  /**
   * @param units the deployable units in scope; flows that start in or pass through one are shown
   * @param judged the flows the model judged significant for the scope, in its order, by ID; null
   *     when it did not judge them, so the highest ranked are shown, marked as chosen by rank
   */
  SequenceFlows(
      ArchitectureModel model,
      Assembler assembler,
      C4Views c4,
      Set<EntityId> units,
      List<String> judged) {
    this.model = model;
    this.assembler = assembler;
    this.c4 = c4;
    this.judged = judged == null ? null : List.copyOf(judged);
    // The names a flow's steps use are added to the document, as the diagrams name them.
    this.flows = new ScopeFlows(model, c4.elements(), units, this::nameOf);
  }

  // Drawing the traced flows.

  /** A step with the Spring profiles under which alone it happens. */
  private record Item(SequenceDiagram.Step step, List<String> profiles) {}

  /** The flow's steps as drawn, labelled with intent phrases where their links have them. */
  private List<Item> drawn(ScopeFlows.Trace trace) {
    return drawn.computeIfAbsent(trace, t -> items(t.steps()));
  }

  private List<Item> items(List<ScopeFlows.Step> steps) {
    List<Item> items = new ArrayList<>();
    for (ScopeFlows.Step step : steps) {
      items.add(new Item(draw(step), step.profiles()));
    }
    return items;
  }

  private SequenceDiagram.Step draw(ScopeFlows.Step step) {
    return switch (step) {
      case ScopeFlows.Message message -> {
        Assembler.Phrased phrase =
            message.phrased() == null ? null : assembler.phrase(message.phrased());
        List<Fact> facts = new ArrayList<>(message.facts());
        if (phrase != null) {
          facts.addAll(phrase.facts());
        }
        yield new SequenceDiagram.Message(
            message.from(),
            message.to(),
            phrase != null ? phrase.text() : message.plain(),
            message.code(),
            message.async() ? SequenceDiagram.MessageKind.ASYNC : null,
            false,
            null,
            message.profiles(),
            message.info() == null ? List.of() : List.of(TextRun.of(message.info())),
            assembler.facts(facts));
      }
      case ScopeFlows.Note note ->
          new SequenceDiagram.Note(
              note.over(), List.of(TextRun.of(note.text())), assembler.facts(note.facts()));
      // A call one of several implementations runs: each an operand of an alt frame.
      case ScopeFlows.Alternatives alternatives -> {
        List<SequenceDiagram.Operand> operands = new ArrayList<>();
        for (ScopeFlows.Operand operand : alternatives.operands()) {
          operands.add(
              new SequenceDiagram.Operand(
                  "as " + operand.name(), frames(items(operand.steps())), List.of()));
        }
        yield new SequenceDiagram.Frame(
            SequenceDiagram.Operator.ALT, operands, assembler.facts(List.of(alternatives.call())));
      }
    };
  }

  // Choosing the flows.

  /** The entry points of the flows shown, in the order they are shown. */
  List<ScopeFlows.Entry> mainEntries() {
    return chosen().stream().map(ScopeFlows.Trace::entry).toList();
  }

  /** The flows shown ({@link ScopeFlows#chosen}). */
  private List<ScopeFlows.Trace> chosen() {
    if (chosen == null) {
      // Every stored flow is drawn, so the document holds the facts of all.
      flows.traces().forEach(this::drawn);
      chosen = flows.chosen(judged);
    }
    return chosen;
  }

  /**
   * One subsection per flow shown, each holding its prose, its trigger and its sequence diagram;
   * none when no entry point reaches outside its unit.
   *
   * @param section the section's ID
   * @param blocks receives the section's own blocks: a paragraph when there is no flow
   */
  List<Section> flows(String section, List<Block> blocks) {
    List<ScopeFlows.Trace> kept = chosen();
    if (kept.isEmpty() && judged != null && !candidates().isEmpty()) {
      // The model judged the traced flows and found none worth drawing (ADR 0108).
      blocks.add(
          new Callout(
              assembler.id(section, "none-significant"),
              Callout.Variant.NOTE,
              null,
              null,
              List.of(TextRun.of(NONE_JUDGED_SIGNIFICANT)),
              List.of()));
      return List.of();
    }
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
    if (judged == null) {
      // Without the model's judgement the flows are the highest ranked, and the reader is told so.
      blocks.add(
          new Callout(
              assembler.id(section, "by-rank"),
              Callout.Variant.NOTE,
              null,
              null,
              List.of(TextRun.of(CHOSEN_BY_RANK)),
              List.of()));
    }
    List<Section> result = new ArrayList<>();
    Set<String> titles = new HashSet<>();
    for (ScopeFlows.Trace trace : kept) {
      result.add(flowSection(section, trace, titles));
    }
    return result;
  }

  /** What a scope says whose traced flows the model judged none worth drawing. */
  static final String NONE_JUDGED_SIGNIFICANT =
      "Flows were traced from the code, but the model judged none of them worth drawing.";

  /** What a scope whose flows no model judged says of them. */
  static final String CHOSEN_BY_RANK =
      "These flows were chosen by rank, not judged: no model chose which of the traced flows are"
          + " worth drawing, so the highest ranked are shown, at most "
          + ScopeFlows.MAX_FLOWS
          + ".";

  // What the LLM is shown (knowledge-build epic, section 11.5).

  /** The scope's candidate flows ({@link ScopeFlows#candidates}). */
  List<ScopeFlows.Candidate> candidates() {
    return flows.candidates();
  }

  /** The content hash of a stored flow's steps, which its narrative holds for, or null. */
  private String content(EntityId flow) {
    return FlowModel.content(model, flow).orElse(null);
  }

  /**
   * A flow's page. Its title is the scenario alone; the request, message or schedule that starts it
   * is the trigger in its Overview table (#767). A title another flow of the scope already has gets
   * its unit's name, then its handler's, so each page's title is its own.
   */
  private Section flowSection(String section, ScopeFlows.Trace trace, Set<String> titles) {
    ScopeFlows.Entry entry = trace.entry();
    String handler = ScopeFlows.methodName(entry.method());
    String id = assembler.id(section, nameOf(entry.unit()) + "." + handler);
    ScopeFlows.Heading heading = trace.heading();
    String starts = heading.starts();
    // The LLM's title, walkthrough and conditions, where it worded this flow as traced here (ADR
    // 0058); else the scenario as the entry point's name says it.
    EntityId flow = trace.flow().id();
    Assembler.FlowWording worded = assembler.flow(flow, content(flow));
    String scenario =
        worded == null || worded.title() == null ? ScopeFlows.scenario(entry) : worded.title();
    String title = scenario;
    if (!titles.add(title)) {
      title = scenario + " (" + nameOf(entry.unit()) + ")";
      if (!titles.add(title)) {
        title = scenario + " (" + nameOf(entry.unit()) + ", " + handler + ")";
        // One handler can start two flows of a unit (two paths, or a gateway route to it too).
        String base = title;
        for (int n = 2; !titles.add(title); n++) {
          title = base + " " + n;
        }
      }
    }
    Assembler.Text walkthrough =
        worded == null ? null : assembler.worded(entry.method(), worded.walkthrough());
    Assembler.Text preconditions =
        worded == null ? null : assembler.worded(entry.method(), worded.preconditions());
    Assembler.Text postconditions =
        worded == null ? null : assembler.worded(entry.method(), worded.postconditions());
    List<Fact> rowFacts = new ArrayList<>(List.of(entry.fact()));
    for (Assembler.Text text : Arrays.asList(preconditions, postconditions)) {
      if (text != null) {
        text.facts().forEach(f -> rowFacts.add(model.fact(f)));
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
            assembler.facts(List.of(entry.fact()))));
    steps.addAll(frames(drawn(trace)));
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
    for (EntityId participant : trace.participants()) {
      assembler.entity(participant);
      participants.add(
          new SequenceDiagram.Participant(
              participant,
              null,
              c4.elements().technology(participant),
              null,
              assembler.facts(c4.elements().containerFacts(participant))));
    }
    blocks.add(
        new SequenceDiagram(
            assembler.id(id, "diagram"),
            title,
            participants,
            steps,
            assembler.facts(List.of(entry.fact()))));
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
  private String prose(ScopeFlows.Trace trace, String starts) {
    ScopeFlows.Entry entry = trace.entry();
    StringBuilder text =
        new StringBuilder("Starts when ")
            .append(starts)
            .append(", handled by ")
            .append(ScopeFlows.methodName(entry.method()))
            .append('.');
    List<String> others = new ArrayList<>();
    // What starts it in the browser is named already.
    Set<EntityId> named = new HashSet<>(List.of(entry.unit()));
    if (trace.gateway() != null) {
      named.add(trace.gateway().gateway());
    }
    ScopeFlows.Start start = trace.start();
    if (start != null) {
      named.add(start.frontend());
      if (start.person() != null) {
        named.add(start.person());
      }
      if (start.via() != null) {
        named.add(start.via());
      }
    }
    trace.participants().stream()
        .filter(p -> !named.contains(p))
        .forEach(p -> others.add(nameOf(p)));
    if (!others.isEmpty()) {
      text.append(" It reaches ").append(ScopeFlows.list(others)).append('.');
    }
    List<String> async = trace.asyncThrough();
    if (!async.isEmpty()) {
      text.append(" It hands work on asynchronously through ")
          .append(ScopeFlows.list(async))
          .append('.');
    }
    List<String> unresolved = trace.unresolved();
    if (!unresolved.isEmpty()) {
      text.append(unresolved.size() == 1 ? " One call" : " " + unresolved.size())
          .append(unresolved.size() == 1 ? " could" : " calls could")
          .append(" not be followed: ")
          .append(ScopeFlows.list(unresolved))
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
                      "Spring profile"
                          + (profiles.size() > 1 ? "s " : " ")
                          + ScopeFlows.list(profiles),
                      inside,
                      List.of())),
              List.copyOf(facts)));
    }
    return result;
  }

  /** The element's name, the element added to the document. */
  private String nameOf(EntityId entity) {
    assembler.entity(entity);
    return assembler.name(entity);
  }
}
