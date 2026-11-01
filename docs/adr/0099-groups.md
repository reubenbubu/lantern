# 0099: Groups from explicit signals

Status: accepted (#535)

## Context

A large system or unit drawn element by element is not readable (`docs/quality-bar.md`, Diagrams
stay readable). The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 9, Grouping)
fixes how related elements are grouped: feature areas of a unit's components and domains of the
system's units, each from explicit signals in the code, never from an LLM, with keys from the
signal. This records how the derive rule implements it and what the epic left open. The agent
decided these under the owner's standing go-ahead for 1D.

## Decisions

- **One rule, `Groups`, in `lantern-derive`.** It reads the components and their interactions
  (#523, #617) and the linked facts, and writes `group-of` and `GROUPS` facts. `GROUPS` is a kind
  of `CONTAINS` with a type of its own, as `HAS_MEMBER` is, so derivation does not write what the
  extractors write and the rules and documents reading `CONTAINS` do not see groups as code
  structure.
- **Keys as the epic gives them**: `group:<unit>/area/<signal>` and `group:system/domain/<signal>`,
  the signal percent-encoded where it holds whitespace or `/`; the `group-of` fact names the kind
  (`feature-area` or `domain`).
- **A domain's scope is `system`.** The epic keys domains `group:<system>/domain/...`; a derive rule
  runs on one system's snapshot and does not know its name, so the scope is the literal `system`.
  Facts of different systems never share a repository, so their groups' observations stay apart.
- **The resource segment of an endpoint with none left** (its path is the prefix all share, or
  holds only variables after it) is the last literal segment of the prefix: `/api/work-orders/{id}`
  in a unit whose paths all start `/api/work-orders` is a `work-orders` resource. The epic gave no
  rule for it, and dropping such endpoints would leave a unit's main controller ungrouped.
- **A package named as a layer is not a feature, and does not cancel the others.** Rule 1 counts
  the sibling packages that hold an entry point and are not named as a layer; with at least 3, each
  is an area, and the components of layer packages (a `messaging` listener beside `invoice` and
  `payment`) are left to rule 3, which the epic expects to place "one in a layer package".
- **The base package is where the entry points are**: the common prefix of the entry points'
  packages, so a shared bean or client in another package tree (a library's) does not move it.
- **Rule 3 runs in rounds**, each against the placements of the rounds before, until a round places
  none: a component linked only to one placed in an earlier round (an outbox poller to its
  listener) joins too, and within a round the order components are looked at does not matter.
  Calls count the `COMPONENT_LINK` facts between the unit's components in either direction; tables
  are what each component's `data-access` fact rests on (tables, or the entity classes a Spring Data
  repository is for), and a component joined by tables cites that fact.
- **An endpoint seen in several views counts once** in rule 2's majority.
- **Only the default view's datasources count** for signal 1: a profile's (local, test) datasource
  is not where the unit keeps its data.
- **A unit using several relational databases** joins the one whose datasource lists the most of
  its schemas, ties by key: the linker records which schemas a unit's migrations build in a
  datasource, not which database each table it touches is in. Relational means reached over JDBC
  or R2DBC.
- **A gateway-route domain** is keyed on the first literal segment of the request as the gateway
  receives it (`routed-endpoint`); a unit routed under several joins the segment most of its routed
  endpoints are under.
- **Repository domains** count the repository of a unit's own modules, and only when they all come
  from one. **Maven-group domains** take the segment after the longest dotted prefix of every
  unit's groupId in the system, as the epic says, for the units the stronger signals left; units
  whose groupId is that prefix stay ungrouped. When the prefix is empty the groupIds share no
  organization and the next segment is a top-level domain (`com`, `org`), which names no domain, so
  there is no Maven-group domain.
- **Lines between groups are computed, not stored** (#615): `Groups.lines` merges member lines per
  ordered pair of ends (a group, or an element no group of the kind holds) and protocol, citing
  the member lines' facts, and leaves out lines inside one group.

## Consequences

- Views can be drawn at group level from stored groups (#530), and the groups named (#536),
  without deciding membership again.
- A unit whose code is laid out neither by feature nor with distinct resource paths, or a system
  whose units share no signal, has no groups of that kind; clustering by coupling (#537) is left
  for what the signals leave ungrouped, if the test sets show it is needed.

## Amendment for #638: cross-cutting components join no area

Rule 3 joined shared aspects and shared-library classes (project-8's `PermissionAspect`, project-4's
`PermissionControlAspect` and the shared library's `MessagingService`) to whichever area happened to
call them most. They serve every area, so putting them in one misleads. Rule 3 now skips a
component whose role is aspect, or whose class is not in one of the unit's own modules (it comes
from a library the unit bundles). They stay ungrouped and are drawn as themselves at area level.
A shared bean of the unit's own code still joins the area that calls it most, as rule 3 says.
