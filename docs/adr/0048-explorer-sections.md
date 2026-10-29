# 0048: The Architecture Explorer's start-here list, catalog, change guidance and decisions

Status: accepted (#97, composer part), amends ADR 0046's Explorer checks, amended by ADR 0049 (#181), pending the user's review (#24)

## Context

`docs/design.md` (Quality bar, Architecture Explorer row) asks the Explorer for an orientation of
at most 300 words followed by a "start here" list of the entry points and files to read first, the
container diagram first, the main flows as sequence diagrams, a catalog of containers, components,
endpoints, queues, tables and config each linked to source, change guidance ("to add an endpoint,
follow X in Y") and observed decisions with rationale quoted from ADRs or READMEs or marked
unknown. ADR 0046 checks these and every Explorer failed them. Issue #97 asks for these sections
and for the summarizer's wording of responsibility sentences and intent phrases; it maps them onto
the report spec `report-specs/c4-model-sequence-diagrams` and says everything is optional: a
missing piece is left out or stated plainly as unknown, never invented. The extractors and linker
now record what the sections need: entry points and main classes (ADR 0035), configuration (ADR
0032), schemas and tables (ADR 0034, 0036), sends and listeners (ADR 0037), HTTP calls (ADR 0038),
deployable units (ADR 0039), links (ADR 0042) and README claims and decision records (ADR 0033).

## Decisions

- **Three new section kinds**, built by `ExplorerSections` (`lantern-compose`) from facts only,
  every list item and table row citing the facts it is built from; where there is nothing to show,
  the section holds one note saying so, which cites nothing (ADR 0046):
  - `start_here` ("Start here", L1 and L2): the entry points of the main flows, in the order
    `SequenceFlows` shows them (or, with no flow, the first five entry points of the units in scope
    by unit and method ID), each with its handler and file; where each unit starts (its
    `main-class`); each repository's README with the fewest folders in its path; and the folder
    holding most of each repository's decision records, with how many.
  - `catalog` ("Catalog", L2 and L3): one table each, left out when empty, of the containers (the
    units in scope with their technology, and the databases they use by default, with who uses
    them), the components (as the component diagrams find them, ADR 0044, with their role), the
    endpoints, message listeners and scheduled jobs (handler, unit and file), the topics, queues and
    exchanges the units send to or receive from, the tables of the schemas their code builds and
    those it reads or writes, and their configuration (each key's default value and each profile's
    value, as the config extractor records them with secrets masked, and the file). Profile
    alternatives (ADR 0044) are left out, as on the diagrams.
  - `change_guidance` ("Change guidance", L2 and L3): "To add an endpoint, follow `Type.method` in
    `file`", and the same for a message listener, a scheduled job, a send and an HTTP call, a schema
    change (the folder of the first file of the schema's most specific migration kind, Flyway, then
    Liquibase, then plain SQL, which its `kind` fact points at) and a setting (the file holding most
    default keys). Each uses an example the code has: an entry point
    in the type with most entry points of its kind, else the first by ID, and the first link of a
    unit whose code is known. Kinds without an example are left out.
- **Decisions get a rationale.** The decision records table (ADR 0044) gains a column "Rationale",
  the first paragraph of the record's context section quoted as written, or "Not recorded." when it
  has none. A scope whose repositories have no decision record gets a note titled "Decisions"
  saying so, and that the reasons for its design are therefore not known. Decisions stated only in
  READMEs stay claims (ADR 0033), so they are not listed.
- **The Explorer's view** (`ViewDefinition.perRepo`) becomes: overview (the orientation: the
  repository's summary, at most 150 words by ADR 0025, and what it builds), start here, containers,
  context, components, main flows, catalog, change guidance, dependencies and structure. The
  context moves after the containers so that the container diagram comes first, as the Quality bar
  asks, and the main flows (ADR 0047) are added. The Explorer template shows a sequence as its
  list of steps (ADR 0028).
- **The C4 report views** (`<repo>.c4`, `system:c4`) gain the start-here section after the
  overview. Content on no page becomes the report's introduction (ADR 0031); the renderer now puts
  each later section's title in bold before its first block there, so the start-here list, and the
  notes of sections without a page, read under their headings. The catalog and change guidance are
  not in these views yet: the spec's pages are C4 levels and sequences only, and where its endpoint,
  queue, table and config tables belong is #182.
- **The C4 report renderer** writes a decision's rationale after its outcome in the decisions
  section's body, as `**Rationale:** <text>`.
- **The Explorer's start-here and catalog checks** (ADR 0046) now pass only when the start-here
  section holds a list and the catalog a table: a note that nothing was found does not meet them.
  A decisions note does meet its check, since the Quality bar allows decisions marked unknown.
- **Acronyms.** The new sections use HTTP and SQL where they are the plain words. No glossary is
  composed yet, so Explorers fail "Acronyms are explained in the glossary" as other documents
  already do (ADR 0046).
- **Checks.** `ExplorerSectionsTest` covers the three sections, the rationale, the no-records note
  and that an Explorer of the runtime facts no longer fails the orientation, start-here, container
  first, flows, catalog and decisions checks. The composer's and the renderer's golden files show
  the new sections and notes for the fixtures.

## Not in this issue

The summarizer's wording: a responsibility sentence per element and an intent phrase per
relationship, worded only from extracted facts with unknowns stated once, which needs prompt
changes and a new LLM role with citations and caching (#181). Until then
element descriptions are summaries (ADR 0044) and line labels say what static facts say. Change
guidance is a *(review)* check; whether these examples help a reader is judged on the test sets.
