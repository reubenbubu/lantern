# 0057: A fact sheet in module and repository prompts

Status: accepted (#197), amends ADR 0010

## Context

A module's prompt shows its own facts and its parts' summaries, and a repository's shows only its
modules' summaries (ADR 0010). Each level is written from the one below, so specifics such as
endpoints, called services and databases wash out on the way up, and the top levels drift to text
that would be true of any Spring application. Issue #197 asks for a short fact sheet beside the
child summaries, within the existing citation rules. The sheet must keep what ADR 0055 set up: the
prompt holds no fact IDs or commits and lists facts in an order that does not depend on them, and
a cache hit is rebound by position.

## Decisions

- **Modules and repositories get a fact sheet; types and packages do not.** A type's prompt shows
  its facts and source file, and a package's parts are types, whose summaries are written from
  those facts one level below.
- **The sheet lists facts about the unit's parts at any depth**, in four groups, each with the
  predicates it takes, in this order:

  | Group | Predicates |
  | --- | --- |
  | Entry points | `main-class`, `endpoint`, `message-listener`, `scheduled` |
  | Links | `CALLS_HTTP`, `SENDS_MESSAGES_TO`, `PUBLISHES`, `CONSUMES`, `USES_BROKER`, `USES_INFRASTRUCTURE`, `http-call`, `message-send`, `http-client` |
  | Data stores | `USES_DATABASE`, `ACCESSES_DATA_OF`, `datasource`, `MAPS_TO`, `READS_TABLE`, `WRITES_TABLE` |
  | Technology | `technology`, `deployable-unit`, `java-release` |

  The linked facts about a deployable unit are already facts of each module it contains (ADR
  0010), so a repository's sheet picks them up from its modules. Facts the unit's own facts list
  are left out. Third-party dependencies (`BUILD_DEPENDS_ON` artifacts) and stereotypes are not
  listed: there are many, and module prompts already show their own dependencies.
- **Short and deterministic.** Each group lists at most 10 facts. Within a group facts are ordered
  by predicate, in the order of the table, then as a unit's facts are (ADR 0055: by the fact as
  the prompt writes it, then where it was observed, and last by ID), and the first 10 are kept; a
  group cut short says so in its heading (`Entry points (10 of 34)`). The predicate order puts
  the links between units ahead of the calls in code that make them.
- **Cited as facts.** The sheet is a section after the unit's own facts, with a heading per group,
  numbered on from them (`[F6]`, ...), so the citation check, the stored `[fact:...]` markers and
  the composer are unchanged. `Summary.inputFacts` lists the unit's own facts and then the sheet's,
  in prompt order, so a cache hit is rebound by position as before. A unit too large for one
  prompt packs the sheet's facts into its parts after its own (ADR 0024).
- **The system prompt says what the sheet is for**: to say specifically what the unit offers and
  works with, rather than what would be true of any application.
- **The cache key follows from the prompt** (ADR 0055): a module or repository is summarized
  again when a fact its sheet shows changes, even if no part's summary reads differently. This
  is a step back from ADR 0010's rule that a unit changes only when its own facts or its parts'
  summaries do, and it is what the issue asks for: the prompt shows those facts, so the summary
  depends on them.
- **`Prompt.VERSION` is 7**, so every cached summary is made again once.

## Not verified

Whether real models write more specific module and repository summaries with the sheet needs real
calls, which tests do not make. The sheet adds at most 40 fact lines, a few thousand characters,
to each module and repository prompt; not measured on a real project.
