# 0047: Sequence flows traced from entry points and stitched across containers

Status: accepted (#96), amended by ADR 0049 (#176), ADR 0050 (#181), ADR 0058 (#195), ADR 0070
(#250), which supersedes its choosing of flows, and the amendment below (#313), pending the user's
review (#24)

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
- **Tracing** follows a method's `CALLS` in the order of the code (the line of each fact), inside
  the unit, each method once per flow (so recursion ends). A call goes to the method declared where
  it points, or the one that type inherits (`EXTENDS`, `IMPLEMENTS`). When types below it, through
  interfaces and abstract or other base classes, declare the method too, the code does not say
  which runs: each is an operand of an `alt` frame (`as MailNotifier`), the declared one too unless
  its type is an interface or of unknown kind; one such method is followed directly, and more than
  four are a note naming them. Sends inside an operand are delivered inside it. A step is drawn for what leaves the unit at that code, found by the links' `source`:
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
    With no default datasource, or several, it is a note naming the tables and saying the database
    is not known or which it may be.
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
- **Choosing flows.** A candidate is every entry point whose trace has a step. Candidates are
  ranked by how many participants they touch, then those handing off asynchronously first, then by
  unit and method ID. In that order, a candidate is shown if it touches a unit in scope, its entry
  point is not reached by a flow already shown (it is part of that flow), and fewer than seven are
  shown. Error flows and
  failure handling need facts about exception handling that no extractor records yet, so they do
  not rank or appear. With no flow, the section says so.
- **Each page** has a title naming the trigger and its unit (`POST /api/orders on storefront`),
  a few sentences from the trace (what starts it, what it reaches, where it hands off, what could
  not be followed), a table of the trigger with pre- and post-conditions "Not known from the
  code.", the diagram and the renderer's steps table. Wording for the reader, intent phrases and
  conditions are the LLM's (#97); ADR 0058 has the LLM word the prose and conditions.
- **Default views are not stored.** Until now the analyze run stored each default view on first
  use and then used it as stored (ADR 0012, ADR 0016), so a new default section, such as this
  one, never reached a workspace that had run before. Now the defaults live in code; a stored view
  is an override of the default and is used as stored. Migration V12 removes the rows earlier runs
  stored that equal a default of the time, and keeps any other row as an override.
- **Checks.** `SequenceFlowsTest` covers tracing, stitching, implementations and inherited
  methods, recursion, the message cap, profiles, notes, choosing, the flow cap and scope on
  hand-made facts; the golden `runtime.c4.json` holds two flows. `DefaultViewsUpgradeIT` checks
  V12. `Project0EndToEndIT` (ADR 0029) checks FLOW-1 and FLOW-2 step by step in project-0's system
  report, which it validates against the report spec's schema.

## Not covered

Replies, activations and loops are not drawn;
sequences in the Architecture Explorer and the Executive Brief, and splitting a flow above 15
participants, are left for later.

## Amendment: flows go on into the jobs they schedule (#313)

Status: accepted, pending the user's review (#24). Since #263 a JobRunr or Quartz job is an entry
point with `scheduledBy`, the methods that schedule it (ADR 0035, amendment for #263), and its flow
started apart from the request that scheduled it. project-4's task reminder starts at the request
that creates the task and goes on into the job, so the flows now do too.

- **Scheduling a job is a step.** When a traced method is in the `scheduledBy` of a
  `background-job`, the trace draws an async message `Schedules <job>` (the job's name, or the
  simple name of its request type or class) from the method's unit to the unit whose code runs the
  job, with `JobRunr job` or `Quartz job` as its code, then a note that the unit does not wait. The
  job is traced after the method's own steps, like a delivery, starting with a note that it runs
  later. A job in the scheduler's own unit is preferred over a copy of it in another unit. The step
  comes at the line of the method's first `enqueue`, `schedule`, `scheduleRecurrently`, `create` or
  `scheduleJob` call to JobRunr or Quartz, else after the method's other steps.
- **Jobs on a schedule are not continued.** A job with a cron (`scheduled`) runs on its schedule,
  not because of the code that registered it, so it keeps its own flow.
- **Choosing.** The job's entry point is reached by the scheduling flow, so it is not shown apart.
  When a candidate ranks level with a flow already shown and holds it, it takes that flow's place,
  so the containing flow is shown, not the part.
- **Checks.** `SequenceFlowsTest` covers the continuation, the deduplication (including the job's
  flow ranking first) and a job scheduled from code no flow reaches.
