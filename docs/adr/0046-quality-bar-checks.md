# 0046: The Quality bar checks the composer makes

Status: accepted (#163), amended by ADR 0048 (#97), ADR 0051 (#159), ADR 0052 (#164), ADR 0062 (#186) and ADR 0063 (#178), amended for #369 and #700

## Context

`docs/design.md` (Checking the quality bar) says the composer checks every check not marked *(review)* on every
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
  `technology_currency` (composed since ADR 0052) and `quality_evidence` (composed since ADR
  0062); the risks section's risk callouts each have a severity, a consequence and a confidence
  (ADR 0052) and come in order of severity.
- **The C4 report** (ADR 0031): a quality report on no page is split by page: each failure goes to
  the end of the page showing what failed, or of the first page, as a list titled "Quality gaps";
  with no failures, a success callout on the first page.
- **Checks.** `QualityBarTest` covers each check on hand-made documents; the composer's and the C4
  renderer's golden files hold the reports of the fixtures.

## Not in this issue

What would make checks pass: legends in Lantern's templates, a glossary, the reader, commits and
date in the model, the Explorer's start-here list and catalog, sequence flows (#96), intent phrases
and descriptions (#97), and the Brief's sections (blast radius and technology currency done in
ADR 0052, quality evidence in ADR 0062).

## Amendment (#369): the check "At most 15 elements" becomes "Readable".

- no fixed element cap. The quality bar (`docs/quality-bar.md`, Diagrams stay readable) sets no element cap; a view is split at the same level, or detail folded away, when a readability signal passes its configurable threshold (`docs/design.md`, Checking the quality bar, Readable diagrams). Until #366 measures the defaults, 15 elements stays the threshold, so the behaviour here is unchanged; read "above 15" and "at most 15 elements" in this ADR as that default threshold. The check reports a diagram over any readability threshold.
- The Architecture Explorer and Executive Brief checks stay as built, but those documents are out of scope for now (`docs/design.md`, Non-goals).

## Amendment (#700): checks of what elements and lines lack are queries on the model.

- *Technology*, *responsibility* and *protocol* are asked of the stored model for the document's scope (`Unknowns` in `lantern-derive`; knowledge-build epic, section 12), not of the diagrams' boxes and lines:
  - containers without a technology: the scope's units and frontends and the data stores they use; topics, queues and exchanges are named by their broker; external systems and people need none;
  - components without a technology, of units with more than one component (those the component views draw);
  - a new check, *Every element has a responsibility sentence* (Quality bar, Every diagram): the same containers, the external systems and people at the other end of their lines, and those components, when neither a responsibility sentence nor a unit's summary describes them;
  - lines without a protocol between two containers (neither end an external system or a person): a unit's lines, and those between two elements the container views draw, such as a route from an exchange to a queue.
- Each of these fails once for the document, naming no block, since it holds for the scope whichever diagram draws the element; the C4 report puts such failures on its first page (above). The responsibility failure counts the elements and points to the unknowns page, which names them, so they are named once.
- **The unknowns page.** The decisions and unknowns section lists, after the decision records, what the model holds as open, each once and from the same queries: links left open ("Not linked", moved from the container views), leads Investigate left unclear or was not asked about ("Not decided", a note citing nothing, so it reads the same whichever model was asked; a lead about no unit, such as a shared store or a Kubernetes Secret, named by what it points at, in the system's scope or that of the repository it was found in; a lead not asked says so without the error the run recorded), the units it did not look at ("Not investigated", moved from the findings table) and elements without a responsibility ("Responsibility not known", a note citing nothing). The unknown caller is not in the model yet (#365).


## Amendment (#741): the unknowns page lists every gap

The 1F and 1G review found gaps the reader could not see. Now:

- **Intent not known.** A note citing nothing lists every interaction of the scope's units that has
  no stored intent (`Unknowns.interactionsWithoutIntent()`), each from where it starts to where it
  ends, with its line's detail, such as an endpoint, so that two calls between the same ends read
  apart. The interactions are those each unit holds (`UnitInteractions` in `lantern-derive`, the
  list Describe asks about, ADR 0103, amendment for #741), plus each component's use of a store,
  which takes its unit's intent for the store (#739). A person's use of a frontend counts as known
  when the intent stored for that frontend exists.
- **Not asked.** Every item any role did not ask the model about, or got no answer for, is stored
  as a `not-asked` fact. Its object is the role, its `reason` is in words that read the same from
  run to run ("a budget refused it", "the request failed", "missing from the answer twice"), and
  its origin is its own, so the next run, which asks again, ends it. Some roles are left out:
  Investigate keeps its own `not-investigated`; a link Resolve was not asked about is already
  under "Not linked"; and the judges' samples write nothing the documents show. The injection
  screen's items are parts of files and are named as such. A note citing nothing lists them by role, what each is about and why; items
  of one role, about the same thing, not asked for the same reason, read as one line with their
  count. The system's scope lists all of them; a repository's, those about its units.
- **Refuted leads.** The quality report lists every lead the investigator refuted, as "A lead the
  LLM investigator refuted": what it is about, the finding that refutes it and the file ranges it
  cites, citing the verdict. Knowledge-build epic section 11.2 puts them in the quality report, so
  a reader can check a refutation. They are listed there as README claims the code contradicts
  are, so a document whose only entries are refuted leads no longer reads "No check failed".
- On a run where a budget refused some units' items, the page lists their interactions under
  "Intent not known" too. Model coverage counts those as "not asked", not "don't know", so the two
  counts are equal only when nothing was refused.
- **The run record** counts, for each role and each kind of field (the part of a field's name
  before its first dot, such as `interactions`), the fields the model left out of an item it
  answered, withdrew in a repair turn after they were rejected, or had dropped after they were
  rejected twice (`fields` in each role's entry). A role names the fields an item asks for
  (`PacketRole.askedFields`); Describe does.
