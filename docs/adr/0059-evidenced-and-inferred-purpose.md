# 0059: Evidenced and inferred purpose in summaries

Status: accepted (#193), amends ADRs 0010 and 0025, amended by ADR 0061 (#77) and ADR 0066 (#192), amended by ADR 0104 (#665: the purpose is computed, never marked by the model), amended for #934 (the C4 report marks inferred text with an icon)

## Context

ADR 0025 asks the model to infer what a unit is for from names, annotations, Javadoc and code
and to state it directly. The design's Grounding says business purpose comes from annotations or
README claims, or is stated as unknown. A repository summary's first sentence is the Executive
Brief's business-purpose sentence (its overview), so an invented purpose lands in the most visible
sentence of a document. The summarizer did not show README claims at all: the docs extractor's
facts belong to no module (ADR 0033). Issue #193 asks for each purpose sentence to be marked as
evidenced or inferred, and for documents to show inferred ones as such.

## Decisions

- **Evidenced means written by people and shown to the model**: a README claim, Javadoc in a
  source file, or a part's summary whose purpose is evidenced. Inferred means worked out from names,
  annotations and code, or not stated at all (a first sentence that says what the unit is and
  contains). Lantern's own annotations will count as evidence when they reach prompts (Phase 3).
- **A repository's prompt shows its README.** The repository's facts are the first 8 claims, as
  written, of its README with the fewest folders in its path (the same README and number the
  Describer shows, ADR 0050). They are listed as its facts, so they are cited, ordered and rebound
  like any other fact (ADR 0055): the prompt lists them in its fact order, by the line as written
  into the prompt, not in the README's order. Modules, packages and types show no README claims.
- **The model marks its answer.** The system prompt asks it to take the purpose from what people
  wrote where the input has it, else to infer it as before, and to start its answer with a line
  `Purpose: evidenced` or `Purpose: inferred`. Each part's summary is shown with its mark
  (`[S1] module shop-core (purpose evidenced): ...`) so a parent can carry an evidenced purpose up.
  The line is read and left out of the text; Markdown around it, a note in brackets after it and
  the summary on the same line are allowed. An answer without the line counts as inferred, with no
  retry. A unit summarized in parts (ADR 0024) shows each part's checked mark in the merge prompt
  (`Part 1 (purpose evidenced): ...`), and the merged answer is marked and checked like any other.
- **The mark is checked.** An answer marked evidenced keeps that mark only if its first citation,
  the one that ends its first sentence, cites a README claim, a source file containing `/**`, or a
  part's summary marked evidenced; otherwise it counts as inferred, as it does when its first
  sentence cites nothing and is left out (ADR 0061). The check is a bound, not proof: it cannot
  tell that the cited Javadoc says what the sentence says (#191 covers whether citations support
  their sentences).
- **Stored with the summary.** `Summary.purpose` is `EVIDENCED` or `INFERRED`, kept in a new
  column of the `summaries` table (rows written before count as inferred; they are not reused,
  since the prompt version changed). A reused summary keeps its mark: the same key means the same
  prompt, and the mark depends only on the answer and the prompt's inputs.
- **The cache key is unchanged in form** (ADR 0055): it hashes the user turn, which now holds the
  README claims and the parts' marks, so a part whose mark changes sends its parent back to the
  model, as a part whose text changes does.
- **Documents show inferred purpose.** The composer opens a summary whose purpose is inferred with
  `Purpose inferred from the code:` as emphasized text, wherever it shows the summary: the overview
  paragraph (the Brief's first sentence) and entity descriptions. An evidenced summary is shown as
  written.
- **The design follows.** Its Grounding now says business purpose comes from what people wrote
  where there is any, and otherwise is inferred from the code and shown as inferred, rather than
  stated as unknown; ADR 0025 is amended to match.
- **`Prompt.VERSION` is 8**, so every cached summary is made again once.

## Not verified

How often real models mark a purpose evidenced, and whether they take it from the README when
there is one, needs real calls, which tests do not make. The fixture projects have no README, so
their documents show every summary as inferred.

## Amendment (#767): the stored basis decides

Documents read whether a text is evidenced or inferred from the `basis` its role stored, no longer
from its citations (ADR 0122).

## Amendment (#934): the C4 report marks inferred text with an icon

The document model keeps the emphasized label (`Inferred:` and its variants) so every document can
show it. The C4 report's renderer reads it off an element's description and passes the element
flagged `inferred` instead, and the template draws a small ≈ mark before the description, in the
box and in the catalogue table, explained once in each diagram's legend ("Description inferred from
the code, not written by people"). On a box the label used most of the room before the text was
cut, and it opened almost every description; the mark keeps the distinction at the cost of one
character. The same review dropped the "LLM ·" badge: what the LLM found carries a small ✦ mark,
explained in the legend the same way, and a group the LLM formed (a domain or feature area) carries
none, since grouping is how the report is arranged, not a finding.
