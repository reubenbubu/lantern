# 0101: Model checks: the support judge and the injection screen

Status: accepted (#526), amended by ADR 0103 (#528: bulk tier, judge tier), amended for #529 (the support judge asks five a request)

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
    investigated risk, which comes with Investigate (#269). `Describer.alsoJudging` names the
    elements whose sentences it checks regardless; `DescriberTest` uses it to follow a sentence
    from Describe through the judge to the document's note (#654).
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

- **Other departures from epic 11.7**, each until the step named:
  - The screen reads whole files a prompt showed, not the ranges that facts and tool results bring
    into prompts. Text that reaches a prompt only as a fact, such as a config value or a README
    claim, is not screened yet (#651).
  - An unsupported text gets the note but not the LLM badge. The C4 report badges what rests only
    on LLM facts (ADR 0086), and these sentences cite static facts. The note marks them in every
    template.
  - The run record counts the judge's items, not its verdicts per role. The log counts the
    sentences checked and found unsupported. Verdicts per role come with the 1F roles that store
    their texts as observations.
  - A judge packet may mix the Describe roles' sentences and the purpose, up to 10 texts. They are
    all responsibility sentences of one kind.
  - The claim list is kept in the judge's cached answer, not beside the text, which is not stored
    as an observation until 1F/1G.
  - Findings are shown as a repository risk. There is no separate findings page.
  - A complete screen ends only the findings of files it read in that run. A finding on a file no
    prompt showed this time, such as one only a cached Resolve answer read, still holds.

## Consequences

- A cold run makes the judge's and the screen's calls on top of the roles'. A warm run makes
  none.
- A document can now say that one of its sentences is not supported by its sources. A repository
  with text addressed to an AI shows it as a risk.

## Amendment for #529: the support judge asks five texts a request

Measured on DeepSeek V4.1 Flash on project-1 to project-4 (2026-11-02); how, the noise between two runs and what the recordings share are in ADR 0112, amendment for #529. Per-item scores 0 to 100; the bound is the one-sided 90% bootstrap lower bound of the mean difference against size 1, trial a. The support judge has no right answer, so its item score is the share of an item's claims judged supported, and only a difference or a change is measured.

| Size | Mean difference | 90% two-sided interval | Items | Changed (limit 85%) | Worst set | Judge's cost, four sets | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1, trial b | 0.0 | −1.1 to +1.1 | 15 | 80% | −2.8 | $0.011 | the noise |
| 5 | +1.4 | −3.1 to +6.1 | 15 | 73% | −3.3 | $0.006 | no difference shown |
| 10 | +3.9 | +1.1 to +7.8 | 15 | 73% | 0.0 | $0.006 | judges more claims supported |
| 20 | +5.0 | 0.0 to +10.0 | 6 | 100% | 0.0 | $0.002 (two sets cached) | fails on changed answers |

Size 1 trial a cost $0.010.

- Its "changed answers" compare the list of claims an item splits into, which differs between any two runs (80% between the two size-1 runs). The limit is that share plus 5 points: 85%. Size 20 changed all 6 of its items.
- **Kept: 5 texts a request** (`lantern.harness.items-per-request.support-judge=5`). Sizes 5 and 10 both pass the epic's one-sided test, but with no right answer a higher score is not better: at 10 the judge calls more claims supported than at 1, and its interval excludes no change, so a larger request may make it laxer; the epic's section 10 now counts such a shift against a size. 5 is the largest size with no difference shown either way. Resampled around no difference, a size truly equal to 1 fails the −7 bound 0% of the time with these 15 items.
- With 6 to 22 items the measurement is weak. To be measured again on a sample of more texts.

## Amendment for #530: the verdict is stored on the text

Documents now read only stored facts (knowledge-build epic, section 12), so the verdict is stored as
the epic's section 11.7 says: a responsibility sentence or purpose the judge checked carries
`support` (`supported`, `partly` or `unsupported`) on its observation, and its confidence is
multiplied by 1, 0.8 or 0.5. A sentence the judge did not check has none. A document marks a
sentence whose stored verdict is `unsupported` with "Not supported by its sources", as before. The
claim list stays in the judge's cached answer.

## Amendment for #765

- An injection finding is also listed on the risks and findings page of the C4 report and the
  other templates that have one, in the "Other findings" table with the merge suggestions of Name
  groups (ADR 0107, amendment for #765): every one in the system's documents, a repository's own
  in its documents.
