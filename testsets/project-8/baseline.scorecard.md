# Scorecard: project-8

- Report: `/home/reuben/workspace/lantern/.claude/worktrees/feature-891/workspace/scores/k891-r2/project-8/_system.c4.html` (project-8)
- Reference: `/tmp/claude-1000/-home-reuben-workspace-lantern--claude-worktrees-bridge-cse-017Z29iQpJ67CHFeDsbnrWSp/d09c26cf-21a9-568a-89eb-473a7b60105f/scratchpad/feature-891-project8-investigate-key/sets/project-8/expected/reference/reference.c4.json`
- Run: 509 LLM calls, 2743462 tokens, $0
  - SUMMARIZE: fake/fake-model (81 calls, $0)
  - SUMMARIZE_TYPES: fake/fake-model (213 calls, $0)
  - RESOLVE: fake/fake-model (4 calls, $0); 2 conversations, 4 turns (at most 2), tool calls: submit 2; ended: ANSWERED 2
  - INVESTIGATE: fake/fake-model (120 calls, $0); 120 conversations, 120 turns (at most 1), tool calls: none; ended: ANSWERED 120
  - INFER_INTENT: fake/fake-model (20 calls, $0)
  - DESCRIBE_ELEMENTS: fake/fake-model (7 calls, $0)
  - NAME_GROUPS: fake/fake-model (9 calls, $0)
  - PROPOSE_RULES: fake/fake-model (0 calls, $0)
  - FLOW_SIGNIFICANCE: fake/fake-model (10 calls, $0)
  - COMPOSE: fake/fake-model (20 calls, $0)
  - TRANSLATE: fake/fake-model (0 calls, $0)
  - JUDGE: fake/fake-model (0 calls, $0)
  - SCREEN: fake/fake-model (25 calls, $0)
  - WORDING_JUDGE: fake/fake-model (0 calls, $0)
- Support judge: describe-element 3 supported, 0 partly, 0 unsupported, 0 asked again, 0 in plain wording; describe-unit 20 supported, 0 partly, 0 unsupported, 0 asked again, 0 in plain wording; flow-narrative 2 supported, 0 partly, 0 unsupported, 0 asked again, 0 in plain wording; name-groups 27 supported, 0 partly, 0 unsupported, 0 asked again, 0 in plain wording; summarize 10 supported, 0 partly, 0 unsupported, 0 asked again, 0 in plain wording; summarize-type 26 supported, 0 partly, 0 unsupported, 0 asked again, 0 in plain wording

## Counts

| What | Recall | Precision | Precision, part connected to the reference |
| --- | --- | --- | --- |
| Containers | 10/16 (63%) | 10/14 (71%) | 10/13 (77%) |
| Data stores | 3/10 (30%) | 3/7 (43%) | same |
| Queues and exchanges | 15/28 (54%) | 15/15 (100%) | same |
| External systems | 0/9 (0%) | 0/6 (0%) | same |
| People | 4/5 (80%) | 4/7 (57%) | 4/4 (100%) |
| Container connections | 28/84 (33%) | 28/59 (47%) | same |
| Flows | 2/9 (22%) | 2/7 (29%) | same |
| Risks | 0/36 (0%) | 0/0 | same |
| Decisions | 3/6 (50%) | 3/5 (60%) | same |
| Glossary terms | 5/14 (36%) | 5/19 (26%) | same |

Connections by protocol (matched/reference, report): person 0/1, 0; HTTP 7/15, 21; other 0/8, 0; data store 6/13, 10; messaging 15/46, 28; email 0/1, 0.
Components (report/reference): 114/117; Staff web app 0/3, Customer web app 0/2, Yard board web app 0/0, Edge gateway 4/3, Customer portal BFF 3/3, Platform discovery 0/0, Platform config 0/0, Work order service 50/48, Technician service 8/10, Parts inventory 7/7, Telematics ingest 5/7, Billing service 26/26, Notification service 11/8, Fleet audit 0/0, Keycloak 0/0, PostgreSQL server 0/0.

## Rows per role

| Role | Result |
| --- | --- |
| Resolve | 3 links: coverage 0%, accuracy -, penalized 33% (p = 3), over-abstention 100%; 0 not asked |
| Describe | elements 170/174 (98%); model: 349/349 interactions a view can draw with their own intent (100%), 0 generic, 0 don't know, 0 not asked; penalized 100% (p = 1); drawn: 307/319 lines with their own intent (96%), 6 generic (0 of them on a line whose link has a stored intent); steps 23 drawn, 23 intended, 23 own, 0 fallback; judge not measured (the run did not judge wording (score-testsets.sh --judge-wording)) |
| Flows | 0/9 recall (0%), 0/7 precision (0%) |
| Flow selection | rank fallback: 3/3 reference flows chosen (100%), 2/7 chosen flows in the reference (29%), penalized 100% (p = 1); 52 candidates, 6 reference flows not among them; 0 overlapping drawn flows |
| Investigate | 0/36 recall (0%), 0/0 drawn risks right (precision -), 0 of 0 drawn unjudged; decoys flagged 0/12, TPR − FPR 0% |
| Repeated risks | none |
| Fixed checks worded | 0/0 right (precision -), 0 of 0 unjudged; 0/36 reference risks |
| Investigate connections | 0/0 right (precision -), 0 of 0 unjudged; 0/82 reference connections found |
| Name groups | 18/18 named (100%); judge not measured (the run did not judge wording (score-testsets.sh --judge-wording)) |
| Purpose | key fact judge not measured (the run did not judge wording (score-testsets.sh --judge-wording)) |
| Harness | $0, 509 calls, tokens in 918443 / out 29593 / cache read 1259536 / cache write 533857; cache hit rate 0% of 784 items; warm not measured (no warm rerun given (--warm <run.json>)); one-file change not measured (no one-file change run given (--change <run.json>)) |

Describe's model coverage by kind of interaction: of the interactions a view can draw, how many have no intent of their own (generic, don't know, not asked), most first.

| Kind | Interactions | Own intent | Uncovered | Generic | Don't know | Not asked |
| --- | --- | --- | --- | --- | --- | --- |
| CALLS_HTTP | 35 | 35 | 0 | 0 | 0 | 0 |
| COMPONENT_LINK | 189 | 189 | 0 | 0 | 0 | 0 |
| CONSUMES | 18 | 18 | 0 | 0 | 0 | 0 |
| data-access | 51 | 51 | 0 | 0 | 0 | 0 |
| PUBLISHES | 16 | 16 | 0 | 0 | 0 | 0 |
| ROUTES_TO | 1 | 1 | 0 | 0 | 0 | 0 |
| SENDS_MESSAGES_TO | 12 | 12 | 0 | 0 | 0 | 0 |
| stomp-send | 12 | 12 | 0 | 0 | 0 | 0 |
| USES_DATABASE | 9 | 9 | 0 | 0 | 0 | 0 |
| USES_IDENTITY_PROVIDER | 6 | 6 | 0 | 0 | 0 | 0 |

Answer key: `/home/reuben/workspace/lantern/.claude/worktrees/feature-891/testsets/project-8/answer-key.json` (prepared from the code and the references; see its header).

## Flows found

- Telematics alert creates predictive work = {{placeholder: scenario title}} (telematics-ingest): trigger `POST /api/telemetry`, participants 5/15 (missing Vehicle telematics device, MongoDB, workorder, fleet.workorder.events, notification.workorder.created, Notification service, SMTP relay, Edge gateway, Staff web app, workorder.read-model)
- Notify staff about a declined payment = {{placeholder: scenario title}} (billing-service): trigger `POST /api/payments`, participants 2/12 (missing Fleet customer, PayGrid, billing, workorder.payment.audit, notification.payment.events, Notification service, notification.failed, fleet.dead.letters, Edge gateway, Staff web app)

## Missing (in the reference, not in the report)

- Containers: Staff web app; Customer web app; Yard board web app; Fleet audit; Keycloak; PostgreSQL server
- Data stores: technician; parts; keycloak; notification; MongoDB; Elasticsearch; MinIO object store
- Queues and exchanges: notification.workorder.created; notification.workorder.completed; notification.workorder.cancelled; notification.maintenance.reminders; notification.payment.events; notification.payment.declined; notification.failed; workorder.payment.audit; notification.commands; notification.broadcast.all; notification.priority; notification.vehicle.received; notification.vehicle.released
- External systems: Vehicle telematics device; PayGrid; PartsHub; Legacy parts endpoint; Accounting SFTP; Accounting partner status API; TextNow; SMTP relay; Fleet registry API
- People: Fleet coordinator
- Container connections: Fleet coordinator -> Customer portal BFF; Staff web app -> Edge gateway; Staff web app -> Keycloak; Customer web app -> Customer portal BFF; Customer web app -> Keycloak; Edge gateway -> Keycloak; Customer portal BFF -> Keycloak; Work order service -> Keycloak; Work order service -> fleet.workorder.events; Technician service -> technician; Parts inventory -> parts; Parts inventory -> Elasticsearch; Parts inventory -> PartsHub; Parts inventory -> Legacy parts endpoint; Notification service -> fleet.workorder.events; Telematics ingest -> MongoDB; Telematics ingest -> MinIO object store; fleet.workorder.events -> notification.payment.events; fleet.workorder.events -> workorder.payment.audit; notification.payment.events -> Notification service; Billing service -> workorder; Billing service -> PayGrid; billing.dead.letters -> notification.payment.declined; notification.payment.declined -> Notification service; Billing service -> Keycloak; Notification service -> billing.dead.letters; fleet.workorder.events -> notification.workorder.created; fleet.workorder.events -> notification.workorder.completed; fleet.workorder.events -> notification.workorder.cancelled; fleet.workorder.events -> notification.maintenance.reminders; notification.maintenance.reminders -> Notification service; notification.payment.events -> fleet.dead.letters; notification.workorder.created -> Notification service; notification.workorder.completed -> Notification service; notification.workorder.cancelled -> Notification service; Notification service -> fleet.dead.letters; fleet.dead.letters -> notification.failed; notification.failed -> Notification service; Notification service -> SMTP relay; Notification service -> TextNow; notification.commands -> notification.commands; notification.commands -> Notification service; notification.broadcast -> notification.broadcast.all; notification.broadcast.all -> Notification service; notification.headers -> notification.priority; notification.priority -> Notification service; Billing service -> Fleet registry API; Billing service -> Accounting SFTP; Billing service -> Accounting partner status API; Yard board web app -> Edge gateway; Notification service -> notification; fleet.workorder.custody -> notification.vehicle.received; fleet.workorder.custody -> notification.vehicle.released; notification.vehicle.received -> Notification service; notification.vehicle.released -> Notification service; workorder.board -> Work order service
- Flows: Book a repair (POST /graphql); Reserve a part for a work order (POST /api/work-orders/{}/parts); Issue an invoice and submit payment (POST /api/invoices); Run accounting reconciliation (POST /api/reconciliation/runs); Handle an unavailable payment provider (POST /api/payments); Staff user signs in (GET /realms/fleetcare/protocol/openid-connect/auth); Subscribe to staff updates (stomp)
- Risks: Shared JWT signing secret; Technician lookup path mismatch; BFF availability parameter mismatch; Shift update method mismatch; Payment publication and database commit; Role substring matching; Forwarded identity trust; Billing dependency and image lifecycle; Shared-library version skew; Unmatched staff summary route; Potential duplicate vehicle-history route mapping; Compose credentials; Stateful replica count; Reconciliation work-order window; Billing reads work-order database; Payment audit listener; Read-model payload mismatch; Customer booking GraphQL field mismatch; Staff WebSocket authentication; Customer booking authentication; Test coverage; Production logging; Notification role checks not enforced; Billing default database; Billing start-up settings; Portal GraphQL open to every origin; Staging drops the notification schema; Committed PayGrid signing key; Vehicle release message skew; Staff app API address baked in; Refund sent before it is recorded; Supplier order sent before commit; Notification STOMP broker per instance; Payment endpoint without role check; Diagnostic snapshot without role check; Maintenance booking without role check
- Decisions: Use Kafka for vehicle telemetry streams; Use JobRunr for SLA checks; Temporarily share work-order data for reconciliation
- Glossary terms: Work order (WO); VIN; DTC; RMA; ETA; BFF; JWT; gRPC; SFTP

## Extra (in the report, not in the reference)

- Containers: fleetcare-customer; fleetcare-staff; fleetcare-yard; commons-fleet-audit
- Data stores: workorder (billing.datasource.workorders.url); parts on localhost; technician on localhost; notification on postgres
- Queues and exchanges: none
- External systems: accounts.partner.example; api.textnow.example; Keycloak; registry.fleetcare.example; sandbox.partshub.example; sandbox.paygrid.example
- People: Subscribed browsers; Fleet member; Telemetry device
- Container connections: billing.dead.letters -> notification-service; fleet.dead.letters -> notification-service; fleet.workorder.custody -> notification-service; fleet.workorder.events -> notification-service; fleet.workorder.events -> workorder-service; fleet.workorder.events -> workorder.read-model; notification.broadcast -> notification-service; notification.commands -> notification-service; notification.headers -> notification-service; fleetcare-customer -> customer-portal-bff; fleetcare-customer -> edge-gateway; fleetcare-staff -> edge-gateway; fleetcare-yard -> edge-gateway; workorder.read-model -> workorder-service; billing-service -> accounts.partner.example; billing-service -> registry.fleetcare.example; billing-service -> sandbox.paygrid.example; billing-service -> workorder (billing.datasource.workorders.url); billing-service -> Keycloak; customer-portal-bff -> Keycloak; edge-gateway -> Keycloak; notification-service -> api.textnow.example; notification-service -> notification on postgres; parts-inventory -> sandbox.partshub.example; parts-inventory -> parts on localhost; technician-service -> technician on localhost; telematics-ingest -> Keycloak; workorder-service -> workorder.read-model; workorder-service -> Keycloak; vehicle.alerts -> telematics-ingest; vehicle.telemetry.raw -> telematics-ingest
- Flows: {{placeholder: scenario title}}; {{placeholder: scenario title}} (workorder-service); {{placeholder: scenario title}} (notification-service); {{placeholder: scenario title}} (notification-service) 2; {{placeholder: scenario title}} (edge-gateway)
- Risks: none
- Decisions: Use Kafka for telemetry ingestion; Use JobRunr for SLA breach checks
- Glossary terms: ADR; AOP; API; HTTP; HTTPS; ID; JDBC; JPA; JSON; LLM; MVC; RESP; REST; TLS

Not connected to anything the reference has (counted as extra; left out of the connected part's precision): Subscribed browsers; Fleet member; Telemetry device; commons-fleet-audit.

## Response contracts

Each size rule of `prompts/<role>.contract.json`, too short and too long apart. Of the texts stored: the share within the requested range; accepted outside it, short and long; and outside even the accepted range (only a rule that is measured, not enforced, keeps those). Then how many answers the checks rejected for their length, short and long, each as first answers and repairs (a repaired answer is also stored). Sentence rules are measured only.

| Role | Field | Unit | Requested | Accepted | Texts | Within requested | Accepted short | Accepted long | Outside accepted (short, long) | Rejected short (first, repair) | Rejected long (first, repair) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| describe-elements | responsibility | words | 6 to 22 | 5 to 25 | 34 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| describe-elements | responsibility | sentences | 1 to 1 | 1 to 2 (measured) | 34 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| describe-units | responsibility | words | 10 to 22 | 8 to 25 | 126 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| describe-units | responsibility | sentences | 1 to 1 | 1 to 2 (measured) | 126 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| describe-units | intent | words | 4 to 7 | 3 to 8 | 293 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| flow-narrative | title | words | 3 to 5 | 3 to 8 | 19 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| name-groups | name | words | 1 to 3 | 1 to 4 | 18 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| name-groups | responsibility | words | 13 to 22 | 11 to 25 | 18 | 0% | 100% | 0% | 0, 0 | 0, 0 | 0, 0 |
| name-groups | intent | words | 5 to 7 | 4 to 8 | 17 | 100% | 0% | 0% | 0, 0 | 0, 0 | 0, 0 |
| summarize | sentence | words | 10 to 30 | 8 to 35 | 358 | 58% | 42% | 0% | 0, 0 | 0, 0 | 0, 0 |

No texts this run: describe-elements intent (words), flow-narrative walkthrough (words), flow-narrative walkthrough (sentences), flow-narrative preconditions (words), flow-narrative postconditions (words), flow-significance reason (words), injection-screen quote (words), investigate title (words), investigate finding (words), investigate finding (sentences), investigate consequence (words), investigate consequence (sentences), investigate description (words), name-groups mergeReason (words), purpose purpose (words), purpose purpose (sentences), purpose keyFact (words), purpose keyFact (sentences), resolve reason (words), wording-judge judgedText (words), wording-judge reasoning (sentences).

### Quality score

Not measured: the run was not judged by the wording judge (`--judge-wording`).

## Unknown caller lines

- Unknown caller to billing-service: {{placeholder: intent of this interaction}}
- Unknown caller to notification-service: {{placeholder: intent of this interaction}}
- Unknown caller to parts-inventory: {{placeholder: intent of this interaction}}
- Unknown caller to workorder-service: {{placeholder: intent of this interaction}}

Each points to entry points nothing in the set reaches (the composer's check passes).

## Quality-bar checks

- pass: Every diagram has at most 50 elements and 2 lines per element
- pass: Every element shown has a description
- pass: Every container and component shown has a technology
- FAIL: Every line has an intent phrase, not one that opens with "uses" or "calls" (System context / System context of project-8: Billing clerk -> project-8; System context / System context of project-8: Dispatcher -> project-8; System context / System context of project-8: Fleet customer -> project-8; System context / System context of project-8: Fleet member -> project-8; System context / System context of project-8: Technician -> project-8 and 1 more)
- pass: Every line between containers names a protocol
- pass: No diagram repeated across sections
- pass: The document pins its commits and date
- pass: The main flows are sequence diagrams
- pass: Observed decisions are stated
- FAIL: Risks are stated
- pass: Acronyms are explained in a glossary

## LLM marks

- Badged: 0 elements, 0 lines.
- System boxes badged (should be none): none.
- Badged but still opening with "Found by the LLM:" (should be none): none.
- Found lines to infrastructure (should be none): none.

The report lists 5 quality gaps of its own. Checks the reference itself fails: diagram-size, line-labels.
