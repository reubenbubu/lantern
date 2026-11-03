# 0109: Purpose and key fact on the harness

Status: accepted (#274)

## Context

The quality bar (`docs/quality-bar.md`, Rubric, C4 report) asks the first paragraph of the
system's report to say what the system is and the single most important fact about it, both
cited. The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.6) fixes the role
that words both: one item for the system, its prompt and answer schema, and two claims. Before
#274 a free-form prompt worded the purpose alone from the units' sentences and README claims (ADR
0060). This records how #274 lands the role and what the epic left open. The agent decided these
under the owner's standing go-ahead for milestone 1F.

## Decisions

- **Where it runs.** Last in Describe, after the units, elements and flows: its item shows what
  they wrote. Under a budget it keeps the first claim on the `flows-and-purpose` share (#659),
  planned before anything is asked on an item of the expected size: each unit and person with a
  sentence of typical length, each external system, seven flow titles, the README claims, and the
  prompt with its schema.
- **The item.** One, its subject `system:snapshot`, asked alone on the reasoning tier
  (`Role.INFER_INTENT`), interactive. It shows:
  - each unit's and person's responsibility as `[A..]`, by name (its reference keyed by the
    element, so two of one name are two references), at most 30 each, and under each the facts
    it cites as `[F..]`, at most 8, so the key fact can cite the facts behind a sentence (rule 4
    of the common prompt wants a cited input for every name); citing the sentence stands for
    those shown facts;
  - each external system by name, with the fact that shows it; two of one name, which the
    documents cannot tell apart either, are shown once;
  - the titles of the flows the system's report draws, in order, which are not citable;
  - each repository's README claims, of its README with the fewest folders, at most 8.
  A fact shown before is written by its reference alone. With no sentence and no README claim it
  is not asked, and the purpose is stated as unknown.
- **Checks.** The shared pipeline: the purpose is prose of at most 25 words, the key fact of at
  most 30, each citing `[F..]` or `[A..]`; an `[A..]` citation stands for the facts that sentence
  cites.
- **Claims.** `(system, purpose, "c4")` and `(system, key-fact, "c4")`, each with its text, cites,
  basis and model, new vocabulary types of the roles (`llm-types.json`). A text is evidenced when
  it cites a README claim, directly or through a sentence that cites one, as the documents mark
  it, else inferred (ADR 0060). Both are left out of the roles' inputs
  (`Describer.withoutTexts`).
- **The first paragraph.** The purpose, marked "Purpose inferred from the code:" when it cites no
  README claim (ADR 0059), then the key fact in the same paragraph, each with its citations; then
  the repositories and what each runs (ADR 0060). Without a purpose the note "The system's purpose
  is not known." stays and the key fact follows it as its own paragraph. Without a key fact the
  paragraph holds the purpose alone. The system's description elsewhere is its purpose only.
- **Support judge.** The purpose is judgeable as before (ADR 0101); the key fact is not yet in
  the judge's sample. The epic's key-fact judge score comes with the wording judge (#668).
- **The old prompt** (ADR 0060's `WordingPrompt.purpose`), its free-form answer check and the
  classes that held its answers are removed; `WordingPrompt` keeps only how a flow's trace is
  written as the key of its wording.
- **The fake model** omits the item, so fake runs state the purpose as unknown, as before.

## Consequences

- A run makes one purpose call, cached under its item, so a rerun over unchanged facts makes
  none. A changed unit sentence, person, external system, flow title or README claim asks again.
- The recordings that accept the first paragraph on the test sets need a real model; they come
  with #529.
- Supersedes ADR 0060 for how the purpose is asked; its rules for marking and stating it stand.

## Amendment (#760): limits in the role's contract

This ADR's word limits and margins now live in the role's response contract, `prompts/<role>.contract.json`, which the checks read (ADR 0118). Change them there. Where they differ from the numbers above, the contract's hold.
