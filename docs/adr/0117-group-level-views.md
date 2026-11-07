# 0117. Container and component views at group level, readable before they are split

Status: accepted (#699)

## Context

The knowledge-build epic (section 12, the Container and Component rows of the view table) asks
for one container overview first, drawn with domains in place of their units when it is not
readable, and for a large unit's component view drawn as an overview of its feature areas plus a
page per area. Before this, any view above 15 elements was split by concern and then per unit.
On project-8, eleven units became seventeen container pages. The owner rejected 15 as arbitrary
and asked for research. `docs/research/readable-large-diagrams.md` found:
- No study gives an element cap for such diagrams.
- Readers first struggle around 50 nodes with dense lines (2 to 4 per node), later with sparse ones.
- Crossings matter more than size.
- C4 and comparable tools keep one overview by folding detail, not by splitting it.

Measuring crossings needs the layout, which only the report's browser code runs today. That work
is #366's.

## Decisions

- **Readable, before layout.** A container or component view is readable when it draws at most
  50 elements (boundaries not counted) and at most 2 lines per element. 50 is the least size at
  which the studies saw readers struggle with dense lines, and 2 per element is the top of what
  they call sparse. Both values are provisional: #366 replaces them with measurements after
  layout, crossings included.
  - The quality check "Readable: at most 50 elements and 2 lines per element" and the scorer's
    diagram-size check use the same values.
  - A sequence diagram keeps its own limit of 15 participants.
- **Container views, in a fixed order.** Stop at the first step that gives a readable view.
  1. The whole system, with lines merged per ordered pair and protocol.
  2. Detail folded away: a topic or queue is drawn as "via" on the line from its one sender to
     its one receiver. This applies when exactly one unit sends to it, exactly one other unit
     receives from it, and it has no other lines. A topic with more senders or receivers is a
     coupling point and stays an element. (The research note's rule, senders × receivers ≤
     senders + receivers, would also fold one sender to many receivers, which is fan-out.)
     - A note under the overview names what was folded.
     - A messaging view one page away draws every topic and queue as an element, split per unit
       when that view is not readable either.
  3. Domains (#535, named by Name groups) in place of their units, when the scope has domains
     with at least two of its units and that overview is readable. Lines are lifted to the domain, and a line whose ends become
     one is dropped. Each domain then gets a page of its units and their neighbours, split by
     concern when that page is not readable.
  4. Split by concern, then per unit, as before.
- **Data stores are not folded into their unit.** The research suggests drawing a store used by
  one unit inside it, but the bar's Container row says each service's schema is its own data
  store, so stores stay elements.
- **Component views.** A unit's component view that is not readable is drawn with its feature
  areas in place of their components, then one page per area with the area's components and
  their neighbours, lifted to their areas.
  - An area page that is still not readable is drawn whole and fails the readability check.
    Splitting it by groups of entry points is left to #366's measured thresholds.
- **Group boxes.** A domain or feature area is named by the display name Name groups stored,
  else by its signal. Its technology line counts its members ("3 containers"), it is tagged
  "Domain" or "Feature area", and it is described by its stored responsibility. A line leaving
  a group is labelled with the intent Name groups stored for it, else as other lines are.

## Consequences

- On the development test sets, every whole-system overview is readable at 50 elements, so neither
  the fold nor the domain view runs there yet; only the feature-area step does. The fold and the
  domain view are covered by tests.

- project-8's eleven units fit one container overview of 38 elements and 54 lines, and
  workorder-service's 56 components become an overview of its feature areas plus a page per
  area.
- Until #366 measures after layout, a 50-element overview may still need zooming on a small
  screen. The thresholds are named constants, `C4Views.MAX_ELEMENTS` and
  `MAX_LINES_PER_ELEMENT`, so #366 changes them in one place.

## Amendment (#737): the limits are a warning, not a cap

On 3 Nov 2026, after the 1G review, the owner decided that no hard element limit decides
grouping. 50 elements and 2 lines per element are rejected as a cap. Views are to be grouped by
meaning, at a level chosen among groupings the code proves, with size only a warning and
readability measured after layout (#764, with #366).

- Until #764 lands, the two values stay where they are, as the quality check "Size: at most 50
  elements and 2 lines per element" (ADR 0123) and the
  scorer's diagram-size check: a warning on the report, never a reason to drop or hide content.
  The fold, domain and feature-area steps above keep running as they do, since #764 replaces them.
- No new work uses `C4Views.MAX_ELEMENTS` or `MAX_LINES_PER_ELEMENT` to decide how a view is
  grouped or split.

## Amendment (#764): the level is chosen by meaning

The research for #764 (`docs/research/grouping-level-by-meaning.md`, high depth) sets the rule
that replaces the fixed thresholds: derivation proposes only levels the code proves, one stored
judgement picks a level per view in a fixed order of criteria, box count is a soft target with a
warning, and the after-layout measures of #366 have the final word (`docs/design.md`, Readable
diagrams). The fold, domain and feature-area steps above stay until the view code of #764 lands.
The owner approved the rule and the soft targets on 4 Nov 2026 (#764); the soft limit's value is
measured by #836 (amendment for #836).

## Amendment (#836): one setting holds the limits, and its value is measured

On 4 Nov 2026 the owner decided that fit is not decided per view during the run (#764's question
(f), superseded). Instead the element limit is found by measuring the report template's own layout
on many synthetic views shaped like Lantern's, and it is soft and configurable: a view a little over
the target is fine, and above target plus tolerance what can be merged is merged.

- `C4Views.MAX_ELEMENTS` and `MAX_LINES_PER_ELEMENT` are gone. `DiagramLimits` holds the element
  target, its tolerance and the most lines per element, with the scale and crossings of ADR 0123;
  the app binds it from `lantern.diagrams`, each value left unset keeping `DiagramLimits.DEFAULT`.
  The composer folds and splits by it, and its quality report's size check is worded from it.
- Until the owner approves the measured value on #836, the defaults keep the old behaviour: a
  target of 50, no tolerance, 2 lines per element. Changing them is a configuration change; #764
  merges by the limit once approved.
- The measurement and the recommended value are in `docs/research/diagram-element-limit.md`;
  `lantern-web/scripts/diagram-limit.mjs` reruns it with one command and gives the same numbers.

## Amendment (#764): the limit picks the level; then merge, fold, split

On 5 Nov 2026 the owner set how the level is chosen, superseding the judgement and soft targets of
the amendment for #764 above: the soft targets are dropped, and the measured limit of #836
(`lantern.diagrams`, target 8 and tolerance 2, approved on #836) is the only size number.

- Levels: none, feature areas (component views), domains (container views). A group needs at
  least `lantern.diagrams.smallest-group` members of the view (2 by default) to be a candidate.
- A view that fits the limit shows every member; each box's tag names its group ("Service ·
  Orders"). No frame is drawn, since the template draws one boundary per diagram (#897).
- Over the limit, each group becomes one box with a page of its own. While the view is still over
  it: lines between two boxes merge into one (labels and technologies kept, a profile only if every
  merged line had one), then topics become "via" labels, then the view is split: an overview plus a
  page per group, or by concern where there are no groups.
- Every threshold is a default in `lantern-compose/src/main/resources/io/lantern/compose/diagram-limits.properties`,
  read by `DiagramLimits.DEFAULT`, with no copy in code; the app's `lantern.diagrams` settings
  override each one.

## Amendment (#764): what no line reaches, and the outside left off

On 6 Nov 2026 the owner settled what a view does when its groups still do not fit:

- **No pockets.** Grouping by coupling what no code signal groups was built and dropped on the
  same day: a pocket was named after its central member, so adding one unit could rename it, which
  ADR 0099 forbids. What the signals leave ungrouped is left to a late LLM step that groups it by
  meaning (#919; `docs/research/grouping-services-into-pockets.md` keeps the research). The limits'
  defaults moved to `lantern-derive/src/main/resources/io/lantern/derive/diagram-limits.properties`,
  read by the composer through `DiagramLimits.DEFAULT`. A container may appear on more than one
  page.
- **What no line reaches** is never drawn with the rest: a container with no line, or a component
  that is not an entry point and has no line, is drawn only on a page of its own, always ("like a
  family picture where the neighbour photobombs it").
- **The outside is left off** a map of domains (or an area overview) still over the limit, with a
  note; the context view and each group's page draw it.
- **Split pages name their group**: a domain's page split by concern is titled with the domain,
  and an area's page still over the limit (after merging its lines) is split by groups of entry
  points, each with what it reaches in the area, packed in order onto pages that fit. A unit with
  no areas still over the limit is split the same way.
- **One step down per page** (6 Nov): an entry point whose own page does not fit shows what it
  calls directly, then each of those that calls further gets a page the same way. A page of entry
  points draws none of the other components being split that call what it shares. The rule reads
  only who calls whom, never what kind of class a component is (the owner rejected a box for
  repository classes as fitted to one test set).
- **What only the outside reaches** (6 Nov): on a component overview that leaves the outside off,
  a component or area left with no line is taken off it; components go on a page "Connected to no
  feature area", split by entry point when too large, and an area keeps its own page.
- **What no group holds keeps its outside** (6 Nov review): a unit in no domain on a map that
  leaves the outside off is drawn again on a page "In no domain" with what it reaches outside, and a
  component in no area on an overview that does the same on a page "In no feature area"; both stay
  on the overview, where their other lines are. Components no entry point reaches have pages titled
  "called by no entry point". A unit with no area still over the limit is split by entry point with
  its outside drawn. A view still over the limit after all of this is drawn as it is, with the
  scorer's warning; there is no further step.
- **What cuts across the areas** (6 Nov): aspects and bundled library classes are left off that
  overview with a note naming them; each area's page draws them.
- **An area of one** is judged after rule 3 has grown it; one still of one is dropped and its
  member placed again (found on project-8, where single-controller feature folders left their
  controllers floating).
- **Found while doing this:** project-8's workorder-service fails its build by design, and calls
  to methods inherited from Spring Data are then lost, so two of its parts lose their lines (#908).
- Describing a domain is left to an LLM step the owner will add.
- **Evidence runs on Haiku 5.5 only** (owner, 6 Nov, for cost): the views' grouping is decided by
  code; the model only names groups and words lines.

## Amendment (#978): every line is in one concern

A container view split by concern drew only the lines whose relationship one of its concerns
names (requests, data, messaging). A line of any other kind, such as what Investigate found
(`INVESTIGATED_CONNECTION`) or an interaction of a type the vocabulary adds, was on no split view,
so the store or system it reaches was drawn nowhere while the system context counted it (project-8:
"Databases 9" with 7 drawn, "External systems 10" with 6). Now such a line is in the concern of
what it reaches: data for a data store, messaging for a topic, queue or exchange, requests for
anything else, as component views already put it in requests. Each count of the system context is
then what the container views draw of its kind; a composer test and project-0's end-to-end test
check that.
