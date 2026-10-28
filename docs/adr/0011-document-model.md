# 0011: Document model

Status: accepted (#12), amended by ADR 0027 (#70) and ADR 0028 (#91), pending the user's review (#24)

## Context

`docs/design.md` (Composition, layout stability and prose stability; Rendering and templates;
Technology stack) says the composer's output is a format-neutral document model of sections,
diagrams, tables, callouts and cross-references, each element linked to the fact IDs it came from;
that it is described by JSON Schema with hand-written Java types checked against it by tests and
generated TypeScript types; and that the exported HTML file inlines the in-scope knowledge so it
works offline. Issue #12 defines it. The composer (#13), the frontend (#14) and the first templates
(#15, #16) build on it. The design leaves open the exact elements, where the schema and types
live, and which tools generate and check the TypeScript types. The authoritative form is
`lantern-core/src/main/resources/schema/document.schema.json`; this record explains the choices.

## Decisions

- **A document is self-contained.** Besides its sections it carries the entities and the fact
  envelopes it refers to, sorted by ID, so a renderer can show names, the navigation tree,
  evidence, origin and confidence without calling the service. Every fact ID a document mentions
  must be in its facts, every entity it mentions in its entities, and every cross-reference must
  point to a section, block or entity of the same document. JSON Schema cannot express these
  references, so the Java types enforce them.
- **Identity and reproducibility.** A document has an ID, a title, an optional audience profile
  name, the template name and version, and its source: snapshot number, vocabulary revision and
  tool version. There is no generation time, so the same inputs give the same JSON. The annotation
  revision the design lists among a document's inputs is added when annotations exist.
- **Sections** have a stable ID, a title, optional granularity levels (`L0` to `L3`; absent means
  every level), an optional entity they are about, fact IDs, blocks and subsections. Section and
  block IDs are unique across the document and are what deep links and cross-references use.
- **Blocks** are paragraph, list, callout, table, diagram, tree, metrics and quality report (0028),
  told apart by `kind`.
  The design names sections, diagrams, tables, callouts and cross-references; lists are needed for
  ordinary narrative, a tree for the dependency tree and module and package structure #13 must
  produce, and metrics for the Executive Brief's headline numbers (#16). Risks are callouts with
  the variant `risk`; the other variants are `note` and `warning`. A risk may have a severity
  (`high`, `medium`, `low`), added for the Executive Brief (0015). Every block, and every list
  item, table row, diagram node and edge, tree node and metric, has its own fact IDs.
- **Rich text** is a list of inline elements: text runs (optionally strong, emphasis or code),
  cross-references and citations. A citation lists the fact IDs behind the text before it, which
  is where summaries' `[fact:...]` markers (0010) end up. A summary's `[summary:<entity>]` marker
  becomes a cross-reference to that entity, marked as a citation (0027).
- **Diagrams** have a kind (`c4-context`, `c4-container`, `c4-component`, `dependency`), nodes and
  edges. A node is an entity (its ID is the entity ID, which is how layouts are keyed), with a
  label, an optional C4 technology line, an optional parent node it is drawn inside (a boundary),
  an optional resolved style (shape, icon, `#rrggbb` color) and an optional stored position
  (integer layout units, and whether a user pinned it). Nodes and edges are held sorted by ID so
  layout input is stable. Without stored positions the renderer lays the diagram out. Sequence
  diagrams (`sequence`) have participants and steps instead of nodes and edges (0028).
- **Where it lives.** The schema sits next to the fact envelope schema in `lantern-core`, and the
  Java types are records in `io.lantern.core.document`, written and read by `DocumentJson` in the
  same strict style as `FactJson`, because the document embeds fact envelopes and both the
  composer and the app use it. `DocumentSchemaTest` keeps schema and Java in line (fields,
  required fields, enums, patterns), as `FactSchemaTest` does for facts. The schema is versioned by
  a `schemaVersion` field, now 1.
- **TypeScript types are generated** at build time by `json-schema-to-typescript`, the most widely
  used generator, into `lantern-web/src/generated/document.ts` (not committed). References
  to the fact envelope schema resolve locally by `$id`. They are checked by compiling with TypeScript a
  typed sample document and lines marked `@ts-expect-error` that the types must refuse.
- **Schema validation tests** use Ajv, the standard JSON Schema validator for JavaScript,
  in `lantern-web`: the fixture `lantern-core/src/test/resources/schema/document/valid-document.json`
  and the typed sample must pass, and each case in `invalid-cases.json` (one change to the fixture
  each) must fail. The Java tests write exactly that fixture and refuse the same invalid cases, so
  the schema, the Java types and the TypeScript types are tested against the same documents. No
  Java JSON Schema validator is added.
- **`lantern-web` is created now**, with only type generation and these tests, so the frontend
  (#14) starts from it. It is a Maven module that uses `frontend-maven-plugin` to download Node
  (the current LTS line) into `lantern-web/target` and run `npm ci`, `npm run
  generate` and `npm test`, so `./mvnw verify` stays the only CI and needs no Node install. The
  plugin caches the Node download in the local Maven repository. `-DskipTests` skips the npm
  tests.
  Versions are pinned in `lantern-web/package.json` and the root `pom.xml`.
- **Java reads as the schema does.** Non-blank text follows JSON Schema's (ECMAScript's) `\S`, and
  an integer written with a zero fraction (`3.0`) is accepted. Metric values are held without
  trailing zeros so equal numbers compare equal, and node positions stay within what a JavaScript
  number holds exactly.
