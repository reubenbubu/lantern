# 0042: Cross-repo links

Status: accepted (#94), amended by ADR 0070 (#250) and ADR 0077 (#257), amended (#265), amended by ADR 0082 (#266), amended (#307), amended (#272), amended by ADR 0083 (#267), amended (#473), amended by ADR 0090 (#518: runtime links under `INTERACTS_WITH`, brokers and infrastructure are containers), amended by ADR 0095 (#575: the step is one link rule per protocol)

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
  (keyed by the unit), and so is one whose server or database is a placeholder no config resolves
  (#255): it is never shared (Redis is the exception: units that write the same placeholder
  text for the host and port share one store, ADR 0073). The linker gives it a `display-name` attribute (#291): its database when
  that is written out, else "<unit name> database", with the datasource key in parentheses when it is
  not the main one; views show that name, with the technology as technology, never the key (which is
  `<unit>/<technology>:<name>`, the technology lowercased with spaces as dashes). A database on a
  server has none and is shown as `<database> on <server>`. The link lists the schemas the unit's
  migrations build. A unit whose code reads or writes (`READS_TABLE`, `WRITES_TABLE`, ADR 0036) a
  table, by name, that another unit's schema declares and that it does not declare itself,
  `ACCESSES_DATA_OF` that unit, with the tables in `reads` and `writes` (DB-5), when in the view the
  database of the first unit's main datasource (`spring.datasource.url`, `jdbc-url`, Hikari's, else
  `spring.r2dbc.url`) is the one the second's migrations run on (`spring.flyway.url` or
  `spring.liquibase.url`, else its main datasource). Two units that merely both reach some database do
  not share each other's tables.
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
  `no-match`, `unknown-routing-key`, `unknown-exchange`), the unresolved keys and the `candidates`: units whose endpoints serve the
  path, or that listen where the exchange routes (for an unknown exchange, on any bound queue). HTTP-5's tax service is one. The LLM resolver
  that picks among candidates is ADR 0045; the questions queue is #170.
- **New vocabulary**, applied by the app on startup after the deployable units', with source
  `cross-repo-links`: entity types `topic`, `queue`, `exchange`, `database`, `message-broker`,
  `external-system` and `infrastructure`; relationship types `CALLS_HTTP`, `PUBLISHES`,
  `CONSUMES`, `ROUTES_TO`, `SENDS_MESSAGES_TO`, `USES_DATABASE`, `ACCESSES_DATA_OF`, `USES_BROKER`
  and `USES_INFRASTRUCTURE`; attribute types `unresolved-link` and `display-name`.
- **Not covered:** profile combinations (`@Profile("a & b")` never matches one active profile, so
  such code links nowhere), env vars and deploy-time overrides, JPA entities as table access
  (only SQL in code), a Feign client's placeholder name, a request's port compared with the
  target's `server.port` (ADR 0077 compares it for gateway routes, which it links; the amendment
  for #307 for calls to this machine), Kubernetes or compose service names that differ
  from `spring.application.name` and the artifact ID, and Spring Cloud Stream specifics beyond
  the shared fact shapes (#139).
- **project-0.** `Project0LinksIT` checks HTTP-1 to HTTP-7, EXT-1, MSG-1 to MSG-5, SPR-4, DB-5
  and the container links of FLOW-1 (HTTP-6, HTTP-7 and MSG-5 are links left open with
  candidates, for the resolver of ADR 0045), with the repositories built in the sandbox and read
  without builds; DB-5's table access needs the build (ADR 0036). It runs when
  `lantern.test.project0` is set, with the same command as `Project0BuildIT` (see Commands in
  `CLAUDE.md`) but `-pl lantern-link` and `-Dit.test=Project0LinksIT`.

## Amendment (#265): a send in a shared library is its callers'

Once JitPack coordinates are matched (ADR 0006, amendment for #265), project-4's four services all
bundle crm-shared-lib's `messaging` module, whose `MessagingService` sends to
`notification-service.send-message`. Taking every unit that runs the code as a sender drew four
senders where only main-service calls the service.

- **The senders of a send in library code** are, of the units running it, those whose own module
  declares it or that run other code calling its method, directly or through other methods (the
  Java extractor's `CALLS`). A call may point at the method as an interface or a supertype
  declares it, or as a subtype inherits it, so the callers of a method include those of the same
  signature on its type's supertypes and subtypes (`EXTENDS`, `IMPLEMENTS`), as the sequence
  flows resolve dispatch. When none of them calls it (it runs from a listener or a schedule of
  the library, say), or only one unit runs it, every unit running it sends, as before. Listeners
  and bindings are unchanged.
- The step's version becomes 6.

## Amendment (#266): what the deployments fill in

A unit's own database, whose server or database is a placeholder no config resolves, keeps its key
and is still never shared, but where a deployment runs the unit its environment fills the
placeholders: the database takes its `display-name` from the database the server container
creates and its `technology` version from that container's image, and the link lists the
`deployments` (ADR 0082). Containers that run a unit or a data store are `deployed-in` facts on
it, a new attribute type with source `cross-repo-links`.

## Amendment (#267): frontends call units

A `CALLS_HTTP` link's subject may be a frontend (`frontend:<repo>[/<folder>]`): one link per URL
its code calls from the browser, and one per nginx proxy that no call goes through. Such a link
names the `proxy` prefix it went through, and `matchedBy` gains `published-port` (this machine or
a private address, by the port a compose container that runs the unit publishes) and
`container-host` (the host is a name of a deployment container that runs the unit). A frontend
built by a compose service from its folder gets `deployed-in` and that service's name as its
`display-name`. The step's version becomes 10. The rules are in ADR 0083.

## Amendment (#307): a call to this machine by its port

Once the HTTP client extractor follows a URL passed as an argument (ADR 0038, amendment for #307),
project-1's BFF calls `http://127.0.0.1:8888{...}`, its gateway's default URL: a path that is all
expressions, which several units' endpoints match.

- **A call to this machine** (`localhost`, `127.0.0.1`, `[::1]`) with a port goes to the one other
  unit whose `server.port` in the view is that port (8080 when it sets none), as gateway routes
  are matched (ADR 0077): `matchedBy: port`, factor 0.8, the `server.port` fact among the link's
  `facts`; then the path against its endpoints as for any target. A unit that sets no port is taken
  only when one of its endpoints serves the path, since other servers on this machine (an identity
  provider, say) listen on 8080 too. Only when no single unit listens
  there is the call linked by its path alone, as before.
- **Expressions in a URL** stay whole: the authority ends where an expression (`{...}`, not a
  `${...}` placeholder) starts, and a path's segments are split at slashes outside braces, so an
  expression is one segment whatever its source holds.
- The step's version becomes 14.

## Amendment (#272): a send to the unit's own listener

A send that reaches a listener of the sending unit is now a `SENDS_MESSAGES_TO` link from the unit
to itself, like any other, so the sequence flows draw the delivery (ADR 0047, amendment for #272).
Before, such sends were left out. The Executive Brief's dependents and dependencies already leave
out links inside the repository's units. The step's version becomes 15.

## Amendment (#473): one way to tell where an address leads

Each linker decided "which unit, container or external system is this address" on its own, and
the answers differed: only frontend calls matched compose names and published ports, only identity
links filled placeholders from a deployment, and a backend call to `192.168.1.10` became an external
system. `Addresses` (`lantern-link`), built once per run beside `LinkIndex` and the deployments,
now answers for HTTP calls, gateway routes, frontend calls, identity providers and mail hosts. The
linkers keep their own work: paths and endpoints, Feign and `lb://` names, identity provider
containers.

1. **Fill.** An address is taken as the calling unit's config resolves it, then as the environment
   of each deployment container that runs the unit fills it in (ADR 0082), the way Spring Boot
   reads it: an environment variable (relaxed binding) comes before the value in the config files
   and before a placeholder's default. (ADR 0082's datasources still keep the config's value.)
   Where the config's answer and the environments' answers lead to different units or systems, the
   link is left open as `ambiguous` with all of them as candidates, and never decided by its path;
   an answer that leads nowhere does not count against one that does. A link filled in this way
   shows the filled URL and lists the container among its `facts`. A whole route URI left to a
   placeholder is filled the same way, and one filled with `lb://name` goes by the name. Identity
   providers try the deployments' answers first, then the config's, since only that linker knows
   identity provider containers. A mail host is the deployments' where one sets it, and every one
   must name the same public domain.
2. **Classify** the host: this machine (`localhost`, `127.*`, `[::1]`); a private address
   (RFC 1918, `0.0.0.0`, `host.docker.internal`, `.local`, `.internal`, `.lan`, `.home.arpa`); a
   Kubernetes name (`.svc`); a name without a domain; a public domain; a public IP address.
3. **Match** in one order, the first step that names any unit deciding: `spring.application.name`
   (`host`, 0.9); a name of a deployment container that runs a unit, in the filling container's
   environment when one filled it (`container-host`, 0.9); the artifact ID (`artifact-id`, 0.8); a
   Kubernetes name by its first label when the whole name names none. This machine or a private
   address goes by its port (the scheme's when none is written): the unit whose compose container
   publishes it (`published-port`, 0.8), else the units whose `server.port` it is, 8080 for one that
   sets none (`port`, 0.8). A unit on the default port is still taken only where it serves the path.
4. **Answer**: a unit; several (`ambiguous`, with candidates); an external system, only for a
   public domain that names no unit; or unknown. A private address or a public IP address is never
   an external system: an HTTP call to one that no unit listens on goes by its path as a call to
   this machine does, and is otherwise left open.

Frontend links take these factors too (a host was 1.0 and a port 0.7 before). Gateway routes left
open (`kind: route`) go to the LLM resolver (ADR 0045) with the units whose endpoints the route's
paths reach as candidates, when a path has a literal segment; a pick is a `CALLS_HTTP` link from the
gateway with the route's id, paths, URL and protocol. Other changes from before: a host under a
private suffix (`.local`, `.internal`, `.lan`) is matched by its port, not by name, and is never an
external system; a URL to this machine without a port is matched by the scheme's port (80, 443);
a frontend's host is matched against deployment container names before artifact IDs. This covers two items of **Not covered**
above: deploy-time values from compose and Kubernetes environments, and service names that differ
from `spring.application.name` and the artifact ID. Containers are still tied to units by name and
image (ADR 0082), not by a compose `build` context. project-0 covers it with DEP-1 to DEP-4. The
step's version becomes 17.
