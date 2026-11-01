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
- **A domain's scope is `system`.** The epic keys domains `group:<system>/domain/...`; a derive rule
  runs on one system's snapshot and does not know its name, so the scope is the literal `system`.
  Facts of different systems never share a repository, so their groups' observations stay apart.
- **The resource segment of an endpoint with none left** (its path is the prefix all share, or
  holds only variables after it) is the last literal segment of the prefix: `/api/work-orders/{id}`
  in a unit whose paths all start `/api/work-orders` is a `work-orders` resource. The epic gave no
  rule for it, and dropping such endpoints would leave a unit's main controller ungrouped.
- **Rule 3 places each component once, against the areas rules 1 and 2 formed**, so the order
  components are looked at does not matter. Calls count the `COMPONENT_LINK` facts in either
  direction; tables are what each component's `data-access` fact rests on (tables, or the entity
  classes a Spring Data repository is for).
- **A unit using several relational databases** joins the one whose datasource lists the most of
  its schemas, ties by key: the linker records which schemas a unit's migrations build in a
  datasource, not which database each table it touches is in. Relational means reached over JDBC
  or R2DBC.
- **A gateway-route domain** is keyed on the first literal segment of the request as the gateway
  receives it (`routed-endpoint`); a unit routed under several joins the segment most of its routed
  endpoints are under.
- **Repository domains** count the repository of a unit's own modules, and only when they all come
  from one; **Maven-group domains** take the segment after the longest dotted prefix of every
  remaining unit's groupId, so units whose groupId is that prefix stay ungrouped.
- **Lines between groups are computed, not stored** (#615): `Groups.lines` merges member lines per
  ordered pair of ends (a group, or an element no group of the kind holds) and protocol, citing
  the member lines' facts, and leaves out lines inside one group.

## Consequences

- Views can be drawn at group level from stored groups (#530), and the groups named (#536),
  without deciding membership again.
- A unit whose code is laid out neither by feature nor with distinct resource paths, or a system
  whose units share no signal, has no groups of that kind; clustering by coupling (#537) is left
  for what the signals leave ungrouped, if the test sets show it is needed.
