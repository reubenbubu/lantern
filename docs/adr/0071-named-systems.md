# 0071: One analysis covers one named system

Status: accepted (#253), amends ADRs 0016, 0018 and 0044, pending the user's review (#24)

## Context

A snapshot carried forward every repository any earlier run had analyzed (ADR 0018). So analyzing
project-0 and then project-4 into the same database gave a project-4 report that included
project-0's shop: 21 of its 37 lines, the context diagram's only line and the system's purpose. The
report's title was "System" (ADR 0070). The design now says that repositories belong to a named
system, that one analysis covers one system, that a snapshot carries forward only its own system's
repositories, that a repository can be removed, and that documents are titled by their system.
#250 left five things open, and this ADR decides them: how snapshot numbers and fact validity
relate to systems, whether a repository can be in two systems, where documents go, how a run
names its system and title, and how a repository is removed. The user confirmed the choices below
on #253.

## Decisions

- **Systems are stored.** The `systems` table holds each system's name, which follows the same
  rules as repository names, and its title, if one was given. A repository's `system` column names
  the system it belongs to, and a snapshot's `system` column names the system it was taken of
  (migration V16).
- **Snapshot numbers and fact validity stay global.** Snapshots are numbered 1, 2, 3 across all
  systems, and fact validity is still a range of those numbers (ADR 0017). A system's snapshot
  numbers therefore have gaps. Another system's facts can still hold at a snapshot's number, so
  every reader of a snapshot's facts now takes only those whose evidence names one of the
  snapshot's repositories:
  - `FactRepository.findValidAt(snapshot, repos)`;
  - the linker, which derives from those facts and records its facts with
    `recordDerived(snapshot, origin, repos, found)`, so it ends only derived facts about the
    snapshot's repositories, never another system's;
  - the Describer and the system's report.

  Per-system numbering would have put a system key into every fact version and every validity
  check. Since ADR 0018 a run already ends only the facts of the repositories it analyzed, so
  global numbers are enough.
- **A repository belongs to at most one system.** A run adds the repositories it names to its
  system. Naming a repository that belongs to another system is refused, and the message names
  the `remove` command. A library that two systems share can be registered under two names
  (`<name>=<path>`), one in each system, each with its own facts and builds. If one name were in
  both systems, each system's runs would end the facts the other system's snapshot needs, because
  a repository's facts depend on the other repositories in its snapshot (ADR 0006, 0007).
  Repository names stay global, because fact evidence uses them.
- **A snapshot belongs to its system.** `SnapshotStore.createCarryingForward(system, revisions)`
  carries forward only repositories that belong to the system, each at its commit in the latest
  snapshot of that system that analyzed it (ADR 0018), and tags the snapshot with the system. A
  repository analyzed only in another system's snapshots, or in snapshots of no system, is carried
  forward only once a snapshot of this system has analyzed it.
- **A refused run changes nothing.** The run checks that none of the repositories it names
  belongs to another system before it registers them or creates the system. So a refused run keeps
  the other system's repository path and settings, and creates no system. Once the check passes,
  membership is stored before the snapshot. A run that fails later (a build, the LLM) keeps the
  repositories it named in the system and the title it was given.
- **The command line.**
  - `analyze [--system=<name>] [--title=<title>] [<name>=]<path>...` analyzes repositories as one
    system. The `--opt=value` form is required, because Spring reads `--system <name>` as an option
    without a value followed by a repository path.
  - Without `--system`, the system is named after the folder all the repositories are in, for
    example `project-4`. If the repositories are not all in one folder, or that folder's name is
    not a valid system name, the run is refused and asks for `--system`.
  - `--title` stores the system's title for this and later runs; a blank title clears it.
  - `scripts/launch.sh` passes `--system` and `--title` through.
- **The title.** A system's documents carry its title:
  - the stored title, if one was given;
  - otherwise a title derived by `SystemTitle`, in this order:
    1. **README headings.** Take the first `# ` heading of each repository's root README
       (`README`, `README.md` or `README.markdown`, any case), read from git at the snapshot's
       commit. The title is the longest run of leading words that at least two of these headings
       start with, and at least half of the repositories that have one. Words are split at
       whitespace, `-`, `—` and `:` and compared ignoring case. The run is spelled as in the first
       such repository in sorted order. Of runs equally long, the one more headings share wins.
       project-4's `# APA-CRM Auth-Service`, `# APA-CRM — File-Service` and
       `# APA-CRM — Main-Service` give `APA-CRM`.
    2. **Repository names.** With two or more repositories, the words all their names start with
       (words split at `-`, `_` and `.`, compared ignoring case, spelled as in the first name in
       sorted order): `mediahub-api-gateway` and `mediahub-config-server` give `mediahub`.
    3. With one repository, its name.
    4. Otherwise the system's name.

  The rule is deterministic, and it uses only READMEs and names. A root `pom.xml`'s `<name>` was
  left out, because it usually names a module rather than the system.

- **Where documents go.** The system's report is the view `system:c4`, titled by the system's
  title, including a stored override of that view. The title also names the system element in
  every document of the run, so a repository's documents name the system they belong to. Their own
  titles stay the repository's name, as the design says. A stored override of the `system:c4` view
  is shared by all systems, since a view is layout, not knowledge; only its title is replaced by
  the system's. Documents are written to
  `<workspace>/documents/<system>/<snapshot>/`, under the same file names as before: `<repo>.html`,
  `<repo>.brief.html`, `<repo>.c4.html` and `_system.c4.html`. Since the system report's name
  starts with an underscore, a repository named like its system cannot clash with it.
- **Removing a repository.** `remove --system=<name> <repo>...` removes the named repositories from
  the system while holding the analysis lock. It takes no snapshot and needs no LLM, Docker or
  build. The next snapshot of the system does not carry them forward. Their stored facts, builds
  and earlier documents stay, but nothing reads the facts, since the system's snapshots no longer
  include those repositories. A removed repository may then join another system.
- **Existing data.** Repositories and snapshots stored before migration V16 belong to no system.
  Their snapshots stay as history, and no system carries them forward. A repository joins the
  first system that analyzes it. So, after the upgrade, the first run of each system must name all
  of that system's repositories once. Nothing is deleted.

## What this amends

- **ADR 0016:** step 2 takes the snapshot of the run's named system, and step 7 writes to
  `<workspace>/documents/<system>/<snapshot>/<view>.html`. The command gains `--system` and
  `--title`, and a `remove` command is added.
- **ADR 0018:** carrying forward is within one system, as ADR 0070 announced. "Removing a
  repository from the system is not supported yet" is replaced by the `remove` command. The linker
  now reads and ends only facts of the snapshot's repositories, instead of facts of every
  repository.
- **ADR 0044:** the system scope covers the snapshot's repositories, which are the named system's.
  The system is named by its title (above), no longer by its repositories, and its report is
  written to the system's folder. The entity ID stays `system:snapshot`: a snapshot now has exactly
  one system, and keeping the ID leaves documents and golden files unchanged. So anything stored
  about a system across snapshots, such as system-level annotations (Phase 3), must key on the
  system's name, not on this ID. When no title is
  passed to the composer (its own tests), it still names the system after its repositories.

## Consequences

- Analyzing project-0 and then project-4 into one database gives a project-4 report with nothing
  of project-0, and project-0's facts keep holding. `Project0EndToEndIT` checks both when
  `lantern.test.project4` names project-4's folder.
- A shared library analyzed in two systems is built and extracted once for each name.
- Documents written before this change stay in `<workspace>/documents/<snapshot>/`.

## Not in this issue

- Listing systems, and renaming or deleting one.
- Running only part of a system. The repositories the run does not name are carried forward, as
  before.
- A display name taken from code or annotations (Phase 3).
- Repositories without a Maven build (#254).
