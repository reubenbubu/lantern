# 0027: Summary citations are markers, not names in the text

Status: accepted (#70)

## Context

A summary cites the summaries of its parts with `[summary:<entity>]` markers (0010), and 0011 made
each one a cross-reference whose text is the entity's name. With real model output these markers
sit at the end of sentences, so the Executive Brief read "…on the compile scope ameba-lib." and
"…the library ameba-lib ameba-lib. It bundles…", and the Explorer showed the names as links in
mid-sentence. Templates could not tell these references from the ones that are words of the text
(the risks' and the overview list's names). Separately, a citation of many facts wrapped inside its
button in the Explorer's details panel and stood as a centred block on its own line.

## Decisions

- **A cross-reference can be marked as a citation**: an optional boolean `citation` on `ref`,
  written only when true (`CrossReference.citation()` in Java). It says the target is a source of
  the text before it. The composer marks the references it makes from `[summary:...]` markers and
  attaches them to the text before them with no space, as it does citations of facts; no other
  reference is marked. The schema version stays 1: the field is optional and absent means what it
  meant before. A new inline kind was not chosen, because a summary citation still points to an
  entity, which a fact citation cannot, and every template already knows how to follow a reference.
- **Templates show a marked reference as a superscript `[↗]`** after the text, styled like a
  fact citation. Its tooltip and accessible name are "Summary of <name>", so the name never appears
  in the reading flow. In the Explorer it selects the entity. On plain pages and in the Brief it
  links to the section about the entity, and is left out when there is none (in the Brief there
  usually is none), since a marker that leads nowhere would only be noise. A summary that opens
  with markers has its text start without a space. Search leaves marked references out of an
  entity's text, as it does citations.
- **A citation never wraps** (`white-space: nowrap`), and one of more than four facts shows the
  first three numbers and counts the rest (`[4, 9, 12 +11]`); its tooltip, and in the Explorer the
  details panel, still list every fact.
