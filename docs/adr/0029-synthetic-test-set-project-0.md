# 0029: The synthetic test set project-0, and the testsets/ format

Status: accepted (#115), pending the user's review (#24)

## Context

The four real test sets (#88) leave gaps: no JMS, Liquibase or RestTemplate, few meta-annotations,
profile-gated links or unresolvable values. Issue #115 adds a small made-up system whose only job
is to exercise Lantern, so tests can check what Lantern finds against an exact answer. The user
decided it lives outside this repository, in one private GitHub repository with one folder per
made-up repository and a script that turns each folder into a local git repository with fixed
author and dates, and that Lantern pins that repository's commit.

## Decisions

- **The source** is the private repository
  [`reubenbubu/lantern-project-0`](https://github.com/reubenbubu/lantern-project-0), written for
  Lantern by its owner; it has no third-party code and no licence file, since it is not published.
  Its README lists every case it covers with an id (`MSG-2`, `HTTP-4`, ...), where it is, and what
  Lantern should find. That README is the one place for the case list; tests name the case id
  they check.
- **The made-up system** is an online shop of five small Maven repositories, `catalog-lib`,
  `catalog`, `storefront`, `payments` and `fulfilment`, downloading only from Maven Central. The
  source's README says which case lives where.
- **The thin service and its library are two repositories** (#155), so the set also checks
  building in dependency order (ADR 0041): `catalog` uses a parent POM and a library that
  `catalog-lib` builds at a `-SNAPSHOT` version published nowhere, so it builds only when given
  `catalog-lib`'s build (case `BUILD-1`). Until #118 they were two modules of one repository. (ADR 0041 now does;
  adding such a case to the source repository is described on #118.)
- **Fixed commits.** `setup.sh` in the source makes each local repository with one commit whose
  author, committer, dates (2026-01-15) and message are fixed, ignoring the user's git
  configuration, so the hashes are the same on every run and machine. Changing a made-up repository
  changes its hash, and its expected answers move in the same Lantern change as the pin.
- **Where it goes.** `~/workspace/lantern-test-projects/project-0/<repo>`, next to the real sets
  (#88), unless a destination is given.
- **The testsets/ format,** for this set and the real ones #88 adds: `testsets/<set>/` holds
  - `pins.txt`: one `<name> <clone URL> <full commit hash>` line per git repository the set is
    made from (for project-0, the one source repository); a pin is moved by editing its hash;
  - `setup.sh [destination]`: puts the set's repositories at their pins under
    `~/workspace/lantern-test-projects/<set>/` or the destination; safe to run again;
  - `lantern.properties`, only when the set needs Lantern settings, such as Maven repositories
    besides Maven Central (ADR 0023). project-0 needs none.
- **Proof.** `Project0BuildIT` (lantern-sandbox) checks each project-0 repository is at the
  commit `setup.sh` makes from the pin, builds them in the sandbox in dependency order, each given
  the builds of those it uses, and checks each succeeds on the JDK its POM asks for, that `catalog`
  had `catalog-lib`'s build and its jar on the classpath, and that `catalog` built alone fails
  (`BUILD-1`); moving the pin moves those commits in the same change. It runs only when
  `lantern.test.project0` names the set-up folder (see Commands in `CLAUDE.md`). Checks of the facts, links, views and
  flows Lantern should find belong to the extractor, linker and composer issues (#92, #94, #113,
  #114), which read the case list.
