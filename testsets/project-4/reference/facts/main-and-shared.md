# project-4 (APA-CRM): Main-Service and crm-shared-lib, ground-truth facts

Pins read: Main-Service `0b59f25` (2026-08-22), crm-shared-lib `442bbbb` (2026-08-22). Paths are relative to each repo. `MS` = Main-Service, `SL` = crm-shared-lib. Java paths under `MS/src/main/java/com/crm/main/` are shortened to `main/…`, and under `SL/<module>/src/main/java/com/crm/sharedlib/<module>/` to `<module>/…`.

## 1. Identity

**Main-Service**
- Purpose: core CRM domain service. It handles organizations, membership, org roles (role definitions live in Auth-Service), invitations, organization file links, and tasks (with priorities, statuses and reminders). It publishes domain events (`MS/README.md:3,24`).
- `spring.application.name: main-service` (`MS/src/main/resources/application.yml:40-41`). Port 8080 (`application.yml:1-2`).
- Spring Boot **4.1.1** parent (`MS/pom.xml:6-11`), Spring Cloud **2025.1.2** (`pom.xml:22`), **Java 25** (`pom.xml:18-20`). The README is stale: it says Java 21 / Boot 3.x / application.properties (`README.md:30-31,63`).
- Key dependencies: webmvc, data-jpa, validation, openfeign, eureka-client, `spring-cloud-starter-kubernetes-client-all`, micrometer-metrics, amqp, JobRunr 8.6.1 (`jobrunr-spring-boot-4-starter`), MapStruct 1.6.3, Lombok, PostgreSQL runtime driver (`pom.xml:36-91`). Tests use H2, Testcontainers RabbitMQ, RestAssured and Awaitility (`pom.xml:92-128`).
- Shared lib: `com.github.APA-CRM:crm-shared-lib:0.3.0-SNAPSHOT` via the JitPack repo (`pom.xml:30-34,197-202`). It takes the aggregate, so it uses classes from **core, messaging and rbac**. Bootstrapping is `@SpringBootApplication @ComponentScan("com.crm") @EnableFeignClients @EnableDiscoveryClient` (`main/MainServiceApplication.java:9-13`). The broad `com.crm` scan is what pulls in the shared-lib beans, because SL has no auto-configuration files (no `spring.factories`/`AutoConfiguration.imports` found).
- Packaging: jar. `Dockerfile` is a multi-stage build, temurin 25 JDK to 25 JRE, running `java -jar app.jar` (`MS/Dockerfile:1-19`). `Dockerfile.local` only copies the prebuilt jar (`Dockerfile.local:1-9`). CI runs `mvn clean verify` on JDK 25, then on main/develop builds `Dockerfile.local` and pushes `aappaa/main-service:<branch|latest>` to Docker Hub (`.github/workflows/backend-ci-cd.yml:3-4,25-53`).
- Deployment: compose service `main-service` with env DISCOVERY_*, MAIN_DATASOURCE_*, RABBIT_*. It depends on discovery, `main-db-service` (postgres:17.5-alpine3.22) and rabbitmq (`Deployment/docker-compose.local.yml:15-16,161-191`; dev variant `docker-compose.dev.yml:19-20,132-155`). The K8s Deployment uses image `aappaa/main-service:develop`, envFrom secret `aappaa-secrets` plus configmap `backend-config-map`, liveness/readiness on `/actuator/health/*`, and limits 1Gi / 1 CPU (`Deployment/kustomize/base/backend-services/mainservice.yml:37-69`). Prometheus scrapes `main-service:8080` (`Deployment/observability/prometheus.yml:9-12`).

**crm-shared-lib** (`SL/pom.xml:14-26`)
- Multi-module pom `com.crm:sharedlib:0.3.0-SNAPSHOT`, "Shared-lib for APA-CRM project". Modules core, messaging, rbac. Boot 4.1.1 parent, Java 25 (`SL/pom.xml:7-31`), but messaging and rbac override the compiler to 21 (`SL/messaging/pom.xml:15-19`, `SL/rbac/pom.xml:14-18`). There is no README. CI runs `mvn clean verify` on JDK 25 for main/snapshot (`SL/.github/workflows/project-ci.yml`).
- **core** (`SL/core/pom.xml:15-53`: webmvc, validation, feign-core, spring-data-commons, jakarta.persistence). It provides at runtime:
  - Seven `@RestControllerAdvice` beans (all `HIGHEST_PRECEDENCE`) mapping exceptions to `CrmErrorResponse`: BadRequest→400, Validation (`MethodArgumentNotValidException`)→400, Unauthorized→401, Forbidden→403, NotFound→404, Conflict→409, RuntimeException/CrmException→500 (`core/exception/advice/*.java`, e.g. `BadRequestControllerAdvice.java:11-23`).
  - `@Facade` stereotype (= `@Component`) (`core/annotations/Facade.java:8-13`).
  - `FeignClientConfig` (not `@Configuration`; used per client) registering `ErrorDecoderImpl`. The decoder turns a remote `CrmErrorResponse` back into the same typed exception through `HttpExceptionFactory` (`core/feign/FeignClientConfig.java:6-12`, `ErrorDecoderImpl.java:28-38`, `core/exception/factory/HttpExceptionFactory.java:24-40`).
  - Header names `User-Id`, `User-Login`, `Organization-Id`, `User-Permissions` (`core/consts/CrmHeaders.java:5-11`).
  - Enums `Resource` {ALL, USERS, USERS_ROLES, ORGANIZATIONS, ROLES, INVITATIONS, FILES, TASKS, TASK_PRIORITIES, TASK_STATUSES} and `Action` {ALL, CREATE, READ, UPDATE, DELETE} (`core/enums/Resource.java:8-19`, `Action.java:8-14`).
  - Shared DTOs for the Auth and Main internal APIs (`core/dto/**`), a JPA Criteria filter framework `BaseCriteriaFilter` (`core/filter/**`), `JwtUtils` (Bearer parsing only), `OrganizationIdExtractor` (regex `^/api/organizations/(\d+)…` or the `Organization-Id` header; servlet and WebFlux variants) (`core/utils/OrganizationIdExtractor.java:18-51`), and the `@AccessControlConstraint` validator that rejects resource ALL mixed with other resources (`core/validation/validators/AccessControlConstraintValidator.java:21-41`).
- **messaging** (`SL/messaging/pom.xml:22-45`: amqp, jackson). It provides:
  - Abstract `BaseRabbitMQConfig` (services subclass it), defining the beans: Jackson `MessageConverter`, `CachingConnectionFactory` from `spring.rabbitmq.*`, a `ConsumerTagStrategy` that tags every consumer with the app name, a `RabbitTemplate` (as `AmqpTemplate`) and a `RabbitAdmin` (`messaging/config/BaseRabbitMQConfig.java:18-67`). It declares no queues itself.
  - `RabbitMQConstants`, the system-wide names of exchanges, queues and routing keys (`messaging/constants/RabbitMQConstants.java:5-39`).
  - Message DTOs `OrgCreatedMessage`, `OrgRootDirCreatedMessage`, `OrgUserRoleChangedMessage` (field `rolesIs`, a typo), `SendInvitationOfOrganizationMessage`, `SendPasswordByEmailMessage`, `SendVerificationCodeByEmailMessage`, `TaskReminderMessage` (`messaging/dto/amqp/*.java`).
  - `@Service MessagingService`, which sends `CrmNotification{recipient{id,type USER|ORGANIZATION}, message{title,code,message,details}}` via `rabbitTemplate.convertAndSend("notification-service.send-message", …)`, i.e. through the default exchange to that queue. Errors are logged and swallowed (`messaging/service/MessagingService.java:58-83`).
- **rbac** (`SL/rbac/pom.xml:20-96`: web, aop, aspectjweaver, core). It provides:
  - `@RequiresPermission(resource, action)` (`rbac/annotation/RequiresPermission.java:8-17`).
  - `@Aspect @Component PermissionControlAspect`. It reads the `User-Permissions` request header, deserializes it and throws `ForbiddenException("Insufficient permissions")` unless a matching resource/action pair exists, with ALL acting as a wildcard (`rbac/aspectj/PermissionControlAspect.java:19-52`).
  - `UserPermissionHeaderSerializer`, whose header format is `RESOURCE:ACT1,ACT2;…` (`rbac/utils/UserPermissionHeaderSerializer.java:17-72`).
  - `CacheConstants.USER_PERMISSION_FORMAT_KEY = "organization:%d:user:%d:permissions"`, used by Auth-Service's Redis cache (`rbac/constants/CacheConstants.java:5`; `Auth-Service/src/main/java/com/crm/auth/cache/UserPermissionRepository.java:71`), and `JwtConstants` userId/login claims.
  - The rbac module does no JWT validation itself. It trusts headers set by the gateway (`Discovery-and-Gateway/api-gateway/.../filters/AuthorizeAndCheckAccessFilter.java:53-55`).

## 2. Inbound

**REST.** All controllers delegate to a `@Facade`. Most endpoints carry `@RequiresOrganizationMembership` (Main's own aspect: a DB check that User-Id is a member of the org, else 403; `main/aspect/OrganizationMembershipControlAspect.java:26-37`) plus `@RequiresPermission` (rbac aspect). Below, **[M,P=RES:ACT]** marks both. User-Id comes from the header.

- `OrganizationController` `/api/organizations` (`main/controller/OrganizationController.java:22-74`):
  - GET `` lists the user's orgs.
  - GET `/{id}/preview` returns a preview.
  - POST `` creates an org.
  - GET `/{id}` gets an org (no membership/permission check).
  - PUT `/{id}` updates it [M,P=ORGANIZATIONS:UPDATE].
- `OrganizationFileController` (`OrganizationFileController.java:26-72`). These link file IDs to the org; they hold metadata only.
  - GET `/{id}/files/{fileId}` [M,P=FILES:READ].
  - GET `/{id}/files/root` [M,P=FILES:READ].
  - POST `/{id}/files/{fileId}` registers a file in the org [M,P=FILES:CREATE].
  - DELETE `/{id}/files/{fileId}` [M,P=FILES:DELETE].
- `OrganizationInvitationController` (`OrganizationInvitationController.java:27-61`):
  - GET `/invitations/{invitationId}`.
  - POST `/{id}/invitations` invites a user [M,P=INVITATIONS:CREATE].
  - PATCH `/invitations/{invId}/accept`.
  - PATCH `/invitations/{invId}/decline`.
- `OrganizationRolesController` (`OrganizationRolesController.java:33-99`):
  - GET `/{id}/roles` (deprecated) [M,P=ROLES:READ].
  - POST `/{id}/roles` [M,P=ROLES:CREATE].
  - GET `/{id}/roles/filter` (paged) [M,P=ROLES:READ].
  - PUT `/{id}/roles/{roleId}` [M,P=ROLES:UPDATE].
  - DELETE `/{id}/roles/{roleId}` [M,P=ROLES:DELETE].
- `OrganizationUserRoleController` (`OrganizationUserRoleController.java:24-47`):
  - PUT `/{id}/users/{userId}/roles/{roleId}` assigns a role [M,P=USERS_ROLES:CREATE].
  - DELETE on the same path unassigns it [M,P=USERS_ROLES:DELETE].
- `OrganizationUsersController` (`OrganizationUsersController.java:27-55`):
  - GET `/{id}/users/filter` [M,P=USERS:READ].
  - DELETE `/{id}/users/{userId}` removes a member [M,P=USERS:DELETE].
- `TaskController` (`TaskController.java:30-85`):
  - GET `/{id}/tasks/{taskId}` [M,P=TASKS:READ].
  - GET `/{id}/tasks/filter` [M,P=TASKS:READ].
  - POST `/{id}/tasks` [M,P=TASKS:CREATE].
  - PUT `/{id}/tasks/{taskId}` [M,P=TASKS:UPDATE].
  - DELETE `/{id}/tasks/{taskId}` [M,P=**TASKS:READ**; looks like a bug, line 78].
- `TaskPriorityController` (`TaskPriorityController.java:28-83`):
  - GET `/{id}/tasks/priorities`.
  - GET `/{id}/tasks/priorities/{pid}`.
  - POST `/{id}/tasks/priorities` (201).
  - PUT `/{id}/tasks/priorities/{pid}`.
  - DELETE `/{id}/tasks/priorities/{pid}` (204).
  - All [M,P=TASK_PRIORITIES:*].
- `TaskStatusController` (`TaskStatusController.java:28-83`): the same five endpoints under `/{id}/tasks/statuses`, all [M,P=TASK_STATUSES:*].
- Internal `/api/internal/organizations` (not routed by the gateway; called service-to-service):
  - GET `/{id}/files/{fileId}/check` returns 204 if the file belongs to the org [M only] (`controller/internal/InternalOrganizationFilesController.java:16-32`). Caller: Gateway `OrganizationFilesClient` (`Discovery-and-Gateway/api-gateway/.../clients/OrganizationFilesClient.java:16-31`).
  - GET `/{id}/users/{userId}` returns `OrganizationUserRolesResponse{organizationId,userId,rolesId}` (`InternalOrganizationUserController.java:19-25`). Caller: Auth-Service `MainClient`, used by `UserPermissionExtractor:77`.
  - GET `/{id}/users/{userId}/exists` (`InternalOrganizationUserController.java:27-33`). Caller: Notification-Service `MainClient`, used by `websocket/StompAuthChannelInterceptor.java:85`.
- Total: 37 public endpoints and 3 internal ones.
- Actuator exposes health, info and prometheus, with liveness/readiness probes (`application.yml:7-25`).
- Gateway routing for MS (`Discovery-and-Gateway/api-gateway/src/main/java/com/crm/gateway/config/RouteConfig.java:38-64`):
  - Route `main-service-authorize` (AuthFilter = JWT only, sets User-Id/User-Login) covers `/api/organizations`, `/api/organizations/*`, `/api/organizations/invitations/**` for GET/POST/PATCH.
  - Route `main-service-check-access` (AuthorizeAndCheckAccessFilter, which also sets User-Permissions) covers the org sub-paths.
  - `/api/organizations/*/preview` matches neither route (see Unknowns).

**Message listeners** (all `@RabbitListener`):
| Queue | Bound to | Payload | Handler |
|---|---|---|---|
| `main-service.create-defaut-task-priorities` | `main-service.events` / `org.created` | OrgCreatedMessage | `service/consumer/CreateTaskDefaultPrioritiesConsumer.java:30` creates Low/High/Medium from `app.tasks.priorities` (`application.yml:65-70`); on error `AmqpRejectAndDontRequeueException` |
| `main-service.create-defaut-task-statuses` | `main-service.events` / `org.created` | OrgCreatedMessage | `CreateTaskDefaultStatusesConsumer.java:30` creates Canceled/Done/Blocked/In Progress/To Do (`application.yml:71-87`) |
| `main-service.create-root-dir-for-organization` | `file-service.events` / `org.root-dir.created` (binding declared by File-Service, `File-Service/.../config/RabbitMQConfig.java:38-42`) | OrgRootDirCreatedMessage{organizationId,fileId} | `OrganizationRootDirCreatedConsumer.java:24` saves the root OrganizationFile |

**Scheduled jobs:** no `@Scheduled`. JobRunr runs a background job server named "Main-service" (`main/config/JobRunrConfig.java:29-34`; `application.yml:57-63`, dashboard disabled). It runs a one-off delayed `TaskReminderJobRequest` → `TaskReminderJobHandler`, job name "Task's reminder job" (`service/jobs/handlers/TaskReminderJobHandler.java:23-63`). Jobs are scheduled from `TaskProcessorService.createOrUpdateTaskReminder` via `BackgroundJobRequest.schedule(reminderAt, …)` and deleted on change or delete (`service/processor/TaskProcessorService.java:114-141`).

## 3. Outbound

**HTTP (Feign).** `AuthClient`: `@FeignClient(name="${app.clients.auth-service.name}")` = `auth-service`, resolved through Eureka/K8s discovery, using the shared `FeignClientConfig` (`main/feign/AuthClient.java:17-40`; `application.yml:97-99`). Endpoints:
- POST `/api/internal/roles` (create role).
- PUT `/api/internal/roles/{roleId}`.
- GET `/api/internal/roles?roleId=…`.
- POST `/api/internal/roles/filter`.
- DELETE `/api/internal/roles/{roleId}`.
- POST `/api/internal/users/filter`.
- GET `/api/internal/users/{userId}`.

These are wrapped by `RoleClientWrapper` and `UserClientWrapper` (`service/wrapper/*.java`). There is no client to File-Service or any other service, although the test props define `app.clients.file-service.name` (`src/test/resources/application-test.properties:15`).

**Messages published.** All except the last go to topic exchange `main-service.events` (durable; `config/RabbitMQConfig.java:19-25`). Main-Service also declares the target queues and bindings (`RabbitMQConfig.java:27-122`).
| Routing key | Payload | Queue(s) bound (consumer) | Producer |
|---|---|---|---|
| `org.created` | OrgCreatedMessage{organizationId,organizationName} | `file-service.create-organization-root-dir` (File-Service `CreateRootDirConsumer.java:28`), plus MS's own two default-priorities/statuses queues | `service/producer/OrganizationCreatedProducer.java:22` |
| `org-user.role-changes` | OrgUserRoleChangedMessage{organizationId,userId,rolesIs} | `auth-service.org-user-role-sync`, TTL 900000 ms (`application.yml:90-93`); consumed by Auth-Service `UpdateOrganizationUserPermission.java:19`, also declared there with the same TTL (`Auth-Service/.../config/RabbitMQConfig.java:37-42`) | `OrgUserRoleChangedProducer.java:42` |
| `org-invitation.created` | SendInvitationOfOrganizationMessage{email,invitationId,organizationName} | queue named `notification-service.send-invitation-of-organization` (the constant is called `…_ROUTING_KEY` but used as a queue name); Notification `SendInvitationOfOrganizationConsumer.java:27` | `InvitationCreatedProducer.java:25` |
| `org.task.remind` | TaskReminderMessage{taskId,title,emails,org id/name,status,priority,dueDate,createdAt} | `notification-service.task-reminder`; Notification `TaskReminderConsumer.java:27` | `TaskReminderProducer.java:39` |
| (default exchange) `notification-service.send-message` | CrmNotification | queue declared by Notification (`Notification-Service/.../config/RabbitMQConfig.java:33`); `SendMessageConsumer.java:20` | `MessagingService` (SL), called from InvitationProcessorService, TaskProcessorService and the two assignment services |

**Database.**
- PostgreSQL at `jdbc:postgresql://${main.datasource.host}:${port}/${database}`, filled from env MAIN_DATASOURCE_* by relaxed binding (`application.yml:42-45`). Postgres 17.5 in compose.
- No migration tool: `ddl-auto: update` (`application.yml:32-34`). JPA auditing is enabled (`config/JpaConfig.java`).
- Entities/tables (`main/persistance/entity/*.java`):
  - `Organization` (Long id; name, address, city, country, email, description).
  - `OrganizationUser` (UUID; org, userId: an Auth user id).
  - `OrganizationRole` (UUID; org, roleId: an Auth role id, roleType ADMIN/MEMBER/USER).
  - `OrganizationRoleUser` (join of role and org-user).
  - `OrganizationInvitation` (UUID; org, userId, invitorId, role, status, expiredAt).
  - `OrganizationFile` (UUID; fileId: a File-Service id, isRoot; unique (file_id, organization_id)).
  - `Task` (UUID; title, description, estimatedTime, status, priority, assignedTo, createdBy, dueDate, reminderAt, isReminded, reminderJobId, completedAt; 5 indexes).
  - `TaskPriority` and `TaskStatus` (Long id; name, color, [type], org).
- JobRunr stores its jobs in the same Postgres datasource through `PostgresStorageProvider` (`config/JobRunrConfig.java:16-22`). Tests use H2 (`application-test.properties:1-3`; `BaseIntegrationTest.java:41-48`).

**Infrastructure.**
- Eureka client: `defaultZone http://${discovery.host}:${discovery.port}/eureka/` (`application.yml:3-6`). Spring Cloud Kubernetes client is also on the classpath (`pom.xml:60-63`).
- RabbitMQ host/port/user/password come from env RABBIT_* (`application.yml:27-31`).
- Prometheus metrics with tag application=main-service.
- No Redis, mail, object storage, external SaaS, tracing or JWT validation in MS. Identity comes from gateway headers (`README.md:70`).
- No Spring profiles in main config. Only the `test` profile exists (H2, Eureka disabled, rabbit health off).

## 4. Components behind entry points
- Organization: `OrganizationFacade` → `OrganizationService` (OrganizationRepository), `OrganizationUserService`, and `OrganizationCreatorService`. Creation uses OrganizationRoleService, OrganizationUserService, OrganizationRoleUserService, RoleClientWrapper, OrgUserRoleChangedProducer and OrganizationCreatedProducer (`service/operation/OrganizationCreatorService.java:31-67`).
- Files: `OrganizationFileFacade` and `InternalOrganizationFilesFacade` → `OrganizationFileService` (OrganizationFileRepository). The root-dir consumer uses the same service.
- Invitations: `OrganizationInvitationFacade` → `InvitationProcessorService` (OrganizationInvitationService, UserClientWrapper, InvitationCreatedProducer, MessagingService) plus `OrganizationUserAssignmentService` (`facade/OrganizationInvitationFacade.java:23-62`).
- Roles: `OrganizationRolesFacade` → `OrganizationRoleAssignmentService` (RoleClientWrapper, OrgUserRoleChangedProducer), `OrganizationRoleFilterService` (Auth filter call) and `OrganizationRoleService`.
- User roles: `OrganizationUserRoleFacade` → `OrganizationUserRoleAssignmentService` (RoleClientWrapper, OrgUserRoleChangedProducer, MessagingService).
- Users: `OrganizationUsersFacade` → `OrganizationUsersFilterService` (UserClientWrapper → Auth `/users/filter`) and `OrganizationUserAssignmentService`.
- Tasks: `TaskFacade` → `TaskProcessorService` (TaskService, TaskStatusService, TaskPriorityService, TaskStatusUpdaterFactory with 5 strategy updaters, TaskMapper, MessagingService, JobRunr) and `TaskFilter` (SL `BaseCriteriaFilter`). `TaskPriorityFacade` and `TaskStatusFacade` → their services. Deletion is blocked if tasks use the priority or status (`TaskStatusService.java:62-70`).
- Internal users: `InternalOrganizationUserFacade` → OrganizationUserService and OrganizationRoleUserService.
- Shared beans used by many: `OrganizationService.getOrganizationOrThrowException` (nearly every facade and all 3 consumers), `OrganizationUserService` (also used by the membership aspect), `RoleClientWrapper`/`UserClientWrapper`, `OrgUserRoleChangedProducer` (4 callers), `MessagingService` (4 callers), and `TaskService` (the job handler too).

## 5. Cross-service flows (as coded)

**F1. Create organization.**
1. FE calls Gateway POST `/api/organizations` (JWT AuthFilter sets User-Id).
2. Gateway forwards to MS `OrganizationController.createOrganization`.
3. `OrganizationCreatorService.createOrganization` runs `@Transactional`:
   a. INSERT Organization.
   b. Feign POST auth-service `/api/internal/roles` "Admin" ALL:[ALL], isDeletable=false. MS INSERTs an OrganizationRole of type ADMIN.
   c. Same call for "Member" ALL:[READ], type MEMBER (`OrganizationCreatorService.java:69-102`; names from `application.yml:94-96`).
   d. INSERT OrganizationUser for the creator, then OrganizationRoleUser (admin).
   e. Async AMQP `main-service.events`/`org-user.role-changes` → Auth-Service (permission sync).
   f. Async `org.created` (`:62-64`).
4. Fan-out of `org.created`:
   - File-Service consumes `file-service.create-organization-root-dir`, creates the root dir and publishes `file-service.events`/`org.root-dir.created` (`File-Service/.../CreateRootDirConsumer.java:28-38`). MS consumes that on `main-service.create-root-dir-for-organization` and INSERTs OrganizationFile(isRoot=true).
   - MS also consumes its own `org.created` twice and INSERTs the 3 default TaskPriorities and 5 default TaskStatuses.

**F2. Invite → accept.**
1. POST `/api/organizations/{id}/invitations`: gateway check-access sets User-Permissions; then the membership aspect, then rbac INVITATIONS:CREATE.
2. Feign GET auth `/api/internal/users/{userId}` (email).
3. INSERT OrganizationInvitation PENDING, expiry now+259200 s (`OrganizationInvitationService.java:28-51`; `application.yml:88-89`).
4. AMQP `org-invitation.created` → Notification (invitation email). AMQP default-exchange `notification-service.send-message` → in-app message to the invitee (`InvitationProcessorService.java:67-82`).
5. PATCH `/api/organizations/invitations/{id}/accept`: validate (owner, not expired, not already handled), set ACCEPTED, Feign getUserById, then a send-message to the invitor (`:85-103`).
6. `OrganizationUserAssignmentService.addUserToOrganization`: INSERT OrganizationUser and RoleUser; AMQP `org-user.role-changes` → Auth; Feign getUserById and getRole; send-message to the ORGANIZATION recipient (`OrganizationUserAssignmentService.java:39-88`).

**F3. Task with reminder.**
1. POST `/{id}/tasks`: INSERT Task (status updater applied).
2. If `reminderAt` is set, JobRunr schedules `TaskReminderJobRequest` (persisted in Postgres).
3. If the assignee is not the creator, send-message "TASK_HAS_BEEN_ASSIGNED" to the assignee (`TaskProcessorService.java:47-76,121-151`).
4. At `reminderAt` the JobRunr worker runs `TaskReminderJobHandler`: Feign GET auth `/api/internal/users/{id}` for assignee and creator emails; AMQP `org.task.remind` → Notification `TaskReminderConsumer` (email); UPDATE task isReminded=true (`TaskReminderJobHandler.java:34-63`).

**F4. Role/permission sync (MS as provider).**
- Role assign/unassign/update/delete in MS publishes `org-user.role-changes` per affected user (`OrganizationRoleAssignmentService.java:42-79`; `OrganizationUserRoleAssignmentService.java:40-102`).
- Auth-Service, building permissions, calls MS sync GET `/api/internal/organizations/{o}/users/{u}` (`Auth-Service/.../service/operation/UserPermissionExtractor.java:77`).
- The Gateway checks file ownership through `/api/internal/.../files/{f}/check`. Notification checks membership through `/exists` on STOMP subscribe.

## 6. Decisions, risks, notes
- No ADRs or design docs in either repo. The README states that authorization is expected at the gateway (`MS/README.md:70`). Code confirms MS trusts the `User-Id` and `User-Permissions` headers without verifying a JWT (`PermissionControlAspect.java:29-31`). Anyone who can reach MS directly (or the `/api/internal/**` endpoints) can impersonate a user.
- AMQP events and Feign side effects happen **inside DB transactions, before commit**. Examples: `OrganizationCreatorService.java:46-64`, where the org consumers call `getOrganizationOrThrowException` and may run before commit, and the consumers' failures are not requeued; and the Auth roles created at step 3b are orphaned if the transaction rolls back. There is no outbox.
- `MessagingService` swallows send errors (`MessagingService.java:78-82`).
- `DELETE /tasks/{taskId}` is guarded by TASKS:READ (`TaskController.java:78`).
- `GET /api/organizations/{id}` has no membership check, and the gateway routes it with AuthFilter only (`OrganizationController.java:55-61`; `RouteConfig.java:39-44`).
- Production config logs SQL with bound parameters: `show-sql`, `logging.level.org.hibernate.orm.jdbc.bind: TRACE` (`application.yml:38,46-56`). `ddl-auto: update` with no migrations.
- No secrets in MS config; all credentials come from env, K8s secret `aappaa-secrets`.
- Shared-lib **version skew**: MS uses 0.3.0-SNAPSHOT, Auth 0.2.0, File 0.2.1, Notification 0.2.5 (each `pom.xml`, around lines 31-33). All are SNAPSHOTs from JitPack, so builds are not reproducible.
- Very new stack (Boot 4.1.1, Java 25) while the README says 21/3.x. JitPack dependency.
- SL code smells:
  - `PredicateFilterUtils.filterByDateRange`: the "to only" branch uses `greaterThan(from)` with a null `from` (`core/utils/PredicateFilterUtils.java:44-49`).
  - `AccessControlConstraintValidator` always adds the "ALL with others" message (`:37-40`).
  - Typos: `rolesIs`, `create-defaut-…` queue names.
- N+1 TODOs in the role services (`OrganizationRoleAssignmentService.java:51,74,89`). Membership check is uncached (TODO at `OrganizationMembershipControlAspect.java:31`).
- Tests: MS has 73 `@Test` methods in 17 classes. These are mostly SpringBootTest + RestAssured integration tests on H2, with RabbitMQ Testcontainers `rabbitmq:4.1-management` for consumer tests (`src/test/java/com/crm/main/BaseIntegrationTestWithRabbitMQ.java:13`). SL has 8 tests, only `UserPermissionHeaderSerializerTest`; core and messaging have none.

## 7. Unknowns
- `GET /api/organizations/{id}/preview` matches no gateway route path (`RouteConfig.java:39-61`). It is unclear whether it is reachable in production; the gateway analyst should confirm.
- Exactly which artifact JitPack serves for `com.github.APA-CRM:crm-shared-lib` (aggregate vs modules), and whether older lib versions contain the same constants. For example, Auth-Service uses its own `AUTH_TOPIC_EXCHANGE_NAME = "auth-service.event"` (`Auth-Service/.../constants/RabbitMQConstants.java:6`), which is not in SL 0.3.0.
- How Notification turns the `CrmNotification` with recipient ORGANIZATION into deliveries (fan-out to members?). That is out of scope here.
- Whether Kubernetes discovery or Eureka is active in K8s deploys: both are on the classpath, and the configmap contents were not read.
- Values of MAIN_DATASOURCE_* and RABBIT_* (env/secret only).
