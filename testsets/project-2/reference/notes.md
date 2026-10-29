# project-2 reference: notes (first pass, amended by the second pass)

> **In the repository:** `build-data.mjs` sits beside this file (run it from here, not from `scripts/`). `embed.cjs` and `render.mjs` are shared and live in `testsets/reference-tools/`; `validate.cjs` is replaced by `lantern-web/scripts/report-spec-check.mjs` (`node lantern-web/scripts/report-spec-check.mjs c4-model-sequence-diagrams <this folder>/reference.c4.json`), which `lantern-web`'s tests also run. `reference.c4.html` is not committed: make it with `embed.cjs`. Pins are in `../pins.txt`.

The independent second pass is in `verification.md`: what it checked, each correction, additions
and the judgement calls a scorer should accept. The numbers below are after that pass (v1.1.0).

## What is described

- Repository: `Event-Management-platform` (the only repository in project-2), at
  `462516947458d4a161dfa0aef482865d9940e241` (`4625169`, "merge: Phase 6 Kafka notifications",
  2026-08-16). The checkout was read in place and not changed.
- Written by hand on 2026-10-29 from the code, configuration, compose file, Dockerfiles, Flyway
  migrations, ADRs and docs. Lantern was not run and no Lantern output was read. Nothing was
  built or run, so behaviour is as read, not as observed.
- Report spec: `report-specs/c4-model-sequence-diagrams/` from the lantern repo at `origin/main`
  `5f56fee` (schema and template).

## Files

- `reference.c4.json`: the report data. Validator result: `schema: valid`,
  `cross-reference issues: none` (11 diagrams, all ≤ 15 elements; 6 sequences, frames balanced;
  every element appears in a diagram or sequence).
- `reference.c4.html`: the template with the data embedded. Rendered in headless Chrome:
  16 pages visited, no console messages.
- `facts/*.md`: the cited file:line facts every statement rests on.
  - `edge-auth-deployment.md`: repository shape, shared libraries, frontend, api-gateway,
    auth-service, compose/Dockerfiles/observability/CI, the nine ADRs, stale docs.
  - `event-venue.md`: event-service and venue-service.
  - `attendee-payment-notification.md`: attendee-, payment-, notification-service, Kafka topics,
    cross-checked behaviours behind the main risks.
- `scripts/`: `build-data.mjs` (written for project-2), and `validate.cjs`, `embed.cjs`,
  `render.mjs` copied unchanged from the project-4 reference.

Rebuild (from this folder; `<spec>` = `report-specs/c4-model-sequence-diagrams` in the lantern repo):

```
node scripts/build-data.mjs reference.c4.json
node scripts/validate.cjs <spec>/c4-model-sequence-diagrams.schema.json reference.c4.json
node scripts/embed.cjs <spec>/c4-model-sequence-diagrams.html reference.c4.json reference.c4.html
node scripts/render.mjs $PWD/reference.c4.html <outdir> '#/ctx-emp'
```

`render.mjs` must run with the default `TMPDIR`: with `TMPDIR` set to this deep scratchpad path,
Chrome cannot create its socket (path too long) and the script fails waiting for the DevTools port.

## Contents in numbers

4 people, 1 system, 1 external system, 8 containers, 7 data stores (6 PostgreSQL databases,
1 Redis), 3 Kafka topics, 34 components; 38 container-level connections with protocol
(16 request, 12 data/JWKS, 10 messaging), 127 diagram relationships in all; 6 sequence diagrams;
12 decisions (9 ADRs + 3 observed); 18 risks (2 High, 5 Medium, 11 Low); 5 unknowns.
Decisions, risks and unknowns sit at the end of the context page (stopgap until #249).

## Scope choices

- **frontend is a container** although it only serves a static landing page and makes no API
  calls (`frontend/src/App.tsx`). People are therefore drawn calling api-gateway directly.
- **External systems: only the optional OAuth2 identity provider**, which is real code
  (`oauth2Login`) but off by default and unconfigured. Payment, email, SMS and maps providers are
  ports with only local implementations (in-process fake payments; email/SMS stored in
  `local_deliveries`; no-op geocoding), so they are not external systems; the context page says
  so in a callout.
- **Kafka is modelled as three topic elements** (kind `queue`), one per producing service, not as
  one broker container; the `.dlt` topics are mentioned but not drawn (nothing consumes them).
- **Six databases as six data stores** even though compose runs them in one PostgreSQL container
  (ADR-0002); the single instance is a risk row and in the deployment table.
- Observability stack (Prometheus, Grafana, Loki, Jaeger, OTel collector) and GitHub Actions are
  left out of the diagrams, as in project-4.
- JWKS fetches from every service to auth-service are drawn once, on the data-stores diagram, to
  keep the request diagram readable.
- Sequences chosen by entry point and business weight: sign-in, publish event, book and pay
  (with the compensation branch), check-in, refund, cancel event.

## Uncertain or judgment calls (for the verifier)

- Risk 1 (High), direct inventory calls: the README lists inventory reserve/release as
  authenticated APIs (`README.md:159`), so exposure may be intended; the High rating rests on
  `confirm` letting an attendee permanently take seats without paying
  (`InventoryService.java:223-279`). Not exercised.
- Risk 2 (High), no refund on cancellation: the event catalogue describes the cancellation
  consumer as cancelling projections and alerting (`docs/architecture/kafka-event-catalog.md:35`),
  so it is as designed; the rating reflects the business impact.
- Redis-on-the-write-path risk: the evictions call `Cache.evict` directly, so no error handler applies
  (second pass); not run. The gateway half of this risk was wrong: the rate limiter fails open
  (see `verification.md`).
- Fake provider "marks failed after restart": reasoned from `verifyPayment` on an unknown id
  returning FAILED and the reconciliation job applying it; not run.
- payment-service classes are written one statement per line in very long lines; citations point
  at those lines (e.g. `RefundStateService.java:14`).
- Gateway filter order (edge filters before Spring Security) is from `getOrder()` values versus
  Spring Security's WebFilter order; plausible but not run. The order among the edge filters was
  corrected in the second pass.
