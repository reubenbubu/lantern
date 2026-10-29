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
- Tasks may run in parallel only if each task has its own isolated environment, used by every
  subagent of that task that runs commands:
  - its own worktree, as above
  - its own temp folder under the session scratchpad, named after its branch with `/` replaced
    by `-` (e.g. `<scratchpad>/feature-12-maven-extractor`), with `TMPDIR` and
    `java.io.tmpdir` pointed at it; never a shared scratch root, and it deletes only what it
    created
  - Docker containers, volumes and networks it starts by hand named `lantern-<number>-<purpose>`
    and started with `--label lantern.task=<number>`; compose runs with
    `COMPOSE_PROJECT_NAME=lantern-<number>`; cleanup removes only resources with that label or
    project, never by name pattern or an unfiltered `prune`. Containers that tests or the
    sandbox start clean up after themselves; report leftovers on the issue instead of removing
    them. Never delete the shared Maven cache folder `~/workspace/lantern-maven-cache`, which
    builds and tests reuse between runs.
  - no fixed host ports; publish to a random host port (`-p 127.0.0.1::5432`) and read it back
    with `docker port`. The local database (Commands) is the exception: its port is fixed, so
    only one task at a time may run it.
  - reviewer subagents only read; they run nothing that builds or starts containers
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
| Research and evidence behind the design | `docs/research/` |
| Document templates and specifications | `report-specs/` (one folder per document: template, schema, sample data) |
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

Requires JDK 21, git and Docker with the `docker` CLI on the `PATH` (tests start PostgreSQL with Testcontainers, create temporary git repositories and run real build containers, which pull the Maven images and download from Maven Central). The build also downloads Node into `lantern-web/target` and npm packages from the npm registry, so no local Node install is needed. Run from the repo root.

- Build and test everything (this is CI): `./mvnw verify`
- Fix formatting: `./mvnw spotless:apply`
- Check formatting only: `./mvnw spotless:check`
- Test one module: `./mvnw verify -pl <module> -am`
- With Node 24 installed, regenerate the TypeScript types and run the frontend tests alone: `npm --prefix lantern-web ci && npm --prefix lantern-web test`
- With Node 24 installed, build the single-file HTML template and write a document model into it: `npm --prefix lantern-web run build && node lantern-web/scripts/render.mjs <document.json> <out.html>`
- Start the local database: `docker compose up -d` (stop with `docker compose down`). Inside a task, run both with `COMPOSE_PROJECT_NAME=lantern-<number>`; it publishes the fixed port 5432, so only one task at a time.
- Sandbox builds download only from the Maven repositories in `lantern.sandbox.maven-repositories` (Maven Central by default; see `docs/adr/0023-configurable-maven-repositories.md`). To allow others, list them all in a properties file and add it to the app's: `--spring.config.additional-location=file:<repositories.properties>,file:<llm.properties>`.
- To download everything through one Maven mirror instead, such as a caching proxy on the local network (`http` and a private-network IPv4 address allowed; see `docs/adr/0030-maven-mirror.md`), set `lantern.sandbox.maven-mirror.id` and `.url` in a properties file outside the repository and add it to the app's the same way. The tests that run sandbox builds use the mirror in the file named by `-Dlantern.test.properties=<absolute path of local.properties>` (or the `LANTERN_TEST_PROPERTIES` environment variable), and Maven Central without one, e.g. `./mvnw verify -Dlantern.test.properties=<absolute path of local.properties>`. Never commit the mirror's address.
- Run the real-provider LLM test (costs money; only with the user's go-ahead), with `ANTHROPIC_API_KEY` already exported: `LANTERN_LLM_REAL_TEST=true LANTERN_LLM_REAL_TEST_MODEL=<model> ./mvnw verify -pl lantern-llm -am`
- Run the app (needs the local database): `./mvnw install -DskipTests && ./mvnw spring-boot:run -pl lantern-app`
- Analyze repositories end to end, one command for register, snapshot, build, extract, summarize, compose and render (needs the local database, Docker and the LLM settings of `docs/adr/0009-llm-configuration.md` in a properties file outside the repository; a real model costs money, so only with the user's go-ahead): `./mvnw install -DskipTests && java -jar lantern-app/target/lantern-app-*-exec.jar --spring.config.additional-location=file:<llm.properties> analyze [<name>=]<path>...`. It writes `workspace/documents/<snapshot>/<repo>.html` (Architecture Explorer), `<repo>.brief.html` (Executive Brief) and `<repo>.c4.html` (the `report-specs/c4-model-sequence-diagrams` report); see `docs/adr/0016-end-to-end-analysis-run.md` and `docs/adr/0031-report-spec-renderer.md`.
- Run that pipeline twice on any Maven repository with the fake model, checking the second run makes no LLM calls, and write the calls and estimated tokens a real run would use to `lantern-app/target/end-to-end/`: `./mvnw verify -pl lantern-app -am -Dlantern.test.repo=<absolute path> -Dit.test=EndToEndIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
- Set up the synthetic test set project-0 at its pinned commit (private repository, so git needs GitHub credentials; see `docs/adr/0029-synthetic-test-set-project-0.md`), then build each of its repositories in the sandbox: `testsets/project-0/setup.sh && ./mvnw verify -pl lantern-sandbox -am -Dlantern.test.project0=$HOME/workspace/lantern-test-projects/project-0 -Dit.test=Project0BuildIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`
- Under rootless Docker, Testcontainers needs `TESTCONTAINERS_HOST_OVERRIDE=localhost` and `TESTCONTAINERS_RYUK_DISABLED=true` exported before running tests.
