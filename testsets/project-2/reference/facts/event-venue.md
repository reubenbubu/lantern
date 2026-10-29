# project-2 ground truth: event-service and venue-service

HEAD `462516947458d4a161dfa0aef482865d9940e241`. Read by hand; nothing built or run.

Path abbreviations:
- `EV/` = `event-service/src/main/java/com/eventplatform/event/`
- `EVR/` = `event-service/src/main/resources/`
- `VE/` = `venue-service/src/main/java/com/eventplatform/venue/`

## 1. event-service

### Identity and configuration
- Port 8082, database `event_service`, Hibernate `validate`, Flyway (`EVR/application.yml:2,8-17`).
- Adds `spring-boot-starter-data-redis` and the OAuth2 resource server to the data-service parent
  (`event-service/pom.xml:18-26`). Redis used as the Spring cache, TTL 5 min
  (`EVR/application.yml:18-27`). `@EnableCaching`, `@EnableScheduling`
  (`EV/EventServiceApplication.java:8-10`). No `CacheErrorHandler` anywhere in the module, so
  Spring's default (rethrow) applies to cache failures.
- JWT validation against auth-service's JWKS with issuer, audience and `typ=access`
  (`EVR/application.yml:33-37`; `EV/config/EventSecurityConfiguration.java:92-99`). Public:
  `GET /api/v1/events`, `/api/v1/events/*`, `/api/v1/event-categories` (`:49-53`); other
  `/api/v1/**` authenticated (`:54-55`).
- Venue client: `RestClient` to `VENUE_SERVICE_URL`, 3 s connect and read timeout
  (`EVR/application.yml:38-40`; `EV/config/VenueClientConfiguration.java:15-22`).
- Inventory hold TTL 15 min (`EVR/application.yml:41-43`); consumer of the attendee topic
  (`:44-46`); outbox to `event-platform.event-lifecycle.v1`, batch 50, every 1 s, 10 attempts
  (`:47-52`); Kafka consumer retry settings (`:53-59`).

### HTTP API
- `EV/api/EventController.java` `/api/v1/events` (`:30`): `GET` list (`:43`) and `GET /{id}`
  (`:56`) public; `POST` (`:62-63`), `GET /{id}/management` (`:76-77`), `PUT /{id}` (`:86-87`),
  `POST /{id}/transitions` (`:99-100`), `DELETE /{id}` (archive, `:116-117`) need ORGANIZER or
  ADMIN.
- `EV/api/EventCategoryController.java` `/api/v1/event-categories`: `GET` public (`:31`);
  create/update/archive ADMIN (`:37-58`).
- `EV/api/TicketTypeController.java` `/api/v1/events/{eventId}/ticket-types`, ORGANIZER or ADMIN
  (`:23-24`): `POST` (`:34`), `PUT` (`:51`), `DELETE` (`:67`).
- `EV/api/InventoryController.java`
  `/api/v1/events/{eventId}/ticket-types/{ticketTypeId}/inventory`, **ATTENDEE, ORGANIZER or
  ADMIN** (`:26-27`): `GET` availability (`:39-45`), `POST /reservations` (`:47-67`),
  `POST /reservations/{id}/release` (`:69-85`), `POST /reservations/{id}/confirm` (`:87-103`).
  All three commands need `Idempotency-Key`. These are the endpoints attendee-service calls; the
  gateway also routes them publicly, because `/api/v1/events/**` goes to event-service
  (`api-gateway/src/main/resources/application.yml:24-27`) and only needs a valid token
  (`api-gateway/.../GatewaySecurityConfiguration.java:57-58`).

### Event lifecycle (`EV/application/EventManagementService.java`)
- Ownership: only the organizer who owns the event or an ADMIN (`:203-207`).
- Details (schedule, venue, category, capacity) change only while `DRAFT` (`:94-101`); update
  writes `event.updated` to the outbox and evicts the cache (`:127-128`).
- Transitions (`:132-168`): `PUBLISHED`, `SALES_OPEN`, `SOLD_OUT`, `CANCELLED`, `COMPLETED`.
  - Publish (`:209-248`): validates schedule, active category and ticket types, then **calls
    venue-service synchronously** to reserve the room with the organizer's bearer token and owner
    reference `event:<eventId>` (`:221-228`), stores the reservation id, appends
    `event.published` to the outbox (`:229-247`). The HTTP call happens inside the
    `@Transactional` method.
  - Cancel (`:282-292`): releases the venue reservation over HTTP (`:287-290`), then the
    transition appends `event.cancelled` (`:154-162`).
  - Each transition evicts the Redis cache entry (`:166`).
- Venue adapter: `POST /api/v1/venues/{venueId}/reservations` and
  `DELETE /api/v1/venues/{venueId}/reservations/{reservationId}` with the caller's
  `Authorization` header (`EV/infrastructure/venue/HttpVenueAvailabilityAdapter.java:24-64`);
  403 → `VENUE_ACCESS_DENIED`, 404 → `VENUE_NOT_FOUND`, other 4xx → conflict, else 503
  (`:66-93`). [Line numbers corrected in the second pass; were `:48-89` and `:91-118`.]
- Cache evictions run inside the `@Transactional` business methods: `EventManagementService.java:128,166,190`,
  `InventoryService.java:107,151,277`. They call `PublicEventCache.evict`, which calls
  `cacheManager.getCache(...).evict(id)` directly (`EV/application/PublicEventCache.java:19-24`), so
  no `CacheErrorHandler` applies at all (handlers wrap only annotation-driven cache operations): a
  Redis failure throws there and rolls the business transaction back. The `@Cacheable` detail read
  uses Spring's default `SimpleCacheErrorHandler`, which rethrows. Not run. [Second pass: direct
  `Cache.evict` made explicit; the conclusion is unchanged.]
- `InventoryService.confirm` (`:223-279`) checks only the requester and that the hold is ACTIVE; it
  does not check the event status, so a hold can be confirmed after the event is cancelled.
  Cancellation releases no inventory holds. [Added in the second pass.]
- Public reads: `PublicEventQueryService.detail` is `@Cacheable("public-event-details")`
  (`EV/application/PublicEventQueryService.java:94-96`); eviction via
  `EV/application/PublicEventCache.java:19-24`.

### Inventory (`EV/application/InventoryService.java`)
- Reserve (`:85-153`): ticket row locked (`requiredTicketForUpdate`, `:94`), expired holds
  returned first (`:96`), idempotency by `Idempotency-Key` bound to event, ticket type, requester
  and quantity (`:98-110`); requires `SALES_OPEN`, sales window, min/max quantity and stock
  (`:111-134`); creates a hold expiring after the TTL (`:135-145`), appends `inventory.held`
  (`:146-150`).
- Confirm (`:223-279`): only the requester or ADMIN (`:250-255`); only an `ACTIVE` hold
  (`:265-270`); appends `inventory.confirmed` (`:271-276`).
- Saga commands (`:309-353`): `confirmSaga` and `releaseSaga` call the same methods as the
  requester (`:321-322,335-336`) and turn a business error into
  `inventory.confirmation-rejected` / `inventory.release-rejected` (`:323-326,337-341,344-353`).
  A repeated confirmation re-emits `inventory.confirmed` (`:314-320`).
- Expiry: `InventoryExpiryJob` every 30 s (`EV/application/InventoryExpiryJob.java:25-30`) calls
  `expireReservation` (`InventoryService.java:281-307`), which emits `inventory.expired`.
- Event types (`EV/integration/EventLifecycleEvents.java:12-22`): `event.published`,
  `event.updated`, `event.cancelled`, `ticket-type.changed`, `inventory.held`,
  `inventory.confirmed`, `inventory.released`, `inventory.expired`,
  `inventory.confirmation-rejected`, `inventory.release-rejected` (all prefixed
  `event-platform.` and suffixed `.v1`).

### Messaging
- Outbox: `TransactionalOutbox.append` writes an `outbox_messages` row in the caller's
  transaction (`EV/outbox/TransactionalOutbox.java:23`;
  `event-service/src/main/resources/db/migration/V2__event_lifecycle_and_inventory.sql:94`).
  `OutboxRelay` every 1 s locks pending rows (`EV/outbox/OutboxMessageRepository.java:14-23`),
  sends each with key = aggregate id and the standard headers, waits up to 10 s for the ack,
  marks published or failed (`EV/outbox/OutboxRelay.java:57-85`); after 10 failures the row is
  dead-lettered (`EV/outbox/OutboxMessage.java:92-100`).
- Consumer `AttendeeInventorySagaConsumer` on `event-platform.attendee-lifecycle.v1`, group
  `event-service-inventory-saga-v1` (`EV/integration/AttendeeInventorySagaConsumer.java:38-42`);
  handles only `inventory.confirmation-requested` and `inventory.release-requested` (`:19-20,49-57`);
  dedups by `messageId` in `processed_integration_events` (`:47-48,59`;
  `V4__integration_event_dedup.sql:1`).

### Tables
`event_categories`, `managed_events`, `ticket_types`, `inventory_reservations`,
`outbox_messages` (`V2__event_lifecycle_and_inventory.sql:1,15,44,71,94`),
`processed_integration_events` (`V4__integration_event_dedup.sql:1`).

## 2. venue-service

- Port 8083, database `venue_service`, JWT from auth JWKS
  (`venue-service/src/main/resources/application.yml:9,25`). Kafka configured (`:18`) but no
  Kafka code.
- `VE/api/VenueController.java` `/api/v1/venues`, ORGANIZER or ADMIN on everything (`:25-26`):
  venue CRUD (`:40-66`), spaces (`:75-97`), `POST /{id}/availability/check` (`:107`),
  availability blocks (`:115-128`), `POST /{id}/reservations` (`:138`),
  `DELETE /{id}/reservations/{reservationId}` (`:151`).
- Reservation (`VE/application/VenueAvailabilityService.java:75-126`): locks the venue row
  (`:80`; `VE/domain/VenueRepository.java:13-15`), requires the caller to own the venue
  (`:81`), idempotent on `ownerReference` (`:86-101`), rejects overlapping whole-venue or space
  entries (`:104-111`, conflict queries `:170-180`; second pass: were `:109` and `:176-179`). Release also requires ownership (`:128-134`).
  So an organizer can only publish an event in a venue they own (or as ADMIN).
- Location enrichment port with a no-network local adapter that returns the input unchanged
  (`VE/application/LocationEnrichmentPort.java:5-9`;
  `VE/infrastructure/maps/LocalLocationEnrichmentAdapter.java:4-11`). No external maps call.
- Tables: `venues`, `venue_amenities`, `venue_metadata`, `venue_spaces`,
  `venue_space_amenities`, `venue_availability_entries`
  (`venue-service/src/main/resources/db/migration/V2__venue_management.sql:1,26,33,41,58,65`).
- The only caller of the reservation endpoints in code is event-service; the gateway also routes
  `/api/v1/venues/**` for organizers (`api-gateway/src/main/resources/application.yml:28-31`).
