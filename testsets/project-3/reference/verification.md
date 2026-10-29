# project-3 reference: verification (second pass)

Independent second pass over the first-pass reference (`notes.md`, `facts/*.md`, `scripts/build-data.mjs`), done on 2026-10-29 by reading the code only. Lantern was not run and none of its output was read. Nothing was built or run.

## Commits

All nine checkouts are clean and at the commits `notes.md` lists: api-gateway `3ad3447`, config-repo `a57c8a6`, config-server `7def2da`, discovery-server `4363aa9`, frontend-ui `acefeda`, infra `aef86fb`, media-service `fab6a4c`, subscription-service `8a16d85`, user-service `c5698fa`. The checkouts were not changed.

## What was checked

- **Every cited file:line.** That is all citations in the three fact files and all evidence in the reference's risks and decisions, about 300 locations (some are line lists such as `:3,16,24,36`). For each one I re-read the lines and checked the claim and the items that cite it. I read whole files rather than samples: every tracked source, YAML, pom, Dockerfile and README in the eight backend and infra repositories; the web app's routes, guard, interceptor, `AuthService`, the three API service classes, the models, `proxy.conf.json` and `angular.json`, plus the cited lines of each feature component; compose, the Makefile, both `.env.example` files, `prometheus.yml`, the Grafana provisioning files, the CI workflow (and a diff of all three copies), and the Insomnia request list. I also read the committed logs at the cited lines.
- **Sampling for anything missed.** I looked at entry points (each `@SpringBootApplication`, each controller, each `@FeignClient` and the WebClient), the config repository's per-service files against each service's `application.yaml` or `bootstrap.yaml`, the gateway routes and discovery locator, deployment (compose ports, environment, volumes, health checks, Makefile, Dockerfiles, CI), monitoring, and the web app's calls against the backend. Nothing in any repository uses RestTemplate, messaging, nginx or k8s.
- **Risk ratings.** I re-derived all 16 ratings from impact and likelihood as the code shows them.

## Corrections (before → after)

1. **The wrong-path Feign call returns 500, not 404.** media-service has no handler for `/api/media/{id}`. Spring MVC raises `NoResourceFoundException` for that path, and media-service's `@ExceptionHandler(Exception.class)` (`mediahub-media-service/.../exception/GlobalExceptionHandler.java:50-56`) turns it into 500. The outcome for user-service is the same, since every Feign error becomes a `RuntimeException` and then 500 (`WatchHistoryServiceImpl.java:22-29`). I fixed the wording in the us-media-client description, in the container relationship user-service → media-service ("wrong path: 404" → "wrong path: always fails"), in the user-service and media-service component relationships, in step 4 of `seq-register-watch` ("404: no handler…" → "500: no handler…; the NoResourceFoundException reaches the catch-all handler"), and in `facts/services.md` and `facts/frontend-and-cross-checks.md`. This is Spring Framework 6.1+ behaviour, read and not run.
2. **The Feign wrong-path risk is now Medium, not High.** Its consequence is real and certain: watch history can never be written. But no client calls the endpoint. The web app reads history from a different, also broken path; the Makefile and Insomnia never call it; and it is reachable through the gateway only through the discovery locator. A broken feature that nothing uses does not rank with open admin creation or config exposure. The risks are now **3 High, 7 Medium, 6 Low**.
3. **CI does not publish usable GHCR images.** The first pass said three services "publish images to GHCR". The `docker-publish` job (`ci-cd.yml:42-74`) checks out the code and runs `docker/build-push-action` with `context: .`, but never builds the jar. `target/` is git-ignored, and `Dockerfile:3` does `COPY target/app.jar`, so the build would fail. I changed the glossary entry, the left-out callout, the "Docker Compose as the only deployment" decision, the deployments table row, the GHCR unknown and the Low compose risk (evidence `ci-cd.yml:42-74`, `Dockerfile:3` added) to say CI *tries to* push. This is as read; whether the images exist on GHCR was not checked.
4. **Monitoring risk: none of the five application targets can be scraped, not two.** In compose, user-, media- and subscription-service publish no ports (`docker-compose.yml:103-158`), so `prometheus.yml`'s `host.docker.internal:8081-8083` targets fail too. I updated the Low monitoring risk and its evidence.
5. **Config exposure risk (High): the config server's port 8888 is published as well** (`docker-compose.yml:18-19`). So the configuration is readable even if the discovery-locator route does not exist (see judgement call 2). I added this to the finding and the evidence.
6. **Error-handling risk (Low):** besides a malformed id, an unknown path also becomes 500 in every service, because all three catch `Exception`. I added the media-service handler `GlobalExceptionHandler.java:50-56` to the evidence.
7. Fact-file citations fixed (they did not reach the report): `app.log:88` → `:98` (Eureka registration of media- and subscription-service); `config.log` lines 106-109 → 106-117; the web app README for `ng serve` and port 4200 is `README.md:10-13`, not `:47-55` (that is the e2e section).

## Additions

- **Relationship operator → discovery-server, "Views the Eureka dashboard" (HTTP :8761)**, on the Configuration and discovery container page. The operator's description already said so, the Insomnia collection has the request, and `docker-compose.yml:5-6` publishes the port. The container relationships go from 23 to 24.
- In the facts: `DS/eureka.log:108` shows `CONFIG-SERVER` registering in the developers' local run, which supports the config server being in the registry.

## Confirmed

Apart from the corrections above, everything checks out against the code:

- **Elements.** The 2 people, the system, Lorem Picsum, the 7 containers with their technology, stack and ports, the 4 data stores, and all 24 components with their classes.
- **Relationships.** All context, container and component relationships, with their protocols.
- **Gateway.** Routes, filters (none), discovery locator, actuator exposure, and its Boot 3.4.3 / Spring Cloud 2024.0.0 against Boot 4.0.3 / 2025.1.0 everywhere else.
- **Config server and config repository.** Git default profile, native profile in compose, PAT credentials, `encrypt.key` unused. The file contents and the missing `*-dev.yml` files.
- **Services.** Endpoints, status codes, the `ApiResponse` envelope, BCrypt, role taken from the request body, the unused `MediaClient`, the load-balanced WebClient, `Optional` in the active check, the entities and enums.
- **Web app.** All its calls and what the backend does with them, the hard-coded ULTRA plan and the picsum thumbnails.
- **Compose.** Ports, credentials, health checks, build contexts that are not in the repository. The Makefile and Insomnia flows.
- **Sequences.** All seven, step by step, apart from correction 1.
- **Decisions.** All 10, with their quoted README lines (`media-service/README.md:9`, `subscription-service/README.md:29`, `user-service/README.md:7-8`).
- **Unknowns.** All 6 (one reworded by correction 3).
- **Ratings.** All other 15 ratings stand:
  - The 3 High: no authentication at all, with ADMIN self-creation and deletes open to anyone; config and actuator exposure through the gateway; a web app that cannot sign in and whose account pages all fail.
  - The 6 other Medium and all 6 Low.

## Open judgement calls (variants a scorer should accept)

1. **Risk ratings.**
   - Watch-history Feign path: Medium or High.
   - Web-app/backend mismatch: High or Medium. It is a functional defect rather than a security one, but it makes the only UI unusable.
   - Config and actuator exposure: High, or Medium if a scorer folds it into "no authentication". It should not be lost as a separate finding.
   - Cross-service consistency: Medium or Low.
2. **Does the config server register in Eureka under compose?** Its own file sets `eureka.client.service-url.default-zone: ${EUREKA_URL:http://localhost:8761/eureka/}` (`mediahub-config-server/src/main/resources/application.yaml:24-27`). `default-zone` is a map key, and Spring Cloud Netflix looks the zone up as `defaultZone`, so this setting is probably never used. Registration then depends on compose's `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` (`docker-compose.yml:21`) being bound as the `defaultZone` key; otherwise the default `localhost:8761`, which is unreachable inside the container, is used. If it does not register, the `/CONFIG-SERVER/**` route does not exist, but the config remains readable on the published port 8888. A scorer should accept a reference that omits the gateway → config-server route, as long as config exposure is reported. Not run.
3. **Status codes, read and not run:**
   - Media-service's 500 for unknown paths (correction 1). A reference saying "404" there should not be penalised much: the consequence, that a watch is never recorded, is identical.
   - 500 for two ACTIVE rows.
   - 500 for a media-info 404.
4. **GitHub as an external system** for the config repository's git profile, and **the config repository as a data store or an external system**: both defensible (`notes.md`, Scope choices).
5. **Lorem Picsum** present or absent: minor.
6. **Extra flows.** "Create a user" (Makefile step 1, Insomnia) and "Create a media item" (Makefile step 2) are reasonable flows that the reference leaves out. A reference that has them instead of, say, the sign-in attempt or the active-subscription check should not lose much. The key flows to expect are start-up (config + Eureka), create subscription (Feign user check) and media info (WebClient).
7. **Web app as a container** although it is never deployed: expected; also acceptable as a note on the gateway's clients.

## Validation and rendering

- `node scripts/build-data.mjs reference.c4.json`, then `validate.cjs` with the spec schema: `schema: valid`, `cross-reference issues: none`. Every diagram has 11 elements or fewer (the platform page now has 8), and all 7 sequences have balanced frames.
- `embed.cjs` wrote `reference.c4.html` (300,047 bytes). `render.mjs` ran with `a temporary TMPDIR`, deleted afterwards: 15 pages visited, no console messages. I checked screenshots of the changed platform diagram and of the watch sequence.
- I removed `tmp/` (its schema and template were byte-identical to `report-specs/c4-model-sequence-diagrams/` at `65f6030`; the scripts take paths as arguments). The rebuild command in `notes.md` now points at the spec folder.
