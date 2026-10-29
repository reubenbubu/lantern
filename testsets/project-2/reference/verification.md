# project-2 reference: verification (second pass)

Independent second pass over the first-pass reference (v1.0.0), done on 2026-10-29 against
`Event-Management-platform` at `462516947458d4a161dfa0aef482865d9940e241` (HEAD confirmed; checkout
clean and unchanged). The code was read by hand. Lantern was not run and no Lantern output was read.
Nothing was built or run, except that the Spring Cloud Gateway 4.3.4 jar from the local Maven cache
was disassembled with `javap` to settle how the rate limiter behaves (see C1). The result is v1.1.0
of `reference.c4.json`, rebuilt from `scripts/build-data.mjs`.

## What was checked

- **Cited claims: all of them.** Every file:line citation in `facts/` and `reference.c4.json` was
  re-read at the cited lines: 409 in the facts files (after this pass's additions) and the 45
  distinct citations in the report's evidence. This covered:
  - every piece of evidence in the risk table, every ADR and observed decision, and every
    sequence step;
  - all controller mappings and `@PreAuthorize` rules and all security chains;
  - every Flyway `CREATE TABLE` line, all `@KafkaListener` topics and groups, and all outbox
    relays and scheduled jobs;
  - compose, `.env.example`, the Dockerfiles, nginx and Vite, observability, CI, and the docs and
    ADR lines.

  A script printed each citation next to the cited source lines, resolving the facts' path
  abbreviations and bare `:N` references, and every pair was compared by hand. Where the script
  attached a bare `:N` to the wrong file, the line was checked against the file the sentence
  means.
- **Sampling for misses.**
  - Entry points (7 `*Application` classes).
  - Every controller, `@KafkaListener` and `@Scheduled` job.
  - Every HTTP client: `grep RestClient|WebClient|RestTemplate|FeignClient|HttpClient` finds only
    the two known adapters.
  - Every `application.yml`: each compose environment variable name was matched against the
    `${…}` placeholders. All match, so no service falls back to `localhost` under compose.
  - `compose.yaml`, `.env.example`, `docker/`, `frontend/` (all of `src`, `nginx.conf`,
    `Dockerfile`, `vite.config.ts`), `observability/`, `.github/workflows/ci.yml`, `Makefile`.
  - There are no Kubernetes or Helm files.
  - Gateway routes against service mappings.

## Corrections (before → after)

**C1. The gateway rate limiter fails open, not closed.** This was the material error.
- Before: "If Redis errors … 503 RATE_LIMIT_UNAVAILABLE (fails closed)". The same claim appeared
  in four places: the `gw-rate-limit` description, the gateway overview ("without it they answer
  503"), the sign-in sequence (alt "bucket empty or Redis down" → "429 … or 503 when Redis
  fails") and the Medium Redis risk ("blocks register, login and refresh with 503").
- After: Spring Cloud Gateway's `RedisRateLimiter.isAllowed` catches Redis errors itself and
  returns *allowed* (remaining -1), so requests pass and the limits stop applying. The filter's
  `onErrorResume` (`AuthenticationRateLimitWebFilter.java:45-50`) never sees a Redis error.
- Evidence: Spring Cloud 2025.0.2 imports `spring-cloud-gateway` 4.3.4 (its BOM). In the 4.3.4
  bytecode, `RedisRateLimiter.isAllowed` contains `Flux.onErrorResume(...)` returning `[1, -1]`
  ("Error calling rate limiter lua"), plus an exception-table catch-all returning
  `new Response(true, …)` ("Error determining if user allowed from redis"). The gateway's
  readiness group includes Redis (`api-gateway/.../application.yml:76-78`), so the gateway reports
  itself not ready.
- Fixed in: the component description, the gateway overview, the sign-in sequence (alt is now
  "bucket empty" → 429, plus a note "If Redis is down the limiter allows the request (fails
  open)"), the Medium Redis risk and `facts/edge-auth-deployment.md` §4.

**C2. The Medium Redis risk: evidence and mechanism.**
- Before: "cache evictions run inside business transactions and no cache error handler is
  configured", with `EventServiceApplication.java:8-10` and `AuthenticationRateLimitWebFilter`
  cited.
- After: the evictions call `cacheManager.getCache(…).evict(id)` directly
  (`PublicEventCache.java:19-24`), which no `CacheErrorHandler` would cover even if one existed.
  The `@Cacheable` read (`PublicEventQueryService.java:94-96`) uses the default handler, which
  rethrows.
- The conclusion (a Redis outage stops publication, transitions, holds, so every booking, and
  public details) is unchanged, and so is the rating (Medium).

**C3. Gateway filter order.**
- Before: "correlation id, origin check, identity-header stripping, rate limit".
- After: "correlation id, security headers, identity-header stripping, origin check, rate limit",
  then Spring Security.
- Evidence: `getOrder()` is HIGHEST_PRECEDENCE in `CorrelationIdWebFilter.java:33`, +5 in
  `GatewaySecurityHeadersFilter.java:29`, +10 in `UntrustedIdentityHeadersFilter.java:34`, +15 in
  `CorsOriginValidationWebFilter.java:42` and +20 in `AuthenticationRateLimitWebFilter.java:73`.

**C4. Check-in sequence.**
- Before: the rejection codes were "… CANCELLED or REFUNDED".
- After: the real `ScanOutcome` names, `TICKET_CANCELLED`, `TICKET_REFUNDED` and `INVALID_TOKEN`
  (`ScanOutcome.java`).
- The two self-steps were also reordered to match the code. The idempotent replay check comes
  before QR verification (`TicketScanService.java:67-87`), and the ticket is locked before the
  status checks (`:93`).

**C5. Refund-window citation.**
- Before: `RefundStateService.java:14`.
- After: `:13` for the window and `:14` for the roles. Changed in the High cancellation risk and
  in `facts/attendee-payment-notification.md`.

**C6. Venue adapter line numbers** (facts only).
- Before: `HttpVenueAvailabilityAdapter.java:48-89` and `:91-118`.
- After: `:24-64` and `:66-93`.

**C7. Book-and-pay sequence, compensation branch.**
- Before: `t-payment → attendee` appeared without a producer step.
- After: added `payment → t-payment` "Relays refund.succeeded", as the refund sequence already
  has.

**C8. Facts-only citation fixes found in the full re-read.** None of these change any statement or
the report data:
- `LocalSmsAdapter.java:14,25` → `:14,28-35`. Line 25 is only `name()`; the message is stored at
  28-35.
- Ticket-scan idempotency `:100-110` → `:67-78` plus the re-check at `:100-110`.
- Venue conflict check `:109` → `:104-111`, and the conflict queries `:176-179` → `:170-180`.
- auth-service security chain `AuthSecurityConfiguration.java:42-109` → `:34-101`.
- Ambiguous bare `:N` citations that pointed at the right lines of a file other than the one
  named just before were made explicit:
  - attendee `application.yml:39-53`, previously read as `AttendeeRuntimeConfiguration.java`;
  - `BookingCoordinator.java:35-36`;
  - `NotificationEventHandler.java:109-122,124-128,130-139`;
  - `NotificationDeliveryJob.java:101,110`;
  - gateway `application.yml:54` and `GatewayJwtConfiguration.java:44-53`;
  - `AuthController.java:95-101`;
  - `JwtKeyConfiguration.java:66-74`.

## Additions

**A1. auth-service component diagram: two JWKS callers.** venue-service and notification-service
also fetch the JWKS (`venue-service/.../application.yml:25`,
`notification-service/.../application.yml:31`, compose `:237,334`). They were missing from the
`cmp-auth` diagram, which listed only gateway, event, attendee and payment. The container-level
data-stores diagram already had them.

**A2. Low risk: a cancelled event's pending bookings can still be confirmed.**
- Cancellation changes the bookings but not the Saga (`BookingPersistenceService.java:185-199`).
- payment-service never hears of the cancellation; its `claim` checks only the order projection,
  the owner and hold expiry (`PaymentStateService.java:17-25`).
- event-service `confirm` does not check the event status (`InventoryService.java:223-279`).
- attendee-service confirms a booking from any status (`BookingSagaService.java:81-103,201-206`).
- Result: paying within the 15-minute hold gives the attendee a confirmed booking and valid
  tickets for a cancelled event, and charges them.

**A3. Low risk: reminders and cancellation alerts are not withdrawn.**
- notification-service plans the 24 h reminder on `booking.created` for every booking not
  `EXPIRED`, including `PAYMENT_PENDING` (`NotificationEventHandler.java:82-85`).
- It ignores `booking.payment-failed`, `ticket-hold.expired` and `booking.refunded` (`:57-67`).
- `event.cancelled` alerts every booking projection of the event (`:130-139`).
- Result: attendees whose booking failed, expired or was refunded still get the reminder and the
  cancellation message.

**A4. Low risk: one shared rate-limit bucket behind nginx.**
- The rate-limit key is the TCP peer address; `X-Forwarded-For` is ignored
  (`AuthenticationRateLimitWebFilter.java:64-69`).
- The frontend's nginx proxies `/api/` to the gateway (`frontend/nginx.conf:8-14`).
- Result: everyone who comes through the frontend shares one sign-up, login and refresh bucket.

The risk table goes from 15 rows (2 High, 5 Medium, 8 Low) to 18 rows (2 High, 5 Medium, 11 Low).
The document is v1.1.0 with a revision entry.

## Confirmed

Unchanged after checking:
- **Elements.**
  - The 4 people and their roles (`Role.java:3-8`; registration grants ATTENDEE,
    `UserAccount.java:79,84`).
  - The single external system: OAuth2, off by default.
  - The 8 containers, 7 stores and 3 topics.
  - All component class lists: every named class exists.
- **Connections.**
  - The only two service-to-service HTTP calls, both carrying the user's token.
  - All 10 Kafka connections with topics and consumer groups.
  - Every gateway route (`application.yml:15-43`) and public rule
    (`GatewaySecurityConfiguration.java:35-60`).
  - The table of event types and consumers. It matches the code's switch statements and
    `docs/architecture/kafka-event-catalog.md:31-58`.
- **Deployment.**
  - Compose profiles, images and the single PostgreSQL with six databases
    (`init-databases.sql:1-41`).
  - Host ports, `depends_on`, the backend Dockerfile, CI and the observability wiring.
  - Loki receives nothing: the collector has only a traces pipeline.
- **Decisions.** All 9 ADR summaries against the ADR texts, plus the 3 observed decisions.
- **Sequences.** Sign-in (apart from C1), publish, book-and-pay (apart from C7), refund and
  cancel, step by step against the code paths.
- **Risk ratings.**
  - Both Highs and all five Mediums stand, the Redis risk with C2's wording. The ratings were
    re-judged, not just carried over.
  - All eight first-pass Lows were re-derived from code and hold: in-memory fake provider,
    compensation inside the consumer transaction, staff scope, host ports, reschedule dead code,
    non-local provider startup failure, Loki and stale docs.
- **The High on inventory endpoints holds.**
  - The gateway routes all of `/api/v1/events/**` with only `authenticated()`
    (`GatewaySecurityConfiguration.java:57-58`).
  - `InventoryController` allows ATTENDEE (`:27`).
  - `reserve` needs only `SALES_OPEN`, the sales window, a per-call quantity range and stock
    (`InventoryService.java:111-134`), with no per-user cap. Any number of keys can be used.
  - `confirm` needs only that the requester owns an ACTIVE hold (`:250-270`).
  - Self-registration gives anyone ATTENDEE.
  - So one free account can permanently remove a ticket type's whole stock without paying. The
    README documents the endpoints as authenticated APIs (`README.md:159,174`), so the exposure
    is intended, but the consequence is not.

## Open judgement calls (variants a scorer should accept)

1. **Inventory exposure (High).** Accept **High** or **Medium**. Medium is reasonable because the
   README documents direct inventory access and the endpoints check ownership; the stock drain
   needs nothing but an attendee account.
2. **No refund on event cancellation (High).** Accept **High** or **Medium**. It is as designed
   (the catalogue describes the consumer as cancelling projections and alerting), and organizers
   and admins can refund manually until the start time.
3. **The new Lows (A2-A4).**
   - A2 may also be rated **Medium** or merged into the cancellation risk.
   - A3 and A4 may be **Low** or omitted. Treat them as bonus findings, not required ones.
4. **The Redis risk.** The rate-limiter part is now a side note. A report that lists only the
   event-service write-path dependency is complete. A report that says login is blocked or
   returns 503 when Redis is down is **wrong**.
5. **The frontend as a container.** Accept modelling it as a container (it is deployed and proxies
   `/api/`). Also accept leaving it out with people calling the gateway directly, since it calls
   no API.
6. **Kafka modelling.**
   - Accept three topic elements (this reference) or one Kafka broker container with the topics
     as labels.
   - The `.dlt` topics are optional.
7. **Databases.** Accept six logical databases or one PostgreSQL container labelled with six
   schemas. One of the two must be stated.
8. **External systems.**
   - Accept the OAuth2 identity provider alone, or no external system.
   - Payment, email, SMS and maps providers modelled as external systems are acceptable only if
     marked as not integrated or fake. Unmarked, they are wrong.
9. **JWKS fetches.** Accept them drawn per service, collapsed into one note, or omitted at
   container level.
10. **Observability stack and CI.** Accept them left out (this reference) or shown as supporting
    infrastructure.
11. **Filter order and fail-open details.** These are component-level detail. Missing them is
    fine; stating the opposite (fails closed) is an error.

## Not verified

- Runtime behaviour. Nothing was run: the fail-open conclusion rests on bytecode, and the
  transaction and rollback effects rest on reading the code.
