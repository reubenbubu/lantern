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
