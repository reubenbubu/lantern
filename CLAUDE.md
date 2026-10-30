# Lantern: instructions for the coding agent

Lantern analyzes a set of Java repositories and generates architecture documents from them.
The standard its output must meet is `docs/quality-bar.md`; how Lantern meets it is
`docs/design.md`, the only design document. Change them in the same PR as any code that
changes them. This file covers only how you work.

## Talking to the user

- Short, plain English. Lead with the point. No walls of text, few tables.
- Ask at most one or two questions at a time, each with your recommendation.
- Report each finished task in two or three lines in chat. Nothing more unless asked.
- The user does not review code. You are responsible for getting it right.

## How work is organized

- Tasks are GitHub issues in `reubenbubu/lantern`, grouped in milestones. A milestone delivers
  one specific outcome of a phase (named like `2C.2 Flows that tell the story`), and its
  description holds its own "Goal" and "Accepted when"; how many issues it has does not matter.
  One milestone is in progress at a time, unless the user says otherwise; work on it
  before issues of other milestones.
  `Housekeeping` (tooling, build and docs that change no output) and `Later` are standing
  buckets with no test drop and never close; `Later` holds what no milestone has taken yet.
- Every issue has a milestone and is written to a template in `.github/ISSUE_TEMPLATE/`:
  `fix.md` for what Lantern does wrong, `feature.md` for what it cannot do yet, chores and
  foundations (a test drop excepted).
- Every issue carries at least one area label, one for each part of Lantern it changes, set when
  it is opened and corrected when its scope changes. This list is the source; each label's
  description on GitHub is a short copy of its line.
  - `area:core`: facts, vocabulary, repository registry, snapshots and the document model
    (`lantern-core`)
  - `area:sandbox`: building repositories in containers (`lantern-sandbox`)
  - `area:extract`: the extractors (`lantern-extract`)
  - `area:link`: deployable units, the linker and the LLM resolver (`lantern-link`)
  - `area:llm`: providers, models, budgets and the LLM cache (`lantern-llm`)
  - `area:summarize`: summaries and wordings (`lantern-summarize`)
  - `area:compose`: the composer: views, flows and quality checks (`lantern-compose`)
  - `area:app`: the `analyze` run, wiring, database migrations and rendering documents
    (`lantern-app`)
  - `area:web`: templates, report specs and the frontend (`lantern-web`, `report-specs/`)
  - `area:testsets`: test sets, references, scoring and coverage, scripts included
  - `area:tooling`: build, local database, other scripts, architecture tests, issue templates
  - `area:docs`: docs-only issues: the bar, the design, ADRs, research notes and this file; an
    issue that also changes code carries its code areas instead
  - A test drop carries `area:testsets`.
- An issue is not ready until it has an "Accepted when": what Lantern's output must show, on
  which test sets (`docs/design.md`, Test sets), and the evidence that proves it. The chat that
  picks up an issue without one writes it first. A PR runs Lantern on those sets only; the
  milestone's test drop runs all development sets, and held-out ones only in milestone 2D.2.
- Held-out test sets (`docs/design.md`, Test sets) are only pinned: no session reads their code,
  analyzes them or looks at their output until milestone 2D.2, where they are scored and judged
  once.
- Each chat works on one issue at a time and does the work itself: design, code, tests, PR,
  merge and cleanup.
- Subagents get only low-impact work: read-only searches across many files, mechanical chores
  with no judgment (a rename, a doc or config tweak, cleanup), research (below), and the review
  in step 5. Use the cheapest model that fits: `haiku` for chores, `sonnet` for searches,
  medium-depth research and reviews of small diffs, `opus` for high-depth research and reviews
  of large or tricky diffs. Never use `fable`. If a subagent's result is wrong, retry one level
  up or do it yourself.
- Research comes before building where others have solved the problem before:
  - When: once at the start of each milestone, on its outcome, as the milestone's first issue;
    and before an issue that introduces a new pattern (its "Research" line says so), as that
    issue's first step. Routine fixes skip it.
  - What: a note `docs/research/<topic>.md` answers one question: what authorities, tools and
    papers say, with sources; evidence from the test sets where it matters; the options and
    their trade-offs; a recommendation; and what it changes in the bar or the design.
  - How deep: the chat picks one of two depths and the note states which, and why; a doubtful
    case takes high.
    - Medium, for practice that is well documented (how a framework or library is used, what
      its config means): the official documentation and one or two other sources, three to
      five pages read; a short note with one recommendation. `sonnet`.
    - High, for open questions (a milestone's outcome, a rule of the bar or the design, how to
      measure something, a choice between approaches): official documentation, tools that solve
      the same problem and research papers, at least eight pages read, options compared on
      their trade-offs; a full note. `opus`.
  - Sources: research searches the web and opens every page it cites (with WebFetch, or
    through the `web-fetch` agent where it has none) and reads it in full; a search snippet is
    only a lead. A source not opened is listed as not read and carries no weight in the
    recommendation; if the depth's count of pages read is not met, the chat retries or reads
    them itself. Code of the test sets is evidence of what occurs, never a substitute for the
    sources.
  - Who: a read-only `general-purpose` research subagent, at the depth's model, returns the
    note's text; the chat's brief names the depth and the rules above. The chat writes the
    note, judges it and decides. The rule it leads to goes into `docs/quality-bar.md`,
    `docs/design.md` or an ADR, in the same PR as the note, and links to the note; the note
    keeps the sources, options and trade-offs. Only a finding that changes direction (scope,
    goals, what the report is for) goes to the user, as one question with a recommendation.
- Steps for an issue:
  1. Pick an issue no other chat has taken (see Concurrent chats). Create its worktree first,
     before anything else: always a git worktree, never the main checkout, one per issue, on a
     branch named `<prefix>/<number>-<short-slug>`, e.g. `feature/12-maven-extractor`. The
     prefix is `feature/`, `bugfix/` or `chore/`, chosen by the issue type.
  2. Read the issue and the design sections it touches. Comment on the issue that work has
     started.
  3. Build and test until everything passes. While working, run the quick check and
     `./mvnw verify -pl <module> -am` for each module you changed (see Commands). Run the full
     check once, before opening the PR, and again only if later commits change code; a change
     only to `docs/` or `CLAUDE.md` skips it (step 6). Commits carry no `Co-Authored-By`
     trailer.
  4. Open a PR whose description says `Closes #<number>` and carries the evidence its issue's
     "Accepted when" names. A change to Lantern's output shows the scores before and after on the
     named test sets and an excerpt or screenshot of the generated document; until the scoring
     command exists (#252), excerpts before and after stand in for scores. A docs or chore issue
     names its own evidence. A PR without evidence is not mergeable.
  5. A reviewer subagent reviews the PR. It first reads `docs/quality-bar.md` and, of
     `docs/design.md`, Overview, Design principles, Phasing and the sections the issue
     touches. Give it the issue, the diff and the evidence, not your reasoning. It checks that
     the change is correct, that the evidence meets the "Accepted when", and that the change
     fits the design and moves Lantern's output toward the quality bar. It only reads; it runs
     nothing that builds or starts containers. Fix what it finds.
  6. Squash-merge when CI, review and evidence pass. CI is local, not on GitHub: it means the
     full check, `scripts/full-check.sh` (see Commands), and it must pass on the branch before
     merging. A change only to `docs/` or `CLAUDE.md` skips it: nothing in the build or the
     tests reads them.
     Leave a short summary on the issue and make sure it is closed.
  7. Clean up: remove the worktree, delete the local and remote branch, and stop or remove any
     containers, temp files or test data the task created. Leave the machine as you found it.
- Concurrent chats: several chats may work at once, each on its own issue. They coordinate
  through the workspace, not through messages:
  - An issue is taken when `git worktree list` shows a branch with its number. If no file in
    that worktree has changed for a day, ask the user before taking it over.
  - The local database is in use when `docker ps` shows it running under another chat's
    `COMPOSE_PROJECT_NAME`. Wait, or work on something that does not need it.
  - Two issues that touch the same files should not run together; pick another.
- Each chat keeps its issue in its own isolated environment, used by every command it or its
  subagents run:
  - its own worktree, as above
  - its own temp folder under the session scratchpad, named after its branch with `/` replaced
    by `-` (e.g. `<scratchpad>/feature-12-maven-extractor`), with `TMPDIR` and
    `java.io.tmpdir` pointed at it in every command that runs Maven or Java, since shell
    variables do not carry over between commands and an empty `java.io.tmpdir` makes Maven
    write its jansi native library into the working directory; never a shared scratch root,
    and it deletes only what it created
  - Docker containers, volumes and networks it starts by hand named `lantern-<number>-<purpose>`
    and started with `--label lantern.task=<number>`; compose runs with
    `COMPOSE_PROJECT_NAME=lantern-<number>`; cleanup removes only resources with that label or
    project, never by name pattern or an unfiltered `prune`. Containers that tests or the
    sandbox start clean up after themselves; report leftovers on the issue instead of removing
    them. Never delete the shared Maven cache folder `~/workspace/lantern-maven-cache`, which
    builds and tests reuse between runs.
  - no fixed host ports; publish to a random host port (`-p 127.0.0.1::5432`) and read it back
    with `docker port`. The local database (Commands) is the exception: its port is fixed, so
    only one chat at a time may run it.
- Each milestone (the standing buckets excepted) ends with one test drop, a short checklist for
  its outcome, which the user tests, tracked by an issue titled "Test drop <milestone>" in that
  milestone and linked from its description; the chat that finishes the milestone's last other
  issue opens it. When the milestone's other issues are merged, the chat that takes the
  test-drop issue writes the drop to
  `~/workspace/lantern-test-drops/<milestone>/`: per test set, Lantern's documents and the
  reference side by side, the scorecards (from 2D.2 also the judge's verdicts), and a README with
  what the milestone set out to do, a checklist of what to try, the known gaps, the reassessment
  of the test sets (`docs/design.md`, Test sets), and the commits and models used. Its path goes
  on the test-drop issue, and the user is told. The user's verdict, recorded on that issue,
  closes it and the milestone; what the user rejects reopens its issue or becomes a new one.
- Keep issues current. If scope changes, a task is blocked, or you find new work, update or
  open an issue right away. An issue's state must always match reality.

## One source of truth

Every piece of information lives in exactly one place. Link to it; never copy it.

| What | Where |
| --- | --- |
| The quality standard the output must meet, each rule with its key source or a link to its research note | `docs/quality-bar.md` |
| What to build to meet it | `docs/design.md` |
| How to work | This file |
| Tasks, status, progress | GitHub issues (written to `.github/ISSUE_TEMPLATE/`) and milestones |
| Decisions made while building | `docs/adr/NNNN-title.md` |
| Research behind a rule of the bar or the design: sources, options, trade-offs, test-set evidence | `docs/research/<topic>.md` |
| Document templates and specifications | `report-specs/` (one folder per document: template, schema, sample data) |
| Build and test commands | The Commands section below; runs, scoring and test-set commands in `.claude/skills/lantern-commands/SKILL.md` |

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

Everything else, decide yourself, concretely, by following `docs/quality-bar.md` and
`docs/design.md`. Nothing waits for the user's review: an ADR records the decision and the part of
the bar or design it follows, and is accepted as written. Only what the bar and design don't settle
and is on the list above goes to the user, as one direction-level question with your
recommendation. Note non-obvious choices in the PR description.

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
- Stay inside this repository and the workspace directories the design defines. Test sets live
  in `~/workspace/lantern-test-projects/`, test drops in `~/workspace/lantern-test-drops/`.

## Commands

Requires JDK 21, git and Docker with the `docker` CLI on the `PATH` (tests start PostgreSQL with Testcontainers, create temporary git repositories and run real build containers, which pull the Maven images and download from Maven Central, or from the Maven mirror below). The build also downloads Node into `lantern-web/target` and npm packages from the npm registry, so no local Node install is needed. Run from the repo root.

- Full check, building and testing everything (this is CI, about 3 minutes): `scripts/full-check.sh [<Maven argument>...]`. It runs the same tests as `./mvnw verify`, which also still works but takes twice as long: first `install -DskipITs` (compiles, unit and frontend tests, formatting check), then every module's integration tests at the same time, one Maven process per module, logged to `target/full-check/<module>.log`. Both steps use a local Maven repository of the checkout, `target/full-check/repository`, chained to the shared one, so the integration tests use the checkout's own modules and no other checkout's. On a machine with a local Maven proxy, set it up in Maven's settings first (the mirror bullet below), so nothing downloads from Maven Central.
- Quick check while working, without integration tests (under a minute): `./mvnw verify -DskipITs`. It runs the unit and frontend tests and the formatting check.
- Where `mvnd` (the Maven daemon) is installed, it can replace `./mvnw` in the quick check for speed (about 25 s instead of 36 s), not in the full check, whose parallel Maven processes it does not serve reliably. A daemon keeps the JDK, `java.io.tmpdir` and environment it started with and serves later builds from any folder, so it ignores a task's `TMPDIR` and `MAVEN_OPTS`: tasks running at the same time use `./mvnw`.
- Fix formatting: `./mvnw spotless:apply`
- Check formatting only: `./mvnw spotless:check`
- Test one module: `./mvnw verify -pl <module> -am`
- With Node 24 installed, regenerate the TypeScript types and run the frontend tests alone: `npm --prefix lantern-web ci && npm --prefix lantern-web test`
- With Node 24 installed, build the single-file HTML template and write a document model into it: `npm --prefix lantern-web run build && node lantern-web/scripts/render.mjs <document.json> <out.html>`
- Start the local database: `docker compose up -d` (stop with `docker compose down`). Inside a task, run both with `COMPOSE_PROJECT_NAME=lantern-<number>`; it publishes the fixed port 5432 (`LANTERN_DB_PORT` changes it), so only one task at a time.
- To download everything through one Maven mirror, such as a caching proxy on the local network (`http` and a private-network IPv4 address allowed; see `docs/adr/0030-maven-mirror.md`), set `lantern.sandbox.maven-mirror.id` and `.url` in a properties file outside the repository and add it to the app's: `--spring.config.additional-location=file:<local.properties>,file:<llm.properties>`. The tests that run sandbox builds use the mirror in the file named by the Maven property `lantern.test.properties` (an absolute path), set in Maven's settings, and Maven Central without one. `-Dlantern.test.properties=<absolute path of local.properties>` overrides it, and the `LANTERN_TEST_PROPERTIES` environment variable is used when it is not set. Never commit the mirror's address. The mirror's address must be one the build containers reach, not `localhost`: the host's private-network IPv4 address, which the proxy must listen on. To keep the rest of the build off Maven Central too (`docs/adr/0030-maven-mirror.md`, amendments for #212 and #225), mirror `*` to the proxy in `~/.m2/settings.xml` (host Maven and the tests' own dependencies), with an always-active profile there that sets `lantern.test.properties`; a plain `./mvnw verify` then needs no flags or environment variables. On the development VM the proxy is `http://localhost:8084/proxy` and the file is `~/workspace/lantern-local.properties`.
- The full check, like `./mvnw verify`, also runs project-0 end to end (about 50 s) when it is set up in `~/workspace/lantern-test-projects/project-0` (`testsets/project-0/setup.sh`, see the `lantern-commands` skill); it fails if that is not at the current pin, so run `testsets/project-0/setup.sh` again after the pin moves. Adding `-Dlantern.test.project4=<absolute path of project-4>` (with `-pl lantern-app -am -Dit.test=Project0EndToEndIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`) also analyzes project-4's Maven repositories into the same database and checks its report shows nothing of project-0 (`docs/adr/0071-named-systems.md`).
- Local runs, `analyze`, scoring, extraction coverage, the real-provider LLM test, test-set setup and the integration tests on the test sets: the `lantern-commands` skill, `.claude/skills/lantern-commands/SKILL.md`.
- Under rootless Docker, Testcontainers needs `TESTCONTAINERS_HOST_OVERRIDE=localhost` and `TESTCONTAINERS_RYUK_DISABLED=true` exported before running tests.
