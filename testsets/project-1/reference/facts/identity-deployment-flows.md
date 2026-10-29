# project-1: identity (Keycloak), deployment, actors, flows, decisions, risks, unknowns

Pin read: `8f5cb68` (see `edge-ui-bff-gateway.md`). Paths relative to the repository root; shorthands as in the other two files.

## 1. Keycloak (identity provider) and its realm

- Image `quay.io/keycloak/keycloak:26.7.4`, `start-dev --import-realm`, PostgreSQL database `keycloak` on `postgres-keycloak`, HTTP on 8080, health on, bootstrap admin `admin`/`admin` by default, realm files mounted from `./keycloak/realm-config` (`compose.yaml:104-140`). `keycloak/README.md:3-5,24-28`: the folder only holds the realm export, which the README says is imported on every start. *(Second pass correction)* Keycloak's `--import-realm` skips a realm that already exists, and Keycloak's database is a persistent volume (`compose.yaml:54`), so the export is applied only on the first start against an empty database; later edits to the file do not reach a running installation.
- Realm `my-realm` (`keycloak/realm-config/realm-export.json:2-17`): brute-force protection, password policy length 8, **access token 300 s**, SSO idle 1800 s / max 36000 s, **refresh-token rotation** (`revokeRefreshToken`, `refreshTokenMaxReuse=0`). Realm roles ADMIN, USER, INTERNAL_SERVICE (`:18-33`).
- Clients (`:34-160`): `admin-service-client` (confidential, service account, secret `admin-secret`, `:35-43`); `bff-client` (confidential, secret `mysecret`, code flow + PKCE S256, redirect `http://localhost:8081/login/oauth2/code/keycloak`, post-logout `http://localhost:4200/*`, no direct grants, audience mapper `template-api`, `:44-76`); `internal-client` (service account, secret `my-internal-secret`, audience `template-api`, `:77-98`); `test-client` (secret `test-secret`, **direct access (password) grants on**, audience `template-api`, `:99-124`); `mobile-client` (public, code + PKCE, redirect `com.example.app://callback` and `http://localhost:*/callback`, audience `template-api`, `:125-159`).
- Users (`:161-216`): `admin`/`password` with ADMIN (`:162-179`), `user`/`password` (`:180-196`), service accounts for `internal-client` (INTERNAL_SERVICE, `:197-204`) and `admin-service-client` (realm-management `manage-users`, `view-users`, `:205-214`).
- No SMTP server is configured in the realm export (the whole file, `:1-217`, has no `smtpServer` key). The production checklist says Keycloak SMTP must be configured for the "Set Password" email (`docs/PRODUCTION_CHECKLIST.md:76-81`).
- Who talks to Keycloak:
  - BFF: OIDC authorization-code login via Spring Security (`bff/src/main/resources/application.properties:17-24`), refresh at the token endpoint (`B/filter/TokenRefreshFilter.java:200-218`), browser redirect to end-session on logout (`B/controller/BffController.java:267-276`).
  - gateway, profile-service, order-service, keycloak-admin-service: JWT validation against `issuer-uri` (OIDC discovery and JWKS) with audience `template-api` (`gateway/src/main/resources/application.properties:112-117`; `CS/ResourceServerDefaultsEnvironmentPostProcessor.java:37-41`).
  - profile-service: client-credentials token for `internal-client` (`PR/application.properties:27-31`).
  - keycloak-admin-service: Keycloak Admin REST API with the `admin-service-client` service account (`K/config/KeycloakConfig.java:25-33`; `K/controller/AdminController.java:47-50,111-121,139-142`).
  - Browser: Keycloak login page and logout redirect (compose note that the browser must resolve `keycloak`, `compose.yaml:9-15`; README `README.md:151-157`).
  - Mobile app (not in the repository): code + PKCE with `mobile-client`, then bearer calls to the gateway (`realm-export.json:125-159`; `README.md:387-405,588-608`; `docs/bff_comparison_analysis.md:41-45`).

## 2. Data stores (compose)

| Store | Image | Host port | Used by | Evidence |
|---|---|---|---|---|
| `redis` | `redis:8-alpine`, `--requirepass ${REDIS_PASSWORD:-redis_password}`, AOF on | 6379 | bff (BFF sessions, Spring Session, refresh locks), gateway (rate limiter) | `compose.yaml:24-39`; `bff/src/main/resources/application.properties:7-15`; `gateway/src/main/resources/application.properties:104-107` |
| `postgres-keycloak` | `postgres:18-alpine`, db `keycloak` | 5432 | Keycloak | `compose.yaml:41-62` |
| `postgres-profile` | `postgres:18-alpine`, db `profile_db` | 5433 | profile-service | `compose.yaml:64-82` |
| `postgres-order` | `postgres:18-alpine`, db `order_db` | 5434 | order-service | `compose.yaml:84-102` |

No message broker, no queue and no object store exists in the repository.

## 3. Deployment and operations

- `compose.yaml` has two modes: infra only by default (Redis, three PostgreSQL, Keycloak, Zipkin, Prometheus, Loki, Grafana), and `--profile apps` adds the five Java services and angular-ui, all on network `sec-network` (`compose.yaml:3-18,20-216,217-425,427-430`).
- App containers and ports (`compose.yaml`): gateway 8888/9888 with Keycloak issuer, Redis and the three service URLs (`:220-257`); profile-service 8082/9082 with `DATABASE_URL`, `KEYCLOAK_ADMIN_SERVICE_URL=http://keycloak-admin-service:8084/api`, internal client and confirmation URL (`:259-296`); order-service 8083/9083 (`:298-331`); keycloak-admin-service 8084/9084 (`:333-364`); bff 8081/9081 with Redis, Keycloak client, `GATEWAY_URL=http://gateway:8888`, `BFF_JWT_SIGNING_KEY` default empty (`:366-406`); angular-ui 4200 (`:408-425`). Every service also publishes its port on the host.
- Host mode: `manage_services.sh start` runs `docker compose up -d --wait` for infra, then each service with `./mvnw -pl <svc> -am spring-boot:run` in the background (`manage_services.sh:35-67`); the Angular dev server runs with `npm start` (`README.md:120-126`).
- Observability: every service exports traces to Zipkin (`*_application.properties` tracing lines, e.g. `bff/src/main/resources/application.properties:68-71`), exposes Prometheus metrics on its management port, scraped by Prometheus (`docker/prometheus/prometheus.yml:11-65`), and can push logs to Loki under the `loki` profile (`common-web/src/main/resources/logback-spring.xml:21-64`); Grafana provisions dashboards (`compose.yaml:189-215`; `docker/grafana/provisioning/*`). None of these take part in a user request.
- CI (`.gitlab-ci.yml`): Maven build, Angular build and tests, Testcontainers tests, report-only Trivy scan, optional Playwright E2E, and on the default branch or tags `docker compose --profile apps build` (`.gitlab-ci.yml:1-123`, docker job `:111-123`). No push to a registry and no deployment job.
- No Kubernetes, Helm or other production descriptor exists in the repository (file list at the pin).
- Secrets: `.env.example` lists every secret as `changeme` and says to never commit `.env` (`.env.example:1-9,30-56`); compose and all `application.properties` fall back to the realm's literal secrets (`compose.yaml:276-279,349-350,383-384`; `bff/src/main/resources/application.properties:21`; `PR/application.properties:28`; `KR/application.properties:13`).

## 4. Actors (people and external systems)

- **Web user**: signs in through the Angular app, keeps one profile and creates and lists their own orders (`UI/app.routes.ts:17-40`; profile and orders services as in `edge-ui-bff-gateway.md` §2).
- **New visitor**: self-registers with email and profile data (no password), confirms by link, then sets the password through Keycloak's email (`UI/features/register/*`; `P/controller/RegistrationController.java:54-78`; `docs/user_registration_flow.md:69-92`).
- **Administrator** (realm role ADMIN): creates and deletes users through the admin APIs (`K/controller/AdminController.java:42-43,136-137`; `P/controller/AdminProfileController.java:23-31`). There is no admin UI; the seeded `admin` user holds the role (`realm-export.json:162-179`).
- **Mobile app** (external, not in the repository): the "mobile lane" (`README.md:24,387-405`; `mobile-client`, `realm-export.json:125-159`).
- Not modelled: Zipkin, Prometheus, Loki, Grafana (operations only); no external email provider exists in the code (`P/service/LoggingEmailService.java:7-28`; realm has no SMTP).

## 5. End-to-end flows (as coded)

1. **Web sign-in**: `/bff/login` → `/oauth2/authorization/keycloak` → Keycloak login → code back to `/login/oauth2/code/keycloak` → Spring Security exchanges the code (JSESSIONID session in Redis) → `/bff/login/success` stores `BffSession` in Redis and sets the BFF_SESSION cookie → redirect to the frontend (`BffController.java:128-197`; `B/config/SecurityConfig.java:94-96`; `bff/pom.xml:38-44`; `UI/core/services/auth.service.ts:41-43`). README sequence `README.md:357-385`.
2. **Authenticated web API call** (e.g. list orders): SPA `GET /bff/api/orders` with cookie and `X-Requested-With` → TokenRefreshFilter refreshes if < 60 s left → BffController verifies the cookie, loads tokens, calls `GET http://gateway:8888/orders?…` with Bearer → gateway validates JWT and audience, rate-limits by user, circuit breaker, rewrites to `/api` → order-service validates JWT again, queries `orders` by `created_by=sub` → back through each layer (`UI/features/orders/orders.service.ts:27-34`; `TokenRefreshFilter.java:91-172`; `BffController.java:293-326,407-435`; `gateway/.../application.properties:29-40`; `O/controller/OrdersController.java:38-41`). README path walk-through `README.md:493-518`.
3. **Self-registration**: `POST /bff/public/profile/register` → gateway `/profile/public/register` (no token) → profile-service `/api/public/register` → disabled profile + pending registration + logged/emailed link; later `GET /bff/public/profile/confirm?token=` → profile-service confirm → keycloak-admin-service `/api/users/register` with an `internal-client` token → Keycloak creates the user and is asked to email UPDATE_PASSWORD → profile enabled (`register.service.ts:32-46`; `BffController.java:337-357`; `RegistrationService.java:100-168`; `KeycloakAdminClient.java:78-93`; `AdminController.java:100-134`; `docs/user_registration_flow.md:9-67`).
4. **Administrator creates a user** (API only): `POST /profile/profile/admin/users` with an ADMIN bearer → gateway → profile-service `AdminProfileController` → keycloak-admin-service `POST /api/users` with the same bearer → Keycloak Admin API → profile insert, compensating delete on failure (`AdminProfileController.java:23-27`; `ProfileService.java:79-107`; `KeycloakAdminClient.java:47-54`; `AdminController.java:42-58`).
5. **Logout (single sign-out)**: hidden form `POST /bff/logout` with `_csrf` → BFF deletes the Redis session, invalidates JSESSIONID, clears cookies → 302 to Keycloak end-session with `id_token_hint` → Keycloak redirects to `<frontend>/login` (`auth.service.ts:58-75`; `BffController.java:230-291`; `README.md:485-491`).
6. **Mobile API call**: mobile app signs in at Keycloak with `mobile-client` (PKCE) and calls the gateway with the bearer token, e.g. `GET /profile` (`README.md:387-405,588-608`).

## 6. Decisions (with recorded rationale where the repo states one)

- Decoupled BFF service rather than a BFF inside the gateway: single responsibility, room to aggregate, three verification layers (`docs/bff_comparison_analysis.md:13-19,33-39`).
- Two lanes: web via BFF cookie session, mobile via bearer tokens straight to the gateway (`README.md:24-25,44-79`; `docs/bff_comparison_analysis.md:41-47`).
- Defense in depth: JWT and audience `template-api` checked at the gateway and again in every service (`README.md:26,348-355`; `docs/defense_in_depth_vs_perimeter.md:31-37`). Role checks exist only in profile-service's admin controller and keycloak-admin-service (`P/controller/AdminProfileController.java:24,30`; `K/controller/AdminController.java:43,101,137`).
- Tokens stay server-side: the browser holds only a signed BFF_SESSION cookie; tokens live in Redis as JSON without the client secret (`B/session/BffSession.java:9-31`; `B/config/RedisConfig.java:11-20`).
- Dual BFF sessions: JSESSIONID (Spring Session) for `/bff/user`, BFF_SESSION for the proxy (`README.md:459-464`; `docs/dual_session_strategy.md:5-35,83-97`).
- Proactive refresh with a 60 s buffer and a per-session Redis lock, because refresh tokens rotate (`README.md:407-433`; `docs/proactive_token_refresh.md:5-65`).
- Keycloak user created only after email confirmation; no password collected; same 201 for every registration (`docs/user_registration_flow.md:69-117`).
- User administration through a dedicated keycloak-admin-service holding the realm-management service account (`README.md:278`; `K/config/KeycloakConfig.java:25-33`).
- Database per service, schema by Flyway, Hibernate validate (`README.md:665-671`).
- Gateway on WebFlux for Spring Cloud Gateway's Redis rate limiter (`README.md:292`).
- Shared libraries and auto-configured resource-server chain (`README.md:223-265`).
- One Dockerfile for all Java services; compose infra mode vs apps profile (`Dockerfile:1-14`; `compose.yaml:3-18`).

## 7. Risks and findings

- **High. Default credentials are live.** The realm export, imported on the first start, contains client secrets (`admin-secret`, `mysecret`, `my-internal-secret`, `test-secret`) and users `admin`/`password` (ADMIN) and `user`/`password`; `test-client` allows the password grant and adds the `template-api` audience, so with the defaults anyone who can reach Keycloak gets an ADMIN token the services accept. Keycloak runs `start-dev`, and the master-realm bootstrap admin defaults to `admin`/`admin` (`compose.yaml:117-118`) (`realm-export.json:35-124,161-196`; `compose.yaml:107`; `README.md:590-601`).
- **High (likely). Self-registered users probably cannot set a password.** No SMTP is configured in the realm, the UPDATE_PASSWORD email failure is logged and swallowed, and 201 is returned; nothing in the code resends it (`AdminController.java:118-127`; realm export without `smtpServer`; `docs/PRODUCTION_CHECKLIST.md:76-81`).
- **Medium. No email sender exists for confirmation links.** Only `LoggingEmailService` (`!prod`), so under `prod` profile-service fails to start until someone writes one (`LoggingEmailService.java:7-28`; `docs/PRODUCTION_CHECKLIST.md:41-51`).
- **Medium. An unconfirmed or expired registration blocks that email for good.** `register` silently no-ops when any profile has the email, including a disabled one whose token expired; no cleanup job exists (`RegistrationService.java:51-56,100-106,139-141`).
- **Medium. Confirmation is not atomic across Keycloak and profile_db.** The Keycloak user is created inside the DB transaction; if the save or commit fails the Keycloak user remains, and retrying the link fails because the username exists (non-201 → "Failed to create user" → 500) (`RegistrationService.java:133-168`; `AdminController.java:114-133`). Admin delete has the mirror case (`ProfileService.java:122-136`).
- **Medium. Anonymous web traffic shares one rate-limit bucket.** Public BFF calls reach the gateway without a token and without `X-Forwarded-For`, and `TRUSTED_PROXY_HOPS=0`, so the key is the BFF's address: all visitors share 10 req/s (burst 20) for registration and confirmation (`BffController.java:407-430`; `G/config/RateLimiterConfig.java:35-53`; `gateway/.../application.properties:20-23,120`).
- **Medium. Redis and Keycloak are single points of failure.** One Redis holds BFF sessions, Spring sessions, refresh locks and rate-limit state; one Keycloak in dev mode with one PostgreSQL; no HA anywhere in the repo (`compose.yaml:24-39,104-140`; readiness includes Redis, `gateway/.../application.properties:128`, `bff/.../application.properties:66`).
- **Low. Web sessions end 30 minutes after login, whatever the activity.** BFF_SESSION's `exp` is fixed at login and never re-issued; refresh only renews the Redis record (`JwtUtils.java:102-118,141-145`; `BffController.java:184`; `SessionRedisService.java:31-33`).
- **Low. Order numbers come from the browser** (`ORD-` + random < 10000) but must be unique across all users, so collisions give 409 as orders grow (`orders.service.ts:38-39`; `OR/db/migration/V1__init.sql:13`; `CW/exception/GlobalExceptionHandler.java:100-104`).
- **Low. `enabled` is inconsistent and unused**: admin-created profiles stay `false`, self-registered ones become `true`, and no code reads it (`ProfileService.java:86-93`; `UserProfileEntity.java:62-64`; `RegistrationService.java:156`).
- **Low. Confirmation tokens are stored in plain text**, although admin-service Javadoc says a BCrypt hash is stored (`RegistrationPersistenceService.java:59-65`; `AdminController.java:92-93`).
- **Low. Confirmation is a state-changing GET** (creates the Keycloak user), so link scanners can trigger it (`RegistrationController.java:71-78`).
- **Low. Every container publishes its port on the host** (services, PostgreSQL, Redis, Keycloak), bypassing the gateway's rate limiting (JWT still checked) (`compose.yaml:28-29,48-49,71-72,91-92,119-120,228-230,267-269,306-308,341-343,374-376`).
- **Low (second pass). A profile's email is whatever the client sends**, unique, and checked by registration, so a signed-in user can claim another person's address and block their self-registration (`services-and-shared.md` §2, `ProfileService.java:49-64`; `RegistrationService.java:103-106`).
- **Low (second pass). No transport security by default**: nginx listens on plain HTTP 4200 (`angular-ui/nginx.conf:2`), Keycloak has `KC_HTTP_ENABLED` (`compose.yaml:114-115`), `BFF_COOKIE_SECURE` defaults to false (`compose.yaml:387`; `bff/src/main/resources/application.properties:36`; `.env.example:42`), Redis TLS off (`bff/.../application.properties:15`; `.env.example:82`).
- **Low. Stale README details**: the architecture diagram shows the order database on 5433 (compose: 5434) (`README.md:75-78`; `compose.yaml:91-92`); admin profile endpoints sit at `/profile/profile/admin/users` through the gateway, a path no document mentions. *(Second pass)* `.env.example` says "the default values work out of the box" (`:7`) but sets the client secrets to `changeme` (`:31,48,56`), which do not match the realm export (`realm-export.json:38,47,80`).

## 8. Unknowns

- How and where the system runs in production: only Compose exists; CI builds images but pushes nowhere.
- The mobile app: referenced by README and the realm, but no code.
- Which email provider is intended for confirmation links and for Keycloak.
- Owners and quality goals: not recorded (README names an author, `README.md:781-785`).
- The order lifecycle: six statuses exist, but no endpoint or job changes them.
