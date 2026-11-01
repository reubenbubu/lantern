# 0096: The facts the Investigate leads start from

Status: accepted (#520)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.2) turns most of
Investigate's work into leads: deterministic queries over claims that point the model at code to
confirm or refute. Its lead table names the extraction each lead needs that did not exist: what a
request's headers, cookies and parameters give the code; `@Transactional`; access checks custom
annotations and aspects make; the payload types of sends, listeners and Feign methods; CORS
policies; and Kubernetes Secrets written as `stringData`. #520 adds them. The leads themselves are
1D's (#524). This record says how the facts are shaped where the epic leaves it open; the agent
decided these points under the owner's standing go-ahead for 1B, and they are recorded here rather
than asked.

## Decisions

- **`reads-request-value`** (Spring extractor), on the method that reads. A handler's binding
  (`@RequestHeader`, `@CookieValue`, `@RequestParam`, Jakarta REST's `@HeaderParam`,
  `@CookieParam`, `@QueryParam`, inherited from an overridden method as Spring and Jakarta REST
  inherit them) has `via: binding`; a call on the request has `via: call`. Calls are read **in any
  code**, not only in filters and aspects as the epic's table says: the lead decides what reaches a
  unit, and a utility a filter calls (project-4's gateway `OrganizationIdExtractor`) is where the
  read is written. The request types followed are servlet requests, a reactive exchange or request,
  and a functional endpoint's request; a chain stops reading at `mutate()`, where a filter writes
  headers. The name is the value as written, constants folded and a `@Value` kept; a nameless
  binding takes the parameter's name, or `*` for a map of every value.
- **`transactional`** (Spring extractor), on a method or type: `kind: transaction` for Spring's or
  Jakarta's `@Transactional`, the value its propagation as written; and `kind: event-listener` for
  `@TransactionalEventListener`, the value its phase. The second is not in the epic's table, but its
  `send-before-commit` trigger excludes paths through such a listener, and nothing else records it.
  The transaction enums join the parser's list of known library enums, so degraded mode reads them
  too.
- **`custom-access-check`** (Spring extractor), beside the `advised-by` fact (ADR 0079): when a
  before or around advice applies to a method because of an annotation, and reads that annotation's
  attributes (takes it as a parameter, as `@annotation(requires)` binds it, or calls its methods).
  The value is the annotation's values as written (`resource=TASK, action=READ`), with `via:
  aspect`, the aspect and the advice. After-advice and an annotation the advice never reads give
  none, and so does an aspect whose and whose annotation's names say nothing of access
  (permission, role, access, auth, security, privilege, grant, policy, guard, entitlement): aspects
  that log, lock, rate-limit or audit read their annotations too, and counting them as checks would
  hide an endpoint that has none from the `weak-access-check` lead. The epic writes it as `access-check` with `via: aspect`, but that needs
  `access-check`'s `kind` widened, and the vocabulary refuses any change to an existing attribute on
  a database that has it (`Vocabulary.changes`), so it is a type of its own; the
  `weak-access-check` lead (#524) reads both.
- **`payload-type`** (messaging and HTTP client extractors), on the method: `role` is `send`,
  `receive`, `request` or `response`; the value is the type's erasure and `typeArguments` lists the
  named types its arguments hold at any depth, so `List<OrderPlaced>` and
  `ResponseEntity<List<Product>>` name the type inside. **Only a type that is, or holds, a type of
  the analyzed sources is recorded**: the `shared-type-skew` lead asks about types an in-set library
  declares, and a payload of `String` or a framework type would only add facts. The payload of a
  send is the argument each API carries it in; of a listener, its `@Payload` parameter, else its
  first that is not a header, channel or acknowledgement; of a client method, its `@RequestBody`
  parameter (for Feign and MicroProfile, else its first without annotations) and its return type.
  Spring Cloud Stream function beans are not covered yet.
- **The code model keeps types.** `ModuleStructure` gains `TypeUse` (an erasure and the named types
  of its arguments): on each parameter, as each method's `returnType`, and per argument of the calls
  of each tracked chain (`Invocation.argumentTypes`). javac gives them whole; the parser gives a
  variable's type as declared, a new object's or a cast's as written, and asks the symbol solver for
  the rest. Request and CORS types join the tracked APIs.
- **`cors-policy`**, one type defined in the Spring vocabulary and recorded by three extractors,
  each from what it reads: the Spring extractor from `@CrossOrigin` (every origin when it names
  none), `CorsConfiguration` and `CorsRegistry`, as written; the Config extractor from Spring Cloud
  Gateway's global CORS keys (per path) and the Actuator's, resolved per profile with the text as
  written kept; the Deployment extractor from an Ingress's nginx `enable-cors` annotations. The
  value is the origins joined; `origins` lists them. #551 makes each extractor declare its
  vocabulary; it must let an extractor emit a type another's vocabulary defines (as here, and
  `payload-type` from the HTTP client extractor).
- **`kubernetes-secret`** (Deployment extractor), per environment as for containers: the Secret's
  name, with the keys it writes as `stringData` and as `data`. Values are never recorded.
- **Keys.** By the epic's rule (section 6, Key attributes: when in doubt, key), every attribute of
  the new types is key except those descriptive everywhere (`written`, and `profile` on extractor
  types).

## Consequences

- The Spring, messaging, HTTP client, Config and Deployment extractors' versions rise, so their
  cached facts are extracted again.
- None of the new facts is read by linking or composition yet, so documents and scores do not
  change; the leads (#524) read them.
- Request reads through a local variable of the headers (`HttpHeaders h =
  exchange.getRequest().getHeaders(); h.getFirst("X-User")`) are not seen: the chain starts from
  `HttpHeaders`, which is not a request type. Spring Cloud Stream functions' payloads are not
  recorded. Both are gaps for a later issue if a lead misses them.
- project-4's Helm Ingress renders `cors-allow-origin: *` unquoted, which is not valid YAML, so that
  Ingress (and its CORS policy) is not read; the chart would fail the same way when applied. Its
  gateway's CORS policy comes from config.
