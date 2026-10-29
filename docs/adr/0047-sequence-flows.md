# 0047: Sequence flows traced from entry points and stitched across containers

Status: accepted (#96), pending the user's review (#24)

## Context

`docs/design.md` (Composition, Sequence diagrams) asks for a few architecturally significant flows
per scope: traced from entry points through the resolved call graph, keeping only steps that cross
a container boundary, stitched across containers, ranked and capped. ADR 0028 gave the document
model its sequence diagrams and ADR 0031 renders them into the report spec
`report-specs/c4-model-sequence-diagrams` as `sequence` pages. Issue #96 asks the composer to fill
those pages from real repositories: the trigger, the steps and a walkthrough, pre- and
post-conditions only when they can be worded from code facts, otherwise marked unknown. The inputs
are the call graph (ADR 0007), entry points (ADR 0035), data access (ADR 0036), sends and
listeners (ADRs 0037, 0040), HTTP calls (ADR 0038) and the linker's links with the code on both
ends (ADR 0042). Nothing may be invented.

## Decisions

- **A section `sequences`** ("Main flows", levels L1 and L2) in the C4 report views of a
  repository (`<repo>.c4`) and of the system (`system:c4`), after the components; one subsection
  per flow, each a `sequence` page. `SequenceFlows` (`lantern-compose`) builds them.
- **Entry points** are the methods of a unit's code (its module and the libraries it bundles)
  with an `endpoint`, `message-listener` or `scheduled` fact.
- **Tracing** follows a method's `CALLS` in the order of the code (the line of each fact), into
  the same method of the classes implementing an interface, inside the unit, each method once per
  flow. A step is drawn for what leaves the unit at that code, found by the links' `source`:
  - an HTTP call (`CALLS_HTTP`), sync, to the unit or external system it reaches, labelled
    `Calls <Handler.method>` with the endpoint as code and the protocol in its walkthrough text;
    the trace goes on in the endpoint's handler (`target`). A profile alternative of the link
    (ADR 0044) is not drawn; its walkthrough text names it.
  - a send (`PUBLISHES`), async, to the topic, queue or exchange, then a note that the sender does
    not wait. Each listener the send reaches (`SENDS_MESSAGES_TO`) gets an async delivery from the
    destination, with the bound queue in its code, and the trace goes on in the listener. Deliveries
    are traced after the sender's own steps, in the order sent, since they happen later. A send no
    listener takes gets a note saying so.
  - data access: `READS_TABLE` and `WRITES_TABLE` of the method, and calls to methods declared on a
    Spring Data repository (`REPOSITORY_FOR`), to the unit's database when it has exactly one by
    default; tables of another unit's schema (`ACCESSES_DATA_OF`) are named in the walkthrough.
  - a call Lantern could not link (`unresolved-link`) is a note, and the trace does not follow it.
  The trace stops after 40 messages with a note.
- **Profiles.** A step is conditional when its link holds only under a profile or its code is
  under a `@Profile` gate naming profiles (a negated gate is not a condition; its links say which
  profiles they miss). Each run of steps under the same profiles is an `opt` frame naming them, at
  one level, and each message lists its profiles.
- **Participants** are containers, named and with the technology of the container diagram: the
  units, databases, topics, queues and exchanges (a RabbitMQ send goes to its exchange, as the
  container diagram folds its queues), and external systems, in order of first appearance. The
  caller of an endpoint is not known, so the flow starts with a note naming its trigger instead
  of an actor.
- **Choosing flows.** A candidate is every entry point whose trace has a step. One whose entry
  point another candidate's trace reaches is part of that flow and left out. Candidates are
  ranked by how many participants they touch, then those handing off asynchronously first, then by
  unit and method ID; at most seven are shown, those touching a unit in scope. Error flows and
  failure handling need facts about exception handling that no extractor records yet, so they do
  not rank or appear. With no flow, the section says so.
- **Each page** has a title naming the trigger and its unit (`POST /api/orders on storefront`),
  a few sentences from the trace (what starts it, what it reaches, where it hands off, what could
  not be followed), a table of the trigger with pre- and post-conditions "Not known from the
  code.", the diagram and the renderer's steps table. Wording for the reader, intent phrases and
  conditions are the LLM's (#97).
- **Checks.** `SequenceFlowsTest` covers tracing, stitching, profiles, notes, choosing and scope on
  hand-made facts; the golden `runtime.c4.json` holds two flows. `Project0C4ReportIT` (ADR 0044)
  now builds project-0 in dependency order and checks FLOW-1 and FLOW-2 step by step.

## Not covered

Calls to Spring Data's inherited methods (`save`, `findById`) leave no `CALLS` fact (ADR 0007),
so FLOW-1's shop-database steps are missing (#176). View definitions stored before this change
keep their sections (ADR 0012), so a workspace that already holds `system:c4` or `<repo>.c4`
shows no flows until those rows are removed. Replies, activations and loops are not drawn;
sequences in the Architecture Explorer and the Executive Brief, and splitting a flow above 15
participants, are left for later.
