# 0043: README claims checked against the code

Status: accepted (#133), pending the user's review (#24)

## Context

`docs/design.md` (Annotations: knowledge layer, READMEs and the wiki) says README claims that
contradict the code are flagged, for example "README says MySQL, code connects to Postgres". The
docs extractor records each README paragraph, list item and table row as a low-trust `claim` fact
(ADR 0033) without checking it, and the extractors and the cross-repo linker record what the code
does (ADRs 0032 and 0034 to 0042). project-0's case DOC-2 is the test: storefront's README says it
"never calls another service synchronously when an order is placed" (contradicted by HTTP-1, HTTP-2
and HTTP-5) and payments' that it "has a database of its own and never reads another service's
tables" (DB-5). The design leaves open which claims are checked, how, and what a flag looks like.

## Decisions

- **A third linker step.** `ClaimChecks` (`lantern-link`, origin `claim-checks`, version 1, kind
  `static`) runs after `CrossRepoLinks`, since most checks read links, and like the other steps
  derives from every fact that holds in the snapshot (ADR 0039). No LLM: every check is a fixed
  reading of the claim's words against facts, so it is deterministic, free and repeatable. A claim
  no check understands is left alone, neither flagged nor confirmed.
- **Whom a README speaks for.** A README at the repository's root speaks for all of its
  deployable units; one in a folder, for the units in that folder or below (where their
  `deployable-unit` fact is written), and for none when no unit is there (`docs/`, a library's
  folder). A repository without units (a library) has nothing to check.
- **Precision first.** A wrong flag misleads a reader more than a missed one, so every check reads
  only plain statements about the units themselves and leaves anything less clear alone.
- **Reading the words.** The claim is lowercased, emphasis and code marks dropped, and split into
  sentences (at `.`, `!`, `?` and `;`). A negation is `never`, `not`, `no`, `none`, `nothing`,
  `without` or a `n't` form; "negated" means one stands at most three words before the verb.
  Sentences about tests, local or development set-ups, examples or migrations (`test`, `local`,
  `dev`, `migrated`, `instead of`, ...) and hedged or future ones (`plan`, `will`, `future`,
  `used to`, `legacy`, `deprecated`, `roadmap`, `migrating`) are skipped by the checks that read a named technology or
  version, so "tests use H2" or "we moved from MySQL to Postgres" are not read as what runs.
- **The checks**, each the value of the fact it makes:
  - `synchronous-calls`: a sentence has the units as the caller, negated (`never calls`, `does not
    call`, `makes no ... calls`, with only qualifiers such as "synchronous" or "HTTP" between), says
    synchronous, blocking, HTTP or REST (only the acronym, as written) in that clause, and names
    no one service ("another service", "other services", or nothing). Not read: calls coming in
    ("nothing calls this service"), other verbs ("does not retry HTTP calls", "never logs HTTP
    requests") and one named service ("does not call billing over HTTP", #160). Contradicted by every `CALLS_HTTP` link
    of the units and every `unresolved-link` of kind `http` (a call whose target is unknown is
    still a call). A qualifier such as "when an order is placed" is not read: every call counts.
  - `other-services-data`: a sentence negates reading, writing or accessing "another service's
    tables" (or data, database, schema). Contradicted by the units' `ACCESSES_DATA_OF` links; a
    claim only about reading needs `reads`, one only about writing `writes`.
  - `own-database`: a sentence says "its own database", "a database of its own" or "dedicated
    database", without a negation before it. Contradicted by a unit the README does not speak for
    using (`USES_DATABASE`) one of the same databases in the default view. Sharing that holds
    only under a profile (both on `localhost` under `local`) is not flagged.
  - `database-technology`: the claim's sentences name exactly one database (PostgreSQL, MySQL,
    MariaDB, Oracle, SQL Server, H2, HSQLDB, Derby, Db2, SQLite), not negated. Contradicted when
    the units use databases and none of them, in any view, is of that kind; a datasource whose
    technology is unknown means no contradiction.
  - `broker-technology`: likewise for Kafka, RabbitMQ and ActiveMQ/Artemis, against the units'
    `PUBLISHES` and `CONSUMES` protocols (`Kafka`, `AMQP`, `JMS`) and `USES_BROKER` technologies.
  - `java-version` and `spring-boot-version`: the claim states exactly one version, compared on the parts it gives with the units'
    `technology` facts; `17+`, "or later" and "at least" make it a minimum. A Java release is read
    only after words about what runs ("runs on Java 17", "requires JDK 21", "built for Java 17",
    "Java version: 17") and not when a feature follows ("Java 8 streams", "the JDK 11
    HttpClient"); a Spring Boot version wherever it is named (`Spring Boot 3.2`, `3.5.x`).
- **The fact.** Attribute `claim-contradiction` on the README entity, the check as its value, so
  a document finds a README's contradicted claims by its key. Attributes: `claim` (the claim
  fact's ID), `stated` and `found` (for technologies and versions: what the claim names and what
  the code shows; for `own-database` the other units), `units` (whom the README speaks for),
  `facts` (the claim's ID, then the contradicting facts') and `contradictedBy`, one entry per
  contradicting fact with its ID, subject, predicate, object, `profile` when it holds only under
  one, and where it is written (`repo`, `path`, `line`). The evidence is the claim's, so both
  sides are cited. One fact per claim and check.
- **Confidence** is the lowest confidence of the contradicting facts times 0.8, for the reading of
  the words. It does not take the claim's own 0.5: the claim is certainly written there; the
  question is only whether the code agrees.
- **New vocabulary**, applied by the app on startup after the cross-repo links', with source
  `claim-checks`: attribute type `claim-contradiction`.
- **Not covered:** ports, endpoint paths, topic and queue names, and named services a claim says
  are or are not called; positive phrasings ("only communicates asynchronously"); scope
  qualifiers ("when an order is placed"); claims in decision records; and having the LLM judge
  free-text claims no check understands (#160). Showing the contradictions in documents is #159.
- **project-0.** `Project0LinksIT` checks DOC-2: storefront's claim is contradicted by HTTP-1,
  HTTP-2 and HTTP-5 with and without builds; payments' by DB-5's shared database with and without
  builds, and by its read of storefront's `orders` table with builds only (ADR 0036). Storefront's
  "publishes ... to RabbitMQ" holds (MSG-2) and is not flagged.
