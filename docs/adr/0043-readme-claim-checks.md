# 0043: README claims checked against the code

Status: accepted (#133), amended by ADR 0051 (#159) and for #160 (below), pending the user's review (#24)

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
    requests") and one named service ("does not call billing over HTTP", which `named-services` reads). Contradicted by every `CALLS_HTTP` link
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
- **Not covered:** scope qualifiers ("when an order is placed"); claims in decision records; and
  having the LLM judge free-text claims no check understands (#221). Showing the contradictions in
  documents is ADR 0051 (#159).
- **project-0.** `Project0LinksIT` checks DOC-2: storefront's claim is contradicted by HTTP-1,
  HTTP-2 and HTTP-5 with and without builds; payments' by DB-5's shared database with and without
  builds, and by its read of storefront's `orders` table with builds only (ADR 0036). Storefront's
  "publishes ... to RabbitMQ" holds (MSG-2) and is not flagged.

## Amendment (#160): ports, endpoints, destinations, named services and "only asynchronous"

- **The subject.** The new checks read a sentence only when all that stands before its verb is
  nothing (a list item), "it", "this service" (or "the service", "the application", "we", ...)
  or one of the README's units by name ("orders", "the orders service"), with at most words such
  as "also" or "is" after it; "our mobile app" or "when it fails, the watchdog" is not read. A unit's names are its artifact
  ID and application names, lowercase, each also without a `-service`, `-app`, `-svc` or `-api`
  ending; a name two units share is no one's. A sentence with "it" as its subject after one in
  the same claim that names another unit is about that unit and is not read, and so is one starting with "its" or "their". Sentences about tests,
  local set-ups and plans are skipped, as for technologies.
- **More checks**, each the value of the fact it makes (`NamedClaimChecks`; the step's version is
  now 2):
  - `port`: "runs on port 8081", "listens on port 8080", "the HTTP port is 8081",
    "server.port=8081"; exactly one port in the claim, and not in a sentence about another port
    (database, broker, management, debug, a proxy or gateway, HTTPS, `localhost`, ...). "Available",
    "exposed" or "reachable on port" is not read: it is usually a compose or Kubernetes mapping,
    and deploy-time overrides are not known (ADR 0042).
    Contradicted when every unit sets `server.port` to a number in every view (ADR 0042's config
    lookup) and none is the stated one; a unit that leaves it unset, or sets it from a placeholder
    or a secret, means the port is not known. Found: the ports set; contradicted by those
    `config-value` facts.
  - `endpoint`: "exposes `GET /orders/{id}`", "provides the endpoint `/refunds`": a path after a
    verb such as exposes, serves, provides, offers or handles, read only with its HTTP method or
    after words about endpoints (endpoint, API, route, resource, path, request), so "serves files
    from /data" is not read; the paths end with the clause (a semicolon or colon, a relative or
    contrasting word such as "which" or "but", or "and" before another verb, so "exposes an API and writes reports to /reports" reads none);
    framework and file paths (`/actuator`, `/swagger-ui`, `/h2-console`, `/error`, `/health`,
    `/var`, ...) are left alone. Contradicted when the units' endpoints are known (every unit the
    README speaks for has at least one controller endpoint, each with a path; Spring Data REST and
    functional routes are not extracted, so a unit without controller endpoints means nothing is
    known) and none serves the path with the method: with or without the unit's context and
    servlet paths in any view, behind leading segments the README adds (a gateway's prefix), or as
    a base the endpoint's path starts with ("a REST API under /api"); variables match any segment. Contradicted by the endpoints
    sharing the most leading segments with the path stated, or all of them when none does.
  - `destination`: "publishes to the `orders.created` topic", "consumes from queue
    `shipments.ready`", "the RabbitMQ topic exchange `shop.orders`": a topic, queue or exchange
    named after a verb of sending (publish, send, emit, produce, write, put) or receiving (consume,
    listen, subscribe, read, receive), up to the end of the clause, "and" or the next such verb.
    Only names with a dot, dash or underscore are read after the word ("topic orders-created"),
    and only with a dot or underscore before it ("the orders.created topic", not "a fan-out
    exchange"), since a plain word ("the orders topic") may describe a destination rather than name
    it; names of a role (`dead-letter`, `retry`, `reply`, ...) are not read. An event's
    name ("publishes an `order.created` event") is not a destination. Contradicted when the units
    publish (or consume) somewhere, no send or listener of theirs is unknown (an `unresolved-link`
    of kind `message`, or a masked one or one of a broker the linker does not know), and the name
    is none of their destinations, the routing keys they send with, the queues their exchanges
    route to (or the exchanges routing to their queues), nor matched by a listener's topic
    pattern. Found: the units' destinations; contradicted by their `PUBLISHES` (or `CONSUMES`)
    links.
  - `named-services`: "never calls billing", "does not call the catalog service over HTTP" (the
    negated forms `synchronous-calls` reads, with a leading list of unit names in place of "another
    service"): contradicted by the units' `CALLS_HTTP` links to a named unit, in any view. "Calls
    catalog" (or "invokes", "sends requests to") or "only calls billing", with the units or "it"
    as the subject: read only when nothing follows the names but HTTP as the way ("over HTTP",
    "directly") or, for "calls", a purpose ("for prices", "to charge orders") without a broker, a
    gateway, "asynchronously" or a condition; "only" only when the names end the clause, so "only
    calls billing when an order is paid" is a scope qualifier and not read. "Calls" is
    contradicted when the units make HTTP calls, every one of them linked (no `unresolved-link` of
    kind `http`, so a call the LLM resolver may still link is not taken as missing), and none
    reaches the named unit; "only" also by their calls to any other unit or system. Every name in
    the list must be a unit's, or the sentence is not read. Stated: what the claim says ("no calls
    to billing", "calls catalog", "calls only billing"); found, for the positive forms, what the
    units call.
  - `synchronous-calls` also reads positive phrasings that all communication is asynchronous:
    "only communicates asynchronously", "talks to other services only through Kafka events", "all
    communication with other services is asynchronous", with the way right after the verb (or after
    "with other services"), so "only talks to Stripe over REST and publishes events" is not read,
    and with the subject rule above; the same contradiction.
- **What stays open** is #221: scope qualifiers, claims in decision records and the LLM's judgment
  of free-text claims, none of which is a fixed reading of words.
- **Checks.** `ClaimChecksTest` covers each check's contradiction and the claims each leaves
  alone: another subject, a host mapping, a port under a profile or from a placeholder, file and
  framework paths, a base path, a path in a later clause, a unit without endpoints, an event's or
  a role's name, a kind of exchange, a verb of the other direction in the clause, an unknown or
  masked send, consumed destinations through routes and topic patterns, name lists with a name no
  unit has or two units share, a call via a broker or with a condition, an unlinked call, a
  pronoun about another unit, tests. `Project0LinksIT` still finds only DOC-2's contradictions, so none of the new checks
  misreads project-0's READMEs.
