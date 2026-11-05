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
3. **Reported on the page.** A view that fails says "Hard to read" and why under it, and a
   quality report lists it under "Readable after layout", linking to it:
   - the page's own quality report, which the composer marks `quality: true` so the template
     knows which section to add to;
   - a quality report the template adds at the end of a page that has none;
   - the report that says every check passed, which speaks for the whole document: before
     showing it, the template measures every container and component view, and lists the gaps
     of every page instead when there are any.
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
- `readability` exists only in the browser's copy of the document; the composer never sees it.
  For #764 to use it as its gate, the measure has to run where the composer can read it: the
  template exposes it as `window.lanternReadability()`, which a headless browser can call, as
  the measurements in `docs/design.md` were taken. How #764 gets it is #764's decision.

## Amendment (#836): the thresholds are the report's own

The template's `READABLE` no longer holds values of its own. Each C4 report carries the limits it was composed
with as its document's `readable` (target, tolerance, lines per element, scale, crossings), which
the renderer writes from the setting `lantern.diagrams` (ADR 0117, amendment for #836). The
template judges fit and crossings by them, and the scorer its size check, so a report is judged by
the limits it was made with, and changing the setting changes both verdicts. A report without
`readable`, such as one written before this change, has no view judged after layout and no size
check in its scorecard. Fit is judged by width alone, as the owner decided on 4 Nov 2026 (#764),
replacing decision 1's whole-canvas fit: a view taller than the canvas scrolls and still fits. The canvas, 1632 × 1024 px, stays in the template: it is the page, not a
limit.

Laying out a view takes longer than this ADR assumed: the measurement for #836 found about 5 s at
20 elements, 27 s at 30 and 2 minutes at 40 in a headless browser, rising about with the fourth
power of the element count, once per view and then cached in the reader's browser.
