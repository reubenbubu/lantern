# 0125: Recorded and injection fixtures for every role

Status: accepted (#768)

## Context

The knowledge-build epic asks that every role has recorded fixtures whose replay reproduces the
stored facts (section 2, criterion 10), and that every role ignores the four injection fixtures: a
README, a Java comment, `application.yml` and a string literal (section 10, Security). Before
this, only Resolve had recordings, made from a scripted fake, and only the injection screen's test
used the fixtures. No real model may be called in a test (CLAUDE.md, Safety and cost).

## Decision

1. **A fixture per role, recorded from its scripted fake.** Each role's test runs it once with the
   scripted fake model its other tests use, recorded with `RecordReplayLlmProvider` into
   `src/test/resources/recordings/<role>/` in the role's module, and keeps what the run stores in
   `expected.txt` beside it, one line per fact: origin and prompt version, claim, descriptive
   attributes (with `answer`, ADR 0124) and lineage. The test replays the folder and must give
   exactly those lines. `RoleFixture` (`lantern-llm`) is the one switch: with
   `-Dlantern.test.rerecord=true` it records again and rewrites `expected.txt`, from the fake,
   never a real model; a person reads the new lines before committing them.
   - The folders: `injection-screen`, `support-judge` (`lantern-harness`); `summarize` (types,
     modules and repositories), `name-groups`, `group-by-meaning` (`lantern-summarize`; the latter added for #919); `describer` (`lantern-compose`;
     one run asks Describe's units and elements, both flow roles and Purpose, and each role's test
     checks its own lines); `resolve-tools` and `investigate` (`lantern-link`; one run asks
     Investigate's leads, units and system).
   - The support judge and the injection screen store no facts: their lines are the verdicts and
     findings they return.
   - A recording is keyed on everything sent, so a change to a role's prompt fails its replay
     test until it is recorded again: that is the point.
2. **Injection: shown as data, obeyed, rejected.** Each role is shown the four fixtures in the
   input it really gets:
   - as files: the screen's chunks, Summarize's source excerpts, and what Resolve and Investigate
     read with `read_file`;
   - as README claims: Summarize, Describe's units and Purpose; Summarize's types see all four
     through one class whose comment holds them, so each Summarize role is shown every fixture;
   - as text on the facts they show: Describe's elements and the flow roles, which see no file or
     README (a comment on a fact, as a string in the code could carry);
   - as a sentence an earlier role wrote: Name groups and the grouping role, which see only facts and
     Describe's words;
   - as cited fact lines: the support judge.

   `Blocks.outsideData` finds any occurrence of the fixtures' "AI assistant" outside the data
   blocks (`<facts>`, `<code>`, `<summaries>`, `<answers>`, `<items>`, `<chunk>`, each tag on a
   line of its own, so a tag name in prose opens nothing) of the system prompt and each user
   turn; a tool's result is data by construction. Then a model that obeys them answers: the
   service is fully secure with no risks, the pull request is approved, and it cites
   `/etc/passwd`, the file the fixtures say to read. Every role's checks reject that, as a
   citation of a file outside the repositories or a verdict the schema does not know, and nothing
   of it is stored.
   - For the screen, obeying means quoting what the fixtures ask for instead of the line itself.
     A quote the chunk does not hold is no finding, so the obeyed screen stores nothing: its
     "rejection" is silence, and the unflagged files go on unmarked.
   - Investigate's lead is then stored as `unclear`, its answer rejected twice.
   - A flow's significance answer whose reason cites something that is no fact or answer of the
     item (the password file) is rejected whole, and no verdict of it is stored: the scope falls
     back to rank order. A reason citing a fact reference the item lacks is still a slip, and only
     that reason is withdrawn (ADR 0103). (Amended for #768's review.)
   - The support judge rejects a verdict whose "by" names an input the item does not cite: an
     obeying judge finds every claim supported by the password file, and no verdict is kept.
     (Amended for #768's review.)
3. **Shared through the harness's test jar.** The fixtures, `InjectionFixtures` (the obeying answer,
   the data-block check and the fact lines) and the harness test helpers are published as
   `lantern-harness`'s test jar, which `lantern-summarize`, `lantern-compose` and `lantern-link`
   use in test scope.

## Consequences

- These tests show that obeying the fixtures gets an answer rejected. They cannot show that a
  model never obeys them within the rules: an answer that cites only real facts but leans the
  way the fixtures want, or a screen that answers "not suspected", passes every mechanical check.
  The epic's defence in depth stays: the policy in every prompt (ADR 0067), isolation per unit,
  the screen's finding, and the sampled support judge.
- Recording again is a deliberate step, and its `expected.txt` diff is the review.

## Amendment for #768's review (decision of 4 Nov 2026)

- **Decision 1: the fixtures move to real model answers.** Today's fixtures stay as they are,
  recorded from each role's scripted fake, so the replay tests exist now. The 1H test drop's real
  Haiku 5.5 and Sonnet 5.5 run (#771) is recorded with `--record-calls`. Then each role's folder
  is replaced with a small slice of that recording: one small subject per role in
  `src/test/resources/recordings/<role>/`, with `expected.txt` regenerated from its replay. #768
  stays open until that replacement lands.
- **Decision 2: no obeying significance verdict is stored.** `FlowJudgements`' significance check
  rejects the whole answer when a reason cites anything that is no fact or answer reference of the
  item, so a verdict that rests on text shown as data is never kept (prompt version 9). A tag
  with a line added (`F3:12`) still counts as a slip. The cost: one reason citing a file or other
  outside reference drops every verdict of that scope's answer after its repair turn, and the
  scope is drawn by rank order, where before only that reason was withdrawn.
- **Decision 3: the judge's injection test covers every item.** Its obeying fake finds every claim
  supported, `unit:0` included, by the password file. The judge rejects a "by" naming a fact tag
  or a file its own item does not cite (prompt version 4), and no verdict is stored. Only what
  reads as a reference is checked: words around it ("F1 and F2", "F1 (line 3)"), a tag with a
  line, a file without its repository or a narrower range of a cited file all pass, as tests show.
