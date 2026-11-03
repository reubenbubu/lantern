# 0120. Levels, technology, counts and boundaries judge a type by its vocabulary ancestors

Status: accepted (#762)

## Context

Design principle 8 makes entity types data: a vocabulary type can extend a built-in one (ADR
0114), and a type that extends `container` is drawn as a container. The drawing and the report's
own checks disagreed. Seven places compared exact type names, so such a type was drawn as a
container but the quality report flagged it "One abstraction level", left its technology out
of the diagrams and the "without technology" check, did not count it among the databases, and
treated an external-system subtype as inside the system's boundary.

## Decisions

- **A type is what its nearest ancestor says.** `Elements.isType` and the composer's `isType`
  answer "this type is, or extends, that type" with `Vocabulary.isA`. The level checks in
  `QualityBar` ask whether a type extends one of the diagram's types (`extendsOneOf`).
- **Technology.** A type that extends `service`, `database` or `container` states its technology
  (`Elements.technology`). Topics, queues and exchanges keep their exact cases, which name their
  broker, and are checked first.
- **Counts and boundaries.** The "Databases", "Topics, queues and exchanges" and "External systems"
  counts, the external-system boundary (C4Views and `Unknowns.isOutside`), and the topic test use
  the same relation.
- **The base type belongs to the container level.** `container` itself is in the container
  diagram's set, so a type that extends it directly is drawn at that level.
- **The quality check gets the vocabulary.** `QualityBar.failures(document, vocabulary)`;
  `Composer` passes its own. The one-argument form keeps Lantern's standard vocabulary for the
  existing callers.

## Consequences

- Not changed here, and to follow: other exact-name comparisons that a new subtype could still
  trip on: `Assembler` (the person and frontend checks), `ScopeFlows` (database), `FlowTracer`
  (external system), `BriefSections` (the technology and person checks), and `ElementNames` (a
  switch on the type). They keep their exact names until a follow-up issue covers them.
- A subtype of `topic`, `queue`, `exchange` or `frontend` gets the plain technology attribute,
  not the broker or frontend wording, since the switch matches exact names first. No such subtype
  exists in Lantern's own vocabulary.

- Output of Lantern's own types is unchanged: the fake-model documents of project-0, project-1,
  project-2, project-3, project-4 and project-8 are byte-identical (123 files).
- Tests in `C4ViewsTest` add a type under `container`, under `database` and under
  `external-system`, and each fails on main before this change.
