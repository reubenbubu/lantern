# project-1 reference: notes

> **In the repository.** Rebuild and check from this folder (`<spec>` is `../../../report-specs/c4-model-sequence-diagrams`):
>
> - `node build-data.mjs reference.c4.json` writes the data from the facts.
> - `node ../../../lantern-web/scripts/report-spec-check.mjs c4-model-sequence-diagrams reference.c4.json` checks schema and cross-references (`lantern-web`'s tests also run it).
> - `node ../../reference-tools/embed.cjs <spec>/c4-model-sequence-diagrams.html reference.c4.json reference.c4.html` embeds the data into the template; `../../reference-tools/render.mjs` renders it in headless Chrome. The HTML is not committed.
>
> **Citations.** The item-level `file:line` citations are in `facts/*.md`; the JSON carries `repo` and `commit` for every element and relationship it sources. Together they are the reading of "every item cited". Pins: `../pins.txt`.

First pass, then an independent second pass (verification) on 2026-10-29; what the second pass checked and changed is in `verification.md`.

## What it describes

- Repository `Enterprise-Spring-Microservices-Template`, commit **`8f5cb6873691f8b67353c4924fb06f469c2ef4e4`** (`8f5cb68`, 2026-09-21, merge of `enhancement/spring-boot-4.1-upgrade`). The checkout was read in place and not changed. Untracked `target/` build output in the checkout was ignored; only tracked files were read.
- Written by hand on 2026-10-29 from the code, configuration, deployment files and the repository's own docs. Lantern's output for project-1 was not read or run.
- Spec: `report-specs/c4-model-sequence-diagrams/` in the lantern repo, read from `origin/main` at `5f56fee` (spec files last changed in `65f6030`).

## Files

- `reference.c4.json`: the report data. **Schema: valid**; cross-reference checks: none.
- `facts/edge-ui-bff-gateway.md`, `facts/services-and-shared.md`, `facts/identity-deployment-flows.md`: the cited `file:line` facts behind every item.
- `build-data.mjs`: writes the JSON; all statements come from the facts.

## Contents

- Elements: 3 people (web user, new visitor, administrator), 1 system, 1 external system (mobile app), 7 containers (angular-ui, bff, gateway, profile-service, order-service, keycloak-admin-service, Keycloak), 4 data stores (Redis, profile_db, order_db, Keycloak's database), 0 queues, 25 components.
- Pages: context (with decisions, risks and unknowns at its end, as in project-4, stopgap until #249), 2 container pages (requests and data; identity and tokens), 5 component pages, 6 sequence pages.
- Relationships: 5 context, 25 container (15 + 10), 50 component; sequences have 91 steps in all.
- 11 observed decisions, 15 risks (2 High, 5 Medium, 8 Low), 5 unknowns.

## Scope choices

- **Keycloak as a container, not an external system.** The repository runs it (compose) and owns its configuration (the realm export, imported on every start), like a database. A reader who models it as an external identity provider is also defensible; a scorer should accept either, as long as the relationships to it are there.
- **Mobile app as an external system.** The README and the realm's `mobile-client` describe it, but no code exists in the repository.
- **Administrator as a person with no UI.** The ADMIN role and the admin endpoints exist; no screen calls them.
- **No email system.** profile-service only logs confirmation links and Keycloak has no SMTP configured, so no email provider is modelled; both gaps are risks instead.
- **Left out:** Zipkin, Prometheus, Loki, Grafana (operations only), GitLab CI (build only), the libraries common-core/web/security/test (not runtime units; described in text). Spring Session's JSESSIONID store is folded into the Redis element.
- Sequences chosen by entry point and importance: web sign-in, an authenticated web API call (list orders, with the proactive refresh), self-registration and confirmation, administrator creates a user, single sign-out, and the mobile lane.

## Uncertain

- **"Self-registered users probably cannot set a password"** (High) rests on the realm export having no `smtpServer` and Keycloak then failing `execute-actions-email`; not run. A deployment that configures SMTP in the Keycloak admin console after import would avoid it.
- **Shared anonymous rate-limit bucket** (Medium) assumes the gateway sees the BFF's container address as the TCP peer for every public call; follows from the code (`TRUSTED_PROXY_HOPS=0`, no `X-Forwarded-For` forwarded) but was not run.
- **30-minute hard session** (Low): the BFF_SESSION JWT expiry is fixed at login; whether users notice depends on Keycloak's SSO cookie making the re-login silent.
- Spring Session's key layout in Redis was not checked; only the dependency and its comment (`bff/pom.xml:38-44`) were read.
- Not read in detail: `common-security/.../openapi/*`, Grafana dashboards, the test sources, the Playwright tests and `package-lock.json`.
- Resolved in the second pass: `GET /bff/login`'s redirect is absolute (Spring Boot's default `server.tomcat.use-relative-redirects=false`, Host pinned to `localhost:8081`), so the browser reaches the BFF's published port 8081 directly for the OAuth2 steps, as the realm's redirect URI requires. The web sign-in sequence and the angular-ui → bff edge now show it.
- Second pass, not run: the servlet session cookie is most likely named `SESSION` (Spring Session's default) rather than the repo's `JSESSIONID`; the reference keeps the repo's name and says so in the glossary.
