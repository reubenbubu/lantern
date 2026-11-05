# 0101: Model checks: the support judge and the injection screen

Status: accepted (#526), amended by ADR 0103 (#528: bulk tier, judge tier), amended for #529 (the support judge asks five a request)

## Context

The harness's checks (ADR 0100) are mechanical. They prove a cited input exists and names what the
text names, but not that it supports what the text claims. No filter reliably stops text in a
repository that is addressed to an AI. The knowledge-build epic (`docs/epics/001-knowledge-build.md`,
sections 10 and 11.7) adds two model checks that only add signals, never gate: a support judge and
an injection screen. This records how #526 builds them on the harness, and where it stops short of
the epic, as it stood before the roles of milestone 1F existed. The agent decided these under the owner's standing
go-ahead for milestone 1E.

## Decisions

- **Two model roles.** `JUDGE` and `SCREEN` join the configurable roles, the judge on the high-volume
  tier, the screen on the bulk tier since 1F.
  - The judge runs on the high-volume tier, which Describe's element role and other writing
    roles share (Describe's units are on the reasoning tier). Since #751 each role's texts are
    judged on the writer's own model (amendment for #751).
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
  - Intent phrases, flow narratives, summaries, group names and Investigate's risks are not
    judged. Phrases are diagram labels that take no note. 1F rebuilt the flow and summary roles
    without bringing the judge to them; #751 does.
- **What a verdict does.**
  - The claims add up as the epic says: `unsupported` if any claim is, else `partly` if any is,
    else `supported`.
  - An unsupported sentence is kept and opens with "Not supported by its sources:", emphasized,
    before any "Inferred:" label, in every document that shows it.
  - The confidence factors (1, 0.8, 0.5) multiply the confidence of the text's stored observation,
    which carries the verdict as `support` (`LlmFacts.withSupport`).
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
    is not done: the screen runs after Describe (#751).
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
    sentences checked and found unsupported. Verdicts per role are #751's.
  - A judge packet may mix the Describe roles' sentences and the purpose, up to 10 texts. They are
    all responsibility sentences of one kind.
  - The claim list is kept in the judge's cached answer; the text's observation carries only the
    verdict.
  - Findings are shown as a repository risk.
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

## Amendment for #751: the judge reads every role's texts, after the screen

- The judge no longer runs inside Describe. Once every role has stored its texts and the injection
  screen has run, the analyze run judges the texts of every writing role: Summarize (both levels),
  Describe's units and elements, the flow narratives, Name groups, Purpose and Investigate
  (`SupportChecks`, `lantern-summarize`). Each role's texts go in requests of their own.
- What it always judges: every investigated risk, and every text that cites a file range, or a fact
  whose evidence lies on one, that overlaps lines the screen flagged. Of the rest, a deterministic
  10%.
- The sample no longer reads the judge item's fingerprint, which hashes the provider and model;
  it reads a hash of the text and what it cites, so every judge model checks the same texts and a
  test drop on Haiku and on Sonnet compares like with like. A text the model rewords is a new text
  and may fall in or out of the sample.
- An item shows each fact it cites as before, and each cited file range with up to 40 of its lines
  read at the snapshot's commit, secrets masked as every role is shown a file (amendment below).
- Texts that read the same and cite the same inputs, such as one intent phrase repeated for many
  interactions, are judged once and the verdict is given to each.
- A verdict is stored on the text's observation as before (amendment for #530), on every kind of
  text the judge reads; `llm-types.json` gives each such type a `support` property. A text already
  judged is not judged again.
- The run record carries `supportVerdicts`: for each role, how many of its texts were found
  supported, partly supported and unsupported.
- **Who judges: the writer's own model** (the owner's decision on #751, 4 Nov 2026). The epic
  asked for a judge other than the writer. Haiku and Sonnet both write, and the owner ruled out
  either checking the other: each model checks its own texts, with a strict prompt. So each role's
  texts are judged on the model of the role that wrote them (`SupportChecks.ROLES`): Haiku's
  summaries, element descriptions, group names and flow narratives on Haiku, Sonnet's risks, unit
  descriptions and purpose on Sonnet. It also makes a run cheaper: the many Haiku texts are no
  longer judged on Sonnet. The setting `lantern.harness.support-judge.model` chooses: `writer`,
  the default, as above; `judge`, every text on the support judge's own model,
  `lantern.llm.roles.judge`, as before #751. Which model each writing role uses stays its own
  setting (`lantern.llm.tiers.*`, `lantern.llm.roles.*`), and its texts' judge follows it. The
  judge runs one session for each writing role's model role, named `support-judge-<model role>`
  (a run holds one session a role name), each at the judge's packet size; its estimates and
  calibrations are those of `support-judge`.
- Only kinds whose type declares `support` are judged: responsibilities, intents, summaries, flow
  narratives, purposes, key facts, investigated risks and finding wordings. A lead's verdict and a
  group's display name are not.
- The judge's facts are recorded once for each role name, every version of that role's facts
  together, so recording one version never ends another's (summaries of a repository not
  analyzed again keep an older version).
- Every range the screen flagged this run counts, not only the first of each file that is stored
  as a finding.
- **A stricter prompt** (prompt version 3). The judge keeps every specific of the text in its
  claims (names, numbers, technologies, what calls or stores what, causes, purposes, words of
  scope); calls a claim supported only when a cited input states it or shows it with no other
  reasonable reading; calls it partly supported when it adds a specific no input shows; judges
  against the cited inputs only, never against what it knows of frameworks or systems like this
  one; and, when unsure, does not call a claim supported. How well it holds is measured on real
  runs in the 1H test drop.
- Documents mark only responsibility sentences and the purpose as unsupported, as before; the
  other kinds carry the verdict in the store only.

## Amendment for #751: the judge's cited lines are masked as every role's are

Until this amendment the judge read a cited range's file straight from git, so a secret on a cited
line, such as the plaintext secret an investigated risk cites, reached its prompt unmasked, and
its value never reached the harness's Secrets check.

- A cited range's lines are now read through `SourceFiles.shownAt`, which masks the file with
  `FileSecrets` (`lantern-extract`), the masking the investigator's tools use (ADR 0054, ADR 0081):
  Java and other code by `SourceSecrets`, config, environment and XML files line by line under
  their secret keys and the run's known secret keys, private keys wherever they are; a key,
  keystore or binary file is never shown. The known keys are matched normalized however a caller
  passes them (`FileSecrets.masked`), so a key such as `app.jwt-key` that is secret only because
  the extractors know it is masked for the judge as for the investigator's tools. Masking keeps
  every line break, so a cited line keeps its number, but for one case: a secret option alone on
  its line inside a comment takes the line break with it (#844).
- Each value the masking took out goes to the run's masked values, which the harness's Secrets
  check reads (ADR 0100, amendment for #645), as it does for every other role.
- `Project0EndToEndIT` plants a plaintext secret in a copy of project-0's config, has the fake
  investigator confirm it as a risk citing its line, and checks that no request of the whole run,
  of any role, holds the secret, nor any other value masking took out in that run
  (`AnalysisRun.Result.secrets`, the set the Secrets check reads); a new path into a prompt that
  forgets to mask fails it.
- Known gaps, older than this amendment and shared with the investigator's tools: the line-break
  case above (#844); a config file with Windows line endings is not masked (#845); a quoted value
  or a Dockerfile `ENV` line reaches the Secrets check with its quotes or key, so the bare value
  repeated in an answer passes it (#846).
