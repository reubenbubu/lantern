# 0097: The derive stage stores the architecture model

Status: accepted (#523), amended (#617, #271, #618)

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

## Amendment for #617: component interactions and deployment instances

The agent decided these under the owner's standing go-ahead for 1D.

- **Interactions are the component diagram's lines, stored.** `ComponentInteractions` reads the
  stored components and the linked graph and writes one `COMPONENT_LINK` per line the diagram drew
  (applies, calls, advises), keyed on the code it rests on (`at`), what it reaches (`target`) and
  its label (`detail`), so two calls from different methods stay two lines, as the diagram's phrase
  counts need. Facts that make one such line merge and cite all they rest on. A component's
  `data-access` is a fact of its own.
- **Where a link's code end is, is stored too.** The composer placed a link between containers at
  the component holding its `source` or `target` code. That is now a `COMPONENT_LINK` of kind
  `code-end` to the link's other end, keyed on the side; the link itself is named only by the line's
  lineage (its `facts`), since no key attribute may hold a fact ID. The composer finds the line by
  the link fact it cites.
- **Instances merge same-named entries; the table does not.** An instance is keyed on its
  environment and name (#615), so two descriptors naming the same container in the same environment
  give one instance citing both. The deployment table keeps one row per descriptor entry, reading
  the entries from the instance's lineage, so its rows and their evidence are unchanged; whether it
  should show one row per instance is a question for the deployment view, not this change.
- **`COMPONENT_LINK` stands for the epic's `INTERACTS_WITH` between components, but is not a kind of it.** Every kind of `INTERACTS_WITH` keys on the links' key attributes (ADR 0090), while a component line keys on the code it rests on, what it reaches and its label, so two calls from different methods stay two lines. The epic's row now says so. Only links to another thing get a code end: an open link, whose object is the text it could not resolve, has none.
- **An instance's environment includes its platform, and a descriptor naming no environment is its own.** Environment names such as `prod` recur across platforms, and two repositories' compose files that each run a `postgres` are not one container; the key then holds `@`, the repository and the descriptor's path, so moving or renaming such a descriptor renames its instances. Parts are percent-encoded where they hold whitespace (project-8 has a Helm environment named `fleetcare prod`) or a separator. The epic's row now says so; #617 records the change.
- **No document changes.** The composer draws the same lines and rows from the stored facts.

## Amendment for #271: components as the bar draws them

The agent decided these under the owner's standing go-ahead for 1D, following the quality bar's
Component row ("Domain, DTO, mapper, configuration and util classes are left out"; code several
entry points share is its own component).

- **Constructors say what a bean uses.** A class uses the types its constructors take, as Spring
  injects them; a constructor's ID names its parameter types even when its calls could not be
  resolved. Without a build (degraded mode) a call whose argument has a library type is not
  resolved, so on project-8's workorder-service, whose build fails, the controllers reached none of
  their services and every repository became a box of its own.
- **Mappers and settings are left out.** A class MapStruct generates (its `generated` fact) and a
  class bound to config by `@ConfigurationProperties` (a new Spring extractor fact,
  `configuration-properties`, with its prefix, read as Spring reads it: from the meta-annotation's
  declaration when the class carries one; extractor version 8) are never components, shared or
  not. Hand-written mappers are known only by name, so they stay when shared.
- **Only code that reaches another container is drawn when nothing reaches it.** An HTTP client
  or a class that makes HTTP calls or sends stays a component when no entry point reaches it, so a
  line to another container still starts at its code; code with only data access (a repository,
  or a bean whose code reads or writes tables) that no entry point reaches is not drawn, unless no
  drawn component would then reach the unit's databases, since the framework may call it (a
  `UserDetailsService`, a data seeder) or calls to it went unseen. One an entry point reaches is a member of that entry point's component, whose data access
  it gives.
- **Intent phrases are Describe's.** Lines between components keep their static label (ADR 0050)
  until Describe words every interaction in the stored model (#528); the issue's intent criterion
  moved there.

## Amendment for #618: every candidate flow, stored

The agent decided these under the owner's standing go-ahead for 1D.

- **The trace moves whole; the wording stays.** `FlowTracer` in `lantern-derive` is the composer's
  trace, moved unchanged: the same entry points, call following, alternatives frames, data-access
  runs, deliveries, jobs and the 40-message (80-step) bound. It records what each step is (its
  action, ends, kind, profiles, the frame operand it sits in) and the facts it rests on; the
  composer words and draws the stored steps (labels, intent phrases, notes, the LLM's outline) as
  it did, and still chooses which flows a scope shows (ADR 0047) until flow significance (#272).
  Every entry point whose trace has a step is stored; the candidates a document may show are
  those whose own steps reach another container, push to STOMP subscribers or call what Lantern
  could not link, as before. The composer draws all of them, as it traced all of them, so a
  document still lists the facts of the flows it does not show.
- **A flow's key holds its unit.** `flow:<unit>/<entry-point claim>`, since a library's entry
  point runs in each unit that bundles it and is a flow in each.
- **A step's link is its first fact.** A claim is no entity, so a step cannot point at its link
  through a relation as the epic's `VIA` reads; the step cites it first, so it is its first lineage
  claim and names the step's key. A note is keyed on the fact it is about (`none` for the note
  that the trace stopped).
- **Who uses the screens is the scope's.** The stored flow names the person the whole system
  shows; a repository's document draws the person its own people give, as before.
- **A flow is drawn whole or not at all.** A document composed from part of the snapshot's facts
  (a repository's, in a run that carries other repositories forward) leaves out a flow resting on
  a fact it does not hold, since it could not cite it; before, it traced the flow from the facts
  it held. In a run of every repository, as the test sets are run, every flow holds.
- **The shape is the epic's, the composer's check is not yet.** `flow-shape` hashes how a flow
  starts and the ordered sender, receiver, kind and link of its messages (#615), and
  `SAME_SHAPE_AS` links each flow to the one of its shape whose key sorts first. The composer
  still hides a flow of the same start and lifelines as one shown (ADR 0070), so documents do not
  change; flow significance (#272) will read the stored shapes.
