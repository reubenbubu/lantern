# 0026: A quality bar, and diagrams of runtime units instead of build units

Status: accepted (#86); the direction is the user's, the details are pending the user's review (#24)

## Context

The user judged the phase 1 diagrams useless: boxes were Maven modules and Java packages, every
line was a build-time "depends on", and nothing said which part is responsible for what. The
cause was the input, not the wording. Phase 1 had only Maven and Java structure facts, so the
composer (ADR 0012) drew what it had. Research into what makes good C4 diagrams, sequence
diagrams and architecture documents (`docs/research/architecture-documentation-quality-bar.md`)
produced a rubric. The user asked for the design to change so Lantern can reach it, keeping the
existing architecture (extractors, fact store with envelope, vocabulary and a C4 level per type,
cross-repo linker, composer, renderer), and for the design to stop being shaped by the one test
set it had been checked on.

## Decisions

- **Adopt the rubric as the design's Quality bar.** `docs/design.md` (Quality bar) condenses it
  into yes-or-no checks for every diagram and for each document, and links to the research for
  the evidence instead of copying it. The composer runs the checks it can make from the document
  model on every document. `docs/research/` becomes a source of truth for research and evidence
  (`CLAUDE.md`, One source of truth).
- **C4 levels as c4model.com defines them.** A container is a runtime unit: an application with
  the in-set libraries it packages, or a data store, never a Maven module or jar. A component is a
  group of functionality behind an interface inside one container, found by role (controllers,
  listeners and scheduled jobs with the beans behind them, using Spring stereotypes and their
  meta-annotations), not by package. Queues and topics are elements or "via" labels, never one
  broker box. Build-time dependencies move to the dependency views. Code diagrams are not drawn.
  This replaces ADR 0012's C4 mapping; the definitions are in the design (Composition, Diagrams).
- **Sequence diagrams from stitched traces.** Flows start at entry points, follow the resolved
  call graph, keep only boundary-crossing steps (HTTP, messaging, database), collapse each
  container to one lifeline, and are stitched across containers by the linker. A few flows per
  scope are chosen by a deterministic ranking (containers touched, async handoff, failure
  handling), so the same flows come back on later runs.
- **The LLM words, it never invents.** It writes names, responsibilities, intent phrases and
  purpose from extracted facts. Every element and relationship comes from a fact, an annotation
  or a Resolve result among candidates the code shows. What code can't show is stated once as
  unknown.
- **Extraction gaps named in the design.** Meta-annotations, placeholder resolution across
  `application*` and `bootstrap*` config per profile, constant folding and one-hop `@Value`
  following, Feign client names against `spring.application.name`, the caller of a client being
  whoever enables or injects it, AMQP topic wildcards, and profile-gated links marked
  conditional. These were added to the existing extractor and linker tables, not as new stages.
- **Executive Brief additions.** Business purpose first, blast radius, technology currency and
  ranked risks. Change-history measures (activity, hotspots) need git history beyond the snapshot
  and are left for later; until then the Brief doesn't rank static findings as debt.
- **Phase reorder.** Phase 1 is built. System view is next and now also holds deployable units,
  the container, component and sequence views, the Quality bar checks and the Brief additions,
  because per-repo documents from Maven and Java facts alone can't meet the bar. Later phases
  are unchanged.
- **Test sets are generic.** The design says test sets are chosen together to cover different
  styles (messaging technology, package-by-feature and by-layer code, thin services, a monolith)
  and names none. The OpenWMS details that were in the design's Open questions moved to ADR 0004.
  Choosing more test sets is #88.

## Consequences

- Phase 1 per-repo documents keep working but do not pass the bar; follow-up issues build the
  extractors, linker and views phase 2 lists.
- Some open phase 1 issues about package-level component diagrams (ADR 0012's mapping) may no
  longer be worth doing once components are found by role.
