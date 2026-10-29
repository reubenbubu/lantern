# 0055: Summary cache keyed on the prompt

Status: accepted (#190), amends ADR 0010, pending the user's review (#24)

## Context

ADR 0010 keys a summary on the sorted IDs of its unit's facts and on each contained unit's summary
key and text. Fact IDs hash the evidence, commit included (ADR 0001), so a new commit that changes
nothing in a unit still gave it a new key: a test showed all seven units of a small repository
summarized again when the same facts were observed at another commit. Facts were also listed in
the prompt in ID order, so the prompt itself was reshuffled by every commit. And a parent's key held
its children's keys, so a child summarized again with the same text still sent every ancestor back
to the model. The Resolve and wording caches (ADRs 0045, 0050) already key on what the model sees.

## Decisions

- **The key hashes what the model sees**: the prompt version, provider, model, the unit's level and
  entity ID, its parts' entity IDs, the system prompt and the unit's whole user turn, with its facts, source files and its
  parts' summaries as the prompt shows them (without fact IDs or citation markers). Level and
  entity IDs hold no commit. The prompt names a module or package without its full entity ID, so
  they keep units whose prompts read the same, such as two modules with the same artifact ID, under
  keys of their own, and a parent's stored citations of its parts' summaries, which name their
  entity IDs, always name the parts it is shown now. A
  unit too large for one prompt is keyed on its whole user turn; its parts and merges follow from
  it and the model's limits.
- **Facts are listed in a fixed order** that does not depend on their IDs: by the fact as the
  prompt writes it, then by where it was observed (repository, file, first line) and last by ID.
  `SummaryNode` holds its facts in this order. `Prompt.VERSION` is 6, so every cached summary is
  made again once.
- **A reused summary is rebound to the current run.** The stored text cites the fact IDs of the run
  that wrote it. The same key means the same prompt, which lists the same inputs in the same order,
  so on a cache hit each fact and contained summary the stored summary was made from is replaced
  by the one at its position now, in its text, citations and inputs. Facts whose lines read the
  same may trade places when their evidence moves; they say the same thing. The store keeps the row
  as written; `Summary.inputFacts` now lists facts in prompt order instead of sorted by ID.
- **Prose stability follows.** A child whose new summary reads the same leaves its parent's prompt,
  and so its key, unchanged, and nothing above it is asked again.

## Not verified

How often real models write the same text for a changed unit, which decides how far up a change
travels, needs real calls, which tests do not make.
