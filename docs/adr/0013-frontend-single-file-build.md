# 0013: Frontend skeleton and single-file build

Status: accepted (#14), pending the user's review (#24)

## Context

`docs/design.md` (Rendering and templates; Technology stack) says the primary output is one HTML
file with all CSS, JavaScript, diagram data and knowledge inlined, built from a TypeScript
component codebase that also becomes the hosted UI, with elkjs laying diagrams out from inputs
sorted by stable ID. Issue #14 names Vite and asks for a `lantern-web` project that renders a
document model (0011) into such a file, wired into the Maven build. The design does not name a UI
framework, how the document gets into the file, or what the Java side receives. The app shell,
themes, search and zoom are the templates' work (#15, #16).

## Decisions

- **React** is the component framework: the most widely used one for TypeScript UIs, and the
  design wants the same components for the read-only file and the editable hosted UI later.
  JSX is compiled by Vite; no React plugin is needed while there is no dev server work.
- **`vite-plugin-singlefile`** inlines the bundle's JavaScript and CSS into `index.html`, the
  standard way to get one file out of Vite. The file is about 1.7 MB, mostly elkjs; nothing is
  loaded from the network.
- **The build output is a template; the document is written in afterwards.** `vite build` puts
  `index.html` into `target/classes/io/lantern/web/`, so the `lantern-web` jar (now `jar`
  packaging instead of `pom`) carries it for the Java renderer. The page holds the placeholder
  `<script id="lantern-document" type="application/json"></script>` exactly once; a renderer
  writes the document model JSON between its tags, with every `<` escaped as the JSON escape `\u003c` so the text
  cannot close the element. The JSON is inserted as is; a replace function that reads `$`
  patterns (JavaScript's `String.replace`, Java's `Matcher.replaceAll`) would corrupt text such as
  `Foo$$EnhancerBySpringCGLIB`. `scripts/render.mjs` does this for tests and by hand; the Java side
  must follow the same contract. One template serves every document, so the frontend is built
  once per release, not per document. The build output is byte-for-byte reproducible.
- **Rendering.** The page reads the embedded JSON, refuses a schema version other than 1 with a
  message, and renders sections (headings by depth, IDs for deep links) and all seven block
  kinds. Cross-references link to the section or block, or for an entity to the first section
  about it. A citation shows the cited facts' 1-based positions in the document's facts with
  their subject, predicate, object and evidence as a tooltip. Base theme tokens and light and
  dark colors follow the system setting; layout, theme switching and density are left to #15.
- **Diagram layout.** elkjs (`elk.bundled.js`, no web worker, so it runs from a `file:` URL)
  with the layered algorithm, top to bottom, orthogonal edges, boundaries as compound nodes
  (`INCLUDE_CHILDREN`) and coordinates relative to the diagram. Nodes and edges are sorted by ID
  before layout and ELK's seed is fixed, so the same diagram gets the same layout whatever the
  input order. Node sizes are estimated from label length. A node whose parent is not in the
  diagram is drawn at the top level, and a parent cycle is broken at its lowest ID; edges to unknown nodes are left
  out. Diagrams are drawn as SVG once laid out; a node color is used only
  if it has the schema's `#rrggbb` form, so a document cannot make the page load anything. Stored node positions are not used yet: nothing
  produces them, and keeping them needs incremental layout (#50).
- **Tests** run with Vitest, Vite's own test runner, in Node; the Ajv schema tests from 0011
  moved to it from `node --test` because Node cannot run JSX. They render the schema fixture
  (every block kind) and the composer's golden `shop` output with React's server renderer, check
  layout determinism and nesting, and build the single file to check it is one file that loads
  nothing from elsewhere and carries an embedded document. Opening the file in a browser is not
  part of `./mvnw verify`, which would need a browser in the build; it was checked by hand in
  headless Chrome.
- **TypeScript settings** for a bundled frontend: `moduleResolution: Bundler` (elkjs is CommonJS
  and only imports cleanly that way), the DOM library, Node types for tests and config, and
  `skipLibCheck` because elkjs's and Vite's own declarations do not compile under TypeScript 7.
  Generated and project sources are still fully checked.
- **New npm dependencies**, pinned exactly: `react`, `react-dom`, `elkjs`; for the build and
  tests `vite`, `vite-plugin-singlefile`, `vitest`, `@types/react`, `@types/react-dom`,
  `@types/node`.
