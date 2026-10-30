# 0015: Executive Brief template v1

Status: accepted (#16), amended by ADR 0026 (#86), ADR 0044 (#95) and ADR 0052 (#164)

## Context

`docs/design.md` (Rendering and templates) describes the Executive Brief as the template for
leadership: one scroll, narrative-led, a few large visuals, headline numbers and risks, at the L0
level ("the system on a page: capabilities, key dependencies, risks"). Issue #16 asks for it with
the Explorer's components and theme tokens, checked against composer output for an OpenWMS
repository at phone, laptop and wide widths. The composer (0012) had no sections for headline
numbers or risks, and nothing in the knowledge names a risk. #12 left open whether risks need a
severity (`variant: risk` had none).

## Decisions

- **Two new section kinds** in the composer's catalog: `headlines` ("At a glance", L0 and L1) and
  `risks` ("Risks", L0 and L1). `ViewDefinition.executiveBrief` lists overview, headlines, risks,
  context and containers, in that order: what the repository is, how big and connected it is, what
  to watch, then the two diagrams. Risks come before the diagrams so a reader who stops early still
  sees them. `perRepo` now names the Explorer's six sections instead of every section, so Explorer
  documents are unchanged.
- **Headline numbers** are counted from facts, each citing the facts it counts: services,
  libraries, modules, Java types, third-party libraries (direct dependencies outside test scope),
  repositories it uses and repositories using it (as on the context diagram). The set is fixed, zeros
  included, so the tiles stay in place across versions.
- **Risks come from fixed checks of the knowledge**, not from the LLM, so they are reproducible and
  cite their evidence. Three checks for now, each giving at most one callout (cycles one per service
  or library), naming at most five items and counting the rest:
  - dependencies outside test scope on unreleased (`-SNAPSHOT`) versions of anything the repository
    does not build itself (medium: a release cannot be rebuilt exactly; on development branches this
    is common, so it is not high);
  - the same dependency at different versions in different modules (medium);
  - packages of a service or library that depend on each other in a cycle (low), found as strongly
    connected components of the component diagram's package dependencies.
  Without risks the section holds a note saying what was checked. More checks (and LLM-found or
  annotated risks) can be added later without changing the model.
- **Risk severity** (the question deferred from #12) is added to the document model: an optional
  `severity` (`high`, `medium`, `low`) on callouts, allowed only on a risk. Risks are sorted most
  serious first, and every template shows the severity as a badge and colors the callout's edge
  (high in the risk color, medium in the warning color, low in the note color). The schema version
  stays 1: the field is optional and nothing has been published yet.
- **Layout.** The single-file page picks the brief by the template name `executive-brief` (0014).
  It reuses the plain page's section and block components and the shared theme tokens; the theme
  switch and its stored choice moved from the Explorer to `src/theme.tsx` so both templates share
  them. A slim sticky header holds the title, links to the sections (from 760px wide) and the theme
  switch; below it one reading column (at most 64rem) with a title block, sections separated by
  rules, headline numbers as large tiles (two per row on phones), and risks as cards. Diagrams are
  static pictures, as on the plain page, with no zoom or context panel. From 600px wide a diagram
  fits the column whole, so its shape shows at a glance even when its labels get small; narrower,
  it keeps at least 75% of its size (as the Explorer's do) and scrolls sideways inside its frame,
  starting at its middle. Deep links (`#<section or block ID>`) scroll there again as diagrams are
  laid out, until the reader scrolls or clicks.
- **Checking it.** Java tests cover the new sections, the risk checks and order independence, and
  golden files `<repo>.brief.json` hold the briefs of the fixture repositories. Vitest renders the
  golden brief and the schema fixture with the brief and checks the severity badge. `OpenWmsComposeIT`
  also wrote `<repo>.brief.json` for every OpenWMS repository (removed in #100); they were rendered and checked by
  eye in headless Chrome at 390px, 1366px and 1920px wide. The overview's narrative there comes from
  the fake model, so its wording was not judged.

## Not in this issue

LLM-written risks and a narrative written for executives (the summaries are written for
engineers), audience profiles, a print stylesheet, and a compact layout for wide diagrams (#53),
which fan-in context diagrams such as `ameba-lib`'s need most: fitted whole, their labels are too
small to read on a laptop.
