# Scorecard: project-8

- Report: `workspace/scores/project-8-baseline/project-8/_system.c4.html` (project-8)
- Reference: `~/workspace/lantern-test-projects/project-8/expected/reference/reference.c4.json`
- Run: 2 LLM calls, 2535 tokens, $0
  - SUMMARIZE: fake/fake-model (2 calls, $0)
  - RESOLVE: fake/fake-model (0 calls, $0)
  - INFER_INTENT: fake/fake-model (0 calls, $0)
  - NAME_GROUPS: fake/fake-model (0 calls, $0)
  - PROPOSE_RULES: fake/fake-model (0 calls, $0)
  - COMPOSE: fake/fake-model (0 calls, $0)
  - TRANSLATE: fake/fake-model (0 calls, $0)

## Counts

| What | Recall | Precision | Precision, part connected to the reference |
| --- | --- | --- | --- |
| Containers | 10/15 (67%) | 10/13 (77%) | 10/12 (83%) |
| Data stores | 3/9 (33%) | 3/6 (50%) | same |
| Queues and exchanges | 13/24 (54%) | 13/13 (100%) | same |
| External systems | 0/9 (0%) | 0/6 (0%) | same |
| People | 4/5 (80%) | 4/6 (67%) | 4/4 (100%) |
| Container connections | 26/75 (35%) | 26/54 (48%) | same |
| Flows | 2/9 (22%) | 2/7 (29%) | same |
| Risks | 0/22 (0%) | 0/0 | same |
| Decisions | 3/6 (50%) | 3/5 (60%) | same |
| Glossary terms | 5/14 (36%) | 5/18 (28%) | same |

Connections by protocol (matched/reference, report): person 0/1, 0; HTTP 7/14, 20; other 0/8, 0; data store 6/12, 9; messaging 13/39, 25; email 0/1, 0.
Components (report/reference): 119/40; Staff web app 0/3, Customer web app 0/2, Edge gateway 4/1, Customer portal BFF 3/2, Platform discovery 0/1, Platform config 0/1, Work order service 47/19, Technician service 10/1, Parts inventory 6/2, Telematics ingest 5/1, Billing service 32/4, Notification service 12/2, Fleet audit 0/1, Keycloak 0/0, PostgreSQL server 0/0.

## Flows found

- Telematics alert creates predictive work = {{placeholder: scenario title}} (POST /api/telemetry on telematics-ingest): trigger `POST /api/telemetry`, participants 5/15 (missing Vehicle telematics device, MongoDB, workorder, fleet.workorder.events, notification.workorder.created, Notification service, SMTP relay, Edge gateway, Staff web app, workorder.read-model)
- Notify staff about a declined payment = {{placeholder: scenario title}} (POST /api/payments on billing-service): trigger `POST /api/payments`, participants 2/12 (missing Fleet customer, PayGrid, billing, workorder.payment.audit, notification.payment.events, Notification service, notification.failed, fleet.dead.letters, Edge gateway, Staff web app)

## Missing (in the reference, not in the report)

- Containers: Staff web app; Customer web app; Fleet audit; Keycloak; PostgreSQL server
- Data stores: technician; parts; keycloak; MongoDB; Elasticsearch; MinIO object store
- Queues and exchanges: notification.workorder.created; notification.workorder.completed; notification.workorder.cancelled; notification.maintenance.reminders; notification.payment.events; notification.payment.declined; notification.failed; workorder.payment.audit; notification.commands; notification.broadcast.all; notification.priority
- External systems: Vehicle telematics device; PayGrid; PartsHub; Legacy parts endpoint; Accounting SFTP; Accounting partner status API; TextNow; SMTP relay; Fleet registry API
- People: Fleet coordinator
- Container connections: Fleet coordinator -> Customer portal BFF; Staff web app -> Edge gateway; Staff web app -> Keycloak; Customer web app -> Customer portal BFF; Customer web app -> Keycloak; Edge gateway -> Keycloak; Customer portal BFF -> Keycloak; Work order service -> Keycloak; Work order service -> fleet.workorder.events; Technician service -> technician; Parts inventory -> parts; Parts inventory -> Elasticsearch; Parts inventory -> PartsHub; Parts inventory -> Legacy parts endpoint; Notification service -> fleet.workorder.events; Telematics ingest -> MongoDB; Telematics ingest -> MinIO object store; fleet.workorder.events -> notification.payment.events; fleet.workorder.events -> workorder.payment.audit; notification.payment.events -> Notification service; Billing service -> workorder; Billing service -> PayGrid; billing.dead.letters -> notification.payment.declined; notification.payment.declined -> Notification service; Billing service -> Keycloak; Notification service -> billing.dead.letters; fleet.workorder.events -> notification.workorder.created; fleet.workorder.events -> notification.workorder.completed; fleet.workorder.events -> notification.workorder.cancelled; fleet.workorder.events -> notification.maintenance.reminders; notification.maintenance.reminders -> Notification service; notification.payment.events -> fleet.dead.letters; notification.workorder.created -> Notification service; notification.workorder.completed -> Notification service; notification.workorder.cancelled -> Notification service; Notification service -> fleet.dead.letters; fleet.dead.letters -> notification.failed; notification.failed -> Notification service; Notification service -> SMTP relay; Notification service -> TextNow; notification.commands -> notification.commands; notification.commands -> Notification service; notification.broadcast -> notification.broadcast.all; notification.broadcast.all -> Notification service; notification.headers -> notification.priority; notification.priority -> Notification service; Billing service -> Fleet registry API; Billing service -> Accounting SFTP; Billing service -> Accounting partner status API
- Flows: Book a repair (POST /graphql); Reserve a part for a work order (POST /api/work-orders/{}/parts); Issue an invoice and submit payment (POST /api/invoices); Run accounting reconciliation (POST /api/reconciliation/runs); Handle an unavailable payment provider (POST /api/payments); Staff user signs in (GET /realms/fleetcare/protocol/openid-connect/auth); Subscribe to staff updates (stomp)
- Risks: Shared JWT signing secret; Technician lookup path mismatch; BFF availability parameter mismatch; Shift update method mismatch; Payment publication and database commit; Role substring matching; Forwarded identity trust; Billing dependency and image lifecycle; Shared-library version skew; Unmatched staff summary route; Potential duplicate vehicle-history route mapping; Compose credentials; Stateful replica count; Reconciliation work-order window; Billing reads work-order database; Payment audit listener; Read-model payload mismatch; Customer booking GraphQL field mismatch; Staff WebSocket authentication; Customer booking authentication; Test coverage; Production logging
- Decisions: Use Kafka for vehicle telemetry streams; Use JobRunr for SLA checks; Temporarily share work-order data for reconciliation
- Glossary terms: Work order (WO); VIN; DTC; RMA; ETA; BFF; JWT; gRPC; SFTP

## Extra (in the report, not in the reference)

- Containers: fleetcare-customer; fleetcare-staff; commons-fleet-audit
- Data stores: workorder (billing.datasource.workorders.url); parts on localhost; technician on localhost
- Queues and exchanges: none
- External systems: accounts.partner.example; api.textnow.example; Keycloak; registry.fleetcare.example; sandbox.partshub.example; sandbox.paygrid.example
- People: Fleet member; Telemetry device
- Container connections: fleetcare-customer -> customer-portal-bff; fleetcare-customer -> edge-gateway; fleetcare-staff -> edge-gateway; edge-gateway -> Keycloak; customer-portal-bff -> Keycloak; notification-service -> api.textnow.example; workorder-service -> Keycloak; parts-inventory -> sandbox.partshub.example; billing-service -> accounts.partner.example; billing-service -> registry.fleetcare.example; billing-service -> sandbox.paygrid.example; billing-service -> Keycloak; telematics-ingest -> Keycloak; billing-service -> workorder (billing.datasource.workorders.url); parts-inventory -> parts on localhost; technician-service -> technician on localhost; billing.dead.letters -> notification-service; fleet.dead.letters -> notification-service; fleet.workorder.events -> notification-service; notification.broadcast -> notification-service; notification.commands -> notification-service; notification.headers -> notification-service; fleet.workorder.events -> workorder-service; fleet.workorder.events -> workorder.read-model; workorder.read-model -> workorder-service; workorder-service -> workorder.read-model; vehicle.alerts -> telematics-ingest; vehicle.telemetry.raw -> telematics-ingest
- Flows: {{placeholder: scenario title}} (WebSocket connection to /ws on notification-service); {{placeholder: scenario title}} (GET /api/fleet/technicians/{id}/overview on edge-gateway); {{placeholder: scenario title}} (POST /api/notifications/broadcast on notification-service); {{placeholder: scenario title}} (POST /api/notifications/email on notification-service); {{placeholder: scenario title}} (POST /api/notifications/alerts/priority on notification-service)
- Risks: none
- Decisions: Use Kafka for telemetry ingestion; Use JobRunr for SLA breach checks
- Glossary terms: ADR; AOP; API; HTTP; HTTPS; ID; JDBC; JPA; JSON; MVC; RESP; REST; TLS

Not connected to anything the reference has (counted as extra; left out of the connected part's precision): Fleet member; Telemetry device; commons-fleet-audit.

## Quality-bar checks

- FAIL: Every diagram has at most 15 elements (notification-service / Components of notification-service (20 elements); workorder-service / Components of workorder-service (57 elements); billing-service / Components of billing-service (41 elements))
- FAIL: Every element shown has a description (accounts.partner.example; api.textnow.example; registry.fleetcare.example; sandbox.partshub.example; sandbox.paygrid.example and 20 more)
- pass: Every container and component shown has a technology
- FAIL: Every line has an intent phrase, not just "uses" (System context / System context of project-8: Billing clerk -> project-8; System context / System context of project-8: Dispatcher -> project-8; System context / System context of project-8: Fleet customer -> project-8; System context / System context of project-8: Fleet member -> project-8; System context / System context of project-8: Technician -> project-8 and 1 more)
- pass: Every line between containers names a protocol
- pass: No diagram repeated across sections
- pass: The document pins its commits and date
- pass: The main flows are sequence diagrams
- pass: Observed decisions are stated
- FAIL: Risks are stated
- pass: Acronyms are explained in a glossary

The report lists 22 quality gaps of its own. Checks the reference itself fails: none.
