# 0123: Readable after layout: crossings and fit, measured where the diagram is drawn

Status: accepted (#366)

## Context

`docs/quality-bar.md` (Diagrams stay readable) says a diagram can be read without zooming and
every line can be followed from end to end. After layout, lines cross as little as possible, with
a warning above about 5 crossings, and the smallest text is readable at page width. Until now
Lantern checked only counts before layout (elements and lines per element, `QualityBar`). Those
cannot see crossings or fit, which only exist once the diagram is drawn, and the C4 report's
template draws it in the browser. #764 needs an after-layout check to decide when a view moves
to a coarser level (`docs/design.md`, Readable diagrams, "Layout has the final word").

## Decision

1. **Measured where it is drawn.** The template measures each container and component diagram
   as it lays it out: the line crossings of the drawn routes, and the scale at which the whole
   diagram fits the maximized canvas. It writes them into the diagram's section as
   `readability` (`crossings`, `fits`, `width`, `height`). Sequence and other diagrams are not
   measured: the bar's rule is about C4 views.
2. **Thresholds in one place** (`READABLE` in the template), from the bar and the five sets'
   measurements (`docs/design.md`, Readable after layout):
   - at most 5 crossings, the bar's value; on the five sets, it falls in the gap between the
     views with 1 to 5 crossings and those with 7 or more;
   - fit: a scale of at least 0.8 on a 1632 × 1024 canvas, the maximized diagram on a
     1920 × 1080 screen, so an 11.5 px line label stays at least 9 px.
3. **Reported on the page.** A view that fails says "Hard to read" and why under it, and its
   page's quality report lists it under "Readable after layout", linking to it. The report
   section the composer writes is marked `quality: true` so the template knows which section to
   add to. A page whose report said every check passes shows the gaps as a list instead.
4. **A warning, not a split.** This issue measures and reports. Moving a view to a coarser level
   when it does not fit is #764's (decision 1 of #737).
5. **The scorer stays pre-layout.** `score.mjs` still scores `diagram-size` from counts. Laying
   out every view in Node took 14 minutes for project-2 and did not finish project-8 in 40, while
   the browser lays out a view in milliseconds.

## Consequences

- The quality report now shows a gap the composer could not see. On the fake runs, 19 of the
  five sets' 44 views are flagged: 5 on project-2, 6 on project-4 and 8 on project-8. Each of
  them fails on fit, and 9 also on crossings.
- `QualityBar`'s element count is now worded as a size warning ("Size: at most N elements and M
  lines per element"), as it is until #764 lands.
- #764 can read `readability` to decide when a view moves.
