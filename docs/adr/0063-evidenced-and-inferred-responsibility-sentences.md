# 0063: Evidenced and inferred responsibility sentences

Status: accepted (#216), amends ADR 0050 and ADR 0056, pending the user's review (#24)

## Context

The Describer's prompts (ADRs 0050, 0056) ask the model to infer purpose from names, paths, routing
keys, tables and README claims. Its responsibility sentences are what diagrams and catalogues show
for deployable units, components, data stores and destinations, so a purpose worked out from names
reads as fact, as a summary's did before ADR 0059. `docs/design.md` (LLM integration, Grounding)
says business purpose comes from what people wrote where there is any, and otherwise is inferred
from the code and shown as inferred. Issue #216 asks for each responsibility sentence to be marked
as evidenced or inferred, and for documents to show inferred ones as such.

## Decisions

- **The mark is read from the citations**, as ADR 0060 does for the system's purpose: a sentence
  citing a README claim is evidenced; one citing none is inferred. The model is not asked to mark
  it, since its citations say which it is, so the prompts, their versions and the answers cached in
  `unit_wordings` are unchanged and no call is made again. Of what people write, the Describer's
  prompts show README claims only (a unit's prompt shows them; an element's shows none, so its
  sentence is always inferred). Javadoc and Lantern's annotations will count once a prompt shows
  them (#208, Phase 3). As in ADR 0059 the check is a bound, not proof: it cannot tell that the
  cited claim says what the sentence says (#191).
- **Documents show inferred sentences.** The composer opens the description of a unit, component,
  data store or destination taken from a responsibility sentence that cites no README claim, among
  the citations still in the snapshot's facts, with `Inferred:` as emphasized text. An evidenced
  sentence is shown as written. The label is shorter than ADR 0059's "Purpose inferred from the
  code:" because the description is the element's text on diagrams, where a box shows a few short
  lines; elsewhere the sentence's citations say what it rests on. Descriptions taken from summaries
  keep ADR 0059's label, and the system's purpose ADR 0060's.
- **Nothing is stored.** The mark depends only on the cited facts, which documents already have,
  so `Wordings` and the `unit_wordings` table are unchanged.

## Not verified

How often real models cite a README claim for a unit's sentence needs real calls, which tests do
not make. The fixture projects have no README, so their documents show every responsibility
sentence as inferred.
