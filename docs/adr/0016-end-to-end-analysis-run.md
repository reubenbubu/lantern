# 0016: End-to-end analysis run

Status: accepted (#17), amended (#100, #58, #245, #254), amended by ADR 0031 (#112), ADR 0044 (#95) and ADR 0071 (#253), amended for #369

## Context

Issue #17 asks for one command that runs the whole Phase 1 pipeline on a repository (register,
snapshot, build, extract, summarize, compose, render), proven on OpenWMS tms.transportation, with
a second run over unchanged code making no LLM calls. `docs/design.md` (Hosted service) has
analysis run as persisted, resumable jobs in the service; nothing schedules or persists jobs yet.
The design also leaves open where workspace storage lives, how stored work is reused between
runs before incremental runs exist (Phase 3), and how the Java side renders the HTML template
(ADR 0013 fixes only the contract).

## Decisions

- **The command is the app itself:** `java -jar lantern-app/target/lantern-app-<version>-exec.jar
  analyze [<name>=]<path>...` (see Commands in `CLAUDE.md`). Each path is the top of a git working
  tree; without a name a repository is named after its directory. Settings come from the usual
  Spring sources, so `--lantern.llm...` options or a properties file outside the repository
  configure the LLM (ADR 0009). The app prints what the run did (builds, facts, summaries, LLM
  calls, tokens and cost, the files written) and exits with 0, or 1 with the reason. Started
  without `analyze` it does nothing different from before. The run happens in the command's
  process (`AnalysisRun`); persisted, resumable jobs are a later step (#56).
- **Order and reuse.** The run first gets the LLM client, so an unconfigured LLM fails at once
  before any work, then takes the analysis lock (ADR 0003) and:
  1. registers each repository, keeping the settings of one registered before;
  2. creates a snapshot of each repository's checked-out commit (`HEAD`), carrying every other
     repository an earlier snapshot analyzed forward at its last analyzed commit (ADR 0018);
  3. builds the repositories in the sandbox, in parallel up to the sandbox's limit, reusing a
     stored successful build of the same `repo@commit` and building a failed one again (see the
     amendment for #58). Each build comes after those of the repositories whose modules it uses
     and gets their artifacts, those of carried-forward repositories included (ADR 0041). To
     rebuild a successful build, delete its directory under `builds/`.
     Reuse is keyed by
     `repo@commit` alone; the design's key also has the JDK image and `settings.xml` hash, which
     matter once images or settings change (#31);
  4. runs the Maven and Java extractors over all repositories of the snapshot and stores the
     analyzed repositories' facts in one transaction (ADR 0018);
  5. summarizes with the `SUMMARIZE` role's model through the cached summarizer (ADR 0010);
  6. composes an Architecture Explorer document (view `<repo>`), an Executive Brief (view
     `<repo>.brief`) and, since ADR 0031, a C4 report (view `<repo>.c4`) per repository, and since
     ADR 0044 a C4 report of the whole system (view `system:c4`, file `_system.c4.html`). A view
     definition stored to override the default is used as stored; otherwise the default, which
     is not stored (ADR 0012, 0015, 0047);
  7. renders each as one HTML file, `<workspace>/documents/<snapshot>/<view>.html`.
- **A fact found again stays as stored.** Extractors give facts the new snapshot as the start of
  their validity, while the same fact (same ID) is stored from an earlier snapshot. The run records
  its facts as the snapshot's static facts, and the docs extractor's as its `readme` facts (ADR 0017, #165): a fact that still holds with the same
  confidence keeps its stored validity, and facts of either kind of the analyzed repositories no longer found stop holding (ADR 0018). The run
  composes from the facts its extractors found, as stored, so the documents of two runs over the
  same commits differ only in their snapshot number.
- **Zero LLM calls on a second run** follows from the above: fact IDs include the commit and
  leave out validity (ADR 0001), so unchanged commits give the same summary cache keys and every
  summary is reused, except units left without a summary, which are tried again (ADR 0010, #76).
  This also holds when a stored failed build is built again (amendment for #58) and fails the same
  way.
- **Workspace storage** is `lantern.workspace` (environment `LANTERN_WORKSPACE`, default
  `workspace` in the working directory, which the repository ignores, next to the OpenWMS
  clones). Builds go to `builds/` (ADR 0005), documents to `documents/`. The shared Maven cache
  directory is `lantern.sandbox.maven-cache-dir` (default `~/workspace/lantern-maven-cache`, ADR
  0005), and the Maven
  repositories builds may use are `lantern.sandbox.maven-repositories` (ADR 0023); other sandbox
  limits keep their defaults.
- **The sandbox stages outputs next to the builds directory** instead of the system temporary
  directory, because moving a directory across filesystems fails (#35); `/tmp` is often a
  separate filesystem.
- **Java renderer.** `HtmlRenderer` in `lantern-app` reads the template from the `lantern-web`
  jar (now a dependency of the app) and follows ADR 0013: the document JSON is concatenated into
  the placeholder with every `<` written as the JSON escape `\u003c`.
- **Budgets.** Only the monthly budget applies (ADR 0009); run budgets and a cost estimate before
  a run are #42. The run reports its calls, tokens and cost from the stored call log, counting
  every call logged while it ran; nothing else uses the LLM yet, so these are the run's own.
- **Proof.** `EndToEndIT` runs the pipeline twice with a fake model and real sandbox builds: always
  on the `multi-module` fixture, and on tms.transportation when the OpenWMS clones are given. It
  checks both documents are written, that the second run reuses the build, every fact and every
  summary, makes no LLM request and logs no call, and that its documents equal the first run's
  apart from the snapshot number. The OpenWMS run also writes the calls and estimated tokens a
  real run would use to `lantern-app/target/end-to-end/`.

## Not in this issue

The real-LLM acceptance run on tms.transportation, which needs the user's go-ahead; persisted,
resumable jobs and progress in the UI (#56); run budgets and the
estimate before a run (#42); parallel LLM calls (#44).

## Amendment (#100): the second repository is any repository

OpenWMS is no longer a test set (#100). Besides the `multi-module` fixture, `EndToEndIT` now runs
on the Maven repository that `lantern.test.repo` names, as an absolute path, when it is set,
instead of on OpenWMS tms.transportation. A relative path is rejected. The repository is
registered as `given-<directory>`, so its name cannot clash with a fixture's, and the calls and
estimated tokens a real run would use go to
`lantern-app/target/end-to-end/given-<directory>-llm-calls.txt`. The new test sets (#88) provide its input.

## Amendment (#58): a stored failed build is built again

A run reuses a stored build only if it succeeded. A stored failed build is built again, once per
run: a repository is built at most once in a run, so a build that keeps failing costs one build per
run and no more. A build counts as failed whatever the cause (ADR 0005): any module failing, a
timeout, or a stop on a resource limit such as memory or disk. A build that keeps timing out
therefore costs up to the build timeout on every run. If the sandbox itself cannot run the
rebuild (a `SandboxException`, for example Docker being unavailable), the run logs a warning and
uses the stored failed build, as it did before. Repositories carried forward are not analyzed, so
they are not built; the extractors see their stored build, failed or not.

When this ADR was first decided, a stored fact's confidence could not change, so a successful
rebuild of a failed build would have made the run fail. Since ADR 0017 a confidence change starts a
new fact version, so rebuilding is safe. A failure may be transient (a download that failed) or
fixed by a change the build key does not cover, such as the Maven repositories of ADR 0023 (#30),
and reusing it would keep the repository in degraded mode until its directory under `builds/` was
deleted by hand. A failure that comes from broken code fails again, at the cost of a build per run;
no setting turns rebuilding off, since none was needed yet.

## Amendment (#245): every call is counted under its role

The run's report showed its summaries and wordings but not the links left open that the LLM
resolver (ADR 0045) asked about, so on project-2 its 478 logged calls did not add up to the 473
summaries and wordings it listed. The report now says how many open links the LLM was asked about,
whatever it answered, and how many answers were reused, and splits the "LLM calls" line by role from the stored call log
(`LLM calls: 478 (SUMMARIZE=449, RESOLVE=5, INFER_INTENT=17, COMPOSE=7), ...`). Retried attempts
and answers rejected twice stay in their role's count (ADR 0021), so only with no retries or
rejections do a role's calls equal what the run asked of it; `EndToEndIT` checks that equality
with the fake model.

## Amendment (#254): a repository without a Maven build is not built

A repository needs no Maven build (design, Repository registry). Before this, step 3 sent every
repository to the sandbox, which stored a failed build for one without a `pom.xml` (ADR 0005), so
it was analyzed in degraded mode and reported as such. Now step 3 first asks
`BuildSandbox.hasMavenBuild`: whether the repository has a `pom.xml` file at its top at the
snapshot's commit. A repository without one is not built and gets no build, not even a stored
failed one from an earlier run; nothing waits for it, since it builds no Maven artifact another
repository could use. The extractors then see it without a build, as they see a carried-forward
repository that has none: the docs and config extractors read its files from git, and the Maven
and Java extractors find no POMs or Java modules in it. A carried-forward repository without one is
likewise given no build. Its facts are not lower-confidence, since nothing failed. `hasMavenBuild`
reads the commit's top folder with `git ls-tree`: only a missing `pom.xml` means no Maven build;
git failing to read the commit fails the run instead of quietly skipping the build.

The run's result lists these repositories apart from its builds (`withoutBuild`), and `analyze`
prints each as `<repo>@<commit>: analyzed without a build (no pom.xml)`, with no degraded-mode
warning. A repository without a top `pom.xml` that has what only a Maven build would let Lantern
read (Java sources, Gradle build files, or POMs below the top) is treated the same, but its code is
not analyzed: the Java extractors find modules only from a top `pom.xml`, and Gradle is not
supported. The run logs a warning and lists it in `codeNotAnalyzed`, and `analyze` prints
`<repo>@<commit>: warning: has <what> but no top-level pom.xml: its code is not analyzed` instead
of the line above, so a reader does not take its documents for an analysis of its code. Only the top `pom.xml` counts, as the sandbox builds from there: a repository with POMs
only in subfolders still has no Maven build, and gets the warning. The sandbox itself is unchanged: asked to build a
repository without a `pom.xml`, it still stores a failed build (ADR 0005).

## Amendment (#369): steps 2 and 7's carrying forward is within one named system, and a repository can be removed from it (ADR 0071).
