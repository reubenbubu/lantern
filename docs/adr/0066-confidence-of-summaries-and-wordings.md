# 0066: Confidence of summaries and wordings, and a unit's fallback description

Status: accepted (#192), amends ADR 0010, ADR 0050, ADR 0059 and ADR 0065, amended by ADR 0068 (#191) and ADR 0069 (#208)

## Context

`docs/design.md` (Design principles 2) says every claim records with what confidence it was made.
Facts have a confidence; summaries (ADR 0010) and the Describer's wordings (ADR 0050) had none.
When a deployable unit has no responsibility sentence (none worded, rejected, skipped, or citing no
fact of the snapshot), documents describe it by its main module's summary, a text about the module
rather than the unit, and nothing told the reader so. The unit's sentence and its module's summary
are written by separate calls and can disagree. Issue #192 asks for a stored confidence on each,
for documents to mark fallback descriptions, and for the two descriptions of one unit to be
reconciled or one chosen consistently.

## Decisions

- **The confidence is worked out, not asked.** It is computed from what the checks already find,
  so no prompt, prompt version or cached answer changes and no call is made again. Asking the model
  how sure it is would change every prompt, and a model's own rating says little about grounding.
  An LLM's text is never trusted as much as a static fact, so the values are the resolver's
  factors for a pick of high and medium confidence (ADR 0045), taken here as values themselves: **0.5** when its purpose rests on what people wrote (a summary marked evidenced, ADR
  0059; a wording citing a README claim, ADR 0065), **0.35** when inferred from the code (`Confidence`
  in `lantern-summarize`).
- **A summary's confidence** is that base times the share of its answer's sentences that were kept:
  sentences citing nothing are left out (ADR 0061), and an answer that needed many left out is less
  grounded. It is rounded to two decimals and is at least 0.01. A unit summarized in parts takes the
  share of its merged answer. Whether the answer came on a retry is not counted: the retry fixed
  what the check rejected. `Summary.confidence` is kept in a new `confidence` column of the
  `summaries` table (migration V15); rows written before did not record what was left out and get
  the confidence of their purpose alone. A reused summary keeps its confidence, as it keeps its
  purpose: the same key means the same prompt and answer. The confidence is not shown to parents and
  is not in the cache key.
- **A wording's confidence** is set on each item of an answer (`WordingAnswer.Item.confidence`):
  responsibility sentences, intent phrases, a flow's texts and the system's purpose. An item's
  sentences cannot be left out (a sentence that breaks the rules rejects the answer; since ADR 0068
  a whole item may be left out, which leaves no item to rate), so it is the base alone: 0.5 if the item cites a README claim of its prompt, else 0.35. It is stored with the
  item in the `unit_wordings` table's `items`, which needs no migration; items cached before have
  none (`null`). Documents do not read it: the composer marks inferred sentences from their
  citations among the snapshot's facts, as ADR 0065 does, and the document model has no place for
  a confidence yet (its confidence badges come with the Explorer's components, docs/design.md, Rendering and
  templates).
- **A fallback description says so.** A deployable unit described by its module's summary opens with
  `From its module's summary:` as emphasized text, or `Inferred, from its module's summary:` when
  the summary's purpose is inferred, in place of ADR 0059's label, on diagrams and wherever else
  the description is shown. A component, package, module or repository described by its own
  summary is not a fallback and keeps ADR 0059's label.
- **One description is chosen, the same way everywhere.** A unit's description is its responsibility
  sentence wherever it has one, and only otherwise its module's summary, marked as above; the
  module's summary stays the module's own description and paragraph. A sentence is chosen over the
  summary even when the summary's confidence is higher, since it is about the unit and short enough
  for a diagram's box. The two texts are not merged: a merge would need the summary in the
  Describer's prompt, which ADR 0050 keeps out so that wordings do not depend on which repositories
  a run analyzes, or a third call.

## Not verified

How well the confidence tracks how often real summaries and sentences are right needs real calls
and a reviewed sample, which tests do not make. The fixture projects have no README, so their
documents show every unit's fallback description as inferred.
