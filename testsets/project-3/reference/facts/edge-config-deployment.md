# project-3 (MediaHub): gateway, configuration, discovery and deployment

Scope: mediahub-api-gateway (HEAD `3ad3447`, 2026-03-15), mediahub-config-server (`7def2da`, 2026-03-15), mediahub-config-repo (`a57c8a6`, 2026-03-15), mediahub-discovery-server (`4363aa9`, 2026-03-15), mediahub-infra (`aef86fb`, 2026-03-15). Paths are relative to `project-3/`. Abbreviations: "GW" = mediahub-api-gateway, "CS" = mediahub-config-server, "CR" = mediahub-config-repo, "DS" = mediahub-discovery-server, "INFRA" = mediahub-infra. Only tracked files were read (`git ls-files`).

## 1. API gateway (`GW`)

**Tech.** Spring Boot **3.4.3** parent (`GW/pom.xml:5-10`), Spring Cloud **2024.0.0** (`GW/pom.xml:18`), Java 17 (`GW/pom.xml:17`). Dependencies: `spring-cloud-starter-gateway` (reactive gateway, `:23`), `spring-cloud-starter-config` (`:27`), Eureka client (`:31`), loadbalancer (`:35`), actuator (`:39`), `spring-cloud-starter-bootstrap` (`:43`). **No** `micrometer-registry-prometheus` and **no** Spring Security dependency (whole `pom.xml`). Image `eclipse-temurin:17-jre-alpine` copying a pre-built `target/app.jar` (`GW/Dockerfile:1-4`). Main class only has `@SpringBootApplication @EnableDiscoveryClient` (`GW/src/main/java/com/mediahub/api_gateway/ApiGatewayApplication.java:7-9`); there is no other Java code in the gateway.

**Local config** (`GW/src/main/resources/application.yaml`): `spring.application.name: api-gateway` (:1-3); config client URI `${SPRING_CLOUD_CONFIG_URI:http://config-server:8888}` (:4-6); discovery locator enabled with `lower-case-service-id: false` (:8-11); three routes (:12-24):

| id | predicate | target |
|---|---|---|
| user_route | `Path=/api/v1/users/**` | `lb://USER-SERVICE` (:13-16) |
| media_route | `Path=/api/v1/media/**` | `lb://MEDIA-SERVICE` (:17-20) |
| subscription_route | `Path=/api/v1/subscriptions/**` | `lb://SUBSCRIPTION-SERVICE` (:21-24) |

No filters at all on any route. Eureka client registers and fetches (:26-33). Actuator exposure `include: "*"` (:35-39). Gateway logging DEBUG (:41-46).

**Config from the config server** (`CR/api-gateway.yml`): the same discovery locator settings (:4-7) and the same three routes (:8-20); actuator `endpoint.gateway.enabled: true` and exposure `health,info,gateway,routes` (:22-29); logging `org.springframework.cloud.gateway: TRACE` (:31-35). The committed gateway log shows the gateway fetching its config by name: `Located environment: name=api-gateway, profiles=[default]` (`GW/app.log:39`, 2026-03-06; media- and subscription-service likewise at `app.log:39` with `profiles=[dev]`, and register in Eureka as `MEDIA-SERVICE` and `SUBSCRIPTION-SERVICE` at `app.log:98`; verification: was `:88`). These logs predate HEAD and describe an earlier run, not the current code.

**Discovery locator.** With `discovery.locator.enabled: true` and `lower-case-service-id: false`, Spring Cloud Gateway creates a route `/{SERVICE-ID}/**` → `lb://{SERVICE-ID}` for every service registered in Eureka. Every registered service (user-, media-, subscription-service, config-server, the gateway itself) is therefore reachable through port 8080 under its upper-case id. This is the **only** way to reach user-service's `/api/history/**` through the gateway (`USER-SERVICE/.../WatchHistoryController.java:11`, not covered by `user_route`).

**What the gateway does not do.** No authentication, token check, rate limiter, circuit breaker, retry or CORS configuration exists anywhere in GW or `CR/api-gateway.yml`. The `/api/v1/auth/**` path the web app calls has no route.

**Tests.** Only the generated context-load test `GW/src/test/java/.../ApiGatewayApplicationTests.java`.

## 2. Config server (`CS`)

**Tech.** Spring Boot **4.0.3** (`CS/pom.xml:5-10`), Spring Cloud **2025.1.0** (`CS/pom.xml:31`), Java 17 (`:30`); `spring-cloud-config-server` (`:36`), Eureka client (`:40`), actuator (`:50`). `@EnableConfigServer @EnableDiscoveryClient` (`CS/src/main/java/com/mediahub/config_server/ConfigServerApplication.java:8-11`). Image `eclipse-temurin:17-jre-alpine` (`CS/Dockerfile:1-4`).

**Config** (`CS/src/main/resources/application.yaml`): port 8888 (:1-2); name `config-server` (:4-6). Two backends (:7-18):
- `git`: `uri: https://github.com/A-007481D/mediahub-config-repo.git`, `default-label: main`, `search-paths: '{application}'`, credentials `${GIT_USER}` / `${GIT_TOKEN}`, `clone-on-start: true` (:10-16).
- `native`: `search-locations: file:///config/` (:17-18).
- Active profile `${SPRING_PROFILES_ACTIVE:git}` (:19-20): **git by default**, native when the variable says so.
- `encrypt.key: ${ENCRYPT_KEY}` (:21-22); no `{cipher}` values exist in CR, so encryption is configured but unused.
- Eureka `service-url.default-zone: ${EUREKA_URL:http://localhost:8761/eureka/}` (:24-27).
- Actuator health and info, `show-details: always` (:30-37).
- `CS/.env.example:1-3` lists `GIT_USER`, `GIT_TOKEN` (a personal access token) and `EUREKA_URL`.
- No Spring Security: the config endpoints (`/{application}/{profile}`) are unauthenticated.

The committed `CS/config.log` shows a git-mode run serving `api-gateway.yml`, `application.yml` and `subscription-service.yml` from a clone under `/tmp/config-repo-…` (lines 106-117; verification: was 106-109). `DS/eureka.log:108` shows `CONFIG-SERVER/localhost:config-server:8888` registering in that local run.

## 3. Config repository (`CR`)

Plain YAML, one file per application plus a shared one; `CR/README.md:1` is a title only.

- `CR/application.yml` (shared by all clients): Eureka `defaultZone ${EUREKA_CLIENT_SERVICEURL_DEFAULTZONE:http://discovery-server:8761/eureka/}` (:1-4); registry fetch every 5 s and initial replication 5 s (:5-6); `prefer-ip-address` (:8); lease renewal 5 s, expiry 10 s (:9-10); actuator exposure `health, info, prometheus` (:12-16); metrics tag `application` (:17-19). HEAD commit message: "perf: speed up eureka registration and fetch for development".
- `CR/api-gateway.yml`: see §1.
- `CR/user-service.yml`: port 8081 (:1-2); `jdbc:postgresql://${DB_HOST:user-db}:${USER_DB_PORT:5432}/mediahub_user` (:6), user `${DB_USER:postgres}`, password `${DB_PASS:password}` (:7-8); `ddl-auto: update`, `show-sql: true` (:10-13).
- `CR/media-service.yml`: port 8082 (:1-2); `…${DB_HOST:media-db}:${MEDIA_DB_PORT:5432}/mediahub_media` (:6); same credentials defaults (:7-8); `ddl-auto: update`, `show-sql: true` (:10-13).
- `CR/subscription-service.yml`: port 8083 (:1-2); `…${DB_HOST:subscription-db}:${SUB_DB_PORT:5432}/mediahub_subscription` (:6); same defaults (:7-8); `ddl-auto: update`, `show-sql: true` (:10-13).
- No `*-dev.yml` files exist, although user-, media- and subscription-service activate profile `dev` (see services facts); they get the default files.

## 4. Discovery server (`DS`)

- Boot 4.0.3, Spring Cloud 2025.1.0, Java 17 (`DS/pom.xml:5-10,30-31`); `spring-cloud-starter-netflix-eureka-server` (`:36`), actuator (`:46`), `micrometer-registry-prometheus` (`:50`). `@EnableEurekaServer` (`DS/src/main/java/com/mediahub/discovery_server/DiscoveryServerApplication.java:7-9`).
- Port 8761, name `discovery-server`, hostname `${EUREKA_HOSTNAME:discovery-server}`, `register-with-eureka: false`, `fetch-registry: false`, defaultZone pointing at itself (`DS/src/main/resources/application.yaml:1-15`): a single standalone instance, no peers. No actuator exposure is configured, so only `health` is exposed over HTTP (Boot default).
- Image `eclipse-temurin:17-jre-alpine` (`DS/Dockerfile:1-4`).

## 5. Deployment (`INFRA`)

**Compose** (`INFRA/docker-compose.yml`), one bridge network `mediahub-network` (:160-162):

| service | source | ports | notes |
|---|---|---|---|
| discovery-server | `build: ./discovery-server` (:2-3) | 8761:8761 (:5-6) | healthcheck wget :8761 (:7-11) |
| config-server | `build: ./mediahub-config-server` (:16) | 8888:8888 (:18-19) | `SPRING_PROFILES_ACTIVE=native` (:22), volume `./mediahub-config-repo:/config` (:23-24), healthcheck `GET /api-gateway/default` (:25-29) |
| api-gateway | `build: ./api-gateway` (:36) | **8080:8080** (:38-39) | config URI and Eureka by env (:40-42), `restart: on-failure` (:43), waits for discovery and config healthy (:44-48) |
| user-db | `postgres:15-alpine` (:53) | 54331:5432 (:59-60) | DB `mediahub_user`, user `postgres`, password `password` (:55-58) |
| media-db | `postgres:15-alpine` (:70) | 54341:5432 (:76-77) | DB `mediahub_media`, `postgres`/`password` (:72-75) |
| subscription-db | `postgres:15-alpine` (:87) | 54351:5432 (:93-94) | DB `mediahub_subscription`, `postgres`/`password` (:89-92) |
| user-service | `build: ./user-service` (:104) | none | `DB_HOST=user-db`, `USER_DB_PORT=5432` (:109-110); waits for its DB, discovery, config (:112-118) |
| media-service | `build: ./media-service` (:123) | none | `DB_HOST=media-db`, `MEDIA_DB_PORT=5432` (:128-129) (:131-137) |
| subscription-service | `build: ./subscription-service` (:142) | none | `DB_HOST=subscription-db`, `SUB_DB_PORT=5432` (:147-148) (:150-156) |

- The build contexts are sub-folders of INFRA named `discovery-server`, `api-gateway`, `user-service`, `media-service`, `subscription-service`, `mediahub-config-server`, `mediahub-config-repo`; the repositories are named `mediahub-*`. None of these folders is in INFRA (`git ls-files`), so the services must be cloned or linked into INFRA under those names first. The Makefile uses the same names (`INFRA/Makefile:7`) and builds each jar with `./mvnw clean package -DskipTests`, renaming it to `target/app.jar` (`:27-35`), which the Dockerfiles copy.
- **The web app is not in compose** and has no Dockerfile (`mediahub-frontend-ui` file list).
- No Prometheus or Grafana service in compose, although `INFRA/prometheus/prometheus.yml:1-32` (targets `host.docker.internal:8761, 8080, 8081, 8082, 8083`) and Grafana provisioning (`INFRA/grafana/provisioning/...`) exist. The gateway lacks the Prometheus registry (§1) and the discovery server does not expose `/actuator/prometheus` (§4), so two of the five targets would fail. Verification: the three services publish no ports in compose (`INFRA/docker-compose.yml:103-158`), so their `host.docker.internal:8081-8083` targets fail too: none of the five application targets is scrapable.
- `INFRA/.env.example:6-13`: `DB_USER=postgres`, `DB_PASS=password`, host ports 5433/5434/5435 — differ from compose's 54331/54341/54351 and from the service READMEs (5434, 5435). `INFRA/postgres/init-db.sql` is empty (0 bytes) and not mounted. `INFRA/README.md` is empty.
- `INFRA/Makefile:55-81` `test-api`: creates a user (POST `/api/v1/users` with `role: USER`), creates a media item (POST `/api/v1/media`), creates a subscription "via Feign validation" (POST `/api/v1/subscriptions`), fetches "Media Info via WebClient" (GET `/api/v1/subscriptions/media-info/{id}`), and reads `/actuator/gateway/routes`, all on `localhost:8080`.
- `INFRA/Insomnia_Mediahub_Collection.json` (requests at lines 56-280): POST/GET `/api/v1/users`, POST/GET `/api/v1/media`, POST `/api/v1/subscriptions`, GET `/api/v1/subscriptions/media-info/1`, GET `/actuator/gateway/routes`, GET Eureka at `:8761` — described as "testing the Mediahub Microservices stack through the API Gateway" (:14).

**CI** (`mediahub-media-service/.github/workflows/ci-cd.yml`, identical in user- and subscription-service except the name on line 1): `mvn clean verify` on JDK 17 (:18-33); on push to `main`/`develop`, build and push the image to `ghcr.io/${{ github.repository }}` with tags `latest`, `dev`, `sha-…` (:42-74). Verification: the `docker-publish` job only checks out and runs `docker/build-push-action` with `context: .`; it never builds the jar, and `target/` is git-ignored, so the Dockerfile's `COPY target/app.jar` (`Dockerfile:3`) has nothing to copy and the push would fail (as read; not run). Gateway, config server and discovery server have no workflow. Compose builds from source and uses none of the GHCR images.

**Committed runtime artefacts.** `GW/app.log`, `CS/config.log`, `DS/eureka.log`, `mediahub-media-service/app.log`, `mediahub-subscription-service/app.log` are tracked; `mediahub-user-service/.env` is tracked but empty (blob `e69de29`).
