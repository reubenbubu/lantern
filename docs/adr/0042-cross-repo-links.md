# 0042: Cross-repo links

Status: accepted (#94), amended by ADR 0070 (#250), pending the user's review (#24)

## Context

`docs/design.md` (Cross-repo linking, scoping and grouping) says the linker matches what one
repository exposes with what another uses: HTTP calls by client name against
`spring.application.name`, else by URL, then the path against endpoints; messaging by names and
routing keys, AMQP topic wildcards matched as the broker matches them; shared databases and
config. A link that holds only under a profile records it; service discovery, config servers and
tracing are infrastructure, not business dependencies; what static matching cannot resolve goes to
the LLM resolver. The Quality bar asks every line between containers to be one-way, link to its
facts, name a protocol and be marked conditional with its profile. The extractors record the
inputs (ADRs 0032 and 0034 to 0038) and the deployable units are the containers (ADR 0039). The
design leaves open what the links look like, how profiles are handled, where a call goes when only
its path is known, and what is matched when.

## Decisions

- **A second linker step.** `CrossRepoLinks` (`lantern-link`, origin `cross-repo-links`, version
  1, kind `static`) runs after `DeployableUnits` and, like it (ADR 0039), derives from every fact
  that holds in the snapshot and records for the whole snapshot. It reads the units through their
  facts: `deployable-unit`, `BUNDLES` and `application-name`, with Maven's and the Java and SQL
  extractors' `CONTAINS` to place code and tables in modules, and modules in the units that run
  them (their own module, or a library they bundle).
- **Views.** Matching runs once per view: the default one (Spring's `default` profile active),
  then each profile the facts name (config, datasources, calls, sends and `@Profile` gates), one
  profile active at a time across all units, as ADR 0032 models config. In a view, a place in the
  code or config takes its profile fact if it has one, else its default fact; code whose
  `@Profile` gate (on its method or type) does not match the active profile does not run. A link
  found in the default view is recorded once, with the facts of every view that found it, and the
  profiles under which it is not found in `exceptProfiles` (code gated `@Profile("!local")`, or a
  URL a profile points elsewhere), so a view can mark it conditional too; one found only under
  profiles is recorded once per profile, with `profile`. The profiles of a gate are the names in
  its expression (`!local` names `local`). So the audit link of
  project-0 (SPR-4) holds only under `audit`, and a URL that only the `local` profile changes to
  `localhost`, still reaching the same endpoint, is the default link, not a second one.
- **Config of a library's code.** Code in a library runs with the config of each unit that
  bundles it (left to the linker by ADR 0039). A URL or destination written with placeholders in
  a library's code is resolved again from what it was written as, with each unit's config in each
  view, as Spring Boot finds it on the unit's classpath (ADR 0039's rule for the name); a value a
  secret was substituted into is not used.
- **HTTP** (`CALLS_HTTP`, a child of `CALLS`, from the calling unit). The callers are the units
  running the call's code; for a Feign client, only those also running code that enables it
  (`ENABLES`, ADR 0038), since nothing creates a client no one enables. The target is found by, in
  order: the Feign client's name against unit names when the URL is the load-balanced
  `http://<name>` one (`matchedBy: service-name`, factor 1.0); the URL's host against unit names,
  or the first label of a Kubernetes `*.svc` host (`host`, 0.9); the host against units' artifact
  IDs (`artifact-id`, 0.8). Then the path, behind the target's `server.servlet.context-path` and
  `spring.mvc.servlet.path`, against its endpoints with the same HTTP method: template variables,
  `*` and a trailing `**` match segments, expressions in the call's path (`{productId}`) match any
  segment, and the endpoint with most literal segments wins, then the first by ID. A target
  without a matching endpoint is still linked, at 0.8 times the confidence. A call to
  `localhost` is linked by its path alone when exactly one other unit serves it (`path`, 0.6).
  A host with a domain (or an IP address) that names no unit is an external system,
  `external-system:<host>`, a child of `system`, keyed by its host alone. When the same call
  reaches different units or systems under different profiles, each of its links lists the
  others in `alternates`: PayGate's sandbox host is the default target, `exceptProfiles: [prod]`,
  and its `prod` host a conditional one, each the other's alternate (HTTP-4), so a view can draw
  them as one system without the linker guessing that two hosts are one. A host without a domain
  that names no unit (`http://inventory-svc`, a compose, Kubernetes or discovery name) is not
  taken for an external system: it is left open as `unknown-host`, with the units serving its
  path as candidates. A unit calling itself is not linked.
- **Messaging.** Topics, queues and exchanges become entities, children of `container`, keyed by
  broker and name: `topic:kafka:<name>`, `queue:jms:<name>`, `queue:rabbitmq:<name>`,
  `exchange:rabbitmq:<name>` (a JMS destination is taken as a queue). A unit `PUBLISHES` to what it
  sends to and `CONSUMES` from what it listens on (a Kafka topic pattern: each topic sent to that
  it matches); an exchange `ROUTES_TO` each queue bound to it. A send reaches a listener, a
  `SENDS_MESSAGES_TO` link from sender to listener unit: Kafka by topic name, or by a listener's
  pattern as a Java regular expression (0.9); JMS by destination name; RabbitMQ through the
  bindings declared for the exchange in any unit's code, the routing key matched as the
  exchange's type routes it (topic patterns with `*` and `#`, direct literally, fanout always,
  headers at 0.5; a binding of unknown type is read as topic when its key has wildcards), then the
  listeners on the bound queue; a send to the default exchange by queue name. These containers
  carry no broker: a broker is never one box (design, Diagrams).
- **Databases** (`USES_DATABASE` to `database:<server>/<database>`, a child of `container`). A
  unit's datasources come from its config as Spring Boot finds it, one per key; units on the same
  server and database share the entity, which gets a `technology` fact (the attribute type now
  describes containers, not only units). An embedded database without a server is the unit's own
  (keyed by the unit), and so is one whose server or database is a placeholder no config resolves (#255): it is named by its database when that is written out, else by the unit, and is never shared. The link lists the schemas the unit's migrations build. A unit whose code
  reads or writes (`READS_TABLE`, `WRITES_TABLE`, ADR 0036) a table, by name, that another unit's
  schema declares and that it does not declare itself, `ACCESSES_DATA_OF` that unit, with the
  tables in `reads` and `writes` (DB-5), when in the view the database of the first unit's main
  datasource (`spring.datasource.url`, `jdbc-url`, Hikari's, else `spring.r2dbc.url`) is the one
  the second's migrations run on (`spring.flyway.url` or `spring.liquibase.url`, else its main
  datasource). Two units that merely both reach some database do not share each other's tables.
- **Shared config** is matched where it names a shared server: message brokers from
  `spring.kafka*.bootstrap-servers`, `spring.cloud.stream.kafka.binder.brokers`,
  `spring.rabbitmq.addresses` or `host` (and `port`), and `spring.artemis` or `spring.activemq`
  `broker-url` (`USES_BROKER` to `message-broker:<technology>:<addresses>`, no C4 level); and, as
  **infrastructure** (`USES_INFRASTRUCTURE`, with `role`), Eureka, Consul and ZooKeeper discovery,
  a config server (`spring.cloud.config.uri`, `spring.config.import` with `configserver:`) and
  Zipkin or OTLP tracing. Keys compare as Spring's relaxed binding does, list items included. The
  target is the unit whose name is the host, else `infrastructure:<host:port>`, no C4 level, so
  views can leave infrastructure out of the business dependencies.
- **What every link carries.** Evidence is the fact on the subject's side (the call, send,
  listener, binding, datasource or config value, where it is written); `facts` lists the IDs of
  the facts on both ends, in every view; `source` and `target` the code on each end (the calling
  or sending method, the endpoint's handler or the listener), for sequence diagrams (#96);
  `protocol` the line's protocol: `JSON/HTTP` or `JSON/HTTPS` when the endpoint is a REST
  controller's, else `HTTP` or `HTTPS` by the URL's scheme, `AMQP`, `Kafka`, `JMS`, `JDBC` or
  `R2DBC`. Confidence is the lowest of the ends' times the match's factor. The intent phrase is
  the LLM's to word (design, Grounding), so a link carries none.
- **Stable link IDs.** A fact ID changes with every commit, since evidence names it. `link` is the
  first 16 hex digits of the SHA-256 of what makes the link the same in every view and snapshot:
  its predicate, both ends, the code on both, and its profile; never a commit.
- **What stays open** is `unresolved-link` on the unit, with `kind` (`http`, `message`,
  `database`), `reason` (`unresolved-placeholder`, `unknown-url`, `unknown-host`, `ambiguous`,
  `no-match`, `unknown-routing-key`), the unresolved keys and the `candidates`: units whose endpoints serve the
  path, or that listen where the exchange routes. HTTP-5's tax service is one. The LLM resolver
  that picks among candidates is ADR 0045; the questions queue is #170.
- **New vocabulary**, applied by the app on startup after the deployable units', with source
  `cross-repo-links`: entity types `topic`, `queue`, `exchange`, `database`, `message-broker`,
  `external-system` and `infrastructure`; relationship types `CALLS_HTTP`, `PUBLISHES`,
  `CONSUMES`, `ROUTES_TO`, `SENDS_MESSAGES_TO`, `USES_DATABASE`, `ACCESSES_DATA_OF`, `USES_BROKER`
  and `USES_INFRASTRUCTURE`; attribute type `unresolved-link`.
- **Not covered:** profile combinations (`@Profile("a & b")` never matches one active profile, so
  such code links nowhere), env vars and deploy-time overrides, JPA entities as table access
  (only SQL in code), a Feign client's placeholder name, a request's port compared with the
  target's `server.port`, gateways and routes, Kubernetes or compose service names that differ
  from `spring.application.name` and the artifact ID, and Spring Cloud Stream specifics beyond
  the shared fact shapes (#139).
- **project-0.** `Project0LinksIT` checks HTTP-1 to HTTP-7, EXT-1, MSG-1 to MSG-5, SPR-4, DB-5
  and the container links of FLOW-1 (HTTP-6, HTTP-7 and MSG-5 are links left open with
  candidates, for the resolver of ADR 0045), with the repositories built in the sandbox and read
  without builds; DB-5's table access needs the build (ADR 0036). It runs when
  `lantern.test.project0` is set, with the same command as `Project0BuildIT` (see Commands in
  `CLAUDE.md`) but `-pl lantern-link` and `-Dit.test=Project0LinksIT`.
