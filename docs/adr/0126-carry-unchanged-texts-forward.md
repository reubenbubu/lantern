# 0126: A re-asked answer keeps the texts whose evidence did not change

Status: accepted (#722)

## Context

The harness asks an item again when one of its fingerprint inputs changed (ADR 0100). An answer is
a fingerprint input of every item that shows it, so the cascade through reworded answers is by
design (knowledge-build epic, section 10; ADR 0100, amendment for #702). A free fake model, which
answers the same input the same way, stops it at the first level. A real model rewords the whole
answer, sentences that rest on nothing that changed included. On project-4, a comment in one class
re-asked 3 items on the fake and 89 on DeepSeek V4.1 Flash. The epic's section 13 makes the
one-file change a fixed gate, measured in every drop (#755).

The research note `docs/research/stable-rewording.md` (high depth) compares six ways to keep a
re-asked answer stable and recommends carrying texts forward by citation, enforced in code, with
downstream keys kept exact: early cutoff (*Build Systems à la Carte*; Salsa's backdating) at the
level of one text.

## Decision

1. **Carry forward by citation.** When an item is asked again, each text of the new answer whose
   earlier version, from the answer stored last for the same role and subject under the same prompt
   version and model, cited only references the item still shows with the same line, keeps that
   earlier text. Its citations are renamed to the references that show those lines now: the same
   name where it still shows the same line, else the first that does. Only texts resting on what
   changed are worded anew. A text is kept only under the same field and the same subject name (a
   component key now naming another component is not the same text).
2. **What a text rests on is stored with it.** Each stored text records a `carry` object beside
   its `text` and `cites`: its field, a hash of what it is about, and a hash of each cited line.
   Readers of the answer read `text` and `cites` and ignore it; it is no part of any fingerprint,
   fact or document. A text that cites nothing the item shows (no citation, or a file range a tool
   read) records nothing and is never kept.
3. **Which texts.** A role says which of its texts may be kept (`PacketRole.carries`):
   - Describe: every responsibility and intent.
   - Flow narratives: every text. Flow significance: none, since a reason explains a verdict the
     model may change.
   - Name groups: a group's name, not a merge's reason, for the same reason.
   - Purpose: the purpose and the key fact.
   - Summarize keeps sentences of one summary in its own form: each earlier sentence whose cited
     lines are all still shown is kept, in its earlier order; a new sentence is added, at its own
     position, only if it cites a line no kept sentence cites and does not repeat a kept one word
     for word. Its sentences are stored with the summary (`sentences`). A summary made from parts
     merged, for inputs that do not fit one prompt, keeps nothing.
   - Resolve, Investigate and the model checks keep nothing: they give verdicts and findings, not
     texts shown to other items.
4. **Fingerprints stay exact.** An item that shows a kept text sees the same words and keeps its
   fingerprint; nothing fuzzy enters a cache key. A summary or answer kept whole is byte for byte
   the earlier one, so what shows it is not asked.

## Consequences

- With a fake model that rewords every call (tests in `SummarizerTest` and `DescriberTest`), a
  change to one class's code re-asks the unit that shows the code and the support judge for its
  one changed text, 2 calls, where without carrying forward it re-asks every element, scope and
  flow after it and the purpose, 11 calls. In Summarize, a type that gains a method re-asks its
  package and module, and the rewording stops there: the module's sentences cite nothing that
  changed.
- A warm run and a cold run may word the same item differently. That is "one possible result" of a
  non-deterministic task, as the research note explains.
- A kept text may be made untrue by a fact it does not cite, such as "the only writer" when a
  second writer appears. The research note's mitigation applies: a claim about a whole set cites
  what it ranges over. If real runs show stale or duplicated texts, the note's second step, a
  re-ask that shows the earlier texts and asks for edits, is the next option.
- The real-run gate stays open on #722: on project-4 with #529's one-file change, #755's gate must
  pass with 0 unexpected items on Haiku 5.5 and Sonnet 5.5.
