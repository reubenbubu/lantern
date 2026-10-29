# 0059: Evidenced and inferred purpose in summaries

Status: accepted (#193), amends ADRs 0010 and 0025, amended by ADR 0061 (#77) and ADR 0066 (#192), pending the user's review (#24)

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
