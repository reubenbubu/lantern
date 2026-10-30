# 0047: Sequence flows traced from entry points and stitched across containers

Status: accepted (#96), amended by ADR 0049 (#176), ADR 0050 (#181), ADR 0058 (#195), ADR 0070
(#250), which supersedes its choosing of flows, and the amendments below (#313, #331, #272),
pending the user's review (#24)

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
  This refines the rule above, kept by ADR 0070, that a flow reached by one already shown is not
  shown: when a candidate holds a flow already shown and touches as many participants, as a
  scheduling flow and its job's flow can when they tie, it takes that flow's place, so the
  containing flow is shown, not the part. This holds for every flow, not only jobs.
- **Checks.** `SequenceFlowsTest` covers the continuation, the deduplication (including the job's
  flow ranking first) and a job scheduled from code no flow reaches.

## Amendment: flows start in the browser (#331)

Status: accepted, pending the user's review (#24). Since #267 frontends are containers and their
calls are `CALLS_HTTP` links from `frontend:` entities (ADR 0083), but the flows still started at the
backend's entry points with a note naming the trigger. The references start them in the browser:
project-4's sign-in and real-time notification, project-1's web sign-in.

- **The linker names the endpoint a frontend's call reaches** (ADR 0083, amendment for #331): the
  link from the frontend carries `reaches` (the handler, or the method that registers a WebSocket
  endpoint), `reachesUnit`, `reachesEndpoint` and, through a gateway, `throughRoute`.
- **A flow whose entry point a frontend's call reaches starts in the browser**, with the call that
  comes first in the frontend's code: a message from the person who uses the frontend ("Uses the
  screens", in a web browser), then from the frontend to the unit or gateway it calls ("Calls
  <Handler.method>", "Navigates the browser to ..." for a navigation, "Opens a socket" for a
  socket), with the request as it reaches the unit or gateway (`POST /api/auth/sign-in`, after the path a proxy of the frontend's server rewrites) as its code, and,
  through a gateway, from the gateway to the unit ("Routes to <Handler.method>", or the route
  link's intent phrase; "Relays the socket"), with the endpoint as the unit serves it. The person
  is the frontend's only one with screens on it (ADR 0074, ADR 0083), else the one with most
  screens on it, by ID among equals; with none, the flow starts at the frontend. The participants
  start with the person, the frontend and the gateway. The flow's opening note and trigger say so
  ("Starts when Organization member, in crm-frontend, sends POST /api/auth/sign-in, which
  api-gateway routes to auth-service"; trigger "POST /api/auth/sign-in from crm-frontend (AuthController.signIn)"); of a
  handler with several endpoints, the one the call reaches names the flow.
- **A socket the frontend opens starts a flow** at the WebSocket endpoint it reaches (ADR 0080),
  titled "WebSocket connection to /ws-notifications on notification-service": after the browser's
  steps, each method an interceptor of the unit's inbound STOMP channel declares (`preSend`, then
  the others in the order the channel calls them) is traced, after a note that every frame the
  client sends passes it. A WebSocket endpoint no frontend opens starts no flow.
- **Choosing (ADR 0070's business weight).** A browser start adds the containers it touches, so
  such flows rank higher. Then, ahead of the ranked rest, come the best ranked flow a person signs
  in through (a request whose path has a segment that ADR 0074 reads as signing in: `sign-in`,
  `login`, `authenticate`...) and the best ranked flow that opens a socket from the browser: how
  people get in, and how the system pushes to them. A candidate of the same shape as a flow already
  shown (started the same way, by a request, a message, a schedule or a job, with the same
  lifelines in the same order) is not shown, since it would be the same picture with other labels:
  project-4's PUT and DELETE of a user's role, project-3's reading and updating of a user.
- **Checks.** `SequenceFlowsTest` covers a frontend's call reaching an entry point directly and
  through a gateway route, a socket through a gateway and the inbound interceptor, a WebSocket
  endpoint no frontend opens, and the sign-in flow coming first. `CrossRepoLinksTest` covers the
  link attributes.

### Not covered

A frontend's call is not tied to the screen that makes it, so the person is the frontend's main
one, not the one whose screen calls; the gateway's own filters (authentication, permission caches)
are not traced into the flow; a request the gateway answers itself through a `forward:` or
`no://op` route may be taken as reaching a lower route's unit; a unit's call to a gateway still stops at the gateway (project-1's BFF
to its gateway); and an entry point that both a frontend and another flow reach is shown only as
part of that flow. On project-4 the password-reset flow still ranks below the seven shown (#272
chooses by business weight).

## Amendment: business scenarios, not endpoints (#272)

Status: accepted, pending the user's review (#24). ADR 0070 asked that flows be chosen by
cross-service reach and business weight and titled as scenarios. project-4's report titled flows
by endpoint, showed one role call where creating an organization makes two, dropped main-service's
deliveries to its own listeners, and noted fourteen times that one of `CrmException`'s subclasses
"runs" a constructor; project-2's flows were pollers and CRUD, three of them at the message cap.

- **A method is traced each time the code calls it**, not once per flow: two calls to the same
  helper from two places are two steps. Only a recursive call, to a method being traced, is not
  followed again; a method whose code added nothing is not traced again, so the trace stays
  small. A delivery or a job is traced once per flow, so a listener that sends to itself does not
  loop.
- **A constructor is never an alternative**: `new X()` runs `X`'s constructor, not one of a class
  below it, so it is followed directly, without an `alt` frame or a note.
- **Self-deliveries are kept.** The linker now links a send to a listener of the same unit
  (ADR 0042, amendment for #272), so the flow draws the delivery back to the unit, such as
  main-service's default task statuses and priorities after `org.created`.
- **A run of data access is one step.** A unit's reads, writes and repository calls in the same
  database, under the same profiles, one after another, are one message: "Reads orders, payment;
  writes payment; calls ParcelRepository.save", with at most four names a verb before "and N
  more".
- **Choosing.** After the sign-in and socket flows, which still come first (a sign-in now also
  when no browser starts it: an endpoint whose own path has a sign-in segment), the rest are taken
  in rank order with what someone asks for (a request, a socket or a STOMP message from a client)
  before a message a listener takes, before a schedule or a job that runs apart from a request.
  A flow that reaches nothing but databases from its entry point, not started in a browser, shows
  nothing the container diagram does not: it comes last and is shown only while fewer than three
  are. Same-shape and containing flows are handled as before.
- **Titles.** A flow's title names its scenario, then its trigger in brackets: "Create
  organization (POST /api/organizations on main-service)". The LLM words the scenario as a title
  of at most six words in the same call that words the walkthrough and conditions (ADR 0058; the
  answer's `title`, which must cite a fact like the other texts, raising the flow prompt's
  version). Without its title, the scenario is the entry point's method name in words
  (`createOrganization` as "Create organization"); a method of one word adds the words of its
  type that do not name a kind of class (`Status.get` as "Get status"). The free fake model of
  ADR 0072 answers the title with `{{placeholder: scenario title}}`.
- **Checks.** `SequenceFlowsTest` covers constructors, a helper called from two places, a unit
  sending to its own listener, a run of data access, the order of requests, schedules and flows
  that reach only databases, a sign-in endpoint without a browser and the titles;
  `CrossRepoLinksTest` a send to the unit's own listener; `DescriberTest` the LLM's title;
  `Project0EndToEndIT` the new steps of FLOW-1 and FLOW-2.

### Not covered

Branches around a send, such as an email sent only under a condition, are still drawn as always
happening: the call graph records no conditions (#350). A flow still starts at its unit's
endpoint when no frontend's call reaches it, not at the gateway route a client calls (#351), so
project-1's administrator flow and project-3's watch record keep the unit's path as their
trigger. Flows of the same shape still differ only by rank: of project-4's sign-up, password
reset and resend, the first by ID is shown.

