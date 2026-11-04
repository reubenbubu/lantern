# 0114: Type names in one generated class; element kinds from the vocabulary

Status: accepted (#280); the class's place and the rule's scope changed by ADR 0121 (#763)

## Context

The composer and the C4 renderer ignored the vocabulary. The composer held about 80 constants
copying predicate and entity type names, and wrote more inline; the renderer mapped five Lantern
types to the report spec's element kinds, chose tags by type name and recognised decision records
by their type. A type that extends `container` was drawn as nothing, since no code named it.
`docs/design.md` (Design principles: open vocabulary, stable core; Composition) wants one typed
layer, and C4 level and kind read from the vocabulary, with the renderer receiving the resolved
kind (knowledge-build epic, section 12).

## Decisions

- **One generated class names every type.** `TypeNames` (core) generates
  `io.lantern.derive.attributes.Types`, one constant per type of every vocabulary this version of
  Lantern applies (`Vocabularies.bySource()`: built-in, extractors, linker, derivation, LLM roles),
  committed and checked by `TypesTest` as the attribute records are (ADR 0090), and rewritten
  with `-Dlantern.records.write=true`. It sits beside `ArchitectureModel` in `lantern-derive`, the
  one module that sees every vocabulary. The analyze run loads its registry from the same
  `Vocabularies.bySource()`.
- **Compose and the renderers name types only through it.** An architecture rule fails on any
  `static final String` of `io.lantern.compose..` or a renderer whose value is a type's name
  (read by reflection, so an alias of another module's constant counts too); the constants that
  only happen to equal one, the fields of a role's answer (`title`, `responsibility`, `purpose`,
  in classes #698 moves out), are named in the rule. A source check fails on a relationship type's
  name written as a string anywhere in that code, and on any type's name given to a fact query
  (`withPredicate`, `predicate().equals`, `type().equals`, `relations`, `knowledge.attribute`,
  `parts`) or written as a `case` label. Both cover the composer and the renderers, as #280's
  scope does; the linker has its own rule on typed facts (ADR 0095). Keys inside
  a fact's attributes are not type names; reading them by record is ADR 0090's and ADR 0095's.
- **The composer resolves what an element is.** `ArchitectureModel` carries the vocabulary; a
  `Composer` built without one uses `Vocabularies.standard()`.
  - A *destination* (what container diagrams draw besides units) is any entity at the container
    or context level, by its type or nearest ancestor with one, that runs no code of the system
    and is neither the system, an actor, a message broker nor an infrastructure server: the
    diagrams draw a broker's topics, queues and exchanges instead, and keep infrastructure apart
    from the business dependencies, as those types' descriptions say.
  - Each `DocumentEntity` gets a `kind` (`database` for a type drawn as a `cylinder`, `queue` for
    one drawn as a `pipe`, `person` below `actor`, `external` below `system`, `decision` for a
    decision record; none otherwise, for what the diagram's level makes it) and a `tag`, the
    type's name for a reader (none for a type named as what it is drawn as, a component's main
    type or an external system).
  - The vocabulary now gives `database` the shape `cylinder` and `topic`, `queue` and `exchange`
    the shape `pipe`.
- **A new interaction is drawn by what it descends from.** A unit's fact whose predicate extends
  `INTERACTS_WITH`, that no container line draws its own way, to a destination becomes a line
  labelled "Uses" with the fact's protocol. A unit's `USES_BROKER` and `USES_INFRASTRUCTURE` stay
  undrawn, for the same reasons.
- **The renderer knows no Lantern type.** It draws an element as its `kind`, else as the widest
  level a diagram shows it at, shows its `tag`, and makes a table a decisions section when every
  row's entity is a decision. Its catalogues stay with it: they group the report spec's kinds,
  which are the template's, not Lantern's.

## Consequences

- A type that extends `container` (or `database`) appears in the container diagram, as a
  container (or a data store), with no code change (`C4ViewsTest`, `C4ReportRendererTest`).
- Documents carry `kind` and `tag` on their entities; every C4 report reads the same.
- The rule covers the composer's LLM roles too, until #698 moves them out.
