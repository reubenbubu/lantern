# project-2 (Event Management Platform) ground truth: edge, auth, shared libraries, deployment

Read by hand from the working tree `project-2/Event-Management-platform` at HEAD
`462516947458d4a161dfa0aef482865d9940e241` ("merge: Phase 6 Kafka notifications", 2026-08-16).
Nothing was built or run. All paths are relative to the repository root.

Path abbreviations:
- `GW/` = `api-gateway/src/main/java/com/eventplatform/gateway/`
- `AU/` = `auth-service/src/main/java/com/eventplatform/auth/`
- `PK/` = `shared/platform-kafka/src/main/java/com/eventplatform/kafka/`
- `PC/` = `shared/platform-contracts/src/main/java/com/eventplatform/contracts/`

## 1. Repository shape

- One Git repository, one Maven reactor: `shared/platform-contracts`, `shared/platform-kafka`,
  `shared/platform-web`, `shared/data-service-parent`, `api-gateway`, `auth-service`,
  `event-service`, `venue-service`, `attendee-service`, `payment-service`,
  `notification-service` (`pom.xml:21-33`). Parent is Spring Boot 3.5.14 (`pom.xml:9-10`), Java 17
  (`pom.xml:36`), Spring Cloud 2025.0.2 (`pom.xml:37`), springdoc 2.8.13 (`pom.xml:38`).
- `frontend/` is a separate npm project (React 19.1.1, Tailwind 4.3.3, TypeScript 5.9.2,
  Vite 8.2.1; `frontend/package.json:15-31`).
- 18 commits; the history is one commit per delivery "phase" (1 to 6), merged into the default
  branch (`git log`).
- README repository map: `README.md:16-35`. "Each service owns its data. Shared modules contain no
  business entities" (`README.md:37`).
- Deferred on purpose: real payment/email/SMS providers, waitlisting, object storage and
  deployment (`README.md:227`); Kubernetes, Helm, Terraform, AWS not required (`README.md:14`).

## 2. Shared libraries (not containers)

- `data-service-parent` is the parent POM of the six data services. It adds platform-kafka,
  platform-web, spring-boot-starter-web, -security, -data-jpa, spring-kafka, Flyway (core and
  PostgreSQL), the PostgreSQL driver, springdoc webmvc, Micrometer Prometheus, the OTel tracing
  bridge and OTLP exporter (`shared/data-service-parent/pom.xml:20-72`). So auth-service and
  venue-service carry spring-kafka although neither produces or consumes anything (no Kafka code:
  `grep -ril kafka auth-service/src/main venue-service/src/main` finds only `application.yml`).
- platform-kafka `KafkaReliabilityAutoConfiguration` (`PK/KafkaReliabilityAutoConfiguration.java:27-64`):
  a `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries` (`:40`) and a
  `DeadLetterPublishingRecoverer` to `<topic>` + dead-letter suffix on the same partition
  (`:36-39`), commits recovered records (`:61`). Defaults in compose: 4 retries, 1 s initial,
  ×2, max 30 s, suffix `.dlt` (`compose.yaml:5-9`).
- platform-contracts `KafkaEventHeaders` defines the record headers `messageId`, `eventId`
  (alias), `eventType`, `schemaVersion`, `eventVersion` (alias), `occurredAt`, `producer`,
  `aggregateType`, `aggregateId` (`PC/KafkaEventHeaders.java:8-18`); `CorrelationIds` carries
  `X-Correlation-Id` and `traceparent`.
- platform-web: `CorrelationIdFilter`, `GlobalApiExceptionHandler`, `ApiErrorResponseWriter`
  (shared error body), auto-configured through `META-INF/spring/...AutoConfiguration.imports`.

## 3. Frontend (`frontend/`)

- `App.tsx` is a static landing page: a hard-coded list of the seven services and a footer
  "Public APIs begin at /api/v1" (`frontend/src/App.tsx:1-11,85-88`). It says "Phase 3" and
  "Booking and payment remain deferred" (`App.tsx:25,53`), so it is stale. **It makes no API
  call**: there is no `fetch` or HTTP client anywhere in `frontend/src`
  (`grep -n fetch frontend/src` finds nothing). `src/api/contracts.ts` only declares TypeScript
  types for events and ticket types (`frontend/src/api/contracts.ts:1-30`).
- Docker: built with `node:24-alpine`, served by `nginx:1.29-alpine` on port 80
  (`frontend/Dockerfile:1-12`). nginx proxies `/api/` to `http://api-gateway:8080`
  (`frontend/nginx.conf:8-14`) and serves the SPA otherwise (`:16-18`). Vite dev server proxies
  `/api` to `http://localhost:8080` (`frontend/vite.config.ts:10`).
- Compose publishes it on host port 3000 (`compose.yaml:405-414`).

## 4. api-gateway

- Spring Cloud Gateway (WebFlux), Spring Security OAuth2 resource server, reactive Redis,
  springdoc webflux, Prometheus, OTel (`api-gateway/pom.xml:18-56`). Port 8080
  (`api-gateway/src/main/resources/application.yml:2`).
- Routes (`application.yml:15-43`), by path prefix to fixed URLs (no service discovery):
  - `/api/v1/auth/**` and `/oauth2/**`, `/login/oauth2/**` → auth-service :8081 (`:16-23`)
  - `/api/v1/events/**`, `/api/v1/event-categories/**` → event-service :8082 (`:24-27`)
  - `/api/v1/venues/**` → venue-service :8083 (`:28-31`)
  - `/api/v1/attendees/**`, `/bookings/**`, `/tickets/**`, `/check-ins/**` → attendee-service :8084 (`:32-35`)
  - `/api/v1/payments/**` → payment-service :8085 (`:36-39`)
  - `/api/v1/notifications/**` → notification-service :8086 (`:40-43`)
- Security (`GW/GatewaySecurityConfiguration.java:26-83`): CSRF off; permit actuator, OpenAPI,
  `/api/v1/auth/register|login|refresh`, the JWKS, OAuth2 paths (`:35-48`); permit
  `POST /api/v1/payments/webhooks/**` (`:49-50`); permit `GET /api/v1/events`,
  `/api/v1/events/*`, `/api/v1/event-categories` (`:51-56`); every other `/api/v1/**` needs a
  valid JWT (`:57-58`); anything else denied (`:59-60`).
- JWT check (`GW/GatewayJwtConfiguration.java:25-35`): Nimbus reactive decoder from the JWK set
  URI (auth-service's JWKS, gateway `application.yml:54`), validates issuer, audience `event-platform-api`
  and `typ=access`. Roles claim → `ROLE_*` authorities (`GatewayJwtConfiguration.java:44-53`). The gateway itself does no
  role checks; it only requires authentication.
- `UntrustedIdentityHeadersFilter` removes `X-User-Id`, `X-User-Email`, `X-User-Roles` and the
  `X-Authenticated-*` variants from every request (`GW/UntrustedIdentityHeadersFilter.java:14-30`).
  No service reads such headers; they all re-validate the JWT (see the other fact files).
- CORS: explicit allowed origins (default `http://localhost:3000`, `application.yml:50`),
  credentials allowed, headers Authorization, Content-Type, X-Correlation-Id, Idempotency-Key
  (`GatewaySecurityConfiguration.java:86-99`); requests with any other `Origin` are rejected 403
  (`GW/CorsOriginValidationWebFilter.java:28-38`).
- Correlation id: generated or validated and forwarded as `X-Correlation-Id`
  (`GW/CorrelationIdWebFilter.java:15-29`).
- Rate limiting: `POST /api/v1/auth/register|login|refresh` per client IP
  (`GW/AuthenticationRateLimitWebFilter.java:17-20,64-69`) through Spring Cloud Gateway
  `RedisRateLimiter` beans (`GW/GatewayRateLimitConfiguration.java:12-46`); defaults 1/s with
  bursts 5, 10, 10 (`application.yml:55-65`). The filter has an `onErrorResume` that answers 503
  RATE_LIMIT_UNAVAILABLE (`AuthenticationRateLimitWebFilter.java:45-50`), but it never fires on a
  Redis outage: Spring Cloud Gateway 4.3.4's `RedisRateLimiter.isAllowed` (the version Spring Cloud
  2025.0.2 imports) catches Redis errors itself and returns "allowed" with remaining -1 (bytecode:
  `Flux.onErrorResume` → `[1, -1]` "Error calling rate limiter lua", and a catch-all returning
  `new Response(true, …)` "Error determining if user allowed from redis"). So the limiter **fails
  open**. [Corrected in the second pass; the first pass said it fails closed.]
- The rate-limit key is the TCP peer address (`AuthenticationRateLimitWebFilter.java:64-69`);
  `X-Forwarded-For` is ignored. Requests through the frontend's nginx (`frontend/nginx.conf:8-14`)
  therefore all share nginx's address. [Added in the second pass.]
- Filter order by `getOrder()`: `CorrelationIdWebFilter` HIGHEST_PRECEDENCE (`:33`),
  `GatewaySecurityHeadersFilter` +5 (`:29`), `UntrustedIdentityHeadersFilter` +10 (`:34`),
  `CorsOriginValidationWebFilter` +15 (`:42`), `AuthenticationRateLimitWebFilter` +20 (`:73`);
  Spring Security's WebFilterChainProxy runs after them (order -100). [Order corrected in the
  second pass: identity stripping runs before the origin check.]
- Readiness includes Redis (`application.yml:76-78`).

## 5. auth-service

- Spring Boot web + security + JPA, OAuth2 resource server **and** OAuth2 client
  (`auth-service/pom.xml:4-15`). Port 8081, database `auth_service`, Hibernate `validate`, Flyway
  (`auth-service/src/main/resources/application.yml:2,8-21`).
- Endpoints, `AU/api/AuthController.java` under `/api/v1/auth` (`:25`):
  `POST /register` (`:36`), `POST /login` (`:45`), `POST /refresh` (`:51`), `POST /logout`
  (`:57`), `POST /sessions/revoke-all` (`:67`), `GET /me` (`:76`),
  `PUT /users/{userId}/roles` (ADMIN, `:83-93` plus `@PreAuthorize` on
  `AuthenticationService.replaceRoles`, `AU/user/AuthenticationService.java:239`),
  `GET /audit-events` (ADMIN, `AuthController.java:95-101`).
  `GET /api/v1/auth/.well-known/jwks.json` (`AU/api/JwkSetController.java:12,21`).
- Security chain (`AU/config/AuthSecurityConfiguration.java:34-101`; second pass: was `:42-109`): permits register, login,
  refresh, JWKS and OAuth2 paths; other `/api/v1/auth/**` need the JWT; stateless unless OAuth2
  is enabled (`:44-47`); `oauth2Login` only when `platform.auth.oauth2.enabled`. BCrypt strength
  from config (`:31`; default 12, `application.yml:30`).
- Roles: `ATTENDEE`, `ORGANIZER`, `EVENT_STAFF`, `ADMIN` (`AU/user/Role.java:3-8`).
  Registration always grants ATTENDEE (audit reason `DEFAULT_ROLE_ATTENDEE`,
  `AuthenticationService.java:84-104`).
- Login (`AuthenticationService.java:108-138`): constant-work BCrypt check against a dummy hash
  for unknown users, 72-byte limit, audit on failure and success.
- Refresh (`:140-201`): opaque refresh token `<sessionId>.<secret>`; session row locked
  (`findByIdForUpdate`, `:144`), hash compared, **reuse of a rotated token revokes the whole
  family** (`:152-163`), rotation inserts the next session row (`:180-191`).
- Role change revokes all the target's refresh sessions (`:241-254`). Logout revokes only the
  refresh session; the access token stays valid until it expires.
- Access token (`AU/session/JwtTokenService.java:36-57`): RS256, issuer, audience, 10-minute TTL
  default (`application.yml:34`), claims `typ=access`, `email`, `roles`, `sid`.
- Signing key (`AU/config/JwtKeyConfiguration.java:55-107`): PEM key pair from configured
  locations; if none and `allow-ephemeral-key` (default **true**, `application.yml:38`;
  `compose.yaml:169`) it generates a 2048-bit RSA key at startup (`JwtKeyConfiguration.java:66-74`), so all tokens die on
  restart.
- OAuth2 login (optional, off by default: `application.yml:40`, `compose.yaml:171`):
  `AU/oauth/OAuth2LoginSuccessHandler.java:42-70` requires a verified email
  (`email_verified`/`verified_email`), calls `AuthenticationService.oauthLogin`
  (`AuthenticationService.java:256-286`), which refuses to auto-link to an existing password
  account (`:288-298`), and writes the token pair as JSON.
- Bootstrap admin from `AUTH_BOOTSTRAP_ADMIN_*`, disabled by default (`application.yml:41-44`,
  `AU/user/BootstrapAdminInitializer.java`).
- Tables: `user_accounts`, `user_roles`, `refresh_sessions` (token hash, family id),
  `external_identities`, `security_audit_events`
  (`auth-service/src/main/resources/db/migration/V2__authentication_schema.sql:1,13,21,46,59`).
- Kafka bootstrap configured (`application.yml:22-23`) but unused.

## 6. Deployment and operations

- `compose.yaml` is the only runtime descriptor. Infrastructure always on: `postgres:17.6-alpine`
  (`:14-31`), `redis:8.2.1-alpine` with AOF (`:33-45`), `apache/kafka:4.0.0` single KRaft node,
  replication factor 1, 3 partitions (`:47-74`). Profiles `observability` / `full-stack` add
  Jaeger 1.68.0, OTel collector 0.132.0, Loki 3.5.3, Prometheus v3.5.0, Grafana 12.1.1
  (`:76-148`). Profile `full-stack` adds the seven backend services and the frontend
  (`:150-414`).
- Every backend image comes from one Dockerfile with `MODULE` build arg: Maven
  3.9.11/Temurin 17 build, `eclipse-temurin:17-jre-alpine` runtime, non-root user, readiness
  health check (`docker/backend.Dockerfile:1-35`).
- One PostgreSQL container holds six databases, one login each, with CONNECT revoked from PUBLIC
  (`docker/postgres/init-databases.sql:1-41`). All passwords `local_dev_only`
  (`init-databases.sql:1-16`; `compose.yaml:162,196,...`).
- Service ports 8081-8086 are also published on the host (`compose.yaml:175-176,209-210,
  240-241,278-279,312-313,346-347`), next to the gateway on 8080 (`:386-387`).
- Startup order via `depends_on: service_healthy`: event-service waits for auth, venue, postgres,
  redis, kafka (`:211-221`); attendee for auth, event (`:280-288`); gateway for all six services
  and Redis (`:388-402`).
- Payment webhook secret default `local-fake-webhook-secret-change-me` (`compose.yaml:305`;
  `.env.example:66`). JWT and QR ephemeral keys allowed by default (`compose.yaml:169,269`).
- Observability: Prometheus scrapes `/actuator/prometheus` on all seven services
  (`observability/prometheus/prometheus.yml:5-16`); OTLP traces → collector → Jaeger
  (`observability/otel-collector/config.yml:1-28`; services send to
  `http://otel-collector:4318/v1/traces`, `compose.yaml:10`). Grafana has Prometheus, Loki and
  Jaeger data sources (`observability/grafana/provisioning/datasources/datasources.yml:3-21`), but
  **nothing ships logs to Loki** (no promtail/agent; the collector has only a traces pipeline;
  `grep -ri loki` finds only compose, Loki config and the Grafana datasource).
- CI (`.github/workflows/ci.yml`): `mvn -B verify` and `docker compose --profile full-stack
  config --quiet` (`:26-30`); frontend `npm ci`, audit, lint, typecheck, build (`:44-62`). No image
  publishing, no deployment.

## 7. Recorded decisions (`docs/adr/`)

Index: `docs/adr/README.md:5-15`, all "Accepted".
- 0001 Kafka is the only broker (`docs/adr/0001-kafka-only-messaging.md:10-12`).
- 0002 Database per service; one local PostgreSQL container hosts the logical databases
  (`0002-database-per-service.md:10-12`).
- 0003 Orchestrated Saga (attendee-service) plus transactional outbox; no XA
  (`0003-saga-and-transactional-outbox.md:10-14`).
- 0004 URI-versioned public APIs `/api/v1` (`0004-api-versioning.md:10-12`).
- 0005 RS256 access tokens validated at every boundary, rotated opaque refresh tokens,
  ephemeral key only for local (`0005-token-and-session-security.md:10-18`).
- 0006 Locked inventory holds, venue reservation over the venue API with the organizer token,
  event outbox (`0006-event-inventory-and-venue-assignment.md:10-16`).
- 0007 Durable booking commands, database-authoritative holds, HMAC-signed QR tickets, locked
  check-in (`0007-attendee-booking-ticket-and-check-in.md:10-16`).
- 0008 Provider-neutral payment port with a fake adapter, refund policy, recoverable Saga
  (`0008-payment-provider-refunds-and-saga-recovery.md:10-18`).
- 0009 Bounded Kafka retry + `.dlt`, dead-lettered outbox rows, durable notification intents
  (`0009-kafka-failure-and-notification-delivery.md:12-21`).

## 8. Stale or contradicting documentation

- `docs/architecture/system-context.md:5` still says notification delivery is deferred; Phase 6
  implemented it. The same diagram (`:9-10`) has users going through the React frontend, which
  calls nothing.
- `system-context.md:67` says retries use bounded backoff "with jitter"; the outbox retry is
  exponential (2, 4, ... capped at 300 s) **without jitter**
  (`shared/platform-kafka/src/main/java/com/eventplatform/kafka/OutboxRetryPolicy.java:10-13`,
  used by `event-service/src/main/java/com/eventplatform/event/outbox/OutboxMessage.java:92-100`),
  and Kafka consumer retry is plain exponential backoff (`PK/KafkaReliabilityAutoConfiguration.java:40-44`).
  Minor.
- Outbox rows are picked with a pessimistic lock, oldest first, only when `nextAttemptAt <= now`
  (`event-service/src/main/java/com/eventplatform/event/outbox/OutboxMessageRepository.java:14-23`).
- `frontend/src/App.tsx:25,53` says "Phase 3" and "Booking and payment remain deferred".
