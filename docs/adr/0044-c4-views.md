# 0044: Context, container and component views, and the system's C4 report

Status: accepted (#95), amended by ADR 0046 (#163) and ADR 0048 (#176), pending the user's review (#24)

## Context

`docs/design.md` (Composition, Diagrams) defines the C4 views by c4model.com's definitions: the
software system is the scope, a container is a runtime unit (an application, data store, queue or
topic), a component a group of functionality behind an interface found by role. ADR 0026 replaced
ADR 0012's mapping (repositories as systems, modules and jars as containers, packages as
components) with these definitions, and the extractors and linker now give the inputs: deployable
units (ADR 0039), their links to each other, databases, topics, queues, exchanges and external
systems (ADR 0042), Spring stereotypes and entry points (ADR 0035) and decision records (ADR
0033). Issue #95 asks the composer to draw the views from them, with the system scope, so that the
report spec `report-specs/c4-model-sequence-diagrams` is filled from real repositories: a context
page per system, container pages, a component page per service, elements of every kind with their
technology, and relationships with a label and a technology. Everything is optional and nothing is
invented. The issue's Quality bar checks and the Executive Brief's blast radius, technology
currency and ranked risks were split off to #163 and #164.

## Decisions

- **The system scope** (`{"kind": "system"}`) covers every repository of the snapshot as one
  software system, the entity `system:snapshot`. Its name is its repositories (`catalog,
  fulfilment, payments and storefront`), or how many there are beyond four, until people name
  systems (annotations, Phase 3) or groups exist (Phase 4). A per-repository document draws its
  repository's units inside the same system. The analyze run composes the view `system:c4` (the
  colon keeps it apart from repository views) over every fact that holds in the snapshot, carried
  forward repositories included, and writes `<snapshot>/_system.c4.html` (the underscore keeps it
  apart from repository files).
- **Context**: the system, the external systems its units call (the linker's `external-system`
  targets) and one line to each, without technology, as the design says; then a note that the code
  names no people, so who uses the system is not known. Headline numbers before the diagram count
  its deployable units, databases, topics, queues and exchanges, and external systems. The landscape
  is not drawn: with one system in scope it would repeat the context; it comes with groups.
- **Containers**: one box per deployable unit in scope, named by its `spring.application.name`, with
  its technology (`Spring Boot 3.5.14 / Java 21`), and its direct neighbours: other units, its
  databases, topics, queues and exchanges (inside the system's boundary) and external systems
  (outside). A per-repository document's units are those whose code is in the repository, their
  own module or a library they bundle. A RabbitMQ queue that an exchange routes to and no unit sends
  to directly is not a box: the line runs from the exchange to the consumer with the queue in its
  technology (`AMQP · payments.order-events`), the design's "via" label, so a broker never becomes
  one box and the diagram stays small. A diagram of more than 15 elements is split into one per unit
  with its neighbours, each its own subsection and so its own page. A diagram still above 15
  elements (one unit with more neighbours, or a scope of one unit) is drawn whole and fails "At most
  15 elements" in the document's quality report (ADR 0046). Service discovery, config
  servers, tracing and brokers (`USES_INFRASTRUCTURE`, `USES_BROKER`) are left out, and
  `SENDS_MESSAGES_TO` is drawn through the topic or queue it goes through.
- **Lines** are one per pair of elements and relationship, built from the link facts: the label
  says what happens with what the links record (`Calls GET /api/products/{id}`, `Sends
  order.created`, `Delivers order.*`, `Stores its data in` for a unit whose migrations build a schema
  there, else `Connects to`, `Reads tables of`), naming two details and counting the rest; the
  technology is the link's protocol. A line all of whose links hold only under profiles lists them;
  a line that holds by default is unmarked.
- **Profile alternatives are a note, not boxes.** A profile-only link that stands in for a default
  link of the same unit and relationship (the same calling method, listener or datasource key, or
  an alternate host the linker names, ADR 0042), the default not holding under that profile, is left
  out of the diagrams and the headline counts and listed in an "Under other profiles" note on the
  context (external systems only), container and component pages: project-0's `local` datasource on
  `localhost`, PayGate's `prod` host. So one real system or database is one box, named by its default
  target. A profile-only link that replaces nothing, such as SPR-4's `audit` send, stays a line
  marked with its profile. Intent phrases
  worded for the reader are the LLM's (#97); these labels are what static facts say.
- **Components**, inside each unit: each controller, message listener or scheduled job is a
  component (named after its class) with the beans (stereotyped classes, Spring Data repositories,
  HTTP client interfaces) reachable only from it through the call graph, injections and
  implementations of interfaces; a bean reachable from several entry points is its own component,
  and so is one reachable from none that reaches outside the unit (data access, an HTTP call or a
  send), such as a repository no code calls. Configuration and
  application classes, domain and util classes are left out. Lines between components are the
  calls between them, labelled with the methods called; lines to and from other containers are the
  container lines attached at the code their link names (`source`, `target`), and data access goes
  to the unit's databases. A component diagram is drawn only for a unit with more than one
  component, as the design asks.
- **Decision records** of the scope's repositories are a table after the container diagram (ID,
  decision, status, outcome), and links Lantern could not resolve a warning naming each.
- **A unit's description** is the summary of the module holding its main code (its main bundled
  library's, else its own), until #97 words descriptions for containers; a component's is its class's
  summary.
- **Package dependencies stay** only for the Executive Brief's package-cycle risk (ADR 0015), read
  from the packages directly (`PackageDependencies`), since packages are no longer drawn.
- **The document model** (ADR 0011) gains an edge `technology` and `profiles` (the Spring profiles
  under which alone it holds), as messages have; schema version 1, both optional. Lantern's own
  templates show them in the edge label.
- **The C4 report renderer** (ADR 0031) now draws an element by its Lantern type where the type says
  more than the diagram level: `database` as a database, `topic`, `queue` and `exchange` as a queue
  (tagged with their type), `external-system` as external, `person` as a person; a component's Java
  type is not shown as a tag. A relationship carries its technology, and its profiles in its label
  (`(only with the Spring profile prod)`). The catalogue after a diagram is one per kind (people,
  systems, containers with a link to their component page, data stores and queues, components,
  external systems), and a table about decision records becomes a decisions section.
- **Checks.** `C4ViewsTest` covers the views on hand-made facts, and the golden `runtime.c4.json`
  holds the system report of those facts for the composer's and the renderer's golden tests.
  `Project0EndToEndIT` (ADR 0029) checks project-0's system report, as the analyze run composes it
  with the repositories built, for its pages, elements and key lines (REPO-1, HTTP-1, MSG-1, SPR-4)
  and against the report spec's schema, and writes it to `lantern-app/target/project-0/` to look
  at. It replaces `Project0C4ReportIT`, which composed the same report outside the run (#167).

## Not in this issue

The Quality bar checks (#163); the Brief's blast radius, technology currency and ranked risks
(#164); sequence flows (#96); intent phrases and descriptions worded for the reader (#97); people
and external systems from annotations (Phase 3); stored layouts.
