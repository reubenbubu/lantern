# 0101: Model checks: the support judge and the injection screen

Status: accepted (#526)

## Context

The harness's checks (ADR 0100) are mechanical. They prove a cited input exists and names what the
text names, but not that it supports what the text claims. No filter reliably stops text in a
repository that is addressed to an AI. The knowledge-build epic (`docs/epics/001-knowledge-build.md`,
sections 10 and 11.7) adds two model checks that only add signals, never gate: a support judge and
an injection screen. This records how #526 builds them on the harness, and where it stops short of
the epic until the roles of milestone 1F exist. The agent decided these under the owner's standing
go-ahead for milestone 1E.

## Decisions

- **Two model roles.** `JUDGE` and `SCREEN` join the configurable roles, both on the high-volume
  tier by default.
  - The judge is of another tier than the texts it checks, which today are all written by
    Describe on the reasoning tier.
  - The screen moves to the bulk tier when 1F adds it.
  - Both are in the `model-checks` share of the run budget (ADR 0100, amendment for #42).
  - Both use the epic's prompts and schemas word for word, after the common system prompt of
    section 11.0 (`CommonPrompt`), which every request the harness lays out will share.
  - Both send at most 10 items a request.
- **What the judge checks.**
  - It checks every responsibility sentence Describe accepts (units, components, data stores,
    destinations and external systems) and the system's purpose, the texts a document shows with
    their sources.
  - It checks a deterministic 10%: the texts whose judge item's fingerprint, read as a number, is
    divisible by 10. A caller can name texts to check regardless, as the epic asks for every
    investigated risk, which comes with Investigate (#269).
  - Each item shows the text and each fact it cites, as the Describe prompt showed it. Its
    fingerprint rests on the text and those lines, so a text judged once is not judged again.
  - Intent phrases, flow narratives and summaries are not judged yet. Phrases are diagram labels
    that take no note. The flow and summary roles are rebuilt in 1F, and the judge follows them
    there.
- **What a verdict does.**
  - The claims add up as the epic says: `unsupported` if any claim is, else `partly` if any is,
    else `supported`.
  - An unsupported sentence is kept and opens with "Not supported by its sources:", emphasized,
    before any "Inferred:" label, in every document that shows it.
  - The confidence factors (1, 0.8, 0.5) are defined but not yet applied. Responsibility
    sentences are not stored as observations with a confidence until 1F/1G (ADR 0100).
  - The verdicts are cached as the judge role's answers in `llm_answers`, the log counts the
    sentences checked and found unsupported, and the role's run record counts its items.
- **What the screen reads.**
  - It reads every file whose text a prompt showed in the run, as shown, with secrets masked: type
    files in Summarize, the start of components' code in Describe, and files Resolve read with
    `read_file`. Roles note them in the harness.
  - It screens the whole file even where a prompt showed only its start, which screens more than
    the epic asks and never less.
  - Files go in chunks of at most 200 lines. A chunk is keyed by its file and first line, and its
    fingerprint rests on the blob and its range, so a blob is screened once
    across runs.
  - A flag counts only when its quote appears in the lines it gives.
- **What a flag does.**
  - A flag becomes a finding `finding:injection-suspected/<repo>:<path>`, with the first flagged
    lines and the quote, of origin `llm` (`injection-screen`). It is recorded for the snapshot
    after Describe and replaces the last screen's findings, but only when every chunk was
    answered, so a refused or failed call ends none.
  - It is shown as a medium risk, "Text addressed to an AI", in the repository's risks. It cites
    the finding, so the C4 report marks the row as found by the LLM (ADR 0086).
  - It does not block the run.
  - The epic also asks that texts citing a flagged range be judged regardless of the sample. That
    waits for 1F: the screen runs after Describe, and today's texts cite facts, not ranges.
- **The fake model** answers every judge item as supported and every chunk as not suspected. Fake
  runs then show what the checks cost, and their documents do not change.
- **Fixtures.** `lantern-harness/src/test/resources/injection` holds text addressed to an AI in a
  README, a Java comment, `application.yml` and a string literal, beside a clean file that mentions
  AI. The screen's test flags the four and not the fifth, from scripted model answers. Whether a
  real model's roles ignore them can only be shown on a real recording, which comes with #529.

## Consequences

- A cold run makes the judge's and the screen's calls on top of the roles'. A warm run makes
  none.
- A document can now say that one of its sentences is not supported by its sources. A repository
  with text addressed to an AI shows it as a risk.
