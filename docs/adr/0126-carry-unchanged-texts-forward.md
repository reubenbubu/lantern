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
   changed are worded anew. A text is kept only under the same field and about the same thing: a
   role names what each text is about (`PacketRole.about`), by default its subject name, so a key
   that now names another component or line is not the same text. Name groups names a line's
   intent by the line's stable key, not by its position.
   - A kept text passes the role's code checks again against what the item shows now; one that
     fails takes the text written this time.
2. **What a text rests on is stored with it.** Each stored text records a `carry` object beside
   its `text` and `cites`: its field, a hash of what it is about, and a hash of each cited line.
   Readers of the answer read `text` and `cites` and ignore it; it is no part of any fingerprint,
   fact or document. A text that cites nothing the item shows (no citation, or a file range a tool
   read) records nothing and is never kept.
3. **Which texts.** A role says which of its texts may be kept (`PacketRole.carries`):
   - Describe: every responsibility and intent.
   - Flow narratives: every text. Flow significance: none, since a reason explains a verdict the
     model may change.
   - Name groups: a group's name, its responsibility and each line's intent; not a merge's reason,
     for the same reason.
   - Purpose: the purpose and the key fact.
   - Summarize keeps sentences of one summary in its own form: each earlier sentence whose cited
     lines are all still shown is kept, in its earlier order; a new sentence is added, at its own
     position, only if it cites a line no kept sentence cites and does not repeat a kept one word
     for word. The summary keeps at most the sentences its prompt asks for, leaving out earlier
     ones first. A kept sentence passes the sentence checks again. It may lead only with the
     earlier first sentence or the model's own first one; otherwise the lead rules (#275) apply as
     to a first sentence left out. Confidence counts only what the checks left out. Its sentences
     are stored with the summary (`sentences`). A summary made from parts merged, for inputs that
     do not fit one prompt, keeps nothing.
   - Resolve, Investigate and the model checks keep nothing: they give verdicts and findings, not
     texts shown to other items.
4. **Fingerprints stay exact.** An item that shows a kept text sees the same words and keeps its
   fingerprint; nothing fuzzy enters a cache key. A summary or answer kept whole shows the same
   text as the earlier one, so what shows it is not asked.

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

## Amendment (#722, review of 4 Nov 2026): found by what a text is about; the cascade stops at a kept text

- **Found by what it is about, not by its key.** An earlier text is looked up by the hash of what
  it is about (`PacketRole.about`), and among several about the same thing, the one that rested on
  the lines the new text cites first, then the one of the same field, then in order, each kept at
  most once. A component or line whose key moves, such as
  from C3 to C4 when a new component sorts first, keeps its wording; a key that now names another
  thing still keeps nothing of the old one. Before, texts were matched by field, so one added
  component reworded every component after it.
- **Each text recorded under its own field.** A stored text is paired with its field by its place
  in the stored answer (its path ends with the field), and only where the stored form names no
  field, such as a summary's sentences, by its words and number of citations. Two texts worded
  alike no longer swap their records.
- **How far a one-file change may re-ask** (decision of 4 Nov 2026 on #722). An item is re-asked
  only when a file it rests on changed, or when the text of an answer it is shown changed in this
  run. A summary above the package is re-asked only if a summary below it got new wording; one
  re-asked that kept its wording stops the cascade, since it shows the same text. Each shown
  answer names the item it comes from, outside the fingerprint (`FingerprintInput.from`), so the
  run's record says whose answers changed (ADR 0072, amendment for #722):
  - Summarize's child summaries and the summaries Describe shows: the child's summarize item;
  - units' sentences shown to the flow roles and to Describe's elements: the unit's Describe item;
    shown to Purpose: the unit's or the element's;
  - interaction phrases shown to the flow narratives: Describe's units role, since a large unit's
    phrases may come from any of its parts;
  - members' sentences and lines' intents shown to Name groups: the role that wrote them;
  - flow titles shown to Purpose: the flow narrative role;
  - Investigate's lead verdicts shown to a unit: the leads role; its risks and system map shown to
    the system pass: the units role.
  The other groups' IDs Name groups is shown are derived, not answers, and are now a text input,
  which asks each Name groups item once anew.
