# 0046: The Quality bar checks the composer makes

Status: accepted (#163), amended by ADR 0048 (#97), ADR 0051 (#159) and ADR 0052 (#164), pending the user's review (#24)

## Context

`docs/design.md` (Quality bar) says the composer checks every check not marked *(review)* on every
document and puts the failures into the document as a quality report block (ADR 0028). ADR 0044
reported only diagrams above 15 elements, in the containers and components sections. Issue #163
asks for the rest. Some checks need content Lantern does not compose yet (a glossary, a catalog,
sequence flows, the Brief's blast radius); those fail, so the report shows what is missing.

## Decisions

- **One report per document, last.** The composer composes the view's sections, makes the checks
  on that document (`QualityBar`) and adds a last section, "Quality report" (ID `quality`), holding
  one quality report block. It has no failures when every check passes. The per-section blocks of
  ADR 0044 are gone. Each failure names the check as the Quality bar words it, the diagram, section
  or block that failed it (none for the document as a whole) and how, naming the elements or lines
  (the first ten, then how many more).
- **Every diagram** (C4 and sequence):
  - *Title*: a C4 diagram's title names its kind (context, container, component) and the label of
    a boundary node, or of a node when nothing is drawn inside another. A sequence diagram has a
    title.
  - *Legend*: Lantern's own templates (Explorer, Brief) draw none, so each of their diagrams fails.
    The C4 report spec's template draws a legend of the kinds it uses; a diagram with a boundary
    and a sequence with asynchronous messages fail, since its legend has neither the dashed boundary
    nor an async arrowhead.
  - *One abstraction level*: a context diagram shows systems, external systems and people; a
    container diagram also deployable units, databases, topics, queues and exchanges; a component
    diagram also types (components). Boundaries are not counted.
  - *At most 15 elements* (boundaries not counted; a sequence's participants).
  - *Technology*: every container and component box, except external systems and people.
  - *Lines*: labelled, and not with a generic verb alone ("uses", "depends on", "calls", "connects
    to", "sends messages", "reads and writes" and the like); linked to facts; when all its facts
    hold only under Spring profiles, naming them; on a container diagram, between containers,
    naming a protocol. At most one level of fragments in a sequence.
  - *Name and type* and *one-way* need no check: the document model requires a node's label, an
    entity ID has a type and every edge has one direction.
- **Every document:**
  - *Acronyms in the glossary*: words of two or more capitals outside code runs, tree labels
    (coordinates) and constants, HTTP methods excepted, must appear in a top-level section titled
    Glossary. None is composed, so documents using acronyms fail, listing them.
  - *Reader*: the document has an audience. The view definitions set none yet.
  - *Snapshot and date*: the model holds the snapshot number but not its commits or a date (ADR
    0011), so every document fails until it does.
  - *Every specific claim links to a fact or source*: paragraphs, list items, table rows, nonzero
    headline numbers, warning and risk callouts, diagram boxes and tree nodes cite facts, or are
    about an entity with facts in the document, or (a box) end a line with facts. Notes state what
    is not known and cite nothing, so a repository without units, and a scope without sequence
    flows (ADR 0047), now say so in a note; the scope's system is Lantern's, not a claim. A flow's
    prose cites the facts of the steps it describes.
  - *No diagram repeated*: two diagrams showing the same elements and lines, whatever their kind.
- **The Explorer** (template `architecture-explorer`): the first section has at most 300 words of
  text and a "Start here" subsection or next section; the first diagram is the container diagram;
  at least one sequence diagram; a top-level section titled Catalog; a table or callout titled
  Decisions.
- **The Brief** (template `executive-brief`): top-level sections `blast_radius`,
  `technology_currency` (composed since ADR 0052) and `quality_evidence` (#186); the risks
  section's risk callouts each have a severity, a consequence and a confidence (ADR 0052) and come
  in order of severity.
- **The C4 report** (ADR 0031): a quality report on no page is split by page: each failure goes to
  the end of the page showing what failed, or of the first page, as a list titled "Quality gaps";
  with no failures, a success callout on the first page.
- **Checks.** `QualityBarTest` covers each check on hand-made documents; the composer's and the C4
  renderer's golden files hold the reports of the fixtures.

## Not in this issue

What would make checks pass: legends in Lantern's templates, a glossary, the reader, commits and
date in the model, the Explorer's start-here list and catalog, sequence flows (#96), intent phrases
and descriptions (#97), and the Brief's sections (blast radius and technology currency done in
ADR 0052, quality evidence #186).
