package io.lantern.core.document;

import io.lantern.core.fact.EntityId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A runtime flow: messages between participants in time order. Templates draw it. Only what gives
 * an element its identity is required: a participant's entity, a frame's operator and operands and
 * a note's text; any other detail may be missing, and templates draw what is there.
 *
 * <p>Participants are held in the order given, left to right, each entity once. Messages and notes
 * name only participants of the diagram.
 *
 * @param id the block ID
 * @param title the title, or {@code null}
 * @param participants the lifelines, left to right
 * @param steps the messages, frames and notes, in time order
 * @param facts the IDs of the facts the diagram as a whole came from
 */
public record SequenceDiagram(
    String id, String title, List<Participant> participants, List<Step> steps, List<String> facts)
    implements Block {

  /** The {@code diagramKind} of a sequence diagram in the JSON form. */
  public static final String DIAGRAM_KIND = "sequence";

  public SequenceDiagram {
    Checks.elementId(id, "block ID");
    Checks.optionalText(title, "diagram title");
    participants = Checks.list(participants, "participants");
    steps = Checks.list(steps, "steps");
    facts = Checks.factIds(facts, "diagram facts");

    Set<EntityId> lifelines = new HashSet<>();
    for (Participant participant : participants) {
      if (!lifelines.add(participant.id())) {
        throw new IllegalArgumentException(
            "Duplicate participant " + participant.id() + " in diagram " + id);
      }
    }
    checkSteps(steps, lifelines, id);
  }

  private static void checkSteps(List<Step> steps, Set<EntityId> lifelines, String diagram) {
    for (Step step : steps) {
      switch (step) {
        case Message m -> {
          checkParticipant(m.from(), lifelines, diagram);
          checkParticipant(m.to(), lifelines, diagram);
        }
        case Note n -> n.over().forEach(p -> checkParticipant(p, lifelines, diagram));
        case Frame f -> f.operands().forEach(o -> checkSteps(o.steps(), lifelines, diagram));
      }
    }
  }

  private static void checkParticipant(EntityId id, Set<EntityId> lifelines, String diagram) {
    if (id != null && !lifelines.contains(id)) {
      throw new IllegalArgumentException(
          "Sequence diagram " + diagram + " names " + id + ", which is not a participant");
    }
  }

  /**
   * A lifeline, which shows an entity.
   *
   * @param id the entity the lifeline shows
   * @param label the text shown on the lifeline, or {@code null} for the entity's name
   * @param technology the technology line, or {@code null}
   * @param style the resolved presentation, or {@code null} for the renderer's defaults
   * @param facts the IDs of the facts the participant came from
   */
  public record Participant(
      EntityId id, String label, String technology, Diagram.Style style, List<String> facts) {

    public Participant {
      Objects.requireNonNull(id, "id");
      Checks.optionalText(label, "participant label");
      Checks.optionalText(technology, "participant technology");
      facts = Checks.factIds(facts, "participant facts");
    }
  }

  /** A step of a sequence diagram: a message, a frame or a note. */
  public sealed interface Step permits Message, Frame, Note {

    /** The IDs of the facts the step came from, sorted. */
    List<String> facts();
  }

  /**
   * A message from one participant to another, or to itself when both ends are the same.
   *
   * @param from the sending participant, or {@code null} when not known
   * @param to the receiving participant, or {@code null} when not known
   * @param label what the message is for, intent first, or {@code null}
   * @param code the protocol detail shown as code, such as {@code POST /v1/orders}, or {@code null}
   * @param messageKind whether it is sync, async or a return, or {@code null} for sync
   * @param activate whether an activation bar starts on the receiver, or {@code null} for the
   *     default: true for a sync message, false otherwise
   * @param deactivate whether the sender's activation bar ends, or {@code null} for the default:
   *     true for a return, false otherwise
   * @param profiles the Spring profiles under which alone the message is sent, any one being
   *     enough; empty when it is always sent
   * @param info further explanation for a walkthrough, or empty
   * @param facts the IDs of the facts the message came from
   */
  public record Message(
      EntityId from,
      EntityId to,
      String label,
      String code,
      MessageKind messageKind,
      Boolean activate,
      Boolean deactivate,
      List<String> profiles,
      List<Inline> info,
      List<String> facts)
      implements Step {

    public Message {
      Checks.optionalText(label, "message label");
      Checks.optionalText(code, "message code");
      profiles = Checks.profiles(profiles);
      info = Checks.list(info, "message info");
      facts = Checks.factIds(facts, "message facts");
    }
  }

  /** Whether the sender of a message waits, does not wait, or replies. */
  public enum MessageKind {
    SYNC,
    ASYNC,
    RETURN;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static MessageKind fromJsonName(String name) {
      Objects.requireNonNull(name, "messageKind");
      for (MessageKind kind : values()) {
        if (kind.jsonName().equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException("Unknown message kind: '" + name + "'");
    }
  }

  /**
   * A fragment around steps. Frames may nest.
   *
   * @param operator what kind of fragment it is
   * @param operands its parts, at least one; for {@code alt}, one per branch
   * @param facts the IDs of the facts the frame came from
   */
  public record Frame(Operator operator, List<Operand> operands, List<String> facts)
      implements Step {

    public Frame {
      Objects.requireNonNull(operator, "operator");
      operands = Checks.list(operands, "operands");
      if (operands.isEmpty()) {
        throw new IllegalArgumentException("A frame must have an operand");
      }
      facts = Checks.factIds(facts, "frame facts");
    }
  }

  /** What kind of fragment a frame is, as in UML. */
  public enum Operator {
    ALT,
    OPT,
    LOOP,
    PAR,
    BREAK,
    CRITICAL;

    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Operator fromJsonName(String name) {
      Objects.requireNonNull(name, "operator");
      for (Operator operator : values()) {
        if (operator.jsonName().equals(name)) {
          return operator;
        }
      }
      throw new IllegalArgumentException("Unknown frame operator: '" + name + "'");
    }
  }

  /**
   * One part of a frame: for {@code alt}, one branch.
   *
   * @param guard the condition under which the steps happen, or {@code null}
   * @param steps the steps
   * @param facts the IDs of the facts the operand, such as its guard, came from
   */
  public record Operand(String guard, List<Step> steps, List<String> facts) {

    public Operand {
      Checks.optionalText(guard, "guard");
      steps = Checks.list(steps, "operand steps");
      facts = Checks.factIds(facts, "operand facts");
    }
  }

  /**
   * A note over participants.
   *
   * @param over the participants the note spans, or empty for all of them
   * @param content the text, not empty
   * @param facts the IDs of the facts the note came from
   */
  public record Note(List<EntityId> over, List<Inline> content, List<String> facts)
      implements Step {

    public Note {
      over = Checks.list(over, "over");
      if (new HashSet<>(over).size() != over.size()) {
        throw new IllegalArgumentException("Duplicate participant in a note");
      }
      content = Checks.content(content, "note content");
      facts = Checks.factIds(facts, "note facts");
    }
  }
}
