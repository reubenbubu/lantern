# 0097: The derive stage stores the architecture model

Status: accepted (#523)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 9) moves the architecture
model (components, interactions, flows, findings, deployment instances) out of the composer: it is
derived once per snapshot and stored as claims with lineage, before any LLM describes it or any
document draws it. The research for #615 (`docs/research/architecture-model.md`) settled its keys
and lineage. #523 builds the stage and stores the first part, the components; #617, #618 and #619
store the rest. The agent decided the points below under the owner's standing go-ahead for 1D.

## Decisions

- **A module, `lantern-derive`, between link and the LLM roles.** The architecture tests already
  name its stage (ADR 0093). It depends on the extractors (for their fact names) and the linker
  (for the rule shape); the composer depends on it.
- **Derive rules are link rules run by the linker, after the link rules.** They implement
  `LinkRule`, declare what they read and write, and join the linker's rules
  (`Linker.standard(…, Derivation.rules())`), so the rule order, the re-run only when what a rule
  reads changed (ADR 0095) and storing with lineage come for free, and a derived fact never feeds a
  link rule. A second linker for derivation would have read the derived facts back as extracted
  ones.
- **A rule can read the whole linked graph.** A derive rule reads what the documents read: every
  extracted fact and every fact the link rules gave, some of which restate extracted predicates
  (message listeners, repositories, routes as value resolution gives them). `LinkRule.LINKED`
  (`*linked`) in its reads gives it every fact of the rules that do not read it, and orders it after
  all of them.
- **The fact index moves with them.** `Knowledge`, the composer's index of a snapshot's facts, is now
  `io.lantern.derive.Knowledge`, public, so the rules and the composer index facts the same way;
  so do the helpers both use (the modules a unit's code is in, a member's owner type, a job's kind).
- **Components, as #615 keyed them.** `component:<unit>/<anchor>`, the anchor the class (or, for a
  gateway's config routes, the module) it is built around, so a new endpoint renames nothing. Each
  is `PART_OF` its unit with its `role` (entry point, aspect, shared, config routes) and
  `technology` as the diagram labels it, `ANCHORED_ON` its anchor, and `HAS_MEMBER` each member: a kind of `CONTAINS`, but a type of its own, since a rule that wrote `CONTAINS` would write what the extractors write and the units rule reads, a cycle the linker refuses. Groups (#535) will need the same. The
  rule is the component diagram's, moved unchanged; the composer now reads the stored components
  and orders them as before (config routes, entry points and aspects, shared beans, each by ID).
- **Lineage is one level.** The `PART_OF` fact cites the facts that give the anchor its role (its
  stereotype, endpoints, listeners or advice); members do not repeat the call graph that placed
  them.
- **Tests compose the model too.** `Derivation.withModel(facts, snapshot)` runs the derive rules
  over facts that were never stored, replacing any model among them, so the composer's tests and
  golden fixtures draw from a model as the run does.

## Consequences

- An unchanged snapshot does not run the component rule again (`LinkRulesIT`); any change to an
  extracted fact does, since components read every extracted fact.
- A repository's documents are composed from its own facts and the linked facts whose evidence is
  in it, as before. Its components are derived from the whole system's facts, so a member from a
  library of another repository that a unit bundles shows inside the component that reaches it; a
  component whose evidence is in that library shows only in the library's own documents. On
  project-4 and project-8 this changed no document.
- Composing without stored components draws no component diagrams: a caller must give the composer
  the model, as the run and `Derivation.withModel` do.
