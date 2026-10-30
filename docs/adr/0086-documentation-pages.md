# 0086: Documentation pages in the C4 report

Status: accepted (#249), amends ADR 0031, ADR 0044, ADR 0048 and ADR 0082, pending the user's
review (#24)

## Context

The report spec `report-specs/c4-model-sequence-diagrams` only had pages at a C4 level (context,
containers, components) and sequence pages. Whatever else a report holds had to sit on one of
them: Lantern put the decision records, the links it could not resolve, the README claims the
code contradicts and the deployment table after the container diagram (ADR 0044, ADR 0051, ADR
0082), and the hand-made references put their sources, glossary, decisions, risks and unknowns at
the end of the context page. In C4 a container page shows runtime units; decisions and risks are
not. The user decided that C4 pages hold only C4 content, and that the rest are documentation
pages, their own navigation group after the sequence diagrams (ADR 0070, #249). The design also
asks every template to mark what the LLM found (design, Grounding), and the spec had no marker.

## Decisions

- **The spec gains a page level `documentation`**, additive, so every existing document stays
  valid. The template lists those pages in their own sidebar group, "Documentation", after the
  sequence diagrams, gives them a card section on the index after the sequence cards, and renders
  them like any page, without a diagram. A documentation page holds no diagram, sequence or steps
  section (a load check, like the spec's other cross-reference rules, also in
  `lantern-web/scripts/report-spec-check.mjs`). The sample data moves its "Key Decisions" off the
  container pages onto a documentation page with risks and unknowns, and adds a glossary page.
- **The spec gains an LLM marker**: `llm: true` on an element or a relationship, and `llmRows` (row
  indices) on a table, for what the LLM found rather than static analysis. The template shows an
  "LLM" badge in tables and prefixes "LLM ·" to the element's type label and the line's label in
  diagrams. Lantern's renderer sets it where every fact cited is of origin `llm`: an element's
  entity and diagram node facts, a line's facts, a table row's facts. That covers what Investigate
  found and also a link the Resolve role picked among the code's candidates (ADR 0045: its facts are
  of origin `llm`), which is equally the LLM's finding.
- **Two new composer sections** take what is not at a C4 level out of the containers section:
  `deployment` ("Deployment", the table of ADR 0082, left out when there are no deployment
  containers) and `decisions` ("Decisions and unknowns": the decision records, then the links not
  resolved and the README claims the code contradicts). The C4 reports list them after the main
  flows; the Explorer and the Brief right after the containers, where their content was, so they
  show the same as before.
- **The renderer makes a documentation page** of each top-level section after the first page that
  holds no C4 or sequence diagram and is not the context, containers, components or main flows
  section (those keep what they hold when they draw nothing, as before), unless it holds only a
  quality report, whose failures still go to the pages they are about. So the decisions, the
  deployment, and the catalog, glossary and findings sections other issues add to the C4 report
  (#182, #229, #273, #269) become documentation pages without further change. Sections before the
  first page stay the index's introduction.
- **The deployment table is a documentation page, not a C4 deployment page.** In C4 a deployment
  view is a diagram of nodes and container instances; Lantern has no deployment diagram kind yet,
  and a page at a C4 level without its diagram is what this decision removes. When a deployment
  diagram is drawn, it gets a deployment level of its own (#352).
- **The references** of project-1 to project-4 move their sources, glossary and decisions, risks
  and unknowns from the context page, and their deployment table from the first container page, to
  four documentation pages (`testsets/documentation-pages.mjs`).
  The scorer reads decisions, risks and glossary terms from any page, so their scores do not change.

## Consequences

- No C4 page of a Lantern C4 report holds decisions, risks, unknowns, the deployment table, a
  glossary or a catalog.
- Element catalogue tables stay after each diagram: they are the diagram's key, part of the spec's
  C4 pages, and where an element's links point. The catalog of #182 is a separate section and a
  documentation page.
- The Explorer's own documentation sections (catalog, change guidance, dependencies, structure,
  glossary) would also be documentation pages if an Explorer view were rendered with this spec.

- A view stored before this change (ADR 0047) lists `containers` without `decisions` or
  `deployment`, so documents of it no longer show the decision records, unknowns or deployment
  table until the view adds those sections. Lantern's default views are kept in code and have them.
- Of what ADR 0070 lists, only the decisions, unknowns and deployment reach Lantern's C4 reports
  today; the risks (#273, #269), glossary (#229) and catalog (#182) become documentation pages when
  those issues add their sections to the C4 views after the main flows.

## Not verified

The fake-model runs of the test sets produce no element, line or row whose facts all come from the
LLM; the marker is checked on the document model fixture (a resolver fact) and the spec's sample
data.
