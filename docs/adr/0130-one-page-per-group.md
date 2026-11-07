# 0130: One page per domain and feature area, nested in a sidebar tree

Status: accepted (#931), amended for #953 (badges, counts, units in no domain), amended for #957 (a group frames its own page)

## Context

The owner reviewed project-8's C4 report on 6 Nov 2026 (milestone 1I) and failed its navigation.
A domain or area too large for one view was split into several pages with long generated titles,
so the Containers menu held 79 pages; its first item was itself called "Containers"; the menu was
flat, so nothing said which unit belongs to which domain; clicking a domain box opened no diagram
of the domain; and on project-3, subscription-service's page did not show the gateway that calls
it, because a gateway's route names the unit it reaches but not the method.

The design already gave a page per group (ADR 0117, amendment for #764); this ADR fixes how those
pages are laid out and reached.

## Decision

1. **One page per domain and per feature area.** The parts a view is split into (by concern, by
   entry point) are views on the group's page, shown as its tabs, never pages of their own. A
   unit's leftover views (components in no area, outside-only, unconnected) are views on the
   unit's page.
2. **The containers overview is titled "Overview"** when domain pages stand beside it.
3. **A sidebar tree.** The document entity carries the group it belongs to (`entity.group`, an
   optional property of the document schema). The C4 report renderer gives each page a `parent`
   (an optional property of the report spec's page): a unit's page names its domain's page, an
   area's page its unit's page. The template nests pages under their parents, opens the branch of
   the page shown and lists its ancestors in the breadcrumbs. The spec check rejects a parent that
   is not a page or that makes a page its own ancestor.
4. **Every box leads somewhere.** A click on a box opens the page about its element (a domain's,
   an area's, a unit's), before any page whose diagram has the element as its boundary; the box's
   list of deeper pages leaves out the page the click opens. The quality report gains the check
   "Every domain or feature area drawn has a page of its own".
5. **A gateway's callers reach the component.** When a call into a unit has no code end, the
   component view reads the gateway's routed endpoints for that unit
   (`ArchitectureModel.routedTypes`) and draws the gateway's line to each component whose endpoint
   a route reaches.

## Consequences

- project-8 went from 79 pages to 51; its Containers menu lists Overview, three domains and the
  unconnected containers, with units and areas beneath.
- A split domain or area shows its parts as tabs on one page, so a reader no longer sees pages
  titled with entry-point lists in the menu.
- Document models without `group` and report data without `parent` still render: both are
  optional, and a page with no parent sits at the top of its level as before.

## Amendment (#953): badges, counts and units in no domain

From the owner's review of 7 Nov 2026:
- Each sidebar item carries a short badge for what its page is about, as L1/L2 do for levels: DO for a domain, CO for a container, FA for a feature area and P for a component (the owner's letters). A components page nested under another without an element of its own is a feature area's.
- Every item with pages beneath shows their count, as the level headings do.
- Each level indents less, and the sidebar is a little wider (320 px).
- A unit in no domain (project-8's edge gateway) had its page alone under a separate Components heading. Its page now sits under the "In no domain" page, or the "With no connections found" page, that draws it. A heading left with no pages goes. The renderer finds those pages by their titles, which the composer keeps in one public place (`PageTitles`).

## Amendment (#957): a group frames its own page

From the owner's review of 7 Nov 2026: opening the Repair work domain showed a page framed by "project-8 [Software System]", Repair work's containers inside beside those of other domains, so nothing said which boxes were the domain's. A feature area's page had the same fault one level down, framed by its container.

- A domain's or feature area's page draws the group as its dashed frame, captioned with its name and "[Domain]" or "[Feature area]", and the legend calls it a domain or feature area boundary.
- Inside are the group's members and each data store, topic, queue or exchange that only the group uses: every line to it, on every view of the report, is from the group or one of its members. A store shared with other groups, or with other areas of the same container, stays outside.
- Boxes of other groups are drawn outside the frame, joined by their lines. There is one frame: the page's title and breadcrumb already name the system and the container.
- An element has one parent, the system or the container, so the renderer lists what each frame holds (`boundaryContains` in the report spec) instead of drawing the elements whose parent the frame is.
