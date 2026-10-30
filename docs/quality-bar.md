# Lantern — Quality Bar

This document sets the standard Lantern's output must meet. `docs/design.md` says how Lantern gets
there and how it checks; where the two disagree, the design is wrong or this bar needs the user's
decision. Each rule here carries the evidence behind it.

**Scope for now.** The bar covers one document: the C4 architecture report
(`report-specs/c4-model-sequence-diagrams`), for one repository and for a whole system. Other
documents (Architecture Explorer, Executive Brief and the rest) are out of scope until the user
brings them in; each then gets its own section here. The scope limits the documents, not the
analysis: extraction and linking aim at every relevant fact, and a document is a selective view of
them (`docs/design.md`, Design principles).

## The standard

A reader who has never opened the code can answer five questions from the report: what runs, what
each running part and data store is responsible for, who or what talks to what, why, and over which
protocol. Every box is a runtime unit with a technology and a one-sentence job; every line is a
one-way runtime interaction with an intent phrase and a protocol ("Makes API calls to
[JSON/HTTPS]"), as in the c4model.com container example.

Facts come from the code first. The LLM words what extraction found, and may find what extraction
missed only with citations Lantern verifies, and what it found is marked as found by the LLM. What
the code cannot show is stated as unknown, never invented.

The bar is met when the report is **as good as or better than the reference** of each test set: a
report written by hand from cited code facts by a careful reader and checked line by line by a
second, independent one (`docs/design.md`, Test sets and Checking the quality bar). A report that
passes every rubric check below but misses what the reference finds does not meet it.

## Boxes and lines follow C4's definitions

C4 defines a container as "a runtime boundary around some code that is being executed or some data
that is being stored": a deployable app, a batch job, a function, a database schema, a bucket. Asked
whether JARs and modules are containers, the site answers "Typically not"
([c4model.com container](https://c4model.com/abstractions/container)). A component is "a grouping of
related functionality encapsulated behind a well-defined interface" inside one container. **JARs,
modules, packages, namespaces and folders are packaging units that "may" map to components but
typically do not** ([c4model.com component](https://c4model.com/abstractions/component)). For
libraries and SDKs, C4 says you are "better off using something like UML"
([c4model.com FAQ](https://c4model.com/faq)). A cache server reached over the network, such as
Redis, is a data store; an in-process cache (Caffeine, Spring's simple map cache, an embedded
Hazelcast member) is part of the container that holds it, never a box of its own
([research](research/extractor-redis.md)).

The notation rules are just as direct. Every element needs an explicit type and "a short
description, to provide an 'at a glance' view of key responsibilities". Every container and
component needs a technology. Every line must be one-way and labelled with its intent, "ideally
avoiding single words like 'Uses'", and lines between containers must name a protocol
([c4model.com notation](https://c4model.com/diagrams/notation)). A Maven aggregator drawn as `api`,
`core`, `commons` and `service` boxes joined by "depends on" arrows breaks four rules at once:
modules drawn as containers, libraries drawn as containers, labels with no intent, and edges that
follow compile direction instead of runtime calls. Nobody can then say who is responsible for what.

## Each C4 level answers one question for one reader

C4 gives each level a fixed scope and fixed elements, and says how strongly it recommends each one.
The table condenses the official definitions
([context](https://c4model.com/diagrams/system-context),
[container](https://c4model.com/diagrams/container),
[component](https://c4model.com/diagrams/component), [code](https://c4model.com/diagrams/code),
[landscape](https://c4model.com/diagrams/system-landscape),
[dynamic](https://c4model.com/diagrams/dynamic),
[deployment](https://c4model.com/diagrams/deployment)).

| Diagram | Question it answers | Reader | Must show | Typical failure |
| --- | --- | --- | --- | --- |
| Landscape | What systems does the organisation run, and who uses them? | Everyone | People and software systems in scope, no single focus | Containers leaking in |
| System Context | Who uses this system, and what does it depend on? | Everyone, including non-technical | One system in focus, user roles, directly connected external systems, no technology | Internals or protocols shown |
| Container | What runs, what does each part do, and how do the parts talk? | Architects, developers, ops | Apps and data stores, each with name, technology and job; protocol on every edge | Modules or libraries as boxes; one "message broker" hub |
| Component | Inside one runnable, which part owns X and what does it call? | Developers | Functional groups behind an interface, with technology and job | Packages drawn as components |
| Code | How is this component implemented? | Developers | Classes and tables for one key component | Drawn at all for routine code |
| Dynamic | How do the parts carry out one feature? | Technical and non-technical | Numbered, scenario-specific interactions at one level | One per endpoint; mixed levels |
| Deployment | Where do the containers run in one environment? | Technical | Nested nodes, container instances, infrastructure nodes | Nodes invented without descriptors |

Context and Container diagrams are recommended "for all software development teams". Component
diagrams only "if you feel they add value", and preferably automated. Code diagrams are "not
recommended for anything but the most important or complex components", because IDEs generate them
on demand. For a generator the effort argument disappears, so the bar for component diagrams becomes
correctness: **a wrong component diagram is worse than none**.

Two C4 rules matter most for microservices. First, model each service as an API container plus its
own database-schema container; promote services to separate software systems only if separate teams
own them ([c4model.com microservices](https://c4model.com/abstractions/microservices)). Second,
**never draw the message bus as one container**. Draw each queue or topic as its own container, or
write "via <queue>" on the producer-to-consumer arrow, because a single hub "hides which producer is
coupled to which consumer"
([c4model.com queues and topics](https://c4model.com/abstractions/queues-and-topics)).

## Diagrams stay readable

A diagram is readable when it answers one question, can be read without zooming, and every line can
be followed from end to end. A diagram that fails this is split into several at the same level (for
example one per concern, or one per service with its inbound and outbound neighbours,
[c4model.com FAQ](https://c4model.com/faq)), or detail is folded away. No number of elements decides
this on its own. Brown gives no cap; third-party guides suggest about 10 to 15 elements
([Visual Paradigm](https://skills.visual-paradigm.com/?p=971)), a heuristic only. Size, line count
and crossing lines are signals of a diagram that has stopped being readable; how Lantern measures
them is in the design.

## Components come from how the code is used, not how it is filed

Automatic extraction of components works only when the code shows its architecture, through
stereotypes, naming and package-by-component layout
([Brown, GOTO 2018](https://files.gotocon.com/uploads/slides/conference_12/515/original/gotoberlin2018-modular-monoliths.pdf)).
No project can be trusted to have a coherent package structure: package-by-layer code (`web`,
`service`, `repository`) says nothing about who does what, and package-by-feature code drifts.
Brown's PetClinic example makes each web controller a component, groups the service and repository
behind it, and leaves out domain and util classes
([c4model.com component](https://c4model.com/abstractions/component)).

So components are inferred from the linkage in the code itself:

1. **Entry points anchor components**: each REST controller, message listener, scheduled job,
   gateway filter or set of gateway routes. They are how a container is used, so they are stable
   across commits and easy to explain.
2. **Code linkage decides membership**: what only one entry point reaches belongs to it; what
   several entry points share is a component of its own.
3. **Packages are evidence only**: they may name a component or break a tie, never decide one; where
   the package layout and the linkage disagree, the linkage wins.

Free clustering of the call graph is not enough: a shared helper reachable from everywhere glues
unrelated parts together, and a small commit can regroup everything, against the stability the
design requires.

## Sequence diagrams show the flows that matter, however many there are

C4 treats its dynamic diagram and a UML sequence diagram as two renderings of the same information,
and recommends using them "sparingly to show interesting/recurring patterns or features that have a
complicated set of interactions" ([c4model.com dynamic](https://c4model.com/diagrams/dynamic)).
arc42's runtime view says the same: pick a "representative selection" by architectural relevance
and prefer schematic scenarios to detailed ones. It also widens the candidates to external
interfaces, start-up and shutdown, and error handling ([arc42 section 6](https://docs.arc42.org/section-6/)).
Neither source gives a number, and none is set here: a small system may have two meaningful flows
and a large one twenty. A flow is shown because it is architecturally meaningful for that system
(it crosses services, hands off asynchronously, handles failure, or is how people enter the system),
never to reach a count, and a flow of the same shape as one already shown is not shown again.
Choosing them is a judgement, which the LLM may make among the flows tracing found.

The research on generated sequence diagrams agrees on one failure: scale. Diagrams that show every
call "rapidly become hard to navigate", and readers end up scrolling in two dimensions. Every
workable approach collapses internals into their parent, filters out utilities, or caps depth
([Cornelissen et al. 2008](https://cms.simula.no/sites/default/files/publications/extravis-journal.pdf);
[Hamou-Lhadj](https://www.eecs.uottawa.ca/~tcl/gradtheses/ahamou/AbdelwahabHamouLhadjPhDThesis.pdf)).
So a flow starts at an entry point, collapses everything inside one container into a single
lifeline, shows a message only where a call crosses a network, broker or database boundary, and is
stitched across services: an HTTP call continues into the callee's endpoint, a publish into the
listener bound to that exchange and key. A single-repo tool like the IntelliJ SequencePlugin cannot
do that stitching ([SequencePlugin](https://github.com/Vanco/SequencePlugin)).

Labels lead with intent and put the technology second, as in Structurizr's scenario-specific
"Requests past orders from" ([Structurizr dynamic view](https://docs.structurizr.com/dsl/cookbook/dynamic-view/)),
for example "Create order [POST /orders]" or "Announce order created
[orders.events / order.created]". Drawing conventions follow UML. A synchronous call has a filled
arrowhead, an async message an open one, and a reply is dashed; replies are drawn only when they
carry meaning ([Agile Modeling](https://agilemodeling.com/style/sequencediagram.htm)). Each queue or
exchange gets its own lifeline rather than one broker lifeline. A note such as "later,
asynchronously" follows an async handoff, because neither Mermaid nor PlantUML can show time gaps
([Mermaid](https://mermaid.js.org/syntax/sequenceDiagram.html)). `alt` or `opt` fragments are used
only for architecturally meaningful branches, such as failure plus compensation. Each diagram has a
title naming the scenario and its trigger, and two to four sentences of prose.

## Words say what the diagram cannot, and nothing false

"Write documentation from the reader's point of view"
([Views and Beyond, Rule 1](https://www.informit.com/articles/article.aspx?p=1641654&seqNum=5)), and
"describe what you can't get from the code"
([Brown guidebook](https://leanpub.com/documenting-software-architecture/c/free)). A sentence such as
"`OrderService` is a service for orders" scores zero. A useful one says what the part decides, owns
or protects. The Views and Beyond "view packet" pairs every diagram with an element catalog and
context, so every box has a written answer to "what does it do?".

LLM-generated docs add their own failure mode: confident errors. An OpenWrt maintainer reported
DeepWiki producing "misleading architecture and dependency information" and wrong config
descriptions, presented as authoritative
([OpenWrt forum](https://forum.openwrt.org/t/important-consideration-deepwiki-ai-generated-docs-may-contain-inaccuracies/249471)).
Evidence on quality is thin. The best benchmark, CodeWikiBench, scores against rubrics derived from
official docs using LLM judges, with about 71 to 74% rubric reliability and only a three-person
human pilot ([CodeWiki, arXiv 2510.24428](https://arxiv.org/html/2510.24428v6)). The defence is
structural: ground every claim in an extracted fact, link it to source, and pin the document to its
commits.

## What the code can and cannot tell

- **Elements and lines** come from code and deployment facts. The LLM may propose ones extraction
  missed, but only with file:line citations Lantern verifies; they are drawn marked as found by the
  LLM. It never adds what it cannot cite.
- **People** are drawn only from human-facing evidence: a frontend's screens, sign-in or sign-up,
  accounts, memberships, invitations. A GUI is for people; an API is for software. Roles the code
  checks say what those people may do; on their own they make no person.
- **Unknown callers.** An API is there to be called, so an entry point meant to be called (an HTTP
  or WebSocket endpoint, or a queue or topic consumed but never published to; not a scheduled job)
  that nothing in the analysed set reaches and no person is shown to use is drawn as called by an
  "Unknown caller", marked as unknown. An endpoint behind a gateway is reached through it. It is neither left out nor replaced by an invented actor.
- **Business purpose** is not required for now. Where the report shows one, it is marked as
  evidenced (people wrote it) or inferred.
- **Owners, rationale, quality goals and production topology** beyond deployment files are not in
  the code. They are stated once, plainly, as unknown.

What the code cannot show will come from people: Lantern users adding context to the system
(`docs/design.md`, Annotations), which replaces an unknown caller with the real one and supplies
purpose, owners and rationale.

## Static extraction can recover the runtime graph

An independent 2024 evaluation ran 9 static architecture-recovery tools on 17 Java/Spring systems.
**Only Code2DFD recovered both components and connections well (F1 0.86; connections 0.87)**, and
most tools found almost no connections. Parsing deployment files gave high precision but low recall;
recall needed deeper source analysis ([arXiv 2412.08352](https://arxiv.org/html/2412.08352v1)).
Code2DFD's approach is many technology-specific extractors over code and config, with every model
item traced back to a snippet ([arXiv 2304.12769](https://arxiv.org/abs/2304.12769)). Context Mapper
detects connections only through `@FeignClient`, which explains its near-zero recall. Springwolf
documents `@RabbitListener` consumers but cannot detect producers, because "there is no
implementation uniform enough"
([Springwolf](https://www.springwolf.dev/docs/configuration/documenting-producers)).

Spring systems hide their runtime graph in recurring ways, and recovering it takes joins across
Java, config and repositories: a service's identity in `spring.application.name`, sometimes in
`bootstrap.yml`; stereotypes behind custom meta-annotations; queue, exchange and routing-key names in
YAML, reaching the send through a `@Value` constructor argument; Feign interfaces defined in the
callee's client jar but called by whoever enables or injects them; AMQP topic wildcards; edges that
exist only under a Spring profile; and config served from a central config server, so the
repository's config is a default rather than production truth. Eureka, tracing and the config
server are infrastructure, not business dependencies.

## Rubric

Each check is yes or no. These are the checks the report is held to; which ones Lantern makes
mechanically on every run and which are judged is in the design.

**Every diagram:** a title naming its type and scope. A legend for every shape, colour, line style
and arrowhead used. One abstraction level. Readable (Diagrams stay readable), else split at the same
level or detail folded away. Every element has a name, a type and a responsibility sentence that does
not restate the name; containers and components have a technology, a container's naming its
frameworks and their versions, and an exchange its type. Every line is one-way, labelled with one
intent phrase other than "uses" or "depends on", linked to the facts behind it, and marked
conditional, naming the profile, when it holds only under a Spring profile; lines between containers
name a protocol (`[JSON/HTTPS]`, `[AMQP]`, `[JDBC]`). Acronyms are explained in the glossary. What the
LLM found is marked as such.

| Diagram | Passes only if |
| --- | --- |
| System Context | Exactly one system in focus. People only from human-facing evidence, and external systems, directly connected. An "Unknown caller" where entry points meant to be called are reached by nothing and used by no person (What the code can and cannot tell). No technology. Unknown actors are marked unknown, never invented. |
| Container | Every box is a deployable app, frontend, job or data store, with `[technology]` and a job. No library modules, no packages. Each service's schema is its own data store. Queues and topics are their own elements or "via" labels, never one broker hub. Every edge is a runtime call with a protocol. Profile-gated edges are marked conditional. Containers with entry points meant to be called that nothing reaches have a line from the "Unknown caller". |
| Component | Scope is one container. Components are anchored on entry points, with the code only they reach; code several entry points share is its own component. Each carries a technology (e.g. "Spring MVC controller") and a job. Domain, DTO, mapper, configuration and util classes are left out. Edges leaving the container carry a protocol. Packages never decide a component on their own. Drawn only when it adds value: more than one component, and not a repeat of the container diagram. |
| Code | Not drawn in the C4 report for now. |
| Landscape | All analysed systems and their people, with no internals. Edges are aggregated from container-level facts. |
| Dynamic / sequence | Names one scenario and its trigger. Participants are all from one C4 level and reuse the static diagrams' names. Only boundary-crossing messages appear. Labels put intent first and protocol second. Sync and async arrows are distinct, with a lifeline per queue or exchange. Returns appear only when meaningful. At most one level of fragments. Two to four sentences of prose. The set holds every architecturally meaningful flow and no others, however many that is, and no two of the same shape. |
| Deployment | Drawn only from real descriptors (Dockerfile, compose, Helm, manifests), one environment per diagram. Otherwise omitted with a note. |

| Document | Passes only if |
| --- | --- |
| C4 report | States its reader and pins its commits and date. Its first paragraph says what the system is (what runs and its main parts) and the single most important fact about it. Every specific claim links to a fact or source. README claims agree with the code. Unknowns are stated once, plainly. No sentence that would be true of any Spring application. One name per concept across text, diagrams and glossary. No diagram repeated across sections. C4 pages hold only C4 content; findings (risks, decisions, unknowns), the glossary, the catalog, the deployment table and the sources are documentation pages of their own. |

## Test material

The bar holds only if it is met across a range of systems that exercises everything Lantern does:
messaging styles, package-by-feature and package-by-layer code, thin services packaging a library,
gateways, frontends, deployment repositories and a monolith. No single system shapes the bar, and the
range is kept adequate as Lantern grows (`docs/design.md`, Test sets).
