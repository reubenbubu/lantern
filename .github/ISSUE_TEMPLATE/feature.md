---
name: Feature
about: Something Lantern cannot do yet (also chores and foundation work), written so a person can follow it and an agent can start from it
---

<!--
Plain English, short sentences. Write for someone who knows what Lantern is but did not do the
last task. Name things as the report and the docs name them, not as the code does; put class
names and file lines only under Approach, where they may go stale.
-->

**Goal.** What Lantern can do afterwards that it cannot today, and for whom (the reader of which
document, or whoever runs or develops Lantern).

**Why.** The rule or section this serves: a line of `docs/quality-bar.md`, or a section of
`docs/design.md`. A new document names the bar section it adds.

**What it shows or does.** The visible result, with one example: an excerpt of the output, or the
command and what it prints.

**Scope.** In: what this issue delivers. Out: what is deliberately left for later, with its issue
if there is one.

**Research.** "Needed: <the question>" when this introduces a new pattern or others have solved
the problem before (`CLAUDE.md`, How work is organized), or "not needed".

**Facts needed.** The facts the result is built from, and which of them extraction and linking do
not provide yet (`docs/design.md`, Design principles 9); those gaps are part of this issue or a
dependency.

**Design changes.** The sections of `docs/quality-bar.md` and `docs/design.md` this adds or
changes, in the same PR; or "none".

**Approach.** *(optional)* Hints and known pitfalls. The agent doing the work decides the approach
against the design; this section does not bind it.

**Accepted when:** what the output must show, on which test sets (`docs/design.md`, Test sets),
checkable by the scorecards, a test or a named excerpt.

**Evidence:** what the PR carries to prove it.

**Depends on:** issues that must land first, or "nothing".

**Size:** S (one module, under a day), M (several modules or a new pattern), L (design-level;
consider splitting).
