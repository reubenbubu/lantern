# 0003: Repository registry, snapshots and the analysis lock

Status: accepted (#5)

## Context

`docs/design.md` (Ingestion and build sandbox, Versioning and incremental computation) says repos
are registered by local path with optional settings, that a snapshot is a set of `repo@commit`
pairs with the vocabulary revision and tool version, and that only one analysis runs at a time.
Issue #5 implements these, so the details have to be fixed now. ADR 0001 requires snapshots to
have increasing numeric IDs, because fact validity is a range of snapshot numbers.

## Decisions

- **Git access** uses the `git` command line tool on the host rather than a Java git library, so
  no new dependency is needed. `git` must be installed where Lantern runs; the build sandbox needs
  it anyway. Lantern never prompts for credentials (`GIT_TERMINAL_PROMPT=0`).
- **Repository identity** is a name of letters, digits, `.`, `_` and `-`, starting with a letter
  or digit (`org.openwms.common.service`). It is the `repo` that fact evidence and snapshots refer
  to. The path must be the top of a git working tree and is stored as its real absolute path.
  Registering an existing name again replaces its path and settings. The clone URL and default
  branch the design mentions are not stored yet; no code needs them so far.
- **Settings** are an optional JDK version, an ordered list of Maven profiles and a list of
  modules to skip, stored as JSON in the `repositories` table.
- **Resolving** accepts anything git calls a revision (`HEAD`, a branch, a tag, a full or
  abbreviated hash) and returns the full commit hash (40 hex characters, or 64 for SHA-256
  repositories). Revisions starting with `-` are refused so they cannot act as git options.
- **Snapshots** are numbered 1, 2, 3 and so on in creation order without gaps; writers take a
  table lock, as vocabulary revisions do (0002). Every commit is resolved before anything is
  written, so a failed creation stores nothing. A snapshot records the latest vocabulary revision
  and the Lantern version, which the Maven build writes into `application.properties`
  (`lantern.version`). The number is the snapshot's name; there is no separate label. The
  annotation revision the design lists is added once annotations exist (Phase 3).
- **Analysis lock** is a PostgreSQL session-level advisory lock with one fixed key, taken with
  `pg_try_advisory_lock` on a connection borrowed from the app's pool and held for the whole
  analysis. A second start fails at once
  with `AnalysisAlreadyRunningException` ("analysis already running"), including from another
  Lantern process on the same database. If the process dies, PostgreSQL drops the connection and
  the lock with it. If releasing fails, the connection is aborted rather than returned to the
  pool, so it cannot be lent out still holding the lock. Borrowing from the pool can wait if the
  pool has no free connection; a dedicated connection can be added if that becomes a problem. Creating a snapshot does not take the lock by itself; the analysis run that
  will use both takes the lock first.
