# 0012: Composer v1, per-repository documents

Status: accepted (#13), C4 mapping replaced by ADR 0026 (#86) and ADR 0044 (#95), amended by ADR 0027 (#70)

## Context

`docs/design.md` (Composition, layout stability and prose stability) says the composer applies a
persisted view definition (scope, audience profile, template, sections and diagrams) to the
knowledge of a snapshot and produces the document model (0011), each element linked to the facts
it came from, deterministically. Issue #13 asks for per-repository documents with C4 context,
container and component diagrams, a dependency tree, module and package structure, and narrative
from the summaries (0010). The design leaves open what a view definition holds exactly, how the
C4 levels map onto what the Maven and Java extractors find (0006, 0007), and how summaries become
narrative. Presentation rules and the previous rendition, the composer's other inputs, do not
exist yet and are not used.

## Decisions

- **View definitions** (`ViewDefinition` in `lantern-compose`) have an ID (also the ID of the
  documents composed from it), a title, a scope, an optional audience profile name, a template
  name and version, and an ordered list of sections, each from a fixed catalog (`SectionKind`):
  `overview`, `context`, `containers`, `components`, `dependencies` and `structure`. The catalog
  fixes each section's title, ID and granularity levels, and which diagrams it holds, so a view's
  structure never depends on the knowledge or the LLM. Sections are named rather than configured
  in detail; options are added when templates need them. Only the per-repository scope exists
  (`{"kind": "repo", "repo": "<name>"}`); system, grouped and custom scopes come with Phase 2 and 4.
  They are stored as JSON in a `view_definitions` table (`JdbcViewDefinitionStore`), one row per
  ID, replaced on save; there is no history yet, since there is no audit log yet.
- **Composer.** `Composer.compose(view, snapshot, facts, summaries)` takes the snapshot's facts
  (it ignores facts that do not hold in the snapshot) and the summarizer's result, and makes no
  LLM calls itself. It records the snapshot number, the snapshot's vocabulary revision and its
  own tool version as the document's source. Everything is visited in ID order, so the same inputs
  give the same JSON whatever order facts and summaries come in.
- **What belongs to a repository**: the modules whose facts were observed in it (as the
  summarizer decides, 0010), the services and libraries that build them, their packages, and the
  types in those packages. All of these are in the document's entities, with their summaries as
  descriptions, so the exported file has the repository's knowledge without the service. Other
  entities a document shows (other repositories, their containers and modules, third-party
  artifacts) are included by name, without summaries. Each entity's facts are its attribute facts,
  the `CONTAINS` facts that place it under its parent and, for a service or library, the
  `CONTAINS` facts naming its modules.
- **Navigation tree parents**: a repository has none; a module's parent is the aggregator module
  that lists it, else its repository; a service or library sits under its repository; a package
  under its module; a type under its package or outer type (a type in the unnamed package under its
  module). A module, service or library observed in several repositories sits under the document's
  own repository if it is one of them. Otherwise, when there are several candidates, the first by
  ID wins.
- **Repositories as systems.** Repositories are not entities in the fact store; documents use the
  summarizer's `repo:<name>` IDs (0010) for them.
- **C4 mapping.**
  - *Context:* the repository and every other repository of the snapshot that it depends on or that
    depends on it, through `BUILD_DEPENDS_ON` facts between modules. Third-party artifacts are left
    out (they have no C4 level, 0006).
  - *Containers:* the repository as a boundary holding its services and libraries (technology line
    such as `Service, jar`), with edges for `BUILD_DEPENDS_ON` between their modules and for
    `PACKAGES` (0006). Containers of other repositories that they depend on are drawn inside their
    own repository's boundary.
  - *Components:* one diagram per service or library, its packages as components, with a `uses`
    edge (relationship `DEPENDS_ON`) from one package to another when a type in the first calls,
    extends or implements a type in the second (`CALLS`, `EXTENDS`, `IMPLEMENTS`, aggregated from
    methods to types to packages). Edges to packages of other containers are left out for now.
  - Test-scoped dependencies are left out of the context and container diagrams, since they are
    not architecture; the dependency tree shows them.
  - Diagram nodes for services and libraries carry the facts that they build their modules and
    how those are packaged, package nodes the fact placing them in their module, and edges the facts
    they aggregate. Repository nodes carry none (repositories have no facts), and diagrams as a whole
    carry none beyond their elements'.
  - Edge IDs are `<from>--<relationship>--<to>`, with characters the model does not allow in IDs
    replaced by `-`; node and edge styles and positions are left empty until presentation rules
    and stored layouts exist.
- **Dependency tree.** One root per module of the repository that has dependencies, showing every
  direct dependency (label: coordinates, version and classifier, then the artifact type when not
  `jar`, the scope when not `compile`, and whether it is optional). An internal
  module below it is expanded with the dependencies Maven passes on (compile or runtime scope, not
  optional), and each internal module is expanded only the first time it appears under a root,
  as `mvn dependency:tree` does, so cycles end and trees stay small. Third-party dependencies are
  not expanded: transitive third-party dependencies are not extracted (0006).
- **Structure tree.** The repository's top modules, with submodules and then packages below each.
  Types are left to the entities' navigation tree.
- **Narrative.** The repository's summary is the overview paragraph, followed by a list of what
  the repository builds; each service's or library's module summary opens its components
  subsection. A summary's `[fact:...]` markers become citations (adjacent markers merged into one,
  attached to the text before them), and `[summary:<entity>]` markers become cross-references to
  the entity marked as citations, shown as citation markers (0027). A summary citing a fact that does not hold in the
  snapshot is refused. Units without a summary simply get no paragraph, so documents can be
  composed before or without any LLM run.
- **Section and block IDs** are fixed: the section kind (`overview`), and below it the block's
  role (`overview.summary`, `context.diagram`) or the entity it is about
  (`components.service:com.example:app.diagram`), so deep links survive regeneration.
- **Golden tests** extract the fixture projects (0004) with the real Maven and Java extractors,
  without builds, in a temporary git repository per fixture committed with a fixed author and
  date, so fact IDs are the same on every machine; a fake model writes the summaries. The
  documents are compared with `lantern-compose/src/test/resources/golden/<repo>.json`.

## Not in this issue

Presentation rules, stored layouts and colors, and prose stability (Phase 3 and 4), other scopes
(Phase 2), sequence diagrams (no call-flow extraction yet), and a job that composes documents as
part of an analysis run (#17).
