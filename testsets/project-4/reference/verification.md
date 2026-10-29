# project-4 (APA-CRM): independent verification

Second pass over the first-pass reference (lantern #251, "Verification"). Done 2026-10-29 at the
commits in `notes.md` (all eight checkouts clean, unchanged). The verifier read the code directly and
did not read any Lantern output or the first pass's comparison with it. Nothing was built or run.

Paths: `DG` = Discovery-and-Gateway/api-gateway/src/main/java/com/crm/gateway, `A` =
Auth-Service/src/main/java/com/crm/auth, `M` = Main-Service/src/main/java/com/crm/main, `F` =
File-Service/src/main/java/com/crm/file, `N` =
Notification-Service/src/main/java/com/crm/notification/notification_service, `SL` = crm-shared-lib,
`FE` = CRM-Frontend, `DEP` = Deployment.

## What was checked

Every item in `reference.c4.json` (57 elements, 122 diagram relationships, 108 sequence steps,
9 decisions, 13 first-pass risks, 6 unknowns, the route, binding, deployment, sources and glossary
tables) was traced to code, and every file:line the data cites (43 distinct citations) was re-read.

- **Gateway:** all of `DG` (RouteConfig, the three filters and BaseGatewayFilter, JwtService,
  UserPermissionService, the Redis repository, both WebClients and their `@LoadBalanced` builder,
  OrganizationIdExtractor, the composite controller and clients, CORS, WebSocket config),
  `application.properties`, both poms, Dockerfiles, CI workflow; eureka-service config.
- **Auth-Service:** every controller mapping, JwtService, AuthenticationService, RefreshTokenService,
  PasswordRecoveryService, PasswordRestoreRequestService, VerificationCodeGenerator,
  UserCreatorService, the permission extractor, saver, consumer and Redis repository, RedisConfig,
  RabbitMQConfig and local constants, MainClient, SecurityConfig, `application.properties`, pom.
- **Main-Service:** every controller mapping and its `@RequiresOrganizationMembership` /
  `@RequiresPermission` (37 public + 3 internal endpoints, recounted), the membership aspect,
  OrganizationCreatorService, InvitationProcessorService and facade, TaskProcessorService,
  TaskReminderJobHandler, JobRunrConfig, RabbitMQConfig, all four producers, all three consumers,
  AuthClient and wrappers, assignment services, `application.yml`, pom.
- **File-Service:** FileController, CreateRootDirConsumer, RabbitMQConfig, FileContent, FileFacade,
  `application.properties`.
- **Notification-Service:** WebSocketsConfig, StompAuthChannelInterceptor, both Feign clients and
  FeignConfig, NotificationSender, all five consumers, EmailService, TemplateBuilder, RabbitMQConfig,
  `application.properties`, pom.
- **crm-shared-lib:** RabbitMQConstants, BaseRabbitMQConfig, MessagingService,
  PermissionControlAspect, CrmHeaders, CacheConstants; the message classes at tags 0.2.0, 0.2.1 and
  0.2.5; the pom at HEAD and at those tags.
- **Frontend:** Dockerfile, nginx.conf, the three environments and `angular.json` configurations,
  every service file's API calls, the STOMP client, both interceptors, routes, enums.
- **Deployment:** both Compose files line by line, Helm values, ConfigMap, Secret, Ingress, gateway
  template, Kustomize base, overlay, init script and ingress, Prometheus config, README lines cited.
- **Sampling for misses:** all controllers, listeners, Feign clients and WebClients in every
  repository, all `application.*` files, every deployment descriptor, every README for recorded
  rationale (none beyond the two the first pass cites), and a search for ADRs or design docs (none).

## Corrections (before → after)

1. **The gateway takes the organization id only from the header.** Before: the check-access filter
   "takes the organization id from the path or Organization-Id header". After: in effect from the
   header only. `OrganizationIdExtractor` matches `^/api/organizations/(?<organizationId>\d+)(/.*)?$`
   against `getURI().toString()` (`DG/utils/OrganizationIdExtractor.java:17,21,30-34`); in WebFlux
   that is the absolute URI (`http://host:8080/api/...`), so the path pattern never matches and the
   header is used (`:23-24,41-44`). The frontend always sends `Organization-Id`
   (`FE/src/app/core/interceptors/organization-interceptor.interceptor.ts:17-28`), so normal use
   works. Changed the `gw-check-access` component; the security consequence is new risk A below.
2. **Publishing before commit: what happens to the consumers.** Before: consumers "are not requeued,
   leaving an organization without root directory or default task priorities and statuses". After:
   the priorities and statuses consumers throw `AmqpRejectAndDontRequeueException`
   (`M/service/consumer/CreateTaskDefaultPrioritiesConsumer.java:57-60`, `...StatusesConsumer.java:57-60`),
   so those are lost; the root-directory reply consumer rethrows a plain exception
   (`M/service/consumer/OrganizationRootDirCreatedConsumer.java:38-41`) and no service configures
   `default-requeue-rejected` (`SL/messaging/.../BaseRabbitMQConfig.java:18-67`, every
   `application.*`), so Spring AMQP requeues it and it is retried until the organization exists. The
   file-service side needs no organization row (`F/service/consumer/CreateRootDirConsumer.java:29-38`).
   Risk row and the create-organization sequence note updated.
3. **Frontend LAN IP.** Before: "the default build hardcodes a LAN IP". After: the default build
   configuration is `local` (`FE/angular.json:78`, localhost); the `production` configuration
   (`:39`) has no file replacement and so uses `environment.ts:2` (`http://192.168.88.113:8080`).
   The image uses `development` (`FE/Dockerfile:12`, `angular.json:55-64`).
4. **Identity-header risk consequence.** Before: anyone "directly on the network or via a public
   route" can act as any user. After: the public routes lead only to `/api/auth/**`,
   `/api/restore-password-request/**` (`A/controller/AuthController.java`,
   `RestorePasswordRequestController.java`) and the STOMP endpoint, none of which reads `User-Id` or
   `User-Permissions`; on filtered routes the gateway's `header(...)` replaces `User-Id`
   (`DG/filters/AuthFilter.java:37-40`). So the exposure is direct network access; through the
   gateway it is latent. Finding text kept; severity kept (judgement call J3).
5. **Task-reminder pre-condition.** Before: "the task has an assignee"; post: "Assignee and creator
   receive...". After: no assignee needed; the job emails the creator and the assignee if set
   (`M/service/jobs/handlers/TaskReminderJobHandler.java:44-53`).
6. **Route table, `main-service-check-access`.** Added `/api/organizations/*` for the methods the
   earlier route does not take (e.g. `PUT /api/organizations/{id}`), per `DG/config/RouteConfig.java:38-45,52-64`.
7. **Email sender failure.** Before: "rethrows on a MessagingException; no dead-letter queue". After:
   a failed send throws (`N/service/email/EmailService.java:35-39`; `mailSender.send` failures are
   unchecked `MailException`s and propagate too), so the message is requeued and retried; no DLQ.
8. **Decision "one topic exchange per producing service".** "queues are declared by both producer
   and consumer" → "most queues": `notification-service.send-message` is declared only by
   Notification-Service (`N/config/RabbitMQConfig.java:30-35`; not in `M/config/RabbitMQConfig.java`).
9. **Unknown on Kubernetes discovery.** Narrowed: all five services have
   `spring-cloud-starter-kubernetes-client-all` on the classpath (each `pom.xml`), so the open point
   is only whether it works at runtime.
10. **Citation fix.** `UserPermissionClient.java:13-14` → `:12-13,24` (constant on 12-13, used on 24).
11. **Build script** now emits the layout of the delivered JSON (decisions, risks and unknowns at the
    end of the context page; the first pass's script still had a separate page, so the committed JSON
    had been edited by hand). Version 1.1.0 with a revision entry.

## Additions

- **Risk A (High): cross-organization permission mix-up.** Main-Service checks membership in the
  path's organization (`M/aspect/OrganizationMembershipControlAspect.java:28-35`, the `@OrganizationId`
  path variable on every guarded endpoint) but the permission from `User-Permissions`
  (`SL/rbac/.../PermissionControlAspect.java:29-43`), which the gateway computes for the header's
  organization (correction 1; `DG/filters/AuthorizeAndCheckAccessFilter.java:45-55`). Anyone can
  create an organization and become its Admin with ALL:ALL (`M/service/operation/OrganizationCreatorService.java:53-77`),
  so a plain member of another organization can probably act there as admin by sending their own
  organization's id in the header. Worded "probably" (not run).
- **Risk B (Medium): unscoped file deletes and creates.** File-Service stores no organization
  (`F/persistance/entity/FileMetadata.java`); only GET/PATCH routes get the ownership check
  (`DG/config/RouteConfig.java:65-78`); DELETE and POST check only a FILES permission
  (`F/controller/FileController.java:39-63`). Removing a member deletes their rows but publishes no
  `org-user.role-changes` (`M/service/assignments/OrganizationUserAssignmentService.java:62-72`), so
  their cached permissions stay in Redis for up to 10 h.
- **Risk C (Low): organization details readable by any signed-in user.** `GET /api/organizations/{id}`
  has no membership or permission check (`M/controller/OrganizationController.java:55-61`) and is
  routed with AuthFilter only (`DG/config/RouteConfig.java:38-45`). The first pass's facts noted it
  (`facts/main-and-shared.md`, section 6) but the reference left it out.

Considered and not added (recorded so a scorer need not penalize them either way):

- Compose's Redis gets `REDIS_PASSWORD` as an environment variable, which the official image does
  not read, so it probably runs without a password on host port 6379 (`DEP/docker-compose.local.yml:264-280`).
  How the services' `AUTH` then behaves was not verified; left out as uncertain and local-only.
- On a cache miss the gateway's WebClient maps a 4xx to a shared-lib exception
  (`DG/config/WebConfig.java:18-26`), which `onErrorResume(WebClientResponseException.class, ...)`
  does not catch, so the client probably gets a 500 rather than the 404. Detail of the existing
  permission-fallback risk; not added.
- Reset codes come from `java.util.Random` (`A/utils/VerificationCodeGenerator.java:11-15`); with
  5 attempts per request and one request per 3 h this is not a practical weakness.

## Confirmed (unchanged)

- Containers, technologies and versions: Boot 3.5.8 / Java 21 / Cloud 2025.0.0 for gateway, eureka,
  auth, file, notification; Boot 4.1.1 / Java 25 / Cloud 2025.1.2 / JobRunr 8.6.1 for main;
  shared-lib versions per pom (Auth `pom.xml:31-32`, File `:32-33`, Notification `:30-31`, Main
  `:32-33`, gateway core+rbac `api-gateway/pom.xml:23-42`); Angular 19.2 served by nginx on 4200.
- Data stores: three PostgreSQL schemas by `ddl-auto=update`; file bytes as `@Lob` in file_db
  (`F/persistance/entity/FileContent.java:31-32`), served from memory (`F/facade/FileFacade.java:40-41`);
  JobRunr in main_db (`M/config/JobRunrConfig.java:16-22`); Redis keys, 10 h TTL, 10 s failure
  marker, lock (`A/cache/UserPermissionRepository.java`, `A/config/RedisConfig.java:48-51`,
  `A/application.properties:41-43`).
- Every exchange, queue, routing key, binding, declarer and listener in the bindings table
  (`SL/.../RabbitMQConstants.java:5-39`, `A/config/RabbitMQConfig.java`, `A/constants/RabbitMQConstants.java:6-10`,
  `M/config/RabbitMQConfig.java:19-122`, `F/config/RabbitMQConfig.java:15-43`,
  `N/config/RabbitMQConfig.java:16-49`, all listeners), the 15-min TTL, the default-exchange send
  (`SL/.../MessagingService.java:73`) and its swallowed errors (`:78-82`).
- All synchronous calls and their paths: gateway → auth `/users/.../persmissions` (wrong prefix) and
  `GET /api/internal/users?userId=`; gateway → main `/api/internal/organizations/{o}/files/{f}/check`
  and the task calls; auth → main `GET /api/internal/organizations/{o}/users/{u}`; main → auth roles
  and users; notification → auth `GET /api/internal/auth/authorize` (auth maps POST) and → main
  `/exists`; notification → Gmail `smtp.gmail.com:587` STARTTLS.
- Routes and their filters (`DG/config/RouteConfig.java:33-85`), `/preview` unrouted, no rate
  limiting, circuit breaker or retry, CORS `*`.
- STOMP endpoint, in-memory broker, interceptor behaviour, destinations; README mismatches.
- All six sequences step by step, except the corrections above.
- Deployment table (both Compose files, Helm, Kustomize), Eureka off in Kubernetes, dev-compose
  gaps, single instances, `restartPolicy` misplacement (facts only).
- The remaining risks (permission fallback path, secrets, GET-with-body, preview route, shared-lib
  drift, single instances, DELETE task with TASKS:READ, CORS, stale READMEs, no migrations), all
  nine decisions and their rationale sources (`Main-Service/README.md:70`, `Deployment/README.md:18-21`).

## Errors in the first pass's fact files (not in the reference unless listed above)

- `facts/edge-and-deployment.md` §1 and §6: organization id "from the path" (correction 1);
  §5: the invitee is "unauthenticated" (the invitation API needs a JWT; the reference already says
  "while signed in").
- `facts/auth-file-notification.md`: gateway signing key cited at `application.properties:17`
  (it is `:22`); File's shared-lib version at `pom.xml:~30` (it is `:32-33`).
- `facts/main-and-shared.md` §6: root-directory consumer failures "not requeued" (correction 2).

## Template issue found while rendering

The report template's inline Markdown applies `**bold**` and `*italic*` inside code spans
(`mdInline` in `report-specs/c4-model-sequence-diagrams/c4-model-sequence-diagrams.html`, around
line 4409: the code replacement runs first, then the emphasis replacements run over its output).
Route patterns such as `` `/api/auth/**` `` and `` `/api/organizations/*` `` therefore render with
stars missing and stray bold or italics. The data is correct; the rendering is not. Worth an issue
against the template.

## Judgement calls (variants a scorer should accept)

- **J1 People.** Two people (member, invited user). Accept also a single "user" person, or an
  additional "organization administrator"; the code has no fixed admin role.
- **J2 Messaging elements.** Exchanges drawn as elements. Accept one "RabbitMQ" message broker
  container/store instead, provided the routing keys or queues are named on the relationships.
- **J3 Severities.** The identity-header risk at High or Medium (exposure needs network access
  behind the gateway). Risk A at High or Medium (not run). Risk B at Medium or High.
- **J4 Eureka.** Accept eureka-service as a container or as infrastructure left out of the diagram;
  accept calling the registry `discovery-service` (its Compose name).
- **J5 Redis.** Accept Redis owned by auth-service or shared between auth-service and api-gateway.
- **J6 Databases.** Accept three stores (auth_db, main_db, file_db) or one PostgreSQL store with
  three schemas, as long as ownership per service is clear.
- **J7 crm-shared-lib.** Correct as a library (not a container); accept it shown as a code-level
  dependency or not shown.
- **J8 Frontend–gateway connection.** Accept the WebSocket drawn as frontend → gateway → notification
  (as here) or frontend → notification "via the gateway".
- **J9 Kubernetes discovery.** Accept "presumably Spring Cloud Kubernetes"; do not accept a claim
  that Eureka runs in Kubernetes.
- **J10 Tasks composite.** Accept the gateway's `/api/composite` controller as a gateway component or
  as a separate "composition" responsibility of the gateway; it is not a separate container.
