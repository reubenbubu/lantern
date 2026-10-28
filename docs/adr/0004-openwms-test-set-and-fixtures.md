# 0004: The pinned OpenWMS test set and the fixture Maven projects

Status: accepted (#6), amended by ADR 0020 (#72) and ADR 0026 (#86), pending the user's review (#24)

## Context

`docs/design.md` (Open questions) made OpenWMS the first target set: 15 repositories, five
services plus the libraries they need (that entry now lives in the amendment at the end). Issue
#6 asks for a script that clones them at pinned commits into a gitignored workspace folder, a
checked-in list of repositories and commits, and small fixture Maven projects for tests. The
choice named the services but not the full list, the commits, or where the workspace is.

## Decisions

- **The 15 repositories** (16 since ADR 0020) are the five services and everything they build on that OpenWMS
  publishes as its own repository: `org.openwms.parent` (the shared parent POM),
  `common.service.lib`, `core.units`, `core.test.arch`, `core.uaa.lib`, `wms.receiving.lib`,
  `tms.routing.lib`, `tms.routing.osip` (the thin `tms.routing` service packages this, and it
  packages `tms.routing.lib`), `core.process.execution` (used by `tms.routing.lib`) and
  `ameba-lib`, which lives at `github.com/spring-labs/ameba-lib`. All are Apache-2.0. Together they
  are 14 MB, matching the measurement in the amendment below. ADR 0020 adds the umbrella repository
  `org.openwms`, which holds `core.util`.
- **Pins** are full commit hashes in `testsets/openwms/repos.txt`, one
  `<name> <clone URL> <commit>` line per repository; a pin is moved by editing that file. Which
  commit each repository is pinned to is decided in ADR 0020.
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

## Amendment (#86): choice and size, moved from the design

Moved here from `docs/design.md` (Open questions) by ADR 0026, which keeps the design free of
test-set detail.

- **Why OpenWMS.** [OpenWMS](https://openwms.github.io/org.openwms) is Apache-2.0, Spring Boot
  4.1 and Maven, with one repository per service, a shared `org.openwms.parent` POM, the in-house
  `ameba-lib`, and AMQP between services. It has five services (`common.service`,
  `tms.transportation`, `tms.routing`, `wms.receiving`, `core.uaa`) and about ten shared libraries.
- **Size.** Measured from shallow clones on 27 Oct 2026: 15 repositories (16 since ADR 0020),
  958 Java files, about 57,000 non-blank lines of main code and 13,400 of test code, 14 MB on disk.
- **Thin services.** Four of the five service repositories are thin (52 to 112 lines each) and
  mainly package their `.lib` artifact, so a service and its library are one deployable (ADR 0006).
- **Host.** The existing development VM (20 cores, 37 GB RAM with about 19 GB free, 106 GB free
  disk, Docker 29.9) is enough for this set, with up to 4 build containers at 4 GB each.
