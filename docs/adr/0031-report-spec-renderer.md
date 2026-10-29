# 0031: Rendering into a report spec, starting with the C4 report

Status: accepted (#112), amended by ADR 0044 (#95) and ADR 0046 (#163), pending the user's review (#24)

## Context

`report-specs/c4-model-sequence-diagrams/` is a target document: a single-file HTML template that
draws C4 context, container and component pages and sequence diagrams from one JSON object, its
`arc-data` element, described by the spec's schema. Issue #112 asks Lantern to generate it from
real repositories. The design keeps one format-neutral document model (ADR 0011) that renderers
format without deciding content (`docs/design.md`, Rendering and templates), and more report specs
will follow, so nothing may be tied to this one. Everything is optional: what Lantern does not know
is left out, never invented, and the template must render without it. Most of what the spec can
show comes from later issues (#92 to #97, #113, #114); #112 builds the path from the model to the
file with what Lantern knows today. The spec's files are the user's and stay as they are. The
decided ID mapping is in a comment on #112.

## Decisions

- **The composer stays format-neutral; a renderer per report spec maps the document model to the
  spec's JSON** (`C4ReportRenderer` in `lantern-app`) and writes it into the spec's template in
  place of its sample data (`ReportSpecTemplate`, which works for any spec that embeds its data in
  one `<script type="application/json" id="...">`). As for Lantern's own template (ADR 0013), every
  `<` is written as `<`. The build copies `report-specs/*/*.html` into the app's class path.
- **The analyze run writes a C4 report per repository**: view `<repo>.c4`, template
  `c4-model-sequence-diagrams` version 1, with the overview, context, containers and components
  sections, rendered to `<workspace>/documents/<snapshot>/<repo>.c4.html` (amends ADR 0016). Its
  diagrams are the composer's current ones (ADR 0012) until #95 composes the views the design
  defines; the renderer does not change with them.
- **IDs.** Lantern IDs are lowercased and every run of other characters becomes `-`
  (`service:orders` becomes `service-orders`), separately for elements, pages and each page's
  sections. Two Lantern IDs that become the same ID are a renderer error, which fails the run. The
  renderer returns the mapping from each element ID back to its entity; storing it, and mapping
  imported placement rules back, is #50, which can also recompute it, since it depends only on the
  IDs.
- **Pages.** A section holding a C4 or sequence diagram becomes a page at that diagram's level
  (`c4-context` context, `c4-container` containers, `c4-component` components, `sequence`
  sequence). Its blocks, and those of subsections without such a diagram, become the page's
  sections in order, a subsection's title heading its first one; its leading paragraphs become the
  page summary, and its entity the page's element. Each diagram is followed by a catalogue of its
  boxes, so the spec's click-through and descriptions work, and each sequence by its steps table.
  Content on no page (the overview) becomes the index's introduction where it is text or a list.
  Other block kinds map to their nearest section type: tables, lists, callouts, headline numbers as
  stats, trees as indented lists, dependency diagrams as a table of their lines, quality reports
  as a list of failed checks (or a success callout). The document's own quality report, on no
  page, is split by page (ADR 0046).
- **Element kinds come from how the diagrams draw an entity**, since the document model holds no
  vocabulary: a box on a context diagram is a system, on a container diagram a container, on a
  component diagram a component, and a node others are drawn inside is the system (container
  diagram) or container (component diagram) around them. An element drawn at several levels takes
  the widest, with its parent from that level, kept only where the schema allows that parent. A
  sequence participant drawn in no diagram is a container, the design's default participant.
  `database` and `queue` need data store and broker types, which the runtime extractors add (#92);
  `person` and `external` come with the views and annotations (#95, Phase 3).
- **An element's Lantern type is its tag** when it differs from its kind (`Service`, `Library`,
  `Package`, `Repository`), so today's library and package boxes are not presented as something
  the design says they are not.
- **Left out because the spec has no place for it:** citations and fact links (the spec has no
  evidence links), the snapshot's commits, and Spring profiles except as a sentence in a message's
  walkthrough text. A message without a known sender or receiver becomes a note naming the unknown
  end. Markdown-lite has no escape, so a `*` or backtick in text may show as formatting.
- **Document fields**: the title, the document ID as its reference and the snapshot number as its
  version. Status, owner, classification and revision history wait for annotations and snapshot
  history (Phase 3); a date waits until documents record one, since the model has no generation
  time (ADR 0011).
- **Checks.** `C4ReportRendererTest` compares the output for the model fixture (every block kind)
  and the composer's golden documents with golden files; `lantern-web`'s Vitest tests validate the
  same files, and the spec's own sample data, against the spec's schema with Ajv and against the
  cross-reference rules its template checks on load.
