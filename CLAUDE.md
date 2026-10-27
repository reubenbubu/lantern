# Lantern: instructions for the coding agent

Lantern analyzes a set of Java repositories and generates architecture documents from them.
What to build is in `docs/design.md` (a copy of the master design doc:
https://claude.ai/artifact/1oi2XNFvDAv4deQw7zCQRR). This file covers only how you work.

## Talking to Reuben

- Short, plain English. Lead with the point. No walls of text, few tables.
- Ask at most one or two questions at a time, each with your recommendation.
- Report each finished task in two or three lines in chat. Nothing more unless asked.
- Reuben does not review code. You are responsible for getting it right.

## How work is organized

- Tasks are GitHub issues in `reubenbubu/lantern`. Phases are milestones.
- The main session coordinates and stays quiet. It picks the next issue, hands it to a
  subagent, and relays a short summary. It does not do task work itself.
- Each task runs in its own subagent, so its context is discarded when it finishes:
  1. Read the issue and the design sections it touches.
  2. Work on a branch named `issue-<number>-<short-slug>`.
  3. Build and test until everything passes.
  4. Open a PR that references the issue.
  5. A separate reviewer subagent reviews the PR diff. Give it the diff and the issue, not the
     implementer's reasoning. Fix what it finds.
  6. Squash-merge when checks and review pass. Leave a short summary on the issue and close it.
- Decisions that will matter later go in `docs/adr/NNNN-title.md`. Don't keep a running log
  file; issues and PRs are the history.

## When to stop and ask

Ask Reuben before:
- changing the design, or working around a part of it that looks wrong or incomplete
- changing public interfaces, the data model or the core fact schema
- adding a dependency
- anything that costs money, including real LLM API calls
- anything outside this repository
- force-pushing, rewriting history, or deleting branches you didn't create

Everything else, decide yourself. Note non-obvious choices in the PR description.

## Doing the work

- Do what the issue asks and nothing more. No extra features, no unrelated refactors. If you
  spot something else, open an issue for it.
- Work in small steps that each build.
- Match the style of the surrounding code.
- Never call something done without running the build and tests. Report failures as failures,
  with the output. Say plainly what you did not verify.

## Safety and cost

- No real LLM calls unless the issue explicitly asks for one. Tests use fakes or recorded
  responses.
- Secrets never go in code, commits, issues or PRs.
- Stay inside this repository and the workspace directories the design defines.

## Commands

To be filled in once the project skeleton exists.
