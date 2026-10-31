# Lantern — Design Document

Oct 29, 2026

## Overview

> **Active epic.** [`docs/epics/001-knowledge-build.md`](epics/001-knowledge-build.md) changes parts of this design (knowledge model, extraction, linking, derivation, LLM roles, composition). Where the two differ, the epic wins until its issues land and bring this document in line.

Lantern is a hosted service that analyzes the local repositories of a named software system, mostly Java, builds a versioned knowledge model of that system, and generates architecture documents for different audiences from that model.

**Problem.** We work in a very complex ecosystem of microservices and libraries with little trustworthy documentation. The wiki exists but can't be relied on. Onboarding is slow, and architecture reviews depend on whoever happens to remember how things connect.

**Goals**

- Produce architecture documents for audiences from executives to lead architects, at the granularity each needs.
- Document repos individually, as one system, or as automatically discovered groups of related components.
- Get as much as possible from static analysis, with an LLM filling the gaps static analysis can't.
- Persist knowledge tied to exact repo versions, so later runs reuse it and update only what changed.
- Capture tribal knowledge, and control over how documents look, through annotations stored in the service.
- Keep document layout stable across versions, changing only what the changes require.
- Make single-file HTML the primary output, with other formats supported.

**Non-goals (for now)**

- Fetching repos from remote providers. Repos are cloned onto the host.
- Trusting the existing wiki. Code is the source of truth, READMEs are low-trust supporting evidence, and annotations add what code can't show.
- Non-JVM application languages as a first-class target, beyond config, SQL, build and deployment files, and frontends seen as containers.
- Documents other than the C4 report (`report-specs/c4-model-sequence-diagrams`). The documents in scope, and the standard they must meet, are in `docs/quality-bar.md`; what this design says of other documents is kept for when the user brings them back into scope.

## Design principles

The knowledge model is the product. Documents are views of it. This design says how Lantern meets the standard in `docs/quality-bar.md`.

1. **Code is the source of truth.** Static analysis comes first. The LLM fills gaps and never overrides evidence from code.
2. **Every claim has provenance.** Each fact records where it came from (static extractor, LLM, human, README), at which commit, and with what confidence. Documents can trace any statement back to its evidence.
3. **Stable identity everywhere.** Entities have semantic IDs (`service:orders`, `topic:kafka:order-created`) that survive refactors. Annotations, layouts and caches all key on these IDs.
4. **Reproducible outputs.** A document is fully determined by code snapshot + annotation revision + tool version + template version.
5. **Incremental by construction.** Everything derived (facts, summaries, layouts, prose) records its inputs, so only what's affected by a change is recomputed.
6. **Stability over freshness in presentation.** Layouts, colors and prose persist across runs and change only as much as the underlying change requires.
7. **Humans can override anything, explicitly and with an audit trail.** Corrections and presentation rules are stored in the service with a full audit log. Nothing a human sets is silently discarded.
8. **Open vocabulary, stable core.** The fact envelope rarely changes. Entity and relationship types are data and can grow without a release.
9. **Extract everything relevant, show what a document needs.** Extraction and linking aim at every relevant fact of the code, not only what today's documents draw, so facts down to the code level are extracted and linked even though the C4 report does not draw them. A document is a selective aggregation of facts. A new document starts by checking which facts it needs that extraction and linking do not yet provide, and those are added first.

## Architecture overview

Eight stages run in order for each snapshot, and every stage reads from or writes to one versioned knowledge store in PostgreSQL. People add annotations and presentation rules to the same store, so the composer sees code facts and human knowledge together.

```mermaid
flowchart TD
  repos[Local repositories<br/>repo@commit set = snapshot] --> build[Build sandbox<br/>container per JDK]
  build --> extract[Static extractors + learned rules]
  extract --> link[Linker and grouping]
  link --> investigate[Investigator - LLM<br/>cited, verified proposals]
  investigate --> summarize[Summarizer - LLM]
  summarize --> compose[Composer]
  compose --> render[Renderers<br/>HTML, Markdown, PDF, Confluence]
  people[People in the UI<br/>annotations, rules, approvals] -- annotations, rules --> store[(Knowledge store<br/>PostgreSQL, versioned)]
  extract -- facts --> store
  link -- links --> store
  investigate -- verified facts --> store
  summarize -- summaries --> store
  store -- reads --> compose
```

Only the composer decides what a document says. Renderers just format its output, which keeps new output formats cheap to add.

Architecture tests keep that order: no stage depends on a later one (extract, link, derive, the LLM roles, compose, render), and renderers read no facts from the store and call no stage that makes them (ADR 0093).

## Ingestion and build sandbox

Repos are cloned onto the service host, and each analysis builds its Maven repos for real in an isolated container, because accurate Java analysis needs the code as it actually compiles.

**Repository registry**

- Each repo is registered with a local path on the host (or a clone URL the host pulls from), a default branch, and optional per-repo settings (JDK version, Maven profiles, modules to skip).
- Repos belong to a named **system**, the software system its documents describe. One analysis covers one system: it names the system, and only that system's repos take part. A repo belongs to at most one system and can be removed from it. How a run names its system and title, and how a repo is removed, is in ADR 0071.
- A repo needs no Maven build. One without a `pom.xml` (a frontend, a repository of deployment files) is registered and snapshotted, is not built, and is read by the extractors that apply to its files; that is not degraded mode.
- An analysis run targets a specific commit per repo. The set of `repo@commit` pairs is a **snapshot** (see Versioning).
- Git credentials for pulling live on the host, outside the knowledge store.

**Build sandbox**

- One container per repo build, run with Docker or Podman on the single VM.
- Images per JDK (8, 11, 17, 21, 25). The JDK is detected from `maven.compiler.release` / `source` / toolchains, with a per-repo override.
- A mounted `settings.xml` gives access to the internal Nexus or Artifactory.
- The Maven repositories builds may download from are configured per analysis (`lantern.sandbox.maven-repositories`, an id and URL each, exact locations, no wildcards); Maven Central alone by default. Test sets name extra ones in their own settings. Downloads are only used to compile; third-party code is never analyzed.
- Optionally, one Maven mirror (`lantern.sandbox.maven-mirror`, an id and URL) stands in for every repository: all downloads of a build, dependencies and plugins alike, go through it, and it is then the only host builds reach. Unlike the repositories, it may be plain `http` and a private-network IPv4 address, so a caching proxy on the local network can serve builds. Its address is the host's own setting, kept in a properties file outside the repository.
- A shared, persistent local Maven repository cache across builds, for speed.
- A snapshot's repositories are built in the order of their internal Maven dependencies, and each build gets, read-only, the artifacts (POMs and jars of compiled classes) of the successful builds of the repositories it depends on, so a repository can use a library or parent POM another repository builds. They never enter the shared cache, so builds of other snapshots cannot pick them up. Repositories that depend on each other are built as if alone.
- The build runs to `compile` (which includes `generate-sources` and annotation processing), with tests skipped. The outputs the analyzer needs are the resolved classpath, generated sources and compiled classes.
- Resource limits (CPU, memory, disk, timeout) per container, builds run as a non-root user, and a concurrency limit for the VM.
- **Fallback:** if a build fails, analysis continues in degraded mode (source parsing with whatever classpath resolves), and the affected facts are marked lower-confidence.

**What leaves the sandbox:** classpath, generated sources, class files and build metadata (effective POM, dependency tree, module graph). No network access from the build except to the configured artifact repositories, or to the mirror when one is set. How the sandbox enforces this, chooses the JDK and stores outputs is in ADR 0005; the build user and disk limit are in ADR 0019; removing what killed builds leave is in ADR 0053; the configured repositories are in ADR 0023; the mirror is in ADR 0030; the build order and other repositories' artifacts are in ADR 0041.

## Static analysis and extractors

Extractors are plugins that read build outputs and source files and emit facts. Static analysis is expected to produce most of the knowledge, with the LLM covering what remains.

**Extractor contract**

- Input: a repo build workspace (sources, generated sources, classpath, class files, build metadata) plus the files the extractor declares interest in (by glob or type).
- Output: facts in the core envelope (see Knowledge model), each carrying the extractor's name and version.
- One interface, `io.lantern.extract.Extractor` (ADR 0092): each extractor names the extractors whose facts it reads and the unit its facts are cached by (one file, one repository with its build, or the whole snapshot); a repository unit that also reads other repositories' files, as Maven reads their POMs, declares them, and they join its key (ADR 0092, amendment for #547). The extractors are found through `ServiceLoader` and run in the order of their requirements, in waves of those whose requirements earlier waves met; the code model is built once, and only if an extractor that reads it misses the cache.
- Two ways to plug in:
  - **In-process Java SPI** for core extractors (fast, shares parsed ASTs).
  - **Out-of-process protocol**: JSON over stdio, LSP-style, for extractors in other languages and for sandboxed learned scripts.
- Extractors are deterministic: same input files and extractor version give the same facts. This is what makes caching safe.

**Initial extractors**

| Extractor | Reads | Produces |
| --- | --- | --- |
| Maven | Effective POM and reactor (POMs as written when there is no effective POM) | Modules, artifacts, internal vs. third-party dependencies, deployable vs. library and what makes it deployable (war or ear, Spring Boot repackage, Quarkus, shade or assembly with a main class, or an image plugin), the library a thin service packages, each module's parent and Java release, and the coverage tools its build runs |
| Java structure | javac Tree API with symbol resolution (JavaParser as fallback, adding Lombok's constructors and log field) | Packages, types, members, inheritance, resolved call graph; generated types marked with their generator |
| Spring / Jakarta | Annotations, including meta-annotations (a custom annotation carrying `@RestController` or `@Service` counts as one), and bean definitions | Application main classes, controllers and endpoints, beans by stereotype, configuration, `@Scheduled` jobs, JobRunr and Quartz jobs with where they are scheduled, `@Profile` and `@ConditionalOnProperty` gates, `@Aspect` types with the methods their pointcuts advise and, on each such method, the annotation that selects it, service discovery and infrastructure-server registrations, method-security switches and access checks, and the call that schedules each job |
| HTTP clients | Feign, Spring HTTP interfaces (`@HttpExchange`), RestTemplate, RestClient, WebClient, JAX-RS and MicroProfile clients, `java.net.http`, OkHttp and Apache HttpClient | Outbound requests as entities, sent by the calling code and addressed to the base of their URL, the URL and service name kept as written for linking to resolve per view (ADR 0094); a load-balanced client's (`@LoadBalanced`, `lb://`) host as the service name; the `@Bean` methods that make clients. The caller of a client interface is the code that enables or injects it, not the jar that defines it |
| Messaging | Kafka, JMS, RabbitMQ listeners and templates; RabbitMQ queue, exchange and binding declarations and Kafka topic declarations; Spring Cloud Stream bindings, functions and `StreamBridge` | Destinations as entities, sent to, listened on, bound and declared by the code, their names, routing keys and groups kept as written for linking to resolve per view (ADR 0094): a name written out is one destination everywhere, one config resolves is its module's. Spring Cloud Stream bindings and function beans as written, bound to destinations in linking from the config facts; a queue's dead-letter routing |
| WebSocket / STOMP | `@EnableWebSocketMessageBroker` and `@EnableWebSocket` configuration (endpoints and their handshakes, broker prefixes and a relay's connection, client channel interceptors), `ChannelInterceptor` classes, `@MessageMapping` handlers, `SimpMessagingTemplate` and `MessageSendingOperations` sends | WebSocket endpoints and their paths, STOMP destinations handled, replied to (Spring's defaults included) and pushed to (a push a relationship to its destination entity, as messaging's sends are), the broker a relay connects to, the interceptors that check connect and subscribe and what their code reads |
| Spring Cloud Gateway and Zuul | Routes built with `RouteLocatorBuilder`, as written; filter classes. Routes in `spring.cloud.gateway` and `zuul.routes` config and the discovery locator's config are read in linking from the config facts (ADR 0094) | Routes per profile with their predicates, filters and target URI (`lb://`, `lb:ws://`, a URL), the discovery locator and its settings, and the global, gateway and web filters requests pass through, as entry points |
| Persistence | JPA entities, Spring Data repositories and documents (MongoDB, Elasticsearch), JDBC templates, MyBatis annotations and XML mappers, datasource and non-relational store config | Entities, tables and collections touched, datasources and non-relational stores |
| Redis | Redis templates and operations, `@RedisHash`, Spring Session, lock registries, the gateway's rate limiter, and Spring and JCache cache annotations and calls in modules whose cache provider is Redis (an in-process cache is never a store), in code, config and POM dependencies | How each module uses Redis, with the keys the code writes out |
| Identity | JWT libraries (jjwt, Nimbus, Spring Security, java-jwt), Spring Security's decoders, its `HttpSecurity` and `ServerHttpSecurity` setup and legacy Spring Security OAuth's annotations, Keycloak's admin client and Keycloak realm exports | HMAC keys for JWTs made from config, naming the config keys of their secret (never its value), and the keys of RSA and EC signers and verifiers; how tokens are checked (JWK set, issuer or public key, with the URL as written); how callers sign in or get tokens (OAuth2 login, resource server, client, authorization server, form, Basic, SAML, X.509, one-time token, passkeys, and legacy Spring Security OAuth's annotations); the server URL an admin client is built with; and the clients a realm export registers, with their flows and redirect URIs |
| People | Role checks (`@PreAuthorize`, `@Secured`, `@RolesAllowed`, `@DeclareRoles`, through custom annotations too, and `hasRole`-like calls on any receiver), Keycloak realm roles and self-registration, the constants of an account's role enum, sign-up, sign-in, confirmation and password-reset endpoints with the roles that guard them, types about members and invitations, and the routes of Angular, React and Vue frontends | The evidence for who uses the system, each citing its line; the linker makes people of it |
| Config | `.properties`, `.yml` (`application*` and `bootstrap*`), Spring profiles, env-var placeholders; a Spring Cloud Config repository's files named after an application (a repository of its own, or a folder of the server's that its native backend serves) | Resolved and unresolved config values per profile, including `spring.application.name`; a config repository's values per application |
| SQL | Flyway and Liquibase migrations, `.sql` files (JSqlParser), with placeholders replaced, and only the copy and change sets for the module's database and contexts | Schema as of the commit: tables, columns, keys |
| Tests | Test sources, parsed without compiling or running them | Test classes per module, how many tests each declares (the repository's composed test annotations included), and which are architecture tests (ArchUnit and similar); the layers and slices ArchUnit's library rules declare; the containers tests start (Testcontainers, embedded Kafka) |
| README and ADR | Markdown and AsciiDoc READMEs, other documents (in a `docs` folder, or `ARCHITECTURE`), and architecture decision records in the repo | Low-trust "documented claims" (paragraphs, list items, table rows and quotes) and diagrams, README claims checked against code; observed decisions with title, status, date and rationale |
| Deployment | Dockerfiles, compose files, Helm charts, kustomize and Kubernetes manifests | Images and the units they run, container instances per environment, published ports, the environment, Spring properties and active profiles each container is started with (command lines and env files included), inbound routes (Ingress, HTTPRoute), infrastructure the system runs with (databases, brokers, gateways, identity providers) and their versions |
| Frontend | A frontend's package manifest (Angular, React, Vue), in its own repository or a folder of another, its environment files, the Dockerfile that builds it (in its folder or elsewhere in the repository) and the nginx config it copies in, development-server proxies, the HTTP calls, sockets and browser navigations in its code, Angular's interceptors, guards, pages and services, and the URLs its templates and styles load | A frontend container with its technology, the URLs it calls from the browser and the lines they are built from, the path prefixes its server passes on, how it signs requests and guards pages, its Angular pages and services, and the hosts it loads |

**Following values.** Paths, queue, exchange and topic names and routing keys are rarely literals where they are used. Extractors resolve `${...}` placeholders against config per profile. The analyzers fold constants, and one shared follower takes a value one hop from a `@Value` field or constructor argument into the template or client call that uses it (ADR 0093). A URL that reaches the call as a parameter of the method making it is followed back to where the method's own class calls it, up to three calls. A value that can't be resolved keeps its placeholder key, which still says what it is.

Later: OpenAPI and AsyncAPI specs, Terraform.

How the Maven extractor names entities, tells internal from third-party dependencies (a dependency under JitPack coordinates, `com.github.<owner>:<repo>` or `com.github.<owner>.<repo>:<module>`, is internal when the snapshot has a repository of that name) and links a thin service to its library is in ADR 0006.

How the Java structure extractor names packages, types and members, which annotation processors it runs, which calls it keeps and how its parsing fallback matches the compiler is in ADR 0007; calls to methods a type inherits from third-party code, such as a Spring Data repository's `save`, are in ADR 0049.

Which test sources the Tests extractor reads, what counts as a test and an architecture test, and how the Maven extractor records coverage tools and their thresholds is in ADR 0064.

Which config files the Config extractor reads, in what order, how it models profiles and placeholders, and how it keeps secrets out of its facts is in ADR 0032.

Which files the README and ADR extractor reads, which decision record formats it understands, and the facts and confidence it gives claims and decisions is in ADR 0033.

Which migrations and SQL files the SQL extractor reads, in what order, and how it names and models schemas, tables, columns and keys is in ADR 0034.

How the Spring / Jakarta extractor reads annotations and meta-annotations through the Java extractor's reading of the code, what facts it makes of stereotypes, main classes, endpoints, scheduled jobs (including JobRunr and Quartz) and profile gates, and which config resolves their placeholders is in ADR 0035. Aspects and the methods they advise are in ADR 0079.

How the Persistence extractor finds entities, repositories and SQL in code, names the tables they touch, and records datasource URLs per profile without credentials is in ADR 0036.

How people are inferred from roles, memberships, invitations, sign-in and frontend routes, and always marked inferred, is in ADR 0074.

How Redis becomes a data store, found from the code, config and dependencies and linked per server with the protocol RESP, is in ADR 0073; its product and version come from the Deployment extractor's containers (ADR 0082).

Which Dockerfiles, compose files, kustomize overlays, Helm charts and manifests the Deployment extractor reads, what an environment is, how it reads templates without rendering them and how it keeps secrets out of its facts is in ADR 0082.

Which listeners, templates and bindings the Messaging extractor reads, how it follows a name from a constant, a placeholder or a `@Value` into the call that uses it, and what facts it makes of listeners, sends and RabbitMQ bindings is in ADR 0037. How it reads Spring Cloud Stream, whose destinations come from config through bindings, into the same facts is in ADR 0040.

What the WebSocket extractor reads of Spring's WebSocket and STOMP code, which facts it makes, how a push to subscribers shows in sequence diagrams and what a gateway route to a WebSocket endpoint needs of it is in ADR 0080.
Which routes and filters the gateway extractor reads (Spring Cloud Gateway, Zuul, the discovery locator), and how lambdas in the code are read for it, is in ADR 0077.

Which manifests, environment files, proxies and calls the Frontend extractor reads, and how it evaluates a URL without parsing the code, is in ADR 0083.

Which HTTP clients the HTTP client extractor reads, how it follows a URL one hop through the code, what facts it makes of calls and of the code that enables or injects a client, and what a recorded URL leaves out is in ADR 0038.

How the Mail extractor and linker make the mail server a unit's config names an external system, linked by `SENDS_MAIL` and shown as a step in the flows that reach the sending code, and which mail libraries, provider SDKs and hosts set in code the extractor reads, is in ADR 0076.

How the Identity extractor reads JWT secrets and admin clients, and how the linker makes the issuers, JWK sets and OAuth2 providers a unit's config names into links to a unit of the system or to an external system, and records the JWT secrets units share, is in ADR 0085.

**Why javac instead of only a parser:** production Spring code relies on Lombok, MapStruct and generated OpenAPI or Protobuf clients. Analyzing through the compiler, with annotation processing on, sees generated members and resolves calls exactly as the build does.

## Knowledge model and schema evolution

The schema has two layers: a small, stable **core envelope** that is code and changes only with a release, and an open **vocabulary** of entity and relationship types that is data and can grow at runtime without a release.

**Core envelope** (every fact has this shape)

A fact is one **observation** (sighting) of a **claim** (what it states). The claim is the subject, predicate, object and the type's key attributes; the observation is the rest (ADR 0091).

| Field | Meaning |
| --- | --- |
| `id` | Content-derived observation ID: the hash of everything below but confidence and validity, so a sighting unchanged at a new commit keeps it |
| `claim` | The claim ID (below), on every fact read from the store |
| `subject` | Stable entity ID, e.g. `service:orders` |
| `predicate` | Relationship or attribute type, e.g. `CONSUMES`, `owner` |
| `object` | Entity ID or typed value |
| `attributes` | JSONB, validated against the vocabulary's definition for this type; key ones belong to the claim, the others to the observation |
| `lineage` | The claim IDs the fact was derived from, for derived and LLM facts |
| `origin` | `static` / `llm` / `human` / `readme` / `rule`, plus extractor, model or user details |
| `evidence` | One or more locations, the first the one the fact is declared in: repo, file, the file's git blob, line range and role (`declared`, `value`, `config`, `cited`). No commit: a snapshot's `repo@commit` pairs give it. A file git does not hold keeps its content hash instead of a blob |
| `confidence` | 0–1 |
| `validity` | Snapshot range in which the observation holds |

**Vocabulary registry** (stored in the database)

- Defines entity types (`service`, `library`, `topic`, `table`, `batch-job`) and relationship types (`CALLS`, `CONSUMES`, `READS_TABLE`).
- Each type has a **parent type**, an attribute schema, a C4 level, and default presentation (shape, icon). An attribute type can also set the kind of value its facts hold (its object kind).
- A relationship or attribute type lists its **key attributes**, on top of its parent's: the attributes that are part of what a fact claims; the others are descriptive. The **claim ID** is the SHA-256 of a fact's subject, predicate, object and key attributes, without evidence, origin, confidence or validity, so every sighting of one thing, at any commit, has the same claim ID, and two facts that differ in a key attribute are two claims. When in doubt an attribute is key: two claims for one thing can be merged later, one claim for two things loses a fact (ADR 0090; which attributes are key: epic `docs/epics/001-knowledge-build.md`, section 6, as amended by #518).
- Runtime relationships (`CALLS`, `CALLS_HTTP`, `PUBLISHES`, `CONSUMES`, `USES_DATABASE`, …) are under the root `INTERACTS_WITH`, drawn as a line; build ones (`BUILD_DEPENDS_ON`, `PACKAGES`) under `DEPENDS_ON`, drawn as a dashed arrow, so a renderer falling back to a parent never draws a build edge as a runtime line. People (`person`) are under the root `actor`, at the context level; message brokers and infrastructure servers are containers.
- Saving a fact checks it against the vocabulary: its entities' types, its predicate, its object (an entity for a relationship, a value for an attribute, of the kind the attribute type sets) and its attributes (ADR 0002).
- Parent types mean a new type renders and behaves sensibly before any code knows about it. A new `batch-job` type that extends `container` appears in diagrams and documents as a container immediately.
- C4 levels follow the definitions under Diagrams in Composition: applications and jobs (deployable units, such as `service`), data stores, and queues and topics (`topic`) are containers; `library`, `module`, `package` and `table` (part of a data store) carry no C4 level; components are found by role, not from a type (#90).
- New types can come from plugins, learned rules (through the approval flow), or administrators in the UI. Every change is versioned and in the audit log. A type belongs to the source that first defined it, and another source that defines it differently is refused (ADR 0002).

**Do schema changes need a release?**

| Change | Release needed? |
| --- | --- |
| New entity, relationship or attribute type | No, it's a vocabulary entry |
| New learned rule producing new types | No, it goes through approval |
| Giving a type another parent, or none | No, it is a new vocabulary revision; older revisions keep the old parent |
| Deprecating or renaming a type | No, an alias maps old to new; existing facts are migrated in the background |
| A template component that renders a new type specially | Yes for the component, but the type works with its parent's rendering until then |
| A new built-in extractor | Yes, unless it's an out-of-process plugin |
| Changing the core envelope | Yes, with a Flyway database migration |

**Versioning rules:** additive vocabulary changes are always allowed, and so is giving a type another parent (or none), as a new revision that keeps stored facts valid, except that declaring a new attribute or object kind constrains new facts only (ADR 0002). Renames and removals go through deprecation with an alias. Each snapshot and document records the vocabulary revision it used, so older documents stay reproducible. Plugins declare which envelope version range they support.

**Typed access:** Java types for the envelope and built-in types are hand-written and kept in line with the JSON Schema by tests; TypeScript types are generated from the schema. Each relationship and attribute type of the extractors and the linker that has attributes has a Java record generated from its attribute schema (`HttpCallAttributes` for `http-call`), committed in its module's `attributes` package and checked against the generator by a test (ADR 0090). Runtime-added types are accessed through a generic API.

**Storage:** PostgreSQL. Claims in one table (subject, predicate, object, key attributes) and their observations in another (descriptive attributes, evidence, lineage, confidence, validity), with indexes on subject, predicate, object, claim, repository and snapshot range. The versions of one observation never overlap; one origin may hold several observations of a claim at once when they differ, such as a link seen from several call sites. A claim holds in a snapshot while one of its observations does. Recording a snapshot names each evidence file by its git blob at the snapshot's commit (ADR 0091). The graph for analysis is loaded into memory per scope.

## Versioning and incremental computation

Lantern works like a build system: every derived artifact records its inputs, and a new run recomputes only what a change actually affects. "Rebuild from scratch" and "update where needed" are the same mechanism with the cache on or off.

**Snapshots**

- A snapshot is a numbered set of `repo@commit` pairs, plus the vocabulary revision, annotation revision and tool version used. Snapshots are numbered 1, 2, 3 and so on in creation order across all systems, because fact validity is a range of snapshot numbers; a snapshot's facts are those that hold at its number and come from its repos (ADR 0071).
- A snapshot belongs to one named system and always describes the whole of it: a repo of that system that a run does not analyze is carried forward at its commit in the latest snapshot of the system that analyzed it (stored its facts), and its facts keep holding. Repos of other systems are never carried in, and a repo removed from the system is not carried forward. A run only ends facts of the repos it re-extracted.
- Every document is tied to exactly one snapshot and names its system by the system's title: the one people gave, else one derived from the repos' README headings or names (ADR 0071). The system's documents are titled by it, a repo's by the repo's name.
- Only one analysis runs at a time. Starting one takes a PostgreSQL advisory lock, held on one database connection until the analysis ends; a second start fails at once with an "analysis already running" error, even from another Lantern process on the same database. If the process dies, the connection drops and the lock is released.

**Cache keys**

| Artifact | Keyed by |
| --- | --- |
| Build outputs | Repo commit + JDK image + `settings.xml` hash |
| Static facts | The unit the extractor declares (ADR 0092): a file's blob, a repository's git tree and build fingerprint, or every repository's; plus the extractor's name, version and code digest, and the IDs of the facts it reads |
| Linked graph | Set of observation IDs in scope + linker version; observation IDs leave the commit out, so an unchanged repository at a new commit keeps them (ADR 0091) |
| LLM summaries | Hash of the prompt (the facts, source files and child summaries it shows, without fact IDs) + prompt version + model |
| LLM link resolutions | Hash of the prompt (the facts it shows, without fact IDs) + prompt version + model |
| LLM wordings of units | Hash of the prompt (the facts it shows, without fact IDs) + prompt version + model |
| Layouts | Diagram ID; updated incrementally, never recomputed by default |
| Document prose | Hash of the facts a section draws from + template version |

**On a new snapshot**

1. Diff each repo's commit against the previous snapshot to find changed files.
2. Rebuild only repos whose commit changed, or whose stored build failed (ADR 0016).
3. Re-run each extractor only on the units whose inputs changed (ADR 0092). Unchanged units reuse cached facts, valid from the new snapshot.
4. Re-link the graph and find which entities changed.
5. Invalidate summaries whose prompt changed (their facts, source files or child summaries), then walk up the hierarchy (class to package to module to service to group to system), regenerating only invalidated nodes.
6. Recompose only document sections whose inputs changed. Unchanged sections keep their exact text.

**Architecture diff.** Because both snapshots are fully materialized, comparing them is a query: entities and relationships added, removed or changed. This feeds the Change Report template and the change overlay on diagrams.

**Retention.** Snapshots that documents reference are kept. Intermediate build outputs can be pruned; facts are small enough to keep.

## Cross-repo linking, scoping and grouping

The linker turns per-repo facts into one system graph by matching what one repo exposes with what another uses. Scopes and groups then decide which part of that graph a document covers.

**Deployable units.** Before matching, the linker folds each deployable unit together: a service (or a library with a Spring Boot main class that no service packages) with the in-set libraries on its runtime classpath, its `spring.application.name` and its technology. These units are the containers of the diagrams, and a library's code runs as part of every unit that packages it. The linker derives its facts from everything that holds in the snapshot and records them for the whole snapshot, so a derived fact stops holding when what it came from changes, in whichever repository. Which facts the units get, and how their name, main class and technology are chosen, is in ADR 0039. Where units ask for versions of an in-set library other than the one its repository builds at the snapshot's commit, the library gets a version-skew fact naming each unit's version, for the findings page.

**Linking rules**

| Link | Matched by |
| --- | --- |
| Library dependency | Maven coordinates of internal artifacts |
| HTTP call | Client name (Feign `name`) against the target's `spring.application.name`, else the resolved URL, as each deployment container's environment sets it (before the config files, as Spring Boot reads them), by its host against unit and deployment container names, or for this machine or a private address its port against the port a deployment container publishes, else the target's `server.port`; then the path against the target's endpoints |
| Frontend call | A path of the page's origin through the proxy of the frontend's nginx or development server; then the URL's host against unit and deployment container names, or for this machine or a private address its port against the port a deployment container publishes, else the unit's `server.port`; then the endpoint it reaches, the unit's own or, through the gateway route that takes it, the routed unit's |
| Gateway route | A load-balanced URI's name (`lb://orders`) against `spring.application.name`, else the URI as for an HTTP call; endpoints of a routed unit that no route reaches are recorded for the findings |
| Messaging | Topic, exchange or queue names and routing keys, produced vs. consumed, with AMQP topic wildcards (`order.*`) matched as the broker matches them |
| Shared database | Datasource URLs and table names; Redis servers (`spring.data.redis.*`), one data store per server |
| Shared config | Common property keys and config servers |
| Identity provider | An issuer, JWK set, OAuth2 provider or admin client URL in config (`spring.security.oauth2.*` and keys like it) against units as for an HTTP call, else an identity provider container a deployment runs, else a host with a domain as an external system; HMAC secrets for JWTs from the same placeholder, value or key are recorded as shared, for the findings (ADR 0085) |
| People | Human-facing evidence: the members of things the code has members of, invitations, accounts, sign-up and sign-in, and a frontend's routes; each kind of person is a `person` entity, inferred. Roles the code checks say what those people may do and make no person on their own (ADR 0074, amendment for #365) |
| Unknown caller | An entry point nothing in scope reaches and no person uses (an HTTP or WebSocket endpoint, a queue or topic consumed but never published to; not a scheduled job; an endpoint behind a gateway is reached through it) gets a line from one `Unknown caller`, marked unknown, until an annotation names the real caller (ADR 0074, amendment for #365) |

A value written in code (`http://${payments.host}/api`) is resolved in linking against the config of the code's module, per view, as the module's `config-value` facts give it, one kind of fact at a time as its extractor stops resolving it (ADR 0094). Every linker resolves an address the same way, in one place: filled in from the deployment, its host classified, then matched in one order; only a public domain that names no unit is an external system, never a private address (ADR 0042, amendment for #473).

A datasource whose server or database is a placeholder is a database of its unit alone, never shared, rather than a link left open; where a deployment runs the unit, its environment fills the placeholders, and the database takes its name and version from the container it is on (ADR 0082). The exception is Redis: units whose host and port are the same unresolved placeholders (`${redis.host}`) are taken to share one server, since a deployment fills them from one environment (ADR 0073). A link that holds only under a Spring profile records that profile. Service discovery, config servers and tracing are linked as infrastructure, not as business dependencies.

Links that static matching can't resolve (a URL assembled from several properties and an env var, for example) go to the LLM resolver with the candidate facts. If it's still unresolved, the link goes to the questions queue. What the resolver is shown, how its answer is checked, the confidence of what it picks and how answers are cached are in ADR 0045; its tools to query the graph and read files are in ADR 0081, the questions queue comes with #170.

How gateway routes become links, and which endpoints count as reached by none, is in ADR 0077. How a frontend's calls and proxies become links, and how people are drawn to the frontends with their screens, is in ADR 0083. What the links look like (one relationship per call, send, datasource or setting between units and the topics, queues, exchanges, databases, brokers, external systems and infrastructure they reach, each with the facts on both ends, its protocol, its profile and a stable link ID), how profiles are applied, and how a library's code takes the config of each unit that bundles it are in ADR 0042.

**Scope modes**

- **Per repo:** each repo is its own scope.
- **System:** all repos in the snapshot, the named system's, form one scope.
- **Auto-grouped:** the tool proposes groups, and each group is a scope.
- **Custom:** any saved selection of entities, using the same selectors as presentation rules.

**Auto-grouping**

1. Build a weighted graph. Edge weights reflect coupling strength: synchronous calls and shared tables weigh more than library use of a common utility.
2. Exclude ubiquitous libraries (logging, commons) from the weights so they don't pull everything into one cluster.
3. Run Leiden community detection (the CWTS Java implementation).
4. The LLM names and describes each cluster from its members' summaries.
5. Groups are stored as entities. Users can rename them, move members, pin them or dissolve them. Those changes are knowledge annotations and survive re-clustering.

On later snapshots, clustering is seeded with the previous groups so membership stays stable unless coupling has really changed.

## LLM integration

The LLM handles understanding that static analysis can't provide, and finds what static analysis misses, always working from extracted facts and real code, never from memory alone. Providers are configurable against the OpenAI or Anthropic API formats.

**Roles** (most frequent first)

| Role | Input | Output |
| --- | --- | --- |
| Summarize | A code unit's facts and child summaries; for a type, its whole source file with secrets masked; for a module or repository, a fact sheet of its parts | Hierarchical summaries at each level |
| Resolve | A link left open, its facts and its candidates' facts, with tools to query the graph and read files when its model can use tools | New facts with `origin=llm` and a confidence |
| Investigate | A deployable unit's or the system's facts and links, with tools to query the graph and read the repos' files (ADR 0081) | Proposed elements (containers, data stores, external systems, people, components), connections and risks, each citing file:line ranges; the verified ones become facts with `origin=llm` and a confidence |
| Infer intent | A deployable unit's facts, README claims and the start of its components' code; a data store's or destination's facts and uses; a system's units' responsibilities with the facts they cite, and its README claims; the glossary once annotations exist | Responsibilities of units, components, data stores and destinations, intent of their links, domain role, a system's purpose |
| Name groups | Cluster members' summaries | Group name and description |
| Propose rules | Repeated unresolved patterns | Declarative extractor rules (see Learned rules) |
| Compose | Document model plus facts in scope; the ranked candidate flows; a main flow's traced facts | Narrative sections for an audience; which candidate flows are meaningful, each with a reason; a flow's walkthrough and pre- and post-conditions |
| Translate | A user's natural-language instruction | A structured annotation or presentation rule |

**Models (Anthropic):** Claude Sonnet for the high-volume roles (Summarize, Name groups, Compose, Translate); Claude Opus for the reasoning-heavy ones (Resolve, Investigate, Infer intent, Propose rules). The models and budget of the real-model runs that accept Phase 2 are in ADR 0070.

**Provider interface**

- A thin Java interface over the official Anthropic and OpenAI SDKs, with an OpenAI-compatible endpoint option for gateways.
- **Capability flags** per configured model: tool use, structured output, prompt caching, context window, cost per token. Callers check capabilities instead of assuming them.
- Different models can be set per role, for example a cheaper model for leaf summaries and a stronger one for composition.

**Grounding**

- Every LLM output must cite the facts or summaries it used; a source file is cited through the fact observed in it. Investigate's proposals are the exception: they cite file:line ranges, which Lantern verifies (below). An output that fails its checks, for example one that cites nothing, is sent back once with the reason, and dropped if it fails again (see ADR 0010). A summary sentence that cites nothing is left out of the summary (ADR 0061), and a summary sentence or a responsibility sentence, intent phrase, narration or system's purpose that names a class, method, table, topic, path or technology none of its cited inputs mention is left out too (ADR 0068).
- The glossary (from annotations) is included in prompts, once annotations exist (Phase 3).
- The LLM words what extraction found: names, the responsibility sentence of each element, the intent phrase of each relationship, a system's purpose and "start here". Through Investigate it may also find what extraction missed, but only with evidence Lantern checks. Every box, line and risk in a document comes from a static or rule fact, an annotation, a Resolve result that picks among candidates the code shows, or a verified Investigate proposal. Nothing else is drawn or stated as found.
- **Investigate's guard rails.** Every proposal cites one or more file:line ranges in the snapshot's repos. Lantern checks that each range exists at the snapshot's commit and holds the identifiers (class, method, property, path, topic, host) the proposal names, and rejects a proposal any citation of which fails. Accepted proposals are stored as facts with `origin=llm`, their citations as evidence and a confidence below a static fact's. Static extraction stays the main source and wins on conflict: a proposal that repeats or contradicts a static or human fact is dropped. Every document marks what Investigate found as found by the LLM, in every template; the C4 report spec marks elements, lines and table rows whose facts all come from the LLM (Investigate, or a link Resolve picked) with its LLM badge (ADR 0086). Investigate is cached by what the model sees and runs within the run's budget, like every role; tests use recorded responses.
- What code and deployment files can't show (owners, rationale, quality goals, people the code does not name, production topology beyond deployment files) comes from annotations or README claims, or is stated once, plainly, as unknown. Business purpose comes from what people wrote (Lantern annotations, README claims, Javadoc) where there is any; otherwise it is inferred from the code and every document shows it as inferred, never as fact. The quality bar does not require a purpose for now, so the system's purpose (ADR 0060) stays as it is, without further work, and is not scored or judged. Each summary's purpose sentence, and each responsibility sentence, is marked as evidenced or inferred.

How summaries are built bottom-up, cached and cited is in ADR 0010, and how their cache keys on the prompt in ADR 0055; how type prompts show their source files, and how a unit too large for one prompt is summarized in parts, is in ADR 0024; how secrets are masked in those files before they are sent is in ADR 0054; how module and repository prompts show a fact sheet of their parts' entry points, links, data stores and technology is in ADR 0057; how the prompt asks for plain, purpose-first wording at each level is in ADR 0025; how a summary marks its purpose as evidenced or inferred, and how documents show it, is in ADR 0059. How the LLM words the responsibility sentences of units and components and the intent phrases of their links, and how documents use them, is in ADR 0050; the responsibility sentences of databases, topics, queues, exchanges and external systems are in ADR 0056; how documents show a responsibility sentence that cites nothing people wrote as inferred is in ADR 0065; how the unit prompt shows the start of each component's code, and how Javadoc in it counts as evidence, is in ADR 0069. The confidence each summary and wording stores, and how a unit without a responsibility sentence is described by its module's summary, marked as such, is in ADR 0066. How the LLM narrates each main flow, its walkthrough and pre- and post-conditions, from the facts of its trace is in ADR 0058. What Resolve is shown about a link left open, and how its answer is checked and cached, is in ADR 0045. How it words the system's purpose from its units' responsibility sentences and its README claims, and how the system's report marks a purpose inferred from the units, or states it as unknown, is in ADR 0060. How every prompt tells the model that its inputs, which come from the repositories, are data and not instructions is in ADR 0067. Why the LLM may now find, through Investigate, is in ADR 0070; what Investigate is shown and how its citations are checked comes with #269.

**Cost and reliability**

- Results are cached by what the model sees (prompt version, provider, model and prompt), so a commit that changes nothing a prompt shows makes no call.
- Per-run and per-scope budgets in tokens and dollars, with an estimate shown before a run starts (configured run budgets and the estimate are #42).
- Every call is logged with its role, purpose, model, tokens and cost.
- Tests never call a real provider: they use a fake or recorded responses (see ADR 0008).
- Calls run in parallel on virtual threads, with concurrency and rate limits per model and retry with backoff (see ADR 0021).
- Models that think by default can have thinking turned off or extra output room per model, and an answer cut off at its output limit is logged as such (see ADR 0022).
- A role with tools holds a conversation: the model calls tools and gets their results until it answers. Each turn is a logged call; each conversation is logged with its turns, tool calls by tool, tokens, cost and how it ended, and the run's record shows them by role. Only the run's budget and a high safety cap on turns bound it (see ADR 0081).

## Learned rules (self-improving extractors)

When the LLM keeps resolving the same in-house pattern, it proposes a declarative rule so static analysis handles that pattern from then on. Low-risk rules are approved automatically; the rest wait for a human.

**Rule format.** Declarative, not arbitrary code. For example: "a method annotated `@AcmeEventHandler` consumes the Kafka topic named by its `topic` attribute." Rules match on annotations, types, call signatures, config keys and file patterns, and emit facts in the core envelope. Sandboxed scripts through the out-of-process protocol are a later option.

**Lifecycle:** proposed, then tested, then risk-assessed, then auto-approved or queued for review, then active. A rule can be retired at any time.

**Risk assessment.** The LLM's own assessment is one input, combined with objective signals:

| Signal | Low risk when |
| --- | --- |
| Test result | The rule reproduces every example that motivated it |
| Blast radius | It adds few facts, and changes or removes none |
| Conflicts | It contradicts no static or human fact |
| Vocabulary | It introduces no new types |
| LLM assessment | The model rates it low risk with a stated reason |

All low: auto-approved and logged. Any other result: queued in the UI with the evidence. Administrators can bypass the review step per rule or turn auto-approval off entirely.

**Clean retraction.** Every fact records the rule and rule version that produced it. Retiring a rule removes its facts and invalidates everything derived from them through the normal incremental mechanism.

## Annotations: knowledge layer

Knowledge annotations are human-supplied facts. They are stored only in the service database, tied to the signed-in user, and never written into repositories. They add the tribal knowledge code can't show and correct what the tool got wrong.

**Kinds**

| Kind | Example | Effect |
| --- | --- | --- |
| Correction | "orders calls billing-v2, not billing-v1" | Overrides or suppresses a fact, everywhere |
| Addition | "A mainframe batch job calls this endpoint nightly" | Adds facts no repo contains, such as external systems |
| Context | "Being decommissioned Q3; replaced by ledger-service" | Narrative attached to an entity |
| Metadata | Owner team, criticality, lifecycle, PII handling | Structured attributes for filtering, selectors and views |
| Grouping | "These six services are the Pricing domain" | Pins or overrides auto-grouping |
| Glossary | "RTP = Real-Time Pricing engine" | Included in every LLM prompt |
| Exclusion | "Ignore `test-harness/` and generated code" | Removes noise before analysis |
| Answer | A reply to a question from the questions queue | Becomes a fact, usually an addition or correction |

**Anchoring.** Annotations attach to stable entity IDs, never file lines. If the anchored entity disappears, the annotation becomes **orphaned**. The tool tries rename detection (same shape, new name) and the LLM can suggest the new anchor; a human confirms. Orphaned annotations are never dropped silently.

**Staleness.** Each annotation records the snapshot it was made against. On later snapshots:

- Evidence unchanged: applied.
- Evidence the annotation depends on has changed (for example, "orders has no DB writes", but a new commit adds a JPA repository): marked **needs review**, and the conflict is shown in documents.
- Anchor gone: **orphaned**.

States: active, needs review, orphaned, retired. Precedence is human, then static, then LLM, but only while the evidence the human saw is unchanged.

**Questions queue.** When confidence is low or analysis hits a dead end, the tool asks a question in the UI, for example "Topic `inventory-sync` is produced but nothing in the analyzed repos consumes it. What does?" Questions are routed to the owners of the entities involved where owners are known.

**Storage and audit.** Annotations are append-only events: create, edit, confirm, retire, each with user and time. The audit log is that event stream, and any past annotation state can be rebuilt for reproducing an old document.

**READMEs and the wiki.** README content becomes low-trust "documented claims". Claims that contradict code are flagged, for example "README says MySQL, code connects to Postgres". The wiki is not ingested. Which claims are checked, how, and what a flag records is in ADR 0043; how documents show the flags is in ADR 0051.

## Annotations: presentation layer

Presentation rules control how knowledge is shown without changing what is true. Any departure from default document behavior can be expressed as a rule.

**Knowledge vs. presentation.** "Don't show this" can mean two things. "This is wrong" is a knowledge correction, so it disappears everywhere. "This is noise here" is a presentation rule, so the fact stays true but is hidden in that scope. The UI always makes the user choose, so tidying an exec report never deletes knowledge by accident.

**Rule shape: selector + properties + scope**, like CSS.

```yaml
- selector: "type:service"
  style: { fill: blue }
- selector: "type:database"
  style: { fill: orange }
- selector: "id:service:a, id:service:b, id:service:c"
  group: { label: "Checkout", box: true }
- selector: "lifecycle:deprecated"
  style: { opacity: 0.5, badge: "deprecated" }
- selector: "id:lib:commons-utils"
  visibility: hidden
  scope: { audience: executive }
```

- **Selectors** match by ID, type (including parent types), group, tag or any attribute. Rules written against types keep working as the system grows: a new database added next month is orange automatically.
- **Properties** cover visibility, grouping and boxes, color, shape, icon, labels and renames, emphasis, level of detail ("collapse library internals"), layout hints ("A left of B", "databases at the bottom"), section order and inclusion, and tone and terminology for generated text.
- **Scopes**, from least to most specific: default theme, audience profile, document, diagram, single element. The most specific rule wins.

**Shared and personal.** Rules on a shared document apply to everyone and need edit rights on that document. Anyone can add a **personal overlay** that changes only their own view.

**Three ways to create a rule**, all stored the same way:

1. **Natural language:** "group A, B and C together". The LLM translates it into a structured rule, shows it back with its scope for confirmation, and the structured rule is stored. The original wording is kept in the audit log. Prose is never re-interpreted on later runs.
2. **Direct manipulation** in the diagram UI: drag, hide, group, recolor.
3. **A rule editor** for exact control.

**Explainability.** A "why does this look like this?" inspector, like a browser's computed-styles panel: "Hidden by rule #12 (set by User, 3 Oct, scope: executive)."

Presentation rules follow the same lifecycle as knowledge annotations: anchored to stable IDs, versioned, audited, and flagged as orphaned when their target disappears.

## Checking the quality bar

The standard every document must meet, with its rubric and the evidence behind it, is `docs/quality-bar.md`. Lantern checks it in four ways.

- **On every run.** The composer makes the rubric's checks that the document model can decide (which ones is in ADR 0046) and puts the failures into the document as a quality report block (ADR 0028). The rest (whether a responsibility sentence restates its name, whether a sentence would be true of any Spring application, whether a first paragraph gives the most important fact, whether a diagram is readable) are judged, below.
- **Scoring** (#252) compares Lantern's system report with the reference of a test set (Test sets), without an LLM: recall and precision for containers, data stores, queues and exchanges, external systems and people, for connections between containers by protocol, for flows (by trigger and participants), risks (by subject) and decisions, and which checks pass. Its scorecard lists the missing and extra items by name, and records the model of every role and the run's cost, which `analyze` writes beside its documents; every role's model is chosen per run from a named settings file or per-role overrides, and two scorecards can be compared side by side. How items are matched, the alias table per set and the run record are in ADR 0072.
- **Extraction coverage** (#380) says, per test set, which reference items the extracted facts back before any linking, so a scorecard's loss is split into extraction and linking: each set's coverage map names, for every reference item, the extractor areas that should find it and the code they should cite (file:line), or marks it linking only; an item is backed when a fact of each area has evidence on its cited lines. `analyze` writes the extracted facts beside its documents for it. Coverage measures recall only, and a sampled hand check at each baseline and test drop guards against coincidental matches. The map, the match and the report are in ADR 0089; the research behind it in `docs/research/extraction-coverage.md`.
- **Judging** (#277): an LLM judge compares each section of Lantern's report with the reference's against the rubric, with reasons cited to both documents. The judge's model, like every role's, is chosen per run.

A reference is a report of a test set written by hand from cited code facts (file:line), in the format of the C4 report spec (`report-specs/`), checked line by line by a second, independent reader, and kept in `testsets/<set>/reference/` with the commits it describes. Each milestone of a phase sets targets for the scores (its milestone in GitHub); targets are confirmed only once a baseline is measured. Real-model runs are recorded, so they can be replayed without cost.

**Other documents (out of scope for now).** The checks of the Architecture Explorer and the Executive Brief, kept for when they come back into scope (Non-goals), with the bar's *(review)* marks for what is judged:

| Document | Passes only if |
| --- | --- |
| Architecture Explorer | An orientation section of at most 300 words followed by a "start here" list of the entry points and files to read first; the container diagram first; the main flows as sequence diagrams; a catalog of containers, components, endpoints, queues, tables and config, each linked to source; change guidance ("to add an endpoint, follow X in Y") *(review)*; observed decisions, with rationale quoted from ADRs or READMEs or marked unknown |
| Executive Brief | Business purpose first, in one sentence *(review)*. Who depends on it and what it depends on (blast radius). Technology currency: runtime and framework versions against their end of life. Risks ranked by impact, each with its consequence and confidence. Quality evidence (tests, architecture tests and coverage where present), with quality goals marked unrecorded when absent. The decisions or options the reader faces *(review)*. No implementation detail *(review)* |

Change-history measures (activity level, hotspots of complex code that changes often) need git history beyond the snapshot and come later. Until then the Brief doesn't present static findings as ranked debt.

**Readable diagrams.** The bar sets no element cap. A view is split at the same level, or detail folded away, when a readability signal passes its threshold: the number of elements, the number of lines, and line crossings after layout, each threshold configurable. Until #366 measures the defaults on the test sets, the views split above 15 elements.

## Composition, layout stability and prose stability

Composition decides what a document says and shows; rendering only formats it. Composition takes the previous rendition as an input, so a rerun on the same or slightly changed knowledge produces the same or a slightly changed document.

**Composer inputs:** knowledge in scope (at the snapshot), the view definition, presentation rules, and the previous rendition (layout state, prior prose, color assignments). **Output:** a format-neutral document model of sections, diagrams, tables, callouts and cross-references, each element linked to the fact IDs it came from.

What the document model contains, and how its TypeScript types are generated and checked, is in ADR 0011; sequence diagrams and the quality report block are in ADR 0028; the commits and date that pin a document are in ADR 0063. How templates show a summary's citations of other summaries is in ADR 0027.

How the first composer builds per-repository documents from view definitions is in ADR 0012. How the composer draws the context, container and component views below, and composes the system scope's C4 report, is in ADR 0044. How it traces, stitches and ranks the candidate sequence flows, and starts them in the browser, is in ADR 0047, and how it uses the LLM's walkthrough and conditions of each in ADR 0058. Which checks of the quality bar the composer makes, and how it reports them, is in ADR 0046. Why a line carries one intent phrase, not a list of them, is in ADR 0088. How it composes the Architecture Explorer's start-here list, catalog, change guidance and decisions with their rationale, and orders the Explorer's sections, is in ADR 0048. How each default view names its reader, how the composer builds the glossary and how Lantern's templates draw legends is in ADR 0063; how the glossary takes meanings from the repositories' documentation, and the C4 report's glossary and legend, in ADR 0087.

**View definitions.** A spec per document: scope, audience profile, template, sections and diagrams. Lantern's defaults are kept in code, so their new sections reach every workspace; a changed view is persisted (ADR 0047). Regenerating a document means applying the same view definition to a new snapshot, so its structure doesn't depend on the LLM's choices each run.

**Diagrams.** Lantern uses the C4 levels with c4model.com's definitions. The software system is the scope: all repos in system scope, a group in grouped scope; a per-repo document shows its repo's containers inside that system. External systems are outbound targets the linker can't resolve to a container in scope, or come from deployment facts, outbound protocols such as mail (#260) and identity providers (#261), verified Investigate proposals or annotations. A container is a runtime unit (an application, job, data store, or queue or topic), never a Maven module or jar; a component is a group of functionality behind an interface, anchored on an entry point and made of the code linked to it; a package is evidence for naming it, never what decides it. Each diagram must also pass the rubric of `docs/quality-bar.md`. The document model holds sequence diagrams as a `sequence` diagram kind (ADR 0028).

| Diagram | Contains | Built from |
| --- | --- | --- |
| Landscape | Every system in scope and the people using them; no internals | Container relationships, aggregated per system |
| System context | One system in focus, its users and the external systems it talks to directly; no technology. Unknown users are marked unknown, not invented; entry points nothing reaches are called by an `Unknown caller` (#365) | Relationships leaving the system; people from human-facing evidence (sign-in, accounts, memberships, invitations, a frontend); annotations for people and for external systems no code names |
| Container | One view of the whole system, split by concern when it is not readable (Checking the quality bar, Readable diagrams). One box per deployable unit, folding in the in-set libraries it packages (a thin service and its library are one box), and one per frontend; each service's own schema as a separate data store; queues, topics and exchanges as elements or "via" labels, never one broker box; a line from the `Unknown caller` to each container with entry points nothing reaches (#365) | Deployable evidence (a Spring Boot main class, a repackaged executable artifact, a Dockerfile), `spring.application.name`, linked HTTP client, messaging and persistence facts, frontend and deployment facts |
| Component | Inside one container, one component per role: each controller, message listener, scheduled job, gateway filter or set of gateway routes with the services and repositories reachable only from it; a bean reachable from several entry points is its own component. Domain, DTO, mapper, configuration and util classes are left out; packages only name components or break ties. When not readable, split by groups of entry points. Drawn only when it adds value: more than one component, and not a repeat of the container diagram | Spring stereotypes (including meta-annotations), aspects that advise an entry point (shown as components with a line to what they advise, ADR 0079) and the resolved call graph |
| Sequence | See Sequence diagrams below | Entry points and the resolved call graph, linked across containers |
| Deployment | Nodes and container instances in one environment; until it is drawn as a diagram, a table of each environment's containers, their images and versions and what runs in them (ADR 0082), which the C4 report shows as a documentation page (ADR 0086) | The Deployment extractor's facts and the units and data stores the linker ties to its containers; omitted when there are none |
| Dependency | Modules and artifacts with their build-time dependencies; not a C4 view | Maven facts |
| Code | Not drawn in the C4 report; its facts (types, members, calls) are extracted and linked all the same (Design principles) | Java structure facts |

Build-time library dependencies are not container or component relationships; they appear only in the dependency tree and dependency views.

**Sequence diagrams.** The architecturally significant flows of a scope, however many there are, as the runtime view.

- Traced from entry points (REST endpoints, message listeners, scheduled jobs) through the resolved call graph, keeping only steps that cross a container boundary: HTTP calls, messages, database access. Traces are stitched across containers: an HTTP call continues in the callee's endpoint, a published message in each listener bound to it, and a method that schedules a background job in that job.
- A flow whose entry point a frontend's call reaches starts in the browser: the person who uses the frontend, the frontend's request, and the gateway route it goes through, if any. Otherwise a flow whose endpoint a gateway forwards to, through a route or its discovery locator, starts at the gateway with the request as the gateway receives it, before the route rewrites its path. A socket the frontend opens to a WebSocket endpoint starts a flow through the endpoint's inbound interceptors. A flow whose entry point nothing in scope reaches and no person uses starts at the `Unknown caller` (#365); a scheduled job's flow starts with a note naming its trigger.
- Participants are all at one C4 level, containers by default, with the container diagram's names: one lifeline per container (its internals collapsed), one per queue or exchange, and actors for people and external systems.
- Labels put intent first and protocol second ("Create order [POST /orders]"). Async messages are drawn as async and followed by a note that the rest happens later. Replies are drawn only when they carry meaning. Profile-gated steps are conditional (`docs/quality-bar.md`); at most one level of fragments.
- Candidate flows are ranked by the containers they touch, whether they hand off asynchronously and whether they handle failure, ties broken by stable ID; the flow a person signs in through and the socket a frontend opens come first, then what someone asks for before what a listener or a schedule starts, and a flow that reaches only databases comes last; a flow of the same shape as one shown (started the same way, the same lifelines in the same order) is not shown again. From the ranked candidates the LLM (role Compose) keeps those that are architecturally meaningful for the system, each with a one-sentence reason, and only those are drawn; it can keep only traced flows, never add one (#272). There is no fixed number: a small system may have two, a large one twenty. A narrower scope shows those that start in or pass through it.
- A method is traced each time the code calls it, a unit's sends to its own listeners are delivered, and a run of data access in one database is one step.
- Each has a title naming the scenario, worded by the LLM, and its trigger, and two to four sentences of prose.

**Granularity through hierarchical summaries**

| Level | Typical audience | Content |
| --- | --- | --- |
| L0 | Executive | The system on a page: capabilities, key dependencies, risks |
| L1 | Architect, new engineer | Groups, services, how they communicate, data stores |
| L2 | Engineer | Inside a service: components, APIs, entities, config |
| L3 | Deep dive | Packages, key classes, flows |

Audience profiles choose levels, sections and tone. One HTML file can embed several levels and let the reader switch between them.

**Layout stability**

- Node positions are stored per diagram, keyed by entity ID.
- On later runs, existing nodes keep their positions and only new nodes are placed, using ELK's incremental layout around the fixed nodes.
- Removed nodes leave a gap.
- When a change passes a threshold (default 10 new nodes in one diagram, configurable per instance and per view), automatic re-layout is allowed, but **positions pinned by users never move**. Only tool-placed nodes are rearranged.
- Inputs are sorted by stable ID and no algorithm uses random seeds, so a run on identical input gives identical output.
- Colors are assigned once and persisted per entity unless a rule says otherwise.
- Dragging a node saves a pinned-position rule, recorded in the audit log.

**Prose stability**

- A section whose input facts are unchanged reuses its exact text.
- A section whose inputs changed is regenerated from the previous text plus the fact diff, with the LLM asked for a minimal edit rather than a rewrite. Documents then read as continuous across versions and diff cleanly.

**Change overlay.** Because layouts are stable, diagrams can show what changed between two snapshots: new elements highlighted, removed ones ghosted, changed relationships marked.

## Rendering and templates

Renderers turn the document model into a format and make no content decisions. Single-file HTML is the primary format. Markdown with Mermaid, PDF and Confluence follow as further renderers of the same model.

**Template structure**

- **Layout:** page structure, navigation, responsive behavior.
- **Theme tokens:** color, typography, spacing, density. Presentation rules override tokens instead of hard-coding styles.
- **Components:** diagram canvas (zoom, pan, minimap), entity card, dependency and API tables, schema view, change overlay, annotation callouts, provenance and confidence badges, glossary tooltips.

**Using the browser well**

- App-shell layout: navigation tree on the left (system, groups, services, modules), content and full-width diagrams in the center, and a context panel on the right with the selected entity's details, evidence and annotations.
- Responsive with CSS grid and container queries: three panes on wide screens, two on laptops, one pane with slide-out drawers on tablets and phones.
- Density toggle (comfortable or compact), granularity switch (L0 to L3), client-side search, deep links, light and dark themes, and a print stylesheet that makes PDF export largely free.
- Truly single-file: CSS, JS, diagram data and in-scope knowledge are inlined with no CDN, so it works offline and as an email attachment. Large scopes use lazy rendering and virtualized tables.

**Two modes, one codebase.** The same TypeScript components build the live hosted UI (editable: annotations, drag-to-pin, rule editing) and the exported file (read-only, with a "suggest a correction" link back to the service).

**Built-in templates**

| Template | Audience | Character |
| --- | --- | --- |
| Executive Brief | Leadership | One scroll, narrative-led, few large visuals, headline numbers and risks |
| Architecture Explorer | Architects, senior engineers | Dense, interactive app shell, everything navigable |
| Onboarding Guide | New engineers | Chapters, "start here", suggested reading paths |
| Service Datasheet | Anyone | One page per repo or service: APIs, dependencies, data, config, owners |
| Change Report | Reviewers | Snapshot-to-snapshot diff with the change overlay |

What the Executive Brief shows, which risks it checks for and how it lays out is in ADR 0015; its blast radius, technology currency and the consequence and confidence of its risks are in ADR 0052, and its quality evidence in ADR 0062 and ADR 0064.

Target documents that Lantern is to generate are in [`report-specs/`](../report-specs/), one folder per document with its template, schema and sample data. The specs are the user's. Each schema says what data its document needs. A renderer per report spec writes a document model into the spec's template, as that schema describes; the analyze run writes the C4 report for each repository and for the named system (ADR 0031, ADR 0044). In the C4 report, C4 pages hold only C4 content; findings (risks, decisions, unknowns), the glossary, the catalog, the deployment table and the sources are documentation pages, their own navigation group after the sequence diagrams, and what the LLM found carries the spec's LLM badge (ADR 0086).

Templates are versioned. Upgrading a template doesn't change a published document's look until the owner opts in. Prototypes will be built against a realistic fictional dataset and refined from there, starting with Architecture Explorer and Executive Brief.

## Hosted service

Lantern runs as a single Spring Boot service on one VM, with PostgreSQL for all persistent state and Docker or Podman for build sandboxes. Repos are cloned on the same host.

**Components on the VM**

| Component | Responsibility |
| --- | --- |
| API and UI server | REST API, serves the web UI, auth, permissions |
| Job scheduler and workers | Analysis runs as resumable jobs: build, extract, link, summarize, compose, render |
| Build sandbox runner | Starts and limits build containers, collects outputs |
| PostgreSQL | Facts, vocabulary, snapshots, annotations and audit events, layouts, cached LLM results, documents |
| Workspace storage | Repo clones, Maven cache (a host folder of its own, ADR 0005), build outputs, rendered files |

**Jobs.** Each stage is a persisted job step, so a failed or interrupted run resumes from the last completed step. Progress and estimated LLM cost are visible in the UI. Until jobs exist, an `analyze` command runs all stages in one process (ADR 0016, #56).

**Users and permissions**

- Every user signs in. The identity provider is chosen in the application properties with lantern.auth.provider: keycloak (OIDC via Spring Security's OAuth2 client; issuer URI, client id and secret as properties) or ldap (Spring Security LDAP bind against the directory; URL, base DN, user and group search filters as properties). Keycloak realm roles or LDAP groups map to Lantern roles through a configurable mapping. Local accounts remain as a fallback.
- Roles: viewer, contributor (annotate, answer questions), document owner (edit shared presentation rules), administrator (repos, vocabulary, rule approval, providers).
- Every write is an audit event with user and time.

**Security**

- LLM API keys and git credentials are stored encrypted, outside the fact tables.
- Build containers have network access only to the configured artifact repositories.
- Code sent to the LLM is limited to what each task needs, and every call is logged with its purpose.

**Scaling path.** A single VM is enough to start. Workers and build runners are separate within the codebase, so they can move to their own machines later without redesign.

## Technology stack

The backend is Java 21+ on Spring Boot because the hardest part, Java analysis, depends on JVM libraries, and the target ecosystem and its maintainers are Java. The frontend is TypeScript because it runs in the browser.

| Area | Choice |
| --- | --- |
| Backend language and framework | Java 21+, Spring Boot, virtual threads |
| Database and migrations | PostgreSQL, Flyway |
| Java analysis | javac Tree API with annotation processing; JavaParser with symbol solver as fallback |
| Maven | Maven Resolver and Maven Model; `mvn` run inside build containers |
| SQL | JSqlParser; Flyway and Liquibase changelog parsing |
| Graph and clustering | In-memory graph; Leiden (CWTS Java implementation); JGraphT for algorithms |
| Diagram layout | ELK in Java on the server; elkjs in the browser (same engine) |
| LLM access | Official Anthropic and OpenAI Java SDKs behind a capability-aware interface |
| Build sandbox | Docker or Podman, per-JDK images |
| Frontend | TypeScript; a single component codebase built as the hosted app and as a single-file bundle |
| Shared schemas | JSON Schema for the fact envelope and document model; hand-written Java types checked against it by tests, generated TypeScript types |

## Phasing

Each phase delivers something usable on real repos. The knowledge model and stable IDs come first because everything later depends on them.

**Test sets.** From Phase 2, Lantern is measured on several test sets, chosen together to cover different styles: messaging technology, package-by-feature and package-by-layer code, thin services that package a library, gateways, frontends and deployment repositories, and a monolith. No single test set shapes the design. Each set's pins and setup are in `testsets/<set>/` (ADR 0029), with its reference where it has one (Checking the quality bar).

- **Development sets:** the open-source systems project-1 to project-4, each with a reference. Development, the evidence of every change and every milestone's test drop use them.
- **How a set is stored.** `testsets/<set>/` holds `pins.txt` (repository, clone URL, full commit), `setup.sh` (clones them at the pins) and, for project-1 to project-4, `reference/` (the data in the report spec's format, the cited facts, `notes.md`, `verification.md`), with a build that checks each reference is valid and describes the pinned commits. Format and tools: ADR 0029, amendment for #251.
- **Held-out sets:** the open-source systems project-5 (booking-microservices), project-6 (spring-petclinic-microservices) and project-7 (piggymetrics). They are pinned, but nobody reads their code or analyzes them until Phase 2's real-model acceptance, where their references are written and they are analyzed, scored and judged once against the same bar, so the bar is not met by tuning to the sets Lantern was built on.
- **Adequacy.** The sets are reassessed at each milestone's test drop: does every extractor, linking rule and diagram kind Lantern has have a set that exercises it, with a reference or an exact answer? Each gap becomes an issue, to add a case to a synthetic set or to add or replace an open-source set.
- **Synthetic sets:** project-0 (ADR 0029) and its larger successor project-8 (FleetCare, #285), written for Lantern, each listing its cases with the exact answer. They are development sets too, never held out. They cover what the open-source sets miss, and no change may lower their scores. project-8's reference stays in its source, which its `setup.sh` puts beside the checkout, and its fake-model scorecard is committed as its baseline (ADR 0029, amendment for #285).

1. **Foundation** (built; remaining fixes tracked in its milestone): a single repo through to a real document.
   - Repo registry, build sandbox, Maven and Java structure extractors
   - Fact store with envelope, vocabulary registry and snapshots
   - Bottom-up LLM summaries
   - Architecture Explorer and Executive Brief templates, single-file HTML, per-repo scope
2. **System view** (next, before the remaining Phase 1 fixes; see ADR 0026): many repos as one named system, measured against references (ADR 0070).
   - Spring, HTTP client, messaging, WebSocket, persistence, config and SQL extractors, following values
   - Deployment and frontend extractors; repos without a Maven build
   - Cross-repo linker, LLM resolver and Investigate
   - Deployable units, container, component and sequence views, findings, and the quality bar's checks
   - Executive Brief blast radius, technology currency, ranked risks and quality evidence (built; further Brief work is out of scope for now, Non-goals)
   - System scope, audience profiles, granularity switch
   - References, scoring and the LLM judge

   It is delivered in milestones that each deliver one outcome, with its own goal and acceptance criteria in [GitHub](https://github.com/reubenbubu/lantern/milestones), grouped in five parts: **2A** measured on real systems (references, scoring, one named system per analysis), **2B** find the whole system, **2C** compose to the bar, **2D** real-model acceptance (including the held-out sets), **2E** foundations for Phase 3, without changing the output.
3. **Living docs:** versions and human knowledge.
   - Incremental runs, architecture diff, Change Report template
   - Knowledge annotations, questions queue, audit log, sign-in and roles
   - Layout and prose stability
4. **Control:** presentation and grouping.
   - Presentation rules (natural language, direct manipulation, editor), personal overlays, inspector
   - Auto-grouping with Leiden
   - Onboarding Guide and Service Datasheet templates
5. **Self-improvement and reach:** learned rules and more formats.
   - Learned rules with risk assessment and approval
   - Markdown, PDF and Confluence renderers
   - Further extractors: OpenAPI and AsyncAPI

## Open questions

- [x] Which identity provider will sign-in use (Azure AD / Entra ID, Okta, Keycloak, other)? Decided: Keycloak or LDAP, selectable by property (see Users and access).
- [x] Which in-house frameworks or annotations are most common, so extractors and early learned rules can target them? Decided: Spring Boot first; the Spring extractors take priority and early learned rules target Spring annotations.
- [x] Which LLM provider and models for the first deployment, and what monthly budget? Decided: Anthropic, $100/month budget. Sonnet for high-volume roles, Opus for reasoning-heavy ones (see LLM integration).
- [x] Rough scale for sizing the VM: number of repos and total lines of code in the first target set. Decided: the first test set (since dropped, #100) fits the existing development VM with up to 4 build containers at 4 GB each; its measurements are in ADR 0004.
- [x] Where does end-of-life data for technology currency come from? Decided: a table of Java, Spring Boot and Spring Framework end-of-life dates bundled with Lantern and updated each release, so no network call or new dependency is needed (ADR 0052).
- [x] Re-layout threshold: how many new nodes in one diagram before automatic re-layout? Decided: 10 by default, configurable.
- [x] Who are the first users, and which document do they need first? Decided: User is the first user. First outputs: C4 diagrams (context, container, component), sequence diagrams, dependency trees, and runtime environment details where the sources hold them.
