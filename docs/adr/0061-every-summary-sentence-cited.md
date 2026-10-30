# 0061: Every summary sentence cites something

Status: accepted (#77), amends ADRs 0010, 0025 and 0059, amended by ADR 0068 (#191)

## Context

The summarizer's prompt asks for references at the end of every sentence, but the check of ADR
0010 only needs one valid citation per answer, so a summary could hold sentences that cite
nothing, such as a purpose sentence ADR 0025 asks to cite what it is inferred from. Issue #77 asks
for such sentences to be rejected or marked low confidence, as the design's Grounding allows. The
review of the LLM strategy (#204) recommends enforcing a citation per sentence by dropping uncited
sentences rather than asking again: that is deterministic and costs nothing, and a retry is kept
for when nothing is left.

## Decisions

- **A sentence that cites nothing is left out.** After an answer passes the checks of ADR 0010,
  which still look at the whole answer (so a left-out sentence cannot hide a marker or an unknown
  reference), it is split into sentences and only those citing at least one reference are kept.
  The summary text, its stored row and a merge prompt's part (ADR 0024) hold only those. No
  second call is made for it. Each answer with left-out sentences is logged at info level with the
  purpose of the call and the sentences.
- **A retry only when nothing is left.** An answer none of whose sentences cites anything cites
  nothing at all, and is sent back once as before ("it cites no references").
- **What counts as a sentence.** A sentence ends at `.`, `!` or `?` (with any closing quotes,
  brackets or emphasis and any citations right after it, as in `It sells books. [F1]`) when the
  next word does not start in lower case or the text ends; at a line ending in a citation; or at a
  blank line. A full stop after `e.g` or `i.e`, or followed by a word in lower case, does not end
  one, nor does one inside a word such as `com.example.shop` or `3.2`. The split is a heuristic: an
  abbreviation it does not know before a capital, such as `Inc. Shop`, splits a sentence, and the
  uncited half is left out.
- **A purpose whose first sentence is left out is inferred.** The `Purpose: evidenced` mark (ADR
  0059) is about the answer's first sentence. If that sentence cites nothing it is left out, and
  the sentence now first is not the one the mark was about, so the summary counts as inferred
  whatever its first citation now cites; the composer then shows `Purpose inferred from the code:`
  before it. The first kept sentence becomes the summary's opening, and so the Brief's overview
  for a repository. Asking again was not chosen, to follow the review's recommendation; a summary
  opening with how the unit works rather than what it is for is the cost.
- **The cache stays consistent.** The key is unchanged in form (ADR 0055): it hashes the prompt,
  and the text stored under it is the text without the left-out sentences, so a reused summary is
  what was shown when it was made and is rebound as before.
- **The prompt says so.** The system prompt adds "A sentence without references is left out of
  the summary." `Prompt.VERSION` is 9, so every cached summary, which may hold uncited sentences,
  is made again once.
- **The Describer is unchanged.** Its answers (ADR 0050, 0056, 0058) are single sentences or short
  texts that each come with their own list of citations, not citations inside the text, so there
  are no sentences to split. Since ADR 0068 each of its texts that names what the facts it cites do
  not mention is left out, as a summary sentence is.

## Not verified

How often real models leave a sentence uncited, and so how much text is left out, needs real
calls, which tests do not make. The fake models of the tests cite every sentence, so the golden
documents do not change.
