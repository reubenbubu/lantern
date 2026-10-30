# project-3 reference: notes (first pass, corrected by the second pass)

> **In the repository.** Rebuild and check from this folder (`<spec>` is `../../../report-specs/c4-model-sequence-diagrams`):
>
> - `node build-data.mjs reference.c4.json` writes the data from the facts.
> - `node ../../../lantern-web/scripts/report-spec-check.mjs c4-model-sequence-diagrams reference.c4.json` checks schema and cross-references (`lantern-web`'s tests also run it).
> - `node ../../reference-tools/embed.cjs <spec>/c4-model-sequence-diagrams.html reference.c4.json reference.c4.html` embeds the data into the template; `../../reference-tools/render.mjs` renders it in headless Chrome. The HTML is not committed.
>
> **Citations.** The item-level `file:line` citations are in `facts/*.md`; the JSON carries `repo` and `commit` for every element and relationship it sources. Together they are the reading of "every item cited". Pins: `../pins.txt`.

The independent second pass is in `verification.md`; its corrections are applied to `build-data.mjs`, `reference.c4.json` and the fact files, and the counts below are updated.

## What it describes

MediaHub, nine repositories under `~/workspace/lantern-test-projects/project-3/`, read in place at these commits (checkouts not changed, all clean):

| Repository | Commit | Date |
|---|---|---|
| mediahub-api-gateway | `3ad3447569811e123d06685e2f99e7d5724a3241` | 2026-03-15 |
| mediahub-config-repo | `a57c8a6f762fc57eb74d41b373fe06ced740ba7d` | 2026-03-15 |
| mediahub-config-server | `7def2dadcb3c19c88a50f0d5d4e0bb91f9b918b0` | 2026-03-15 |
| mediahub-discovery-server | `4363aa9f0daefa956e47210da226a9485fe6c03b` | 2026-03-15 |
| mediahub-frontend-ui | `acefeda6b3e99dc4667be91e0ea620c65249ca92` | 2026-03-24 |
| mediahub-infra | `aef86fb6c1988cdef3512cdfaf2705a7e5209a65` | 2026-03-15 |
| mediahub-media-service | `fab6a4cb1240653196da2a62afc847c0edf95cb0` | 2026-03-15 |
| mediahub-subscription-service | `8a16d856c6ccee5908401ffae58334b3b5db7dfd` | 2026-03-15 |
| mediahub-user-service | `c5698fa7e4e333ff11c00fe2bafb51568550acf1` | 2026-03-15 |

- Written by hand on 2026-10-29 from tracked files only (code, config, the config repository, compose, Makefile, Insomnia collection, CI, READMEs). Lantern was not run and no Lantern output was read. Nothing was built or run, so behaviour is as read, not as observed; the committed run logs (`app.log`, `config.log`) were used only to confirm config fetching works and they predate HEAD.
- Spec: `report-specs/c4-model-sequence-diagrams/` from the lantern repo at `origin/main` `6867320` (spec files last changed in `65f6030`, the same version the project-4 reference uses).

## Files

- `reference.c4.json`: the report data. Validator: `schema: valid`, `cross-reference issues: none` (8 diagrams, all ≤ 11 elements; 7 sequences, frames balanced; every element appears in a diagram or sequence).
- `facts/edge-config-deployment.md` (gateway, config server, config repo, discovery server, compose, CI), `facts/services.md` (user-, media-, subscription-service), `facts/frontend-and-cross-checks.md` (web app, its calls checked against the backend, and the flow and exposure cross-checks): the cited file:line facts behind every item.
- `build-data.mjs`: writes the JSON from the facts.

## Contents

- Elements: 2 people (viewer, operator/API client), 1 system, 1 external system (Lorem Picsum), 7 containers (mediahub-ui, api-gateway, discovery-server, config-server, user-, media-, subscription-service), 4 data stores (three PostgreSQL databases, the config repository), 0 queues, 24 components.
- Pages: context, 2 container pages (requests and data; configuration and discovery), 5 component pages (web app, gateway, three services), 7 sequence pages, and 4 documentation pages (decisions, risks and unknowns; deployment; glossary; sources, #249).
- Relationships: 3 context, 24 container (13 + 11), 45 component; sequences have 83 steps in all.
- 10 observed decisions, 16 risks (3 High, 7 Medium, 6 Low), 6 unknowns.
- Sequences, chosen by entry point and importance: service start-up (config fetch and Eureka registration, the system's backbone), sign-in attempt from the web app, browse the catalogue, create a subscription (the main business write, used by the Makefile and Insomnia), check for an active subscription (the README's "crucial" call), record a watch, media details through subscription-service (the WebClient call the Makefile tests).

## Scope choices

- **Config repository as a data store**, not an external system: it is one of the system's own repositories and the config server only reads it. GitHub, where it is cloned from in the default `git` profile, is mentioned in its description and in the left-out callout instead of being modelled separately. A reader who models GitHub as an external system is also defensible.
- **Config server and Eureka as containers** (tag Infrastructure), as project-4 does with Eureka.
- **Web app as a container** although nothing deploys it: it is a runtime unit of the system, run with `ng serve`.
- **Lorem Picsum as an external system**: the browser loads every thumbnail from it at runtime. It is minor; a scorer should not penalise its absence much.
- **Operator as a person**: the Makefile `test-api` and the Insomnia collection show people calling the gateway API directly for user, media and subscription management; no admin UI exists. The ADMIN role exists but is never checked.
- **No queues**: no broker or messaging exists.
- **Left out:** Prometheus and Grafana (config files only, not in compose), GitHub Actions and GHCR (build and publish only, images unused), the empty `postgres/init-db.sql`.
- The gateway's components are configuration, not classes (its only class is the application class); they are modelled anyway because the route table, the discovery locator and the actuator carry the main risks.

## Uncertain

- **Discovery locator exposing config-server** (High): follows from `discovery.locator.enabled: true`, the config server registering in Eureka, and no security anywhere; the exact URL `/CONFIG-SERVER/user-service/default` and the unresolved `${DB_PASS:password}` in the answer were not run. Whether the config server's Eureka registration succeeds in compose also depends on `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` overriding its `service-url.default-zone` (`EUREKA_URL`) setting; the default `localhost:8761` would not reach the registry from inside its container. If it does not register, the locator does not expose it, but its port 8888 is published on the host anyway.
- **Gateway actuator route changes** ("probably change the gateway's routes"): Spring Cloud Gateway's actuator write operations are framework behaviour for 2024.0.0 with `management.endpoint.gateway.enabled: true`; not run.
- **Two ACTIVE subscriptions → 500**: Spring Data's `Optional` single-result query throwing `IncorrectResultSizeDataAccessException` is framework behaviour; not run.
- **Media-info 404 → 500** and **non-numeric path id → 500**: follow from `retrieve()` / type-mismatch exceptions reaching the `@ExceptionHandler(Exception.class)` catch-all; not run.
- Which configuration wins in the gateway (its own `application.yaml` exposing all actuator endpoints vs `api-gateway.yml` narrowing them): remote properties normally take precedence, so the narrower list is assumed when the config server is reachable; both expose the gateway endpoint, so the risk holds either way.
- Checked and dropped: I first suspected media- and subscription-service would fetch the wrong config because `spring.application.name` is in `application.yaml` rather than `bootstrap.yaml`; their committed logs show `Located environment: name=media-service, profiles=[dev]` (`app.log:39`), so it works and is not a risk.
- Not read in detail: Angular page templates beyond the lines cited, `package-lock.json`, Grafana dashboard JSON, the Insomnia collection beyond its URLs and methods, the run logs beyond the lines cited.
