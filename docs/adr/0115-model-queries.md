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
  name itself. Describe's items read `Elements` directly, not the composer's views.
- **Nothing a document shows changes.** The queries are the composer's code moved, with the same
  order and words.

## Consequences

- #718 does the same for flow candidates, and #698 then moves the roles into the LLM-roles stage,
  reading only these queries and the model.
