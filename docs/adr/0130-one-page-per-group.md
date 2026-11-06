# 0130: One page per domain and feature area, nested in a sidebar tree

Status: accepted (#931)

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
