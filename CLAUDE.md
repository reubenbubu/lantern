# Lantern: instructions for the coding agent

Lantern analyzes a set of Java repositories and generates architecture documents from them.
What to build is in `docs/design.md`, the only design document. Change it in the same PR as
any code that changes the design. This file covers only how you work.

## Talking to the user

- Short, plain English. Lead with the point. No walls of text, few tables.
- Ask at most one or two questions at a time, each with your recommendation.
- Report each finished task in two or three lines in chat. Nothing more unless asked.
- The user does not review code. You are responsible for getting it right.

## How work is organized

- Tasks are GitHub issues in `reubenbubu/lantern`. Phases are milestones.
- The main session coordinates and stays quiet. It picks the next issue, hands it to a
  subagent, and relays a short summary. It does not do task work itself.
- Each task runs in its own subagent, so its context is discarded when it finishes:
  1. Read the issue and the design sections it touches. Comment on the issue that work has
     started.
  2. Always work in a git worktree, never in the main checkout. One worktree per issue, on a
     branch named `<prefix>/<number>-<short-slug>`, e.g. `feature/12-maven-extractor`. The
     prefix is `feature/`, `bugfix/` or `chore/`, chosen by the issue type.
  3. Build and test until everything passes. Commits carry no `Co-Authored-By` trailer.
  4. Open a PR whose description says `Closes #<number>`.
  5. A separate reviewer subagent reviews the PR diff. Give it the diff and the issue, not the
     implementer's reasoning. Fix what it finds.
  6. Squash-merge when CI and review pass. CI is local, not on GitHub: it means
     `./mvnw verify` (see Commands), and it must pass on the branch before merging. Leave a
     short summary on the issue and make sure it is closed.
  7. Clean up: remove the worktree, delete the local and remote branch, and stop or remove any
     containers, temp files or test data the task created. Leave the machine as you found it.
- Keep issues current. If scope changes, a task is blocked, or you find new work, update or
  open an issue right away. An issue's state must always match reality.

## One source of truth

Every piece of information lives in exactly one place. Link to it; never copy it.

| What | Where |
| --- | --- |
| What to build | `docs/design.md` |
| How to work | This file |
| Tasks, status, progress | GitHub issues and milestones |
| Decisions made while building | `docs/adr/NNNN-title.md` |
| Build and test commands | The Commands section below |

No running logs, plan files, TODO lists or notes outside these places. If two places disagree,
fix the wrong one in the same change.

## When to stop and ask

Ask the user before:
- changing the design, or working around a part of it that looks wrong or incomplete
- changing public interfaces, the data model or the core fact schema
- adding a dependency not already named in the design or the issue
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

Requires JDK 21, git and Docker (tests start PostgreSQL with Testcontainers and create temporary git repositories). Run from the repo root.

- Build and test everything (this is CI): `./mvnw verify`
- Fix formatting: `./mvnw spotless:apply`
- Check formatting only: `./mvnw spotless:check`
- Test one module: `./mvnw verify -pl <module> -am`
- Start the local database: `docker compose up -d` (stop with `docker compose down`)
- Clone the OpenWMS test set at its pinned commits into `workspace/openwms` (safe to rerun): `testsets/openwms/clone.sh`
- Run the app (needs the local database): `./mvnw install -DskipTests && ./mvnw spring-boot:run -pl lantern-app`
