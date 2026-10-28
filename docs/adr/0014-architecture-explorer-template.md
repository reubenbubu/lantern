# 0014: Architecture Explorer template v1

Status: accepted (#15), pending the user's review (#24)

## Context

`docs/design.md` (Rendering and templates) describes the Architecture Explorer as the dense,
interactive template for architects and senior engineers: an app shell with a navigation tree, the
content with full-width diagrams and a context panel with the selected entity's details and
evidence; three panes on wide screens, two on laptops and one with slide-out drawers on tablets and
phones, using CSS grid and container queries; light and dark themes, client-side search, deep
links and diagram zoom and pan. Issue #15 asks for exactly these, checked against composer output
for an OpenWMS repository at phone, laptop and wide widths. The frontend (0013) renders a document
model (0011) as a plain page. The design leaves open how one page picks a template, what the
navigation tree holds, how deep links look, the breakpoints, and how zoom and pan work. #12 left
open whether tree nodes need IDs of their own for deep links or expand and collapse.

## Decisions

- **One page, every template.** The single-file build stays one template file (0013). It picks the
  layout by the document's template name: `architecture-explorer` gets the app shell, `executive-brief` the
  brief (0015), any other name the plain page. The shell reuses the plain page's components; an
  `Interaction` context turns entity references, citations, diagram nodes and trees into the
  interactive versions, so templates share one renderer.
- **Navigation tree.** The left pane lists the document's sections, then its entities as a tree
  built from their parents (0012): the document's own repository first, expanded, then every other
  entity without a parent, grouped by type (other repositories, third-party artifacts). Children are
  sorted by type (service, library, module, package, type) and name, each with a short type badge.
  Subtrees are rendered only when expanded, so documents with hundreds of types stay quick.
  Selecting an entity in the tree also scrolls to the section about it, if there is one.
- **Context panel.** With nothing selected it shows what the document was made from (template,
  snapshot, vocabulary revision, tool version, counts). For an entity: its type, name and ID, its
  parents, its description, the sections and diagrams it appears in, what it contains (the first
  50), and each of its facts with subject, predicate and object, attributes, evidence (repository,
  path, lines, commit), origin, confidence and the snapshots it holds in. A citation shows the facts
  it cites the same way.
- **Selecting.** Clicking an entity reference, a diagram node or a tree entry selects the entity:
  the context panel shows it and opens as a drawer where it is one. References in the text select
  without moving the content; the navigation tree and search also scroll to the entity's section.
- **Deep links** are the address's fragment: `#<section or block ID>` scrolls there, as in the plain
  page, and `#entity=<URL-encoded entity ID>` selects that entity, scrolls to its section and opens
  its details. Element IDs cannot contain `=`, so the two never clash. Selecting an entity pushes a
  history entry, so Back returns to the previous selection, and the fragment always names the
  selected entity. The facts of a citation are shown without changing the address. Diagrams are laid
  out after the first render and change the page's height, so a deep link is scrolled to again after
  each layout until the reader scrolls or clicks.
- **Tree nodes need no IDs** (the question deferred from #12). Folding is local to the page:
  dependency and structure trees fold below their roots with `<details>`, which keeps its own state,
  and the navigation tree keys its state by entity ID. A tree node that names an entity is reached
  by that entity's deep link. The schema is unchanged.
- **Layout.** CSS grid with a container query on the shell: one pane below 1024px wide, with the
  navigation and the context panel as drawers opened from the header (Escape or the backdrop closes
  them; focus moves into a drawer when it opens and back when it closes); from 1024px the navigation
  sits beside the content and the context panel is a drawer; from 1500px all three panes show. So a
  1366px laptop gets two panes and a 1920px screen three. Panes scroll independently below a fixed
  header; closed drawers are clipped so phones never scroll sideways.
- **Themes.** Light and dark use the same tokens as the plain page. The page follows the system
  setting until the reader toggles; the choice is kept in local storage where the browser allows it
  for the page (it may not for a file) and set as `data-theme` on the root element.
- **Search** is client-side over section titles, titled blocks (diagrams, trees, tables, callouts),
  and entity names, IDs and descriptions. Every word must match; exact and leading matches of the
  name rank first, then names containing the words, then matches elsewhere, each in document order.
  `/` focuses it; arrow keys, Enter and Escape work in the results.
- **Zoom and pan** are written by hand on a CSS transform of the diagram's SVG, with no new
  dependency: buttons (zoom in, zoom out, show the whole diagram), Ctrl or Cmd with the wheel (which
  is also how browsers report a trackpad pinch), dragging, a two-finger pinch on touch screens, and
  `+`, `-`, `0` and the arrow keys once focused. Plain wheel and vertical swipes still scroll the
  page. A diagram opens fitted to the content's width but never enlarged and never below 75% scale,
  so wide component diagrams stay readable and are panned; its viewport is at most 70% of the
  window's height. A press becomes a drag only after it moves 4 pixels, so a click on a node still
  selects it.
- **Checking it.** Vitest tests render the shell for the golden composer output and the schema
  fixture and test the navigation tree, placements, search, deep links and zoom arithmetic in Node.
  Behaviour in a real browser (drawers, search, theme, deep links, pan, zoom and pinch, and the
  three widths) was checked by hand in headless Chrome with composer output for OpenWMS
  repositories. `OpenWmsComposeIT` (run only when `lantern.test.openwms` was set) wrote those
  documents, extracted without builds and summarized by the fake model, to
  `lantern-compose/target/openwms-documents` (removed in #100). Documents for a real repository
  now come from `analyze` or from `EndToEndIT` with `lantern.test.repo` (see `CLAUDE.md`).

## Not in this issue

The density toggle, the print stylesheet and a diagram minimap (#52); a more compact layout for
wide diagrams (#53); the granularity switch (Phase 2); virtualized tables and the hosted UI's
editing. Stored node positions are #50.
