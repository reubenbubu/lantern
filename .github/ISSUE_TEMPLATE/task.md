---
name: Task
about: A piece of work on Lantern, written so a person can follow it and an agent can start from it
---

<!--
Plain English, short sentences. Write for someone who knows what Lantern is but did not do the
last task. Name things as the report and the docs name them, not as the code does; put class
names and file lines only under Approach, where they may go stale.
-->

**Problem.** What Lantern's output (or the build, or the process) does wrong today, as a reader
sees it, on which test set. One concrete example: "project-4's container diagram shows X".

**Why.** The rule this serves: a line of `docs/quality-bar.md`, or a section of `docs/design.md`
for work the bar does not cover (build, cost, foundations).

**Expected.** What the output shows instead, in the same terms as the example.

**Approach.** *(optional)* Hints and known pitfalls. The agent doing the work decides the approach
against the design; this section does not bind it.

**Accepted when:** what the output must show, on which test sets (`docs/design.md`, Test sets),
checkable by the scorecards, a test or a named excerpt.

**Evidence:** what the PR carries to prove it (scorecards before and after, excerpts, test
results).

**Depends on:** issues that must land first, or "nothing".

**Size:** S (one module, under a day), M (several modules or a new pattern), L (design-level;
consider splitting).
