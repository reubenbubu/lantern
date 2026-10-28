# 0004: The pinned OpenWMS test set and the fixture Maven projects

Status: accepted (#6), pending the user's review (#24)

## Context

`docs/design.md` (Open questions) makes OpenWMS the first target set: 15 repositories, five
services plus the libraries they need. Issue #6 asks for a script that clones them at pinned
commits into a gitignored workspace folder, a checked-in list of repositories and commits, and
small fixture Maven projects for tests. The design names the services but not the full list, the
commits, or where the workspace is.

## Decisions

- **The 15 repositories** are the five services and everything they build on that OpenWMS
  publishes as its own repository: `org.openwms.parent` (the shared parent POM),
  `common.service.lib`, `core.units`, `core.test.arch`, `core.uaa.lib`, `wms.receiving.lib`,
  `tms.routing.lib`, `tms.routing.osip` (the thin `tms.routing` service packages this, and it
  packages `tms.routing.lib`), `core.process.execution` (used by `tms.routing.lib`) and
  `ameba-lib`, which lives at `github.com/spring-labs/ameba-lib`. All are Apache-2.0. Together they
  are 14 MB, matching the design's measurement. `core.util` has no repository of its own and
  comes from Maven Central.
- **Pins** are full commit hashes, each the head of the default branch on 28 Oct 2026. They live
  in `testsets/openwms/repos.txt`, one `<name> <clone URL> <commit>` line per repository.
  Whether these commits build against each other is checked when the Maven extractor first builds
  them; a pin is moved by editing that file.
- **Workspace folder** is `workspace/` at the top of this repository, gitignored; the test set
  goes into `workspace/openwms/<name>`. The design's workspace storage holds repo clones, so this
  is the same place Lantern's own clones can go later.
- **The script** `testsets/openwms/clone.sh` fetches only the pinned commit (depth 1) and checks
  it out without a branch. Rerunning it leaves a clone at its pin untouched and moves one at
  another commit back to its pin. It never discards work: it stops on a clone with a different
  origin URL or uncommitted changes to tracked files, and keeps untracked files such as build
  output. A new clone is made in a temporary directory and moved into place when complete.
- **Fixtures** live in `lantern-extract/src/test/resources/fixtures`, since the Maven extractor
  is their first user: `multi-module` (a parent with `api` and `core`, `core` depending on
  `api`), `lombok` (code that compiles only with Lombok's annotation processor) and
  `lib-and-service` (a library and a separate thin service project that depends on it and
  repackages it into an executable jar with `spring-boot-maven-plugin`, like the OpenWMS
  services). They need no parent POM and pin plugin versions to those of Spring Boot 4.1.2, the
  version Lantern builds with; Lombok is 1.18.48, the version Spring Boot 4.1.2 manages.
- **Checking the fixtures build** is `FixtureBuildIT`, run by `./mvnw verify`. It builds a copy of
  each fixture with this repository's Maven wrapper, using an empty temporary local repository
  chained to the outer build's local repository as a read-only tail (`maven.repo.local.tail`,
  Maven 3.9). Fixture artifacts are therefore never installed into the user's `~/.m2`, and
  nothing is left behind. It needs network access for anything the outer build has not already
  downloaded (Lombok, about 2 MB).
