# 0115: What documents and roles ask about, as model queries in derive

Status: accepted (#717)

## Context

The composer's diagram code worked out what a container or component diagram shows: the lines
from the link facts (labels, details, protocols, profiles, folded queues, profile alternatives),
the data stores and destinations, each element's name and technology, the people and their
screens, a unit's components. The LLM roles reached into it to decide what to ask about, so they
depended on the composer, which `docs/design.md` (Architecture overview, stage order) and the
knowledge-build epic (sections 5 and 12) rule out: roles run before composition, and composition
only reads the model. #698 moves the roles; this records where the queries they share go first.

## Decisions

- **Queries live in `lantern-derive`, beside `ArchitectureModel`.** `Elements` answers what the
  container and component diagrams of a scope show: the units, frontends and destinations, the
  lines (`Elements.Line`, one per link fact, in the order the composer drew them), an element's
  neighbours and technology, the facts that make it a container, the people and their screens,
  a unit's components and their names and facts. `ElementNames` names every element as the
  documents do. `MailProviders` and `IdentityProviders`, the name tables they use, moved with
  them.
- **The composer only draws.** `C4Views` builds diagrams, edges, notes and counts from
  `Elements`, and `Assembler` names entities through `ElementNames`; neither computes a line or a
  name itself. Lifting lines to a view's level (the context diagram's lines from the system and
  from each person, the per-pair edges) and the note on profile alternatives stay with the
  composer: lines are lifted when a view is composed (`docs/design.md`, Composition). Describe's items read `Elements` directly, not the composer's views.
- **Nothing a document shows changes.** The queries are the composer's code moved, with the same
  order and words.

## Consequences

- #718 does the same for flow candidates, and #698 then moves the roles into the LLM-roles stage,
  reading only these queries and the model.

## Amendment for #718: flow candidates

- **`ScopeFlows` (`lantern-derive`) answers which flows a scope may draw.** It traces each stored
  flow (`FlowModel`) into neutral steps: a message (from, to, its label as the facts give it,
  code, whether the sender waits, what it adds, its facts, and the link whose intent phrase may
  label it), a note, or an alternatives frame with its operands. With the flow go the lines the
  roles are shown, by its facts only. It also gives what starts a flow (its heading), the
  candidates the significance role is shown (one per stored shape), the rank of ADR 0047, and the
  flows a scope shows with or without the model's judgement (ADR 0070, ADR 0108).
- **The composer draws and phrases.** `SequenceFlows` turns the steps into sequence-diagram steps,
  applies the intent phrase (ADR 0050) where a step's link has one, groups profile-only runs into
  `opt` frames, and writes each flow's section. The flow roles take their candidates and narrated
  flows from `ScopeFlows`.
- **Names go through a hook.** `ScopeFlows` names elements with a function it is given, by
  default `ElementNames`; the composer's function also adds the element to its document, so a
  document holds the same entities as before.
