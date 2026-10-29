# project-2 ground truth: attendee-service, payment-service, notification-service, Kafka topics

HEAD `462516947458d4a161dfa0aef482865d9940e241`. Read by hand; nothing built or run.

Path abbreviations:
- `AT/` = `attendee-service/src/main/java/com/eventplatform/attendee/`
- `PA/` = `payment-service/src/main/java/com/eventplatform/payment/`
- `NO/` = `notification-service/src/main/java/com/eventplatform/notification/`

## 1. attendee-service (booking Saga orchestrator)

### Configuration
- Port 8084, database `attendee_service` (`attendee-service/src/main/resources/application.yml:9`);
  JWT from auth JWKS (`:30-31`); QR key `TICKET_QR_SIGNING_SECRET`, ephemeral allowed by default
  (`:32-35`); event-service base URL and 3 s timeout (`:36-38`;
  `AT/config/AttendeeRuntimeConfiguration.java:23-29`); hold expiry every 30 s (`application.yml:39-41`);
  consumes event and payment topics (`application.yml:42-45`); Saga recovery every 5 s (`application.yml:47`); outbox to
  `event-platform.attendee-lifecycle.v1` (`application.yml:48-53`). [Second pass: these citations made explicit; they are lines of `application.yml`, not of `AttendeeRuntimeConfiguration.java`.]

### HTTP API
- `AT/api/AttendeeProfileController.java` `/api/v1/attendees/me`, ATTENDEE (`:17-18`): `GET`
  (`:27`), `PUT` (`:33`).
- `AT/api/BookingController.java` `/api/v1`, ATTENDEE (`:29-30`): `POST /bookings` (`:42`),
  `GET /bookings` (`:54`), `GET /bookings/{id}` (`:60`), `GET /attendees/me/tickets` (`:67`).
- `AT/api/TicketScanController.java` `/api/v1`, EVENT_STAFF, ORGANIZER or ADMIN (`:24-25`):
  `POST /tickets/validate` (`:35`), `POST /check-ins` (`:47`).

### Booking creation (synchronous part)
- `BookingCoordinator.create` (`AT/application/BookingCoordinator.java:29-50`): ensures the
  profile from the JWT (`AT/application/AttendeeProfileService.java:24-37`), claims a durable
  booking command by `Idempotency-Key` (`BookingCoordinator.java:35-36`; `AT/application/BookingCommandService.java:22`),
  **reserves inventory at event-service over HTTP** with key `attendee-reserve:<commandId>`
  (`:40-42`); for a zero-price hold it also confirms over HTTP (`:43-47`); then persists the
  booking (`:49`).
- HTTP adapter: `POST .../inventory/reservations`, `.../confirm`, `.../release`, forwarding the
  attendee's bearer token, `Idempotency-Key`, correlation id and `traceparent`
  (`AT/infrastructure/event/HttpEventInventoryAdapter.java:28-66`).
- `BookingPersistenceService.complete` (`AT/application/BookingPersistenceService.java:81-159`):
  saves registration, booking, line item, ticket hold and Saga row (initial state `CONFIRMED`,
  `PAYMENT_PENDING` or `EXPIRED`, `:131-134`); for a paid booking appends
  `booking.payment-requested` (`:140-148`); for a confirmed (free) booking issues tickets
  immediately (`:150-151`, `issueTickets` `:226-240`); appends `booking.created` (`appendBookingCreated`, `:243-253`).

### Saga (`AT/application/BookingSagaService.java`)
- `paymentProcessing` (`:69-78`): `PAYMENT_PENDING` → `PAYMENT_PROCESSING`.
- `paymentSucceeded` (`:81-103`): if the hold is already confirmed, finalize (`:90-92`); if the
  hold is no longer active or has expired, **compensate** (`:94-98`); else
  `INVENTORY_CONFIRMATION_PENDING` and append `inventory.confirmation-requested` (`:99-102`).
- `paymentFailed` (`:106-129`): releases an active hold with `inventory.release-requested`
  (`:119-124`), appends `booking.payment-failed` (`:125-128`).
- `inventoryConfirmed` (`:132-142`): confirms the hold; finalizes only from
  `INVENTORY_CONFIRMATION_PENDING`.
- `inventoryUnavailable` / `holdExpired` (`:145-155`) → `handleUnavailable` (`:236-256`): with a
  payment in flight, compensate; otherwise expire and append `ticket-hold.expired`.
- `refunded` (`:158-182`): marks selected tickets refunded, booking refunded or partially
  refunded, appends `booking.refunded`.
- `recover` (`:185-199`): re-appends the confirmation or compensation command for stuck Sagas;
  driven by `BookingSagaRecoveryJob` every 5 s (`AT/application/BookingSagaRecoveryJob.java:3`).
- `finalizeConfirmation` (`:201-226`): confirms hold, booking and registration, creates one
  ticket per seat and appends `ticket.issued` per ticket **including the signed QR token and the
  attendee's email, phone and locale** (`:210-220`), then `booking.confirmed` (`:222-225`).
- `appendCompensation` (`:258-264`): appends `payment.compensation-requested`.
- Event types: `AT/integration/AttendeeLifecycleEvents.java:9-20`.

### Consumers
- `EventLifecycleConsumer` on `event-platform.event-lifecycle.v1`, group `attendee-service-v1`
  (`AT/integration/EventLifecycleConsumer.java:44-48`): `inventory.expired` and
  `inventory.confirmation-rejected` → `inventoryUnavailable`; `inventory.confirmed` →
  `inventoryConfirmed`; `event.cancelled` → `BookingPersistenceService.cancelEvent`
  (`:59-70`); dedup by `messageId` (`:53,71`).
- `cancelEvent` (`AT/application/BookingPersistenceService.java:185-199`) sets every booking,
  registration and ticket of the event to `CANCELLED`. **It appends no outbox event and requests
  no refund.**
- `PaymentLifecycleConsumer` on `event-platform.payment-lifecycle.v1`
  (`AT/integration/PaymentLifecycleConsumer.java:43-73`): `payment.processing`,
  `payment.succeeded`, `payment.failed`, `refund.succeeded` → the Saga methods above.
- `TicketHoldExpiryJob` every 30 s (`AT/application/TicketHoldExpiryJob.java:26-31`) → `holdExpired`.

### Tickets and check-in
- QR token: HMAC-SHA256 over issuer, ticket id, event id, token version and issue time
  (`AT/application/QrTokenService.java:52-60`); key from `TICKET_QR_SIGNING_SECRET` (min 32
  bytes, `:45-48`) or an ephemeral random key when allowed (`:36-44`).
- `TicketScanService` (`AT/application/TicketScanService.java:50-151`): idempotent per scanner
  and key (`:67-78`, re-checked after the ticket lock at `:100-110`; second pass added `:67-78`), checks event, scanner permission, status and token version
  (`:111-138`), on check-in locks the ticket, records a `CheckIn` and appends
  `ticket.checked-in` (`:144-151`). Scanner permission: ADMIN, **any EVENT_STAFF**, or the
  organizer who owns the event (`AT/security/AuthenticatedActor.java:34-35`).

### Outbox
- `AttendeeOutboxRelay` every 1 s to the attendee topic (`AT/outbox/AttendeeOutboxRelay.java:40,56`),
  same pattern as event-service (pessimistic lock on pending rows,
  `AT/outbox/AttendeeOutboxRepository.java:14-21`).

### Tables
`attendee_profiles`, `booking_commands`, `registrations`, `bookings`, `booking_line_items`,
`ticket_holds`, `tickets`, `check_ins`, `check_in_attempts`, `attendee_outbox_messages`,
`processed_integration_events`
(`attendee-service/src/main/resources/db/migration/V2__attendee_booking_ticketing.sql:1,11,26,39,58,80,99,131,140,156,176`),
`booking_sagas` (`V3__booking_payment_saga.sql:1`).

## 2. payment-service

- Port 8085, database `payment_service` (`payment-service/src/main/resources/application.yml:9`);
  JWT from `JWT_JWK_SET_URI` (`:35`); provider `fake`, webhook secret, processing timeout 30 s,
  reconciliation every 10 s (`:37-40`); consumes the attendee topic (`:42-43`); outbox to
  `event-platform.payment-lifecycle.v1` (`:45-48`).
- Security: webhooks public, other `/api/v1/**` authenticated
  (`PA/config/PaymentSecurityConfiguration.java:33-35`), JWT issuer/audience/typ (`:41-46`).
- `PA/api/PaymentController.java` `/api/v1/payments` (`:7`): `POST` (ATTENDEE only, needs
  `Idempotency-Key` and `paymentMethodToken`, `:11`), `GET` list (`:12`), `GET /{id}` (`:13`),
  `GET /{id}/transactions` (`:14`), `POST /{id}/verify` (`:15`), `POST /{id}/refunds` (`:16`),
  `POST /webhooks/{provider}` with `X-Payment-Signature` (`:17`).
- Pay (`PA/application/PaymentApplicationService.java:10-15`): `claim` in one transaction
  (`PA/application/PaymentStateService.java:17-25`; needs the booking order projection, else 409
  `PAYMENT_ORDER_NOT_READY` (`:18`), owner only, hold not expired), then the provider call outside
  a transaction, then `apply` (`:27-37`) which updates payment/attempt, records a transaction and
  appends `payment.processing|succeeded|failed` with attendee contact and event snapshot
  (`:44`).
- Provider port: create, verify, refund, verify webhook (`PA/provider/PaymentProvider.java:4-16`).
  The **only** implementation is `FakePaymentProvider` (`PA/provider/FakePaymentProvider.java:18-41`):
  outcome chosen by the `sandbox-*` token (`:29`), payment state kept **in memory**
  (`ConcurrentHashMap`, `:22`), verification of an unknown payment returns FAILED
  `PROVIDER_PAYMENT_NOT_FOUND` (`:32`), webhook HMAC-SHA256 with the configured or a random
  ephemeral secret (`:26,34-37`). There is no outbound network call: no real payment provider is
  integrated.
- Webhook (`PA/application/PaymentWebhookService.java:9`; `PaymentStateService.java:39-43`):
  provider name must match, signature verified, dedup by provider event id in
  `provider_webhook_events`.
- Reconciliation: every 10 s verifies payments stuck in `PROCESSING` longer than the timeout
  (`PA/application/PaymentReconciliationJob.java:9`).
- Refund (`PA/application/RefundApplicationService.java:9`; `PA/application/RefundStateService.java:11-22`):
  closes when the event starts (`RefundStateService.java:13`); allowed for the paying ATTENDEE, the
  event's ORGANIZER or ADMIN (`:14`) [second pass: window line was cited as `:14`]; ticket projections exclude checked-in tickets. Compensation
  refund (`RefundApplicationService.java:10`; `RefundStateService.java:23`) is triggered from the
  consumer and calls the provider **inside the consumer's database transaction**
  (`PA/integration/AttendeeLifecycleConsumer.java:59-60,74-76`).
- Consumer `AttendeeLifecycleConsumer` on the attendee topic, group `payment-service-v1`
  (`PA/integration/AttendeeLifecycleConsumer.java:56-80`): `booking.payment-requested` → booking
  payment order projection (`:82-92`), `ticket.issued` and `ticket.checked-in` → ticket projection,
  `payment.compensation-requested` → full refund.
- Event types `payment.processing`, `payment.succeeded`, `payment.failed`, `refund.succeeded`,
  `refund.failed` (`PA/integration/PaymentLifecycleEvents.java:7`); payloads carry attendee email,
  phone, locale (`:9-10`).
- Outbox relay every 1 s (`PA/outbox/PaymentOutboxRelay.java:53-65`).
- Tables: `booking_payment_orders`, `payments`, `payment_attempts`, `refunds`,
  `payment_transactions`, `provider_webhook_events`, `payment_ticket_projections`,
  `payment_outbox_messages`, `processed_integration_events`
  (`payment-service/src/main/resources/db/migration/V2__payments_refunds_and_outbox.sql:1,12,25,36,48,58,66,73,83`).

## 3. notification-service

- Port 8086, database `notification_service`
  (`notification-service/src/main/resources/application.yml:9`); group
  `notification-service-v1` (`:21`); JWT (`:31`); consumes all three lifecycle topics (`:33-36`);
  email and SMS provider `local`, reminder lead 24 h, 5 delivery attempts, batch 50, every 1 s
  (`:38-43`).
- `NO/api/NotificationController.java` `/api/v1/notifications` (`:16`): `GET`/`PUT /preferences`
  (`:27,32`), `GET` own intents (`:39`), `GET /local-deliveries` ADMIN (`:44-45`).
- Consumer: one listener on the three topics (`NO/integration/NotificationEventConsumer.java:19-29`).
  Handler (`NO/integration/NotificationEventHandler.java:51-70`) maps `booking.created`,
  `booking.confirmed`, `payment.succeeded`, `payment.failed`, `ticket.issued`, `event.updated`,
  `event.cancelled`, `refund.succeeded` to intents; dedup by `messageId` (`:56,68-69`).
  `booking.created` also stores the recipient and booking projection and plans an
  `EVENT_REMINDER` at start minus the lead (`:72-86`; `NO/application/NotificationPlanner.java:154-155`).
  `ticket.issued` puts the QR token into the email variables (`NotificationEventHandler.java:109-122`).
  `event.cancelled` cancels reminders and alerts every booking of the event (`NotificationEventHandler.java:130-139`).
  `event.updated` reschedules reminders and sends `EVENT_RESCHEDULED` when the start changed
  (`NotificationEventHandler.java:124-128`; `NotificationPlanner.java:110-126`). [Second pass: handler citations made explicit.]
- Types: `BOOKING_RECEIVED`, `BOOKING_CONFIRMED`, `PAYMENT_CONFIRMED`, `PAYMENT_FAILED`,
  `TICKET_ISSUED`, `EVENT_REMINDER` (optional), `EVENT_CANCELLED`, `EVENT_RESCHEDULED`,
  `REFUND_CONFIRMED` (`NO/domain/NotificationType.java:4-12`). SMS only when the user opted in
  (`NotificationPlanner.java:94`); reminders can be switched off (`:84,129-135`).
- Delivery: `NotificationDeliveryJob.deliverDue` every 1 s (`NO/application/NotificationDeliveryJob.java:73-85`)
  locks due intents, renders the template (`NO/template/TemplateCatalog.java:17-31`), calls the
  email or SMS port (`NotificationDeliveryJob.java:101`), records the attempt; after the maximum, `DEAD_LETTERED` (`NotificationDeliveryJob.java:110`).
- Providers: only `LocalEmailAdapter` and `LocalSmsAdapter`, both active only for provider
  `local`, which store the rendered message in `local_deliveries` instead of sending it
  (`NO/provider/LocalEmailAdapter.java:14-37`; `NO/provider/LocalSmsAdapter.java:14,28-35`; second pass: was `:14,25`, where line 25 is only `name()`). No real
  email or SMS provider is integrated; any other provider value leaves no sender bean.
- Tables: `notification_preferences`, `notification_recipients`,
  `booking_notification_projections`, `notification_intents`, `notification_delivery_attempts`,
  `local_deliveries`, `processed_integration_events`
  (`notification-service/src/main/resources/db/migration/V2__notification_delivery.sql:1,9,19,31,59,73,83`).

## 4. Kafka topics (as used in code)

No topic is declared in code (`grep -rn "NewTopic\|TopicBuilder"` finds nothing), so topics are
created by the broker's auto-creation with 3 partitions and replication 1 (`compose.yaml:59-63`).

| Topic | Producer (outbox) | Consumers |
| --- | --- | --- |
| `event-platform.event-lifecycle.v1` | event-service | attendee-service, notification-service |
| `event-platform.attendee-lifecycle.v1` | attendee-service | event-service (inventory commands), payment-service, notification-service |
| `event-platform.payment-lifecycle.v1` | payment-service | attendee-service, notification-service |
| `<topic>.dlt` | any consumer's error handler | none in code |

The event catalogue in `docs/architecture/kafka-event-catalog.md:31-58` matches the code's event
types and consumers.

## 5. Behaviour worth noting (cross-checked)

- An attendee can call event-service's inventory `reserve` and `confirm` directly through the
  gateway (routes and roles above). Confirming a hold outside a booking permanently decrements
  stock without payment; attendee-service's `inventoryConfirmed` ignores it unless the Saga is
  waiting (`BookingSagaService.java:132-142`).
- Event cancellation cancels bookings and tickets (`BookingPersistenceService.java:185-199`) and
  notifies attendees, but no service starts a refund; the attendee or organizer must call
  `POST /payments/{id}/refunds` before the event start time (`RefundStateService.java:13`).
- Rescheduling notifications cannot fire for booked events: event details can change only in
  `DRAFT` (`event-service/.../EventManagementService.java:96-101`), and bookings need
  `SALES_OPEN` (`event-service/.../InventoryService.java:111-116`).

## 6. Added in the second pass (verification)

- A booking still `PAYMENT_PENDING` when its event is cancelled can still be paid and confirmed.
  `cancelEvent` changes bookings, registrations and tickets but not the Saga row
  (`AT/application/BookingPersistenceService.java:185-199`); payment-service does not consume the
  event topic and `claim` checks only the order projection, owner and hold expiry
  (`PA/application/PaymentStateService.java:17-25`); event-service `confirm` does not check the event
  status (`event-service/.../InventoryService.java:223-279`); `paymentSucceeded` and
  `finalizeConfirmation` confirm the booking without checking its status
  (`AT/application/BookingSagaService.java:81-103,201-206`; `Booking.java:72-80` sets any status).
  Result: a confirmed booking with ISSUED tickets for a cancelled event, if paid within the hold.
- notification-service handles only `booking.created`, `booking.confirmed`, `payment.succeeded`,
  `payment.failed`, `ticket.issued`, `event.updated`, `event.cancelled`, `refund.succeeded`
  (`NO/integration/NotificationEventHandler.java:57-67`). `booking.created` plans an
  `EVENT_REMINDER` for every booking whose status is not `EXPIRED` (`:82-85`), including
  `PAYMENT_PENDING`; nothing cancels that reminder when the booking's payment fails, its hold expires
  or it is refunded. `event.cancelled` plans `EVENT_CANCELLED` for every booking projection of the
  event regardless of outcome (`:130-139`).
- Scan outcomes are `VALID`, `CHECKED_IN`, `ALREADY_CHECKED_IN`, `INVALID_TOKEN`, `TAMPERED_TOKEN`,
  `TICKET_NOT_FOUND`, `WRONG_EVENT`, `TICKET_CANCELLED`, `TICKET_REFUNDED`, `TICKET_NOT_ISSUED`,
  `ORGANIZER_NOT_OWNER` (`AT/domain/ScanOutcome.java`). The idempotent-replay check runs before
  QR verification (`AT/application/TicketScanService.java:67-87`); the ticket is locked before the
  status checks (`:93`).
