# project-4 (APA-CRM): edges and runtime topology

Scope: Discovery-and-Gateway (HEAD 62649cb, 2026-06-04), CRM-Frontend (0797125, 2026-06-28), Deployment (3857d28, 2026-08-23). Paths are relative to `project-4/`. "DG" = Discovery-and-Gateway, "FE" = CRM-Frontend, "DEP" = Deployment.

## 1. API Gateway (`DG/api-gateway`)

**Tech.** Spring Boot 3.5.8 parent (`DG/pom.xml:10`), Spring Cloud 2025.0.0 (`DG/pom.xml:28`), Java 21 (`DG/pom.xml:24-26`). Reactive Spring Cloud Gateway, `spring-cloud-starter-gateway-server-webflux` (`DG/api-gateway/pom.xml:59`), Eureka client (`:64`), `spring-cloud-starter-kubernetes-client-all` (`:69`), Prometheus registry (`:74`), reactive Redis (`:79`), jjwt 0.12.6 (`:19,83-95`), crm-shared-lib `core` and `rbac` 0.2.0-SNAPSHOT from JitPack (`:24-49`, `DG/pom.xml:33-38`). Image `eclipse-temurin:21-jre-jammy` (`DG/api-gateway/Dockerfile.local:1`).

**Config** (`DG/api-gateway/src/main/resources/application.properties`): `spring.application.name=api-gateway` (:1), port 8080 (:2), reactive (:3). Eureka at `http://${discovery.host}:${discovery.port}/eureka/` (:4). Redis host/port/user/password come from `${redis.*}` (:6-9). Actuator exposes health, info, prometheus, with probes (:11-16). Global CORS origins/methods/headers default to `*` (:18-20), plus the same in `WebFluxConfig.java:16-28`. JWT key `app.token.signing.key=${auth.token.signing.key}` (:22). Downstream logical names: auth-service, file-service, main-service, notification-service (:24-27). `@EnableDiscoveryClient` (`ApiGatewayApplication.java:9`).

**Routes** (`config/RouteConfig.java:33-85`, checked in this order):

| id | predicate | filters | target |
|---|---|---|---|
| notifications-service-web-sockets | `/ws-notifications/**` | none | `lb:ws://notification-service` (:34-37) |
| main-service-authorize | `/api/organizations`, `/api/organizations/*`, `/api/organizations/invitations/**`; GET/POST/PATCH/OPTIONS | AuthFilter | `lb://main-service` (:38-45) |
| auth-service-authorize | `/api/users/**` | AuthFilter | `lb://auth-service` (:46-51) |
| main-service-check-access | `/api/organizations/*`, `/*/users/**`, `/*/roles/**`, `/*/users/*/roles/**`, `/*/invitations`, `/*/files/**`, `/*/tasks/**` | AuthorizeAndCheckAccessFilter | `lb://main-service` (:52-64) |
| file-service-check-access-and-file-existence | `/api/files/**`; GET/PATCH/OPTIONS | CheckAccess + OrganizationFilesFilter | `lb://file-service` (:65-71) |
| file-service-check-access | `/api/files/**`; DELETE/POST/OPTIONS | CheckAccess | `lb://file-service` (:72-78) |
| authorization-api | `/api/auth/**`, `/api/restore-password-request/**` | none (public) | `lb://auth-service` (:79-84) |

**What the gateway checks itself:**
- **AuthFilter** (`filters/AuthFilter.java:26-43`) reads `Authorization: Bearer …` (`utils/JwtUtils.java:9-13`). It checks the HS-signed JWT with a Base64 HMAC key, rejects expired tokens and reads the `USER_ID_KEY` and `USER_LOGIN_KEY` claims (`service/JwtService.java:16-49`). It returns 401 `{"message":"Unauthorized"}` on failure. On success it adds the `User-Id` and `User-Login` headers (header names in `crm-shared-lib/core/.../CrmHeaders.java:5-11`).
- **AuthorizeAndCheckAccessFilter** (`filters/AuthorizeAndCheckAccessFilter.java:28-57`) does the JWT check, then needs an organization ID. It takes it from the path `/api/organizations/{id}` or from the `Organization-Id` header (`utils/OrganizationIdExtractor.java:16-43`) and returns 403 when there is none. It then gets the user's permissions in that organization (`service/UserPermissionService.java:17-20`): first the Redis key `organization:%d:user:%d:permissions` (`cache/UserPermissionRepository.java:14-22`; `crm-shared-lib/rbac/.../CacheConstants.java:5`), and on a miss the auth-service over HTTP (`clients/UserPermissionClient.java:13-29`). It adds `User-Id`, `User-Login` and a serialized `User-Permissions` header. The gateway does not decide permit or deny itself; downstream services enforce the permissions header.
- **OrganizationFilesFilter** (`filters/OrganizationFilesFilter.java:36-80`) runs for GET and PATCH on `/api/files/{uuid}…`. It needs `Organization-Id`, `User-Id` and the file UUID, then calls main-service `GET /api/internal/organizations/{orgId}/files/{fileId}/check` (`clients/OrganizationFilesClient.java:15-34`). A 4xx from downstream is passed back to the caller (`BaseGatewayFilter.java:30-41`).
- **None found:** no rate limiter, no circuit breaker, no retry filter, and no stripping of incoming `User-Id`/`User-Permissions` headers on the public routes.

**Gateway's own REST controller (composition).** `TasksCompositeController` serves `/api/composite/organizations/{orgId}/tasks/filter|{taskId}` with GET, POST, PUT and DELETE (`composite/controllers/TasksCompositeController.java:17-70`). It checks the JWT and gets permissions (`composite/service/UserDetailsService.java:22-38`). It then calls main-service `/api/organizations/{orgId}/tasks[/filter|/{taskId}]` with the `User-Id` and `User-Permissions` headers (`composite/clients/TasksClient.java:24-90`). It adds user details from auth-service `GET /api/internal/users?userId=…` (`composite/clients/UsersClient.java:15-32`) and merges them (`composite/TasksComposite.java:35-99`). The WebClients are `@LoadBalanced` with base URLs `http://auth-service` and `http://main-service` (`config/WebConfig.java:18-26`, `config/WebClientConfig.java:12-28`).

**Stores.** Redis only, and only read (a `ReactiveRedisTemplate<String,UserPermission>` in `config/RedisConfig.java:14-28`). The writer is Auth-Service (`Auth-Service/src/main/java/com/crm/auth/cache/UserPermissionRepository.java` uses the same key constant).

## 2. Eureka (`DG/eureka-service`)
- `spring-cloud-starter-netflix-eureka-server` and actuator (`DG/eureka-service/pom.xml:21-29`), Spring Cloud 2025.0.0, Boot 3.5.8. `@EnableEurekaServer` (`EurekaServerApplication.java:8`).
- `spring.application.name=eureka-service`, port 8761, no self-registration or registry fetch (`DG/eureka-service/src/main/resources/application.properties:1-4`). It is a single standalone instance with no peers.
- **Who registers (each uses defaultZone `http://${discovery.host}:${discovery.port}/eureka/`):**
  - api-gateway (`DG/.../application.properties:4`)
  - auth-service (`Auth-Service/src/main/resources/application.properties:1,4`)
  - file-service (`File-Service/src/main/resources/application.properties:1,4`)
  - notification-service (`Notification-Service/src/main/resources/application.properties:1,4`)
  - main-service (`Main-Service/src/main/resources/application.yml:3-6,41`)
- **Compose:** the container is called `discovery-service` (`DEP/docker-compose.dev.yml:73-84`, `DEP/docker-compose.local.yml:72-85`).
- **Kubernetes:** Eureka is not deployed. Both the Helm ConfigMap and the kustomize overlay set `EUREKA_CLIENT_ENABLED=false` (`DEP/heml-chart/templates/config-map.yaml:11`, `DEP/kustomize/overlays/dev/configuration-dev.properties:2`), and the profiles become `dev,kubernetes`. So discovery in Kubernetes is presumably Spring Cloud Kubernetes, the gateway having that dependency. RBAC grants get/list/watch on services, endpoints and pods (`DEP/heml-chart/values.yaml:46-63`, `DEP/kustomize/base/rbac.yml`).

## 3. Frontend (`FE`)
- **Tech:** Angular ^19.2, PrimeNG ^19 (Aura theme), Tailwind 4, `@stomp/stompjs` ^7.2.1, TypeScript ~5.7 (`FE/package.json:12-42`). Standalone bootstrap with HTTP interceptors (`FE/src/app/app.config.ts:13-32`).
- **Serving:** a multi-stage image. Stage 1 is `node:24.3.0-alpine3.22` running `ng build --configuration development`; stage 2 is `nginx:alpine3.22` (`FE/Dockerfile:1-21`). nginx listens on **4200** and serves only static files with an SPA fallback (`try_files … /index.html`). There is **no reverse proxy to the API** (`FE/nginx.conf:1-13`). `EXPOSE 82` in the Dockerfile (:19) is misleading, since compose maps `82:4200` (`DEP/docker-compose.dev.yml:16-17`).
- **Backend base URL:** the browser calls the gateway directly at `environment.apiUrl`:
  - development: `http://localhost:8080` (`FE/src/environments/environment.development.ts:2`), which is what the image uses.
  - local: `http://localhost:8080` (`environment.local.ts:2`).
  - default/production: hardcoded LAN IP `http://192.168.88.113:8080` (`environment.ts:2`).
  - File replacements are in `FE/angular.json:59-75`.
- **Auth handling:**
  - Login: `POST /api/auth/sign-in`. Also `sign-up`, `refresh` and `logout` (`FE/src/app/core/services/auth.service.ts:16-41`).
  - Token storage: `token`, `tokenType` and `refreshToken` are kept in **localStorage** (`core/services/auth-storage.service.ts:9-38`).
  - `AuthInterceptor` adds `Authorization: <type> <token>`. On a 401 it calls refresh once and retries; if refresh fails it goes to `/login` (`core/interceptors/auth-interceptor.service.ts:24-84`).
  - `OrganizationInterceptor` adds `Organization-Id` from localStorage to every request (`core/interceptors/organization-interceptor.interceptor.ts:13-27`).
  - The route guard only checks that the tokens are in localStorage (`core/guards/authentication.guard.ts:5-14`).
  - There is no external IdP; tokens are issued by Auth-Service.
- **Users and roles:** there are no fixed role names such as admin or sales. Access uses custom per-organization roles, each a resource × action matrix:
  - resources: All, Users, User-Roles, Roles, Organizations, Invitations, Files (`core/enums/resource.ts`)
  - actions: All, Create, Read, Update, Delete (`core/enums/action.ts`)
  - Users belong to one or more organizations and switch between them (`/organization/choose`, `/organization/create`; `app.routes.ts:33-34`).
- **Routes** (`FE/src/app/app.routes.ts:27-51`):
  - public: login, forgot-password, verify-code/:requestId, restore-password, registration, invitation/:invitationId
  - guarded, under `HomeComponent`: users, roles, user/me, user/:id, organization/:id, files/root, files/:fileId, tasks, tasks/:taskId, tasks/priorities, tasks/statuses
- **API paths used**, with the gateway route that serves each:
  - Auth: `/api/auth/*` (public); `/api/restore-password-request`, `/{id}/restore-password`, `/{id}/resend` (`core/services/restore-password.service.ts:15-32`) (public)
  - Users: `/api/users/me`, `/api/users?fullName=`, `/api/users/{id}` with PATCH and `/update-password` (`user.service.ts:14-38`); `/api/users/sessions[/{id}]` with GET and DELETE (`user-session.service.ts:12-26`) → AuthFilter → auth-service
  - Organizations: `/api/organizations` GET/POST, `/{id}` GET/PUT, `/{id}/preview` (`organizations/organization.service.ts:14-38`) → main-service
  - Invitations: `/api/organizations/invitations/{id}[/accept|/decline]`, `/{org}/invitations` (`organization-invitation.service.ts:13-38`)
  - Organization users and roles: `/{org}/users/filter`, `/{org}/users/{id}`, `/{org}/roles[/filter|/{id}]`, `/{org}/users/{u}/roles/{r}` (`organization-users*.service.ts`, `organization-roles.service.ts`) → check-access → main-service
  - Organization files: `/{org}/files/root|{fileId}` (`organization-files.service.ts:12-32`) → main-service
  - Files: `/api/files` POST multipart; `/api/files/{id}` GET/PATCH/DELETE; `/{id}/content` blob (`core/services/files.service.ts:14-55`) → file-service
  - Task priorities and statuses: `/api/organizations/{org}/tasks/priorities|statuses[/{id}]` CRUD (`tasks/task-priorities.service.ts`, `tasks/task-statuses.service.ts`) → main-service
  - Tasks: `/api/composite/organizations/{org}/tasks[/filter|/{id}]` (`composite/task-composite.service.ts:17-55`) → gateway composite
  - Notifications: a STOMP WebSocket to `apiUrl + '/ws-notifications'`, sending `Authorization` in the connect headers. It subscribes to `/topic/organizations/{orgId}/notifications` and `/user/topic/notifications` (`core/services/notification.service.ts:28-61`).

## 4. Deployment topology

### 4a. `docker-compose.local.yml` (builds from source with `Dockerfile.local`; `.env.local` is not committed, `DEP/.gitignore:1`)
- **Databases:** postgres:17.5-alpine3.22, one per service, all on network `backend-net` (:10-12):
  - main-db-service, host port 5432 (:15-32)
  - auth-db-service, 5433 (:34-51)
  - file-db-service, 5434 (:53-70)
- **discovery-service:** port 8761, with a healthcheck (:72-85).
- **api-gateway:** port 8080 (:87-120).
  - env: defaultZone `http://discovery-service:8761/eureka/`, `REDIS_*`, `AUTH_TOKEN_SIGNING_KEY`
  - depends on discovery, redis and all four services being healthy
- **auth-service** (:122-159):
  - env: `DISCOVERY_*`, `AUTH_DATASOURCE_*`, `REDIS_*`, `RABBIT_*`, `AUTH_TOKEN_SIGNING_KEY`
  - uses auth-db, Redis and RabbitMQ
- **main-service** (:161-191): `MAIN_DATASOURCE_*` and `RABBIT_*`; uses main-db and RabbitMQ.
- **file-service** (:193-221): `FILE_DATASOURCE_*` and `RABBIT_*`; uses file-db and RabbitMQ.
- **notification-service** (:223-249): `RABBIT_*`, `SMTP_USERNAME/PASSWORD` and `FRONTEND_URL`; uses RabbitMQ and SMTP. Its SMTP host is fixed in its own config as `smtp.gmail.com:587` with STARTTLS (`Notification-Service/src/main/resources/application.properties:10-15`).
- **rabbitmq:** `rabbitmq:3-management`, ports 5672 and 15672 (:251-260).
- **redis:** `redis:8.4.0-alpine`, port 6379 (:263-279).
- **Observability** (compose profile `observability`):
  - prometheus `prom/prometheus:v3.11.3`, port 9090 (:281-301)
  - grafana `grafana/grafana:13.0.1-security-01-ubuntu`, port 3000, admin credentials from env (:303-319)
  - Prometheus scrapes `/actuator/prometheus` on main-service, auth-service, file-service, notification-service and api-gateway, all on port 8080, every 30 s (`DEP/observability/prometheus.yml:1-28`)
  - Grafana uses the Prometheus datasource (`grafana/provisioning/datasources/datasource.yml`) and file-provisioned JVM and Spring Boot dashboards (`grafana/provisioning/dashboards/*.json`)
- **No frontend service** in this file, even though the README says there is one.

### 4b. `docker-compose.dev.yml` (pre-built Docker Hub images `aappaa/*:develop`; run through `DEP/Makefile:1-27` with `.env.dev`)
- **frontend-angular:** `aappaa/frontend-angular:develop`, `82:4200` (:12-17). It is not on backend-net, which it does not need because the browser calls the gateway.
- **Databases:** main-postgres-service 5432, auth-postgres-service 5433, file-postgres-service 5434 (:19-71).
- **Services:**
  - discovery-service: `aappaa/discovery-service:develop`, port 8761 (:73-84)
  - api-gateway: `aappaa/gateway-service:develop`, port 8080, env **only** `DISCOVERY_HOST/PORT` (:86-98)
  - auth, main, file and notification services: `aappaa/*:develop` (:100-195)
- **rabbitmq-service:** `rabbitmq:3-management` (:197-207).
- **redis-service:** `redis:7.4.4-alpine` (:210-226).
- **Missing:** observability, and app healthchecks.

### 4c. Helm chart `DEP/heml-chart` (chart `apa-crm` 0.1.0, appVersion develop; `Chart.yaml:1-6`)
- **Deployments and ClusterIP Services** on port 8080 for apigateway, authservice, mainservice, fileservice and notificationservice. Images are `aappaa/<name>:develop` (`values.yaml:2-21`; `templates/*service.yaml`, `templates/apigateway.yaml`). Each defaults to 1 replica with 512Mi/500m requests and 1Gi/1 CPU limits (`values.yaml:121-187`).
  - Probes: `/actuator/health/liveness|readiness` (`apigateway.yaml:53-68`).
  - Non-root security context (`:24-42`).
- **Ingress:** nginx class, host `www.apa.local`, path `/` → apigateway:8080 (`templates/ingress.yml:1-30`). It has CORS annotations with `allowOrigin: "*"` **and** `allowCredentials: true` (`values.yaml:31-44`; origin/credentials at :36-38).
- **ConfigMap** (`templates/config-map.yaml:9-25`):
  - `APP_CLIENTS_*_SERVICE_NAME` set to the release-prefixed Kubernetes service names
  - DB, RabbitMQ and Redis hosts from values, which are empty by default (`values.yaml:210-234`)
  - `FRONTEND_URL`
- **Secret:** an Opaque `stringData` secret with the DB credentials for all three databases, `AUTH_TOKEN_SIGNING_KEY`, SMTP, RabbitMQ and Redis (`templates/secrets.yaml:1-28`). Its values are empty in `values.yaml:73-119`.
- **ServiceMonitor** (Prometheus Operator, label `release: prometheus`): selects `backend-service`/`aappaa` and scrapes port `http` at `/actuator/prometheus` every 20 s (`templates/servicemonitor.yaml`).
- **Not in the chart:** Postgres, RabbitMQ, Redis, Eureka and the frontend. These are expected to be external, with hosts set through values.

### 4d. Kustomize (`DEP/kustomize`, namespace `aappaa`)
- **Base resources** (`base/kustomization.yml:1-21`):
  - **postgres:** a StatefulSet with `postgres:17.5-alpine3.22`, a **single instance** holding `auth_db`, `file_db` and `main_db`, created by an init script (`base/postgres.yml`, `base/init-scripts/init-databases.sh:4-8`). Data is a hostPath PV at `/data/postgresql`, 5Gi (`base/postgres-pv-pvc.yml`).
  - **rabbitmq:** `rabbitmq:4-management` (`base/rabbitmq.yml:25`).
  - **redis:** `redis:8.4.0-alpine` with `--requirepass` (`base/redis.yml:25-34`).
  - **Service types:** NodePort for the infrastructure; ClusterIP services named `gateway-service`, `auth-service`, `main-service`, `file-service` and `notification-service` for the apps (`base/backend-services/*.yml`).
  - **ingress:** host `www.aappaa.com`, `/` → gateway-service:8080 (`base/ingress.yml`).
- **Dev overlay:**
  - ConfigMap from `configuration-dev.properties`: Eureka off, profiles `dev,kubernetes`, all DB hosts `postgres-service`, `rabbitmq-service`, `redis-service`. `FRONTEND_URL` is not set.
  - Secret from a `.env` that is not committed.
  - Label `environment: develop`.
  - Sources: `overlays/dev/kustomization.yml`, `configuration-dev.properties:1-20`.

### 4e. Infrastructure per service (all environments)

| Service | DB | Redis | RabbitMQ | Other |
|---|---|---|---|---|
| api-gateway | – | read (permission cache) | – | Eureka/K8s discovery |
| auth-service | auth_db (Postgres) | yes (writes the permission cache) | yes | JWT signing key |
| main-service | main_db | – | yes | – |
| file-service | file_db | – | yes in local compose and K8s; not in dev compose | – |
| notification-service | – | – | yes | Gmail SMTP, FRONTEND_URL, WebSocket STOMP |

None of the deployment files have **MinIO/S3, MailHog, Keycloak, Loki, Zipkin/Tempo or OTel**.

### 4f. CI/CD
- GitHub Actions in each repository build and push Docker Hub images `aappaa/<img>:<branch>`, with `main` tagged `latest`.
- Gateway: `DG/.github/workflows/backend-ci-cd.yml:1-56` builds and pushes **only** `aappaa/gateway-service` (:5, 49-56).
- Frontend: `FE/.github/workflows/frontend-ci-cd.yml:1-49`.
- No workflow in these repositories builds `aappaa/discovery-service`.

## 5. Actors and external systems
- **End user:** an organization member using a browser SPA. They register, log in, create or choose an organization, invite members, manage roles, users, files and tasks, and receive real-time notifications.
- **Organization administrator:** not a fixed role. Any member whose custom role grants Roles, Users or Invitations rights.
- **Invitee:** an unauthenticated user who opens `/invitation/:id` and accepts or declines.
- **External systems:**
  - **Gmail SMTP** (`smtp.gmail.com:587`): outbound email for invitations and password-reset codes (the reset purpose is inferred from the restore-password flow and `FRONTEND_URL` "used in the email letters", `values.yaml:233-235`).
  - **Docker Hub** (`aappaa/*`): image registry.
  - **JitPack:** shared-lib artifacts.
- **Operators:** the Prometheus/Grafana stack, and the Prometheus Operator in Kubernetes.

## 6. End-to-end flows (from config and code)

1. **Load the SPA.** The browser gets `http://host:82` from nginx, which serves static files from container port 4200. nginx does not touch any API traffic.

2. **Log in.**
   1. The SPA sends `POST {apiUrl}/api/auth/sign-in` to the gateway on port 8080.
   2. The `authorization-api` route forwards it, with no filter, to `lb://auth-service`.
   3. The response carries `token`, `tokenType` and `refreshToken`, which the SPA keeps in localStorage.
   4. The user picks an organization. Its ID goes into localStorage and from then on into the `Organization-Id` header.

3. **Organization-scoped request**, e.g. `GET /api/organizations/{org}/users/filter`.
   1. The gateway's `main-service-check-access` route checks the JWT signature and expiry.
   2. It takes orgId from the path.
   3. It reads Redis `organization:{org}:user:{uid}:permissions`; on a miss it calls auth-service.
   4. It adds `User-Id`, `User-Login` and `User-Permissions` and forwards to `lb://main-service`, which enforces the permissions.

4. **Read a file**, `GET /api/files/{uuid}/content`.
   1. The CheckAccess filter runs, with orgId taken from the `Organization-Id` header.
   2. The OrganizationFilesFilter calls main-service `/api/internal/organizations/{org}/files/{uuid}/check`.
   3. The request is forwarded to `lb://file-service`.

5. **Tasks (composition).**
   1. The SPA calls `/api/composite/organizations/{org}/tasks/filter`.
   2. The gateway's own controller checks the JWT and gets permissions from Redis or auth-service.
   3. It calls main-service `/api/organizations/{org}/tasks/filter`.
   4. It calls auth-service `/api/internal/users?userId=…`.
   5. It merges the two results.

6. **Notifications.**
   1. The SPA opens a STOMP connection to `{apiUrl}/ws-notifications`.
   2. The gateway passes it through to `lb:ws://notification-service` without checking authentication. The JWT travels in the STOMP CONNECT `Authorization` header.
   3. The SPA subscribes to the organization topic and to the user queue.

7. **Token refresh.** On a 401 the interceptor calls `POST /api/auth/refresh` and retries the request.

8. **Kubernetes.** The request goes from the nginx Ingress (`/`) to the gateway Service. The gateway resolves `lb://<APP_CLIENTS_*>` through Kubernetes discovery. No SPA is deployed.

## 7. Decisions and risks
- **README rationale.** Docker Compose is meant for local and dev, Helm for "Kubernetes production" (`DEP/README.md:18-21`). The README admits Helm secrets are plaintext and suggests Sealed Secrets or the External Secrets Operator (`:450-458`).
- **Parts of the README are generic or inaccurate:**
  - it lists `deployment.yaml` and `service.yaml` templates that do not exist (`:399-404`)
  - it claims a frontend in the local compose (`:183`)
  - it lists env vars such as `JWT_EXPIRATION` and `DATABASE_POOL_SIZE` that no service config uses (`:526-579`)
- **Bug: the permission lookup misses its path.** On a cache miss the gateway calls `http://auth-service/users/{u}/organizations/{o}/persmissions` (`DG/.../UserPermissionClient.java:13-14`). Auth-service serves that endpoint under `/api/internal` (`Auth-Service/.../InternalUserPermissionsController.java:12,18`), and the gateway's path lacks that prefix. So the lookup works only when Redis already has the entry. "persmissions" is misspelled on both sides.
- **Bug: an organization endpoint the gateway does not route.** The frontend calls `GET /api/organizations/{id}/preview` (`FE/.../organization.service.ts:26`), and Main-Service serves it (`Main-Service/.../OrganizationController.java:36`). No gateway route pattern matches it, since `*` matches one path segment only, so it probably returns 404.
- **Risk: incoming identity headers are not stripped.** The public routes (`/api/auth/**`, `/api/restore-password-request/**`, `/ws-notifications/**`) pass client-supplied `User-Id` and `User-Permissions` headers straight to downstream services, which use those headers to make access decisions.
- **Risk: CORS.**
  - The gateway defaults to `*` for origins, methods and headers (`application.properties:18-20`).
  - The Helm ingress sets `*` together with credentials (`values.yaml:36-38`), which browsers reject; the chart's own comment warns about this.
- **Config gaps:**
  - The dev compose gateway gets no `REDIS_*` or `AUTH_TOKEN_SIGNING_KEY` (`docker-compose.dev.yml:89-91`), yet `${auth.token.signing.key}` and `${redis.host}` have no defaults, so startup probably fails.
  - The dev compose file-service gets no RabbitMQ env.
  - The kustomize dev config has no `FRONTEND_URL`.
  - The kustomize Postgres pod needs `POSTGRES_DATASOURCE_USERNAME/PASSWORD` keys that the Helm Secret does not define.
- **Committed secrets or hardcoded values:**
  - A test JWT key in `DG/api-gateway/src/test/resources/application-test.properties:11` (test only).
  - The LAN IP `192.168.88.113` in `FE/src/environments/environment.ts:2`.
  - No real `.env` files are committed (`DEP/.gitignore`).
- **Frontend:**
  - The production image is built with `--configuration development` (`FE/Dockerfile:12`), so it targets `localhost:8080`, and the API URL is fixed at build time.
  - Tokens sit in localStorage, which is exposed to XSS.
- **Single points of failure:**
  - one Eureka instance (no peers)
  - one Postgres StatefulSet for all databases in kustomize
  - single Redis and RabbitMQ instances
  - Helm defaults to 1 replica everywhere
  - The gateway's authorization depends on Redis and auth-service being available.
- **Versions:**
  - `rabbitmq:3-management` in compose is RabbitMQ 3.x, against 4 in kustomize.
  - Redis is 7.4.4 in dev and 8.4.0 elsewhere.
  - Images float on `:develop` with `imagePullPolicy: Always`.
  - CI uses `actions/checkout@v3` and passes `node-version` to checkout instead of setup-node (`frontend-ci-cd.yml:21-24`).
- **Manifest defects:**
  - `restartPolicy: OnFailure` is placed inside the container spec, which is not valid there (`heml-chart/templates/apigateway.yaml:45`, `kustomize/base/backend-services/apigateway.yml:40`).
  - The gateway Service port has no name, so the ServiceMonitor (port `http`) does not scrape the gateway (`apigateway.yaml:87-88`).
  - The StorageClass `local-storage` is defined but the PV uses `manual`.
  - The directory is named `heml-chart`, a typo for helm-chart.

## 8. Unknowns
- Where the `aappaa/discovery-service` image comes from; no workflow builds it.
- Whether the services other than the gateway include Spring Cloud Kubernetes discovery. That is needed for `lb://` in Kubernetes with Eureka disabled.
- Where file content is stored. There is no object store or volume in any deployment, so presumably in `file_db` or the container filesystem. File-Service analysts should confirm.
- How notification-service checks the STOMP `Authorization` header, since the gateway does not.
- How the SPA is served in Kubernetes. The ingress sends `/` to the gateway, and no frontend manifest exists.
- The values in `.env.dev` and `.env.local`, which are not committed. The README's `.env.dev.example` does not exist in the repository.
- Which RabbitMQ exchanges and queues connect the services. That is covered by the service analysts.
