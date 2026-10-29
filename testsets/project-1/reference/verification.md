# project-1 reference: verification (second pass)

Independent second pass over the first-pass reference for `Enterprise-Spring-Microservices-Template` at `8f5cb6873691f8b67353c4924fb06f469c2ef4e4` (HEAD of the checkout matched; nothing in it was changed). Done on 2026-10-29 by reading the repository's tracked files. Lantern was not run and its output for project-1 was not read.

## What was checked

- **Every cited `file:line` in `facts/`** (all 347 cited spans, counting those this pass added) against the lines they cite: root and module POMs, the Dockerfiles, `compose.yaml` (all 432 lines), the realm export (all 217 lines), every main Java class of bff, gateway, profile-service, order-service, keycloak-admin-service, common-core, common-web and common-security, every `application*.properties`, the Flyway migrations, the Angular routes, guard, interceptor, services, components, `proxy.conf.json` and `nginx.conf`, the README sections and `docs/*.md` ranges cited. Also read in full against their citations: `.gitlab-ci.yml:1-123` (build, frontend, manual Playwright e2e, Testcontainers test, report-only Trivy, docker build without push), `common-web/src/main/resources/logback-spring.xml:21-64` (default/dev, json, loki profiles; `LOKI_URL`), `docker/prometheus/prometheus.yml:11-65` (scrapes the five Java services' management ports, not Keycloak), the Grafana provisioning (`compose.yaml:189-215`; Loki, Zipkin and Prometheus datasources; one Spring Boot dashboard), `common-web/pom.xml:61-77`, `.env.example:1-126`, `docs/user_registration_flow.md:9-67,69-117`, `docs/dual_session_strategy.md:5-35,83-97` and `docs/proactive_token_refresh.md:5-65`. Every one supports its claim; none needed a correction. Two doc staleness points were noted but change nothing in the reference: `docs/dual_session_strategy.md:14` says the JSESSIONID session is in memory (the code keeps it in Redis), and `docs/proactive_token_refresh.md:31-35` and `README.md:417` still describe loading an `OAuth2AuthorizedClient` (the code loads a `BffSession`).
- **Every item in `reference.c4.json`**: 41 elements (with their properties), all 8 diagrams' 80 relationships and their protocols, all 6 sequences (step by step), 11 decisions, 13 risks, 5 unknowns, the deployment, clients, routes and glossary tables.
- **What the first pass might have missed**, by sampling entry points (all controllers and filters; there are no listeners or schedulers), clients (RestClient in bff and profile-service, keycloak-admin-client; no WebClient, Feign or messaging), configuration, deployment (`compose.yaml`, both Dockerfiles, nginx, `manage_services.sh`, `.gitlab-ci.yml`, `.env.example`; no Kubernetes or Helm) and the frontend. Nothing outside the first pass's element list exists at runtime: no queue, broker, cache other than Redis, scheduler, or further external system.

## Corrections (before → after)

1. **Sign-in path: the browser talks to the BFF directly, not only through the `/bff` proxy.**
   - Before: container overview "The browser talks only to its own origin"; edge angular-ui → bff `JSON/HTTP via nginx proxy`; sign-in sequence showed `302 /oauth2/authorization/keycloak` followed by the browser going to Keycloak, and Keycloak calling the BFF with the code.
   - After: the redirect is absolute, `http://localhost:8081/oauth2/authorization/keycloak` (Spring Boot's default `server.tomcat.use-relative-redirects=false`; Host pinned to `localhost:8081` by `angular-ui/nginx.conf:47-48` and by `changeOrigin`, `angular-ui/proxy.conf.json:5`); the browser calls the BFF's published port for `/oauth2/authorization/keycloak` (which redirects to Keycloak with state and PKCE) and for the callback `/login/oauth2/code/keycloak` (the realm's only `bff-client` redirect URI, `realm-export.json:48-50`), then is redirected to `/bff/login/success`. Overview text, edge (`JSON/HTTP via nginx proxy; HTTP redirects to :8081`) and the sequence (15 → 19 steps) updated. This also resolves the first pass's "Not checked" item in `notes.md`.
2. **Realm import.** Before: "re-imported on every start" (Keycloak element property, High risk). After: imported at startup only when the realm does not exist yet; Keycloak's `--import-realm` skips an existing realm and its database is a persistent volume (`compose.yaml:54,107`). The repo's own `keycloak/README.md:26-28` says "on every startup"; that statement is about Keycloak's behaviour, not the code, so this correction rests on Keycloak's documented import semantics.
3. **Default-credentials risk (High) was incomplete.** Added the Keycloak bootstrap console admin `admin`/`admin` (`compose.yaml:117-118`), which gives control of the whole Keycloak server, to the finding and its consequence.
4. **Decision "Defense in depth".** Before: "the services also check roles". After: only profile-service's admin endpoints and keycloak-admin-service check realm roles; the gateway and order-service check none (`AdminProfileController.java:24,30`; `AdminController.java:43,101,137`; no `@PreAuthorize` in order-service).
5. **Browser edges said HTTPS.** web-user → angular-ui, visitor → angular-ui, web-user → keycloak and visitor → keycloak changed from `HTTPS` to `HTTP`: nginx listens on plain 4200 (`angular-ui/nginx.conf:2`), Keycloak runs with `KC_HTTP_ENABLED` on 8080 (`compose.yaml:114-115`), and no TLS is configured anywhere. The mobile edges were left as written (`JSON/HTTPS`), since the mobile app is outside the repository.
6. **Citation slip in `facts/edge-ui-bff-gateway.md`**: the 30-minute TTL on every save is in `SessionRedisService.java:31-33`, not `RedisConfig.java:26-35` (which sets the JSON serializer). Fixed.

## Additions

1. **Risk (Low): a profile's email is whatever the client sends.** `POST /api` takes `email` from the body and never compares it with the token (`ProfileService.java:49-64`; `UserProfileRequest.java:19-20`), the UI field is editable (`profile.component.ts:80`), `user_profiles.email` is unique (`V1__init.sql:16`) and registration silently skips any email already present (`RegistrationService.java:103-106`). Any signed-in user can claim another person's address and block their self-registration.
2. **Risk (Low): no transport security by default.** Plain HTTP on every hop, `BFF_COOKIE_SECURE` false by default, Redis without TLS (`angular-ui/nginx.conf:2`; `compose.yaml:114-115,387`; `bff/src/main/resources/application.properties:15,36`; `.env.example:42,82`).
3. **Stale-docs risk extended**: `.env.example` says its values "work out of the box" (`:7`) but sets the client secrets to `changeme` (`:31,48,56`), which do not match the realm export (`realm-export.json:38,47,80`); copied as-is it breaks sign-in and the service calls.
4. **Glossary, JSESSIONID**: notes that with Spring Session and no cookie name configured the servlet session cookie is most likely `SESSION` on the wire (not run); the reference keeps the repo's term. Recorded in `facts/edge-ui-bff-gateway.md` and `notes.md`.
5. Document version 1.0.0 → 1.1.0 with a revision entry for this pass.

## Confirmed as written

- All 7 containers and 4 data stores, their technologies, ports and owners; the single external system (mobile app); the three people and their access.
- All context and component relationships, the gateway route table (including the order -1 block route and the `/profile/profile/admin/users` path), the Keycloak client table, the deployment table.
- The list-orders, self-registration, administrator-creates-user, sign-out and mobile sequences, step by step (including the `REQUIRES_NEW` insert, the internal-client token, the swallowed UPDATE_PASSWORD failure, the saga compensation, which works because `user_profiles.id` is IDENTITY so the insert fails inside the `try`).
- Decisions 1-3 and 5-11 and their cited rationale.
- Risks: default credentials, SMTP/password email, no email sender, blocked email after expiry, non-atomic confirmation, shared anonymous rate-limit bucket (`callGateway` forwards no `X-Forwarded-For`, `TRUSTED_PROXY_HOPS=0`), single instances, 30-minute hard session (the JWT is issued only at `BffController.java:184`), browser order numbers, `enabled` flag (only echoed in `UserProfileResponse`), plain-text tokens and state-changing GET, published host ports, README port.
- All five unknowns.

## Open judgement calls for the scorer

- **Keycloak: container or external system.** Accept either. The repository runs it (compose, `start-dev --import-realm`) and owns its realm configuration, which is why the reference draws it as a container with its own database; a reader who draws Keycloak as an external identity provider (and leaves out its PostgreSQL) is equally defensible, provided the relationships to it are present (BFF login/refresh/logout, JWKS validation by the gateway and the three services, client credentials from profile-service, Admin REST from keycloak-admin-service, browser login).
- **Keycloak's database.** Accept its omission when Keycloak is modelled as external.
- **Observability stack** (Zipkin, Prometheus, Loki, Grafana): left out because none takes part in a user request. Do not penalise a report that includes them as containers or external systems, and do not require them.
- **Mobile app**: external system (no code in the repository). Accept also a "mobile user" person, or both.
- **Administrator**: a person with no UI; accept an output that folds the admin into the web user if the admin API relationships still appear.
- **Libraries** (common-core, -web, -security, -test): not containers; an output that lists them as containers should not be credited for them.
- **Browser-to-BFF direct edge**: accept the sign-in either drawn through angular-ui (as here) or as a separate browser → bff edge; do not penalise an output that only shows the proxied path, since the code needs Spring Boot's redirect default to reveal it.
- **SESSION vs JSESSIONID**: either name is acceptable.

## Validation

- `node scripts/build-data.mjs reference.c4.json` then `node scripts/validate.cjs <lantern>/report-specs/c4-model-sequence-diagrams/c4-model-sequence-diagrams.schema.json reference.c4.json`: **schema valid, no cross-reference issues** (spec files unchanged since `65f6030`).
- `reference.c4.html` re-embedded with `scripts/embed.cjs` and rendered in headless Chrome over all 14 pages with `scripts/render.mjs` (run from `/tmp/claude-1000/v1`, deleted afterwards): no console messages; the new sign-in sequence renders with 19 steps.
