# 0063: Reader, pinned commits and date, legends and glossary

Status: accepted (#178), amends ADR 0011, ADR 0046 and ADR 0048, pending the user's review (#24)

## Context

`docs/design.md` (Quality bar) asks every document to state its reader and pin its snapshot
(commits) and date, every diagram to have a legend for every shape, color, line style and arrowhead
used, and acronyms to be explained in the glossary. ADR 0046 checks these, and every document of
project-0 failed them: the default view definitions set no audience, the document model held the
snapshot number but not its commits or a date (ADR 0011), the Explorer's and the Brief's templates
drew no legend, and nothing composed a glossary. Issue #178 asks for what makes these checks pass.
The Explorer's start-here list, catalog and section order it also names were done by ADR 0048, and
its decisions note (an Explorer of a repository without decision records says the decisions are not
known) already meets "Observed decisions, with rationale or marked unknown".

## Decisions

- **Reader.** Lantern's default views name their reader in the audience field the model already
  has, in the words of the design's built-in templates table: the Explorer "architects and senior
  engineers", the Brief "leadership", and the C4 reports "architects and new engineers" (L1 in the
  granularity table). Audience profiles that choose levels and tone come later; this is only the
  name. A view a user stored keeps its own audience, none included.
- **Pinned commits and date.** The document's source gains `commits` (the full commit hash of each
  repository the document covers, by name: one for a repository's view, all the snapshot's for a
  system view) and `date` (the day the snapshot was taken, in UTC, as `YYYY-MM-DD`). Both are
  required, so every document pins them and "It pins its snapshot (commits) and date" needs no check,
  as name and type do not (ADR 0046). The schema version stays 1: documents are written, never read
  back from storage. Composing stays deterministic, since the date is the snapshot's, not the
  time of composing.
- **Templates show them.** The Explorer and the Brief say under the title "For <reader>. Pinned to
  commit <repo> <first 12 characters>, as of <date>.", the full hash as a tooltip; the Explorer's
  document panel lists the reader, the snapshot's date and each commit. The C4 report renderer adds
  the same sentence at the end of the report's introduction and the date as its `updated` field.
- **Legends.** Lantern's own templates (Explorer, Brief) draw a legend under every diagram, with a
  swatch and a meaning for each thing the diagram uses: the box (naming the element types drawn,
  and the bracketed technology), the dashed boundary, the arrow (with its bracketed protocol and the
  Spring profiles it holds under) and each color. The model does not say what a color means, so a
  color is named only as set by a presentation rule, and a diagram with colors still fails the
  legend check ("The template's legend does not say what the colors mean."); the composer sets no
  colors. A sequence, shown in these templates as a list of its steps (ADR 0028), gets a key to the
  marks it uses: the arrow, async, reply, profiles, fragments and notes. The C4 report spec's legend
  is unchanged, and its gaps (the dashed boundary, the async arrowhead) still fail.
- **Glossary.** A new section kind `glossary` ("Glossary", every level) ends the Explorer's and the
  Brief's views. The composer builds it last, from the document composed without it: the acronyms
  the Quality bar finds (ADR 0046) are looked up in a table bundled with Lantern
  (`lantern-compose/src/main/resources/io/lantern/compose/glossary.csv`, acronym and meaning, the
  meanings using no acronym the table does not explain). Those it explains form a table of term and
  meaning; those it does not are named in a note as code, so the check still reports them; with no
  acronym used, a note says so. No LLM call is made, so nothing is invented. Definitions are not
  claims about the system, so the glossary cites no facts and the claims check skips it.
- **The C4 reports get no glossary yet.** Their renderer leaves tables on no page out (ADR 0031),
  so a glossary there would be checked but not shown. Where the spec shows one, and its legend's
  missing entries, are #229.
- **The runtime fixture's `worker`** had no technology fact, so the composer's golden C4 report
  failed "No technology for worker"; the composer reads the `technology` fact correctly, so the
  fixture now has one.
- **Checks.** `GlossaryTest` covers the table and the section; `QualityBarTest` the legends of
  Lantern's templates, colors and a glossary needing no facts; `ComposerTest`, `ViewDefinitionTest`
  and the golden files the readers, commits, date and glossary; the web tests the legends, the key
  and the pinned line.

## Not in this issue

Start-here lists, flows and catalogs of repositories without units, which have nothing to list and
fail honestly.
