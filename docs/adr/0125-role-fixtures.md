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
     modules and repositories), `name-groups` (`lantern-summarize`); `describer` (`lantern-compose`;
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
   - as a sentence an earlier role wrote: Name groups, which sees only facts and Describe's words;
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
   - A flow's significance is kept without its reason: field-level repair drops the reason that
     cites the password file, and keeps the verdict, which the fixtures did not touch (ADR 0103).
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
