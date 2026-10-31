# 0029: The synthetic test set project-0, and the testsets/ format

Status: accepted (#115)

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
  `catalog-lib`'s build (case `BUILD-1`). Until #155 they were two modules of one repository.
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
    `~/workspace/lantern-test-projects/<set>/` or the destination; safe to run again; for
    project-0 it records the pin in `<destination>/.lantern-pin` (#167);
  - `lantern.properties`, only when the set needs Lantern settings, such as Maven repositories
    besides Maven Central (ADR 0023). project-0 needs none.
- **Proof.** `Project0BuildIT` (lantern-sandbox) checks the set-up folder was set up from the pin
  in `pins.txt` (its `.lantern-pin`), reads each repository's commit from the repository, builds
  them in the sandbox in dependency order, each given the builds of those it uses, and checks each
  succeeds on the JDK its POM asks for, that `catalog` had `catalog-lib`'s build and its jar on
  the classpath, and that `catalog` built alone fails (`BUILD-1`). Like every project-0 test
  (#177), it names no commit: `pins.txt` is the one place, so moving the pin is that one edit plus
  the expected answers that move with it. It runs only when `lantern.test.project0` names the
  set-up folder (see Commands in `CLAUDE.md`). Checks of the facts, links, views and flows Lantern
  should find belong to the extractor, linker and composer issues (#92, #94, #113, #114), which
  read the case list.
- **End to end in every build** (#167). `Project0EndToEndIT` (lantern-app) runs the analyze
  pipeline with the fake model over all project-0 repositories as one snapshot, twice, and checks
  every repository builds and gets its documents, the second run reuses every build, fact and
  summary and makes no LLM call, and the system's C4 report (ADR 0044). Unlike the other project-0
  tests it needs no property: plain `./mvnw verify` runs it when project-0 is set up in the default
  folder, and skips it, with the reason, when that folder does not exist, so the build passes on a
  machine without the private set. `lantern.test.project0` points it at another folder. Its commits
  come from `pins.txt` alone: `setup.sh` records the pin it set the folder up from in
  `<destination>/.lantern-pin`, the test fails, saying to run `setup.sh`, when that is not the
  current pin, and it reads each repository's commit from the repository. It adds about 50 seconds.

## Amendment (#251): the open-source sets, their references and the held-out sets

- **Format for project-1 to project-7.** `testsets/project-N/pins.txt` lists every repository with
  its clone URL and full commit hash, as for project-0. `setup.sh [destination]` is one line that
  calls the shared `testsets/setup-pins.sh`: it clones each repository to
  `~/workspace/lantern-test-projects/project-N/<name>` (or the destination) if missing, checks the
  checkout's `origin` is the pinned URL, fetches the pin and checks it out detached; safe to run
  again. Which sets are development and which are held-out is in `docs/design.md`, Test sets; the
  held-out ones were pinned with `git ls-remote` alone and their `setup.sh` header says so.
- **References.** `testsets/project-N/reference/` holds `reference.c4.json` (the report spec's
  data), `facts/` (the cited `file:line` facts), `notes.md` (what it describes, scope choices),
  `verification.md` (the independent second pass) and `build-data.mjs`, which writes the JSON from
  the facts. The rendered `reference.c4.html` is not committed: `testsets/reference-tools/embed.cjs`
  embeds the JSON into the spec's template and `render.mjs` renders it in headless Chrome. The
  schema and cross-reference checks are `lantern-web/scripts/report-spec-check.mjs`, the one
  implementation, which also replaces the per-set `validate.cjs` copies.
- **Proof.** `lantern-web/test/test-set-references.test.mjs`, part of `./mvnw verify` and needing
  no network or checkout, checks for each reference that it is valid for the spec, that every
  `repo` and `commit` its sources cite is a repository and a prefix of the commit in `pins.txt`,
  and that `notes.md` names each pinned commit in full. Moving a pin therefore fails the build
  until the reference is redone.

## Amendment (#285): project-8, FleetCare

- **The source** is the private repository
  [`reubenbubu/lantern-test-fleetcare`](https://github.com/reubenbubu/lantern-test-fleetcare), laid
  out like project-0's: one folder per made-up repository (15), a `setup.sh` that makes each a local
  git repository with one fixed commit (2026-02-01), and a README that lists every case with its
  place and exact answer. `testsets/project-8/pins.txt` pins its one commit, and
  `testsets/project-8/setup.sh` works like project-0's, recording the pin in `.lantern-pin`.
- **Its expected answers stay in its source.** FleetCare's `expected/reference/` holds the
  reference in the format of the open-source sets' (amendment for #251: `reference.c4.json`,
  `aliases.json`, `facts/`, `notes.md`, `verification.md`, `build-data.mjs`), and its citations name
  the commits its `setup.sh` makes, which are not in `pins.txt`. It is written and checked with the
  code it describes, so it is not copied into Lantern: `setup.sh` copies it to
  `<destination>/expected/`, and moving the pin moves code and answers together.
- **Scoring.** `scripts/score-testsets.sh project-8` analyzes every git repository of the checkout as
  one system and scores the report against `<destination>/expected` (`score.mjs` reads its
  `reference/`), after checking the checkout's `.lantern-pin` is the pin. The scorecard of a
  fake-model run is committed as `testsets/project-8/baseline.scorecard.md`, the baseline no change
  may lower (`docs/design.md`, Test sets); it is rewritten in the change that moves the pin or
  changes what Lantern finds on the set.
- **Proof.** `lantern-web/test/test-set-references.test.mjs` checks, in every build, that
  `pins.txt` is well formed and, where project-8 is set up in the default folder, that its
  `.lantern-pin` is the pin and its reference is valid for the report spec; without the private set
  it skips that check, as `Project0EndToEndIT` does.
