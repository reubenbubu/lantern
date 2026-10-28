# 0028: Sequence diagrams and the quality report in the document model

Status: accepted (#91), pending the user's review (#24)

## Context

The design's runtime view is a set of sequence diagrams, and the composer must put the Quality bar
checks a document fails into the document (`docs/design.md`, Quality bar; Composition, Diagrams and
Sequence diagrams; ADR 0026). ADR 0011 left sequence diagrams out of the document model. The
report spec `report-specs/c4-model-sequence-diagrams/` is the first target template that draws
them; more report specs will follow, so Lantern's model stays format-neutral and must be able to
hold everything such a template can show (#112). Any detail may be missing: the composer leaves out
what it does not know or says plainly that it is unknown, and templates render without it.

## Decisions

- **A sequence diagram is a diagram block with its own shape**: `kind` `diagram`, `diagramKind`
  `sequence`, an optional title, participants and steps (`sequenceDiagram` in the schema,
  `SequenceDiagram` in Java and TypeScript). Graph diagrams keep their shape, nodes and edges; a
  sequence has no nodes or edges, and graph code does not have to handle steps. The schema version
  stays 1: documents valid before stay valid.
- **Participants are entities, in the order given**, left to right, each once, unlike nodes,
  which are sorted. Each has optional label (absent: the entity's name), technology and style, and
  its fact IDs, like a node.
- **Steps are messages, frames and notes**, told apart by `kind`, each with its fact IDs:
  - A message has optional `from` and `to` (absent: not known; the same participant twice is a
    self-call), `label` (the intent), `code` (the protocol detail, such as `POST /orders`),
    `messageKind` (`sync`, `async` or `return`; absent: sync), `activate` and `deactivate` (absent:
    the usual default, as in the report spec), `profiles` (the Spring profiles under which alone it
    is sent; absent: always) and `info` (rich text for a walkthrough). Profiles are per message,
    since the Quality bar marks lines as conditional; a frame whose messages all hold under one
    profile marks each of them.
  - A frame has an operator (`alt`, `opt`, `loop`, `par`, `break`, `critical`) and one or more
    operands, each an optional guard, its steps and its own fact IDs. Frames hold their steps
    instead of the report spec's flat open, `else` and `end` markers, so a frame can't be left
    unclosed; a renderer flattens them. Frames may nest: the design's "at most one level of fragments" is a Quality bar
    check, which the composer reports, not something the model forbids.
  - A note has rich text and optionally the participants it spans (absent: all).
  Messages and notes may name only participants of the diagram.
- **A quality report is a block** (`kind` `quality-report`) with an optional title and a list of
  failures. Each failure names the check as the Quality bar words it, and optionally the section,
  block or entity that failed it (a cross-reference target, checked like one) and rich text on how
  it failed, with its fact IDs. No failures means every check the composer makes passed.
- **Required fields are only those that give an element its identity**: `kind`, block IDs, a
  participant's entity, a frame's operator and operands, a note's text, a failure's check, and the
  lists (`participants`, `steps`, `failures`, `facts`), which may be empty, as elsewhere in the
  model.
- **No sequence renderer of Lantern's own.** Drawing is the report-spec templates' job (#112). The
  Architecture Explorer and the Executive Brief show a sequence diagram as a plain numbered list of
  its steps, so a document that has one still reads, and a quality report as a list of the failed
  checks.
