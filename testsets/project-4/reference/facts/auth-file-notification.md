# project-4 (APA-CRM) ground truth: Auth-Service, File-Service, Notification-Service

Read by hand from the working trees under `project-4/` (HEADs: Auth-Service 6cb4eda, File-Service 413caa5, Notification-Service 0fc0353, crm-shared-lib 442bbbb, Main-Service 0b59f25). Nothing was built or run.

Path abbreviations used in citations:
- `A/` = `Auth-Service/src/main/java/com/crm/auth/`
- `F/` = `File-Service/src/main/java/com/crm/file/`
- `N/` = `Notification-Service/src/main/java/com/crm/notification/notification_service/`
- `RMQC` = `crm-shared-lib/messaging/src/main/java/com/crm/sharedlib/messaging/constants/RabbitMQConstants.java`
- `MainRMQ` = `Main-Service/src/main/java/com/crm/main/config/RabbitMQConfig.java`

Shared-lib note: every service pulls `com.github.APA-CRM:crm-shared-lib` from JitPack at a git tag, not from the HEAD checkout. Auth uses 0.2.0-SNAPSHOT (`Auth-Service/pom.xml:30-33`), File uses 0.2.1-SNAPSHOT (`File-Service/pom.xml:~30`), Notification uses 0.2.5-SNAPSHOT (`Notification-Service/pom.xml:30-31`) and Main uses 0.3.0-SNAPSHOT (`Main-Service/pom.xml:32-33`). The shared-lib HEAD is 0.3.0-SNAPSHOT on Boot 4.1.1 and Java 25 (`crm-shared-lib/pom.xml:10,16,31`). The pinned tags 0.2.0 and 0.2.5 are on Boot 3.4.3 and Java 21 (`git show <tag>:pom.xml`). The queue and routing-key string values used below are the same at all of these tags (checked 0.2.0, 0.2.1 and 0.2.5). The artifact is the whole multi-module lib (core, messaging, rbac); the services do not pick single modules.

---

## 1. Auth-Service

### Identity
- Purpose: the central identity provider. It handles sign-up and sign-in, issues JWT access and refresh tokens, and manages users, roles and RBAC, password reset by email, and sessions (`Auth-Service/README.md:3-14`).
- `spring.application.name=auth-service`, port 8080 (`Auth-Service/src/main/resources/application.properties:1-2`).
- Spring Boot 3.5.8, Spring Cloud 2025.0.0, Java 21 (`Auth-Service/pom.xml:9,17-21`). Libraries: jjwt 0.12.6, yauaa 8.1.0 (user-agent parsing), MapStruct 1.6.3, spring-security-crypto (only BCrypt; there is no Spring Security filter chain) (`A/config/SecurityConfig.java:13`).
- Dockerfile: a multi-stage build, `eclipse-temurin:21-jdk-jammy` to `21-jre-jammy`, runs `mvnw clean package` (`Auth-Service/Dockerfile:1-19`). CI pushes `Dockerfile.local`, a JRE image that copies `target/*.jar`, as `aappaa/auth-service:<branch|latest>` (`Auth-Service/.github/workflows/backend-ci-cd.yml:5-6,48-55`).
- Shared-lib use: `core` (DTOs, exceptions, `CrmHeaders`, `FeignClientConfig`, `@Facade`), `messaging` (`BaseRabbitMQConfig`, queue constants, message DTOs) and `rbac` (`UserPermission`, `JwtPayload`, `JwtConstants`, `CacheConstants`).

### Inbound REST (no auth inside the service; it trusts the `User-Id` header set by the gateway)
- **AuthController** `/api/auth` (`A/controller/AuthController.java:16`). The gateway routes it without auth (`Discovery-and-Gateway/api-gateway/.../RouteConfig.java:79-84`).
  - `POST /sign-in` (:22) checks the credentials and returns the access JWT and a refresh token. Reads the `User-Agent` header.
  - `POST /sign-up` (:33) creates a user, optionally with a generated password that is emailed, and returns tokens.
  - `POST /refresh` (:44) rotates the refresh token and issues a new JWT.
  - `POST /logout` (:55) deletes the refresh token. Returns 204.
- **RestorePasswordRequestController** `/api/restore-password-request` (`A/controller/RestorePasswordRequestController.java:17`), public.
  - `POST /` (:23) creates a restore request and emails a 4-digit code.
  - `PATCH /{requestId}/resend` (:32) resends the code.
  - `PUT /{requestId}/restore-password` (:39) checks the code and returns tokens (a login).
- **UserController** `/api/users` (`A/controller/UserController.java:16`). The gateway applies `authFilter`, which validates the JWT and sets `User-Id` (`RouteConfig.java:46-51`).
  - `GET /me` (:22), `GET ?fullName=` (:30) to search by full name, `GET /{userId}` (:37), `PATCH /{id}` (:44) to update a profile, `PATCH /{id}/update-password` (:53).
- **UserSessionController** `/api/users/sessions` (`A/controller/UserSessionController.java:15`). Sessions are refresh tokens.
  - `GET` (:21) lists sessions. `DELETE /{id}` (:28) ends one. `DELETE` (:37) ends all.
- **InternalAuthController** `/api/internal/auth` (`A/controller/internal/InternalAuthController.java:13`).
  - `POST /authorize` (:19) validates an access token and returns `AuthResponse{id, login}`. Called by Notification-Service (see the mismatch in section 6).
- **InternalRolesController** `/api/internal/roles` (`A/controller/internal/InternalRolesController.java:17`). Called by Main-Service through `Main-Service/src/main/java/com/crm/main/feign/AuthClient.java:17-32`.
  - `GET ?roleId=` (:23), `POST` (:30) to create a role, `POST /filter` (:39) for a paged search, `PUT /{roleId}` (:48), `DELETE /{roleId}` (:58).
- **InternalUsersController** `/api/internal/users` (`A/controller/internal/InternalUsersController.java:16`). Called by Main (`AuthClient.java:35,38`) and by the gateway composite `UsersClient` (`.../composite/clients/UsersClient.java:14`).
  - `POST /filter` (:22), `GET /{userId}` (:31), `GET ?userId=` (:38) for light users by ids.
- **InternalUserPermissionsController** `/api/internal` (`A/controller/internal/InternalUserPermissionsController.java:12`).
  - `GET /users/{userId}/organizations/{organizationId}/persmissions` (:18). The typo "persmissions" is in the code. It returns the user's `UserPermission` for an organization.
- Actuator: health, info, prometheus (`application.properties:14-19`).

### Inbound messaging
- `@RabbitListener(queues="auth-service.org-user-role-sync")` in `A/service/consumer/UpdateOrganizationUserPermission.java:19`. It receives `OrgUserRoleChangedEvent{organizationId,userId,rolesIs}` and recomputes and caches the permissions in Redis.
  - Queue name: `RMQC:15`. Auth declares it durable with a TTL of 900000 ms (`A/config/RabbitMQConfig.java:19,37-41`, `application.properties:45`).
  - The producer is Main-Service. It publishes to exchange `main-service.events` with key `org-user.role-changes` (`Main-Service/.../producer/OrgUserRoleChangedProducer.java:42`). The binding is `MainRMQ:110-113`.
- No scheduled jobs.

### Outbound
- **HTTP (Feign)**: `MainClient`, `@FeignClient("${app.clients.main-service.name}")` resolves to `main-service` through Eureka (`A/feign/MainClient.java:9`, `application.properties:34`). The call is `GET /api/internal/organizations/{organizationId}/users/{userId}` and returns `OrganizationUserRolesResponse` (role ids) (`MainClient.java:12`). It is used only when there is a permission cache miss (`A/service/operation/UserPermissionExtractor.java:77`).
- **RabbitMQ publish**: topic exchange `auth-service.event`, declared by Auth (`A/constants/RabbitMQConstants.java:5`, `A/config/RabbitMQConfig.java:29-34`). This constant is local to Auth, not in the shared lib.
  - Routing key `user.random-password` with payload `SendPasswordByEmailMessage{email,password}` (`A/service/producer/SendPasswordToEmailProducer.java:20`). Auth binds it to queue `notification-service.send-password` (`A/config/RabbitMQConfig.java:50-55`, `RMQC:9`).
  - Routing key `user.restore-password` with payload `SendVerificationCodeByEmailMessage{email,verificationCode}` (`A/service/producer/SendVerificationCodeProducer.java:22`). Auth binds it to queue `notification-service.send-verification-code` (`A/config/RabbitMQConfig.java:44-46,59-64`, `RMQC:11`).
  - JSON converter and connection factory come from the shared `BaseRabbitMQConfig`.
- **DB**: PostgreSQL at `jdbc:postgresql://${auth.datasource.host}:${port}/${database}` (`application.properties:6-8`).
  - Schema is managed by `ddl-auto=update` (:9). There is no Flyway or Liquibase.
  - Entities: `User` (table `user_`: login, email, password (BCrypt), first/last/full name, phone, aboutYourself) (`A/persistance/entity/User.java:18-48`); `Role` (name, isDeletable, eager `AccessControl` list) (`Role.java:21-39`); `AccessControl` (resource and action enums from the shared lib); `RefreshToken` (token, deviceInfo, expiredAt, ManyToOne User) (`RefreshToken.java:19-39`); `PasswordRestoreRequest` (OneToOne User, verificationCode, attemptsCount, createAt). There is a JPA repository for each.
  - Tests use H2 (`src/test/resources/application-test.properties:1`).
- **Redis**: `spring.data.redis.*` (`application.properties:29-32`).
  - Permission cache key `organization:%d:user:%d:permissions` (`crm-shared-lib/rbac/.../CacheConstants.java:5`), with TTL 36000 s (`application.properties:41`, `A/cache/UserPermissionRepository.java:27,40`).
  - Failure marker `<key>:failure` with TTL 10 s (`:30,50`).
  - Distributed lock through `RedisLockRegistry("lock")` (`A/config/RedisConfig.java:50`).
  - The API gateway reads the same cache first (`Discovery-and-Gateway/api-gateway/.../service/UserPermissionService.java:18-20`).
- **JWT issuing**: HMAC (HS*) through jjwt with a Base64 secret from `app.token.signing.key=${auth.token.signing.key}` (`application.properties:35`, `A/service/JwtService.java:23,67-69`).
  - Claims: `userId`, `login`, iat and exp (`JwtService.java:29-35`). Expiry is 30 minutes (`application.properties:38`).
  - There is no JWKS. The gateway validates tokens locally with the same shared secret (`api-gateway/.../application.properties:17`, `.../service/JwtService.java:24,43-46`).
  - The refresh token is an opaque 50-character secure string stored in the DB with expiry 7200 minutes, about 5 days (`application.properties:36-37`, `A/service/RefreshTokenService.java:29,108-113`).
- **Infrastructure**: Eureka client (`application.properties:4`), the spring-cloud-kubernetes client (`pom.xml:126-129`), Micrometer Prometheus. No tracing. No Spring profiles; everything is configured by properties placeholders that Deployment supplies as environment variables (`Deployment/docker-compose.dev.yml:100-120`).

### Components
- AuthController → `AuthFacade` → `AuthenticationService` (`UserService`, `UserCreatorService`, `JwtService`, `DeviceInfoService` (yauaa), `RefreshTokenService`). Refresh goes through `RefreshTokenProvider` (`A/service/operation/RefreshTokenProvider.java`).
- RestorePassword → `RestorePasswordRequestFacade` → `PasswordRecoveryService` → `PasswordRestoreRequestService` and `SendVerificationCodeProducer`.
- User/UserSession → `UserFacade` / `UserSessionFacade` → `UserService` / `RefreshTokenService`.
- Internal roles and users → `InternalRoleFacade` / `InternalUsersFacade` → `RoleService`, `UserService` and criteria filters (`A/filter/**`).
- Internal permissions → `InternalUserPermissionsFacade` → `UserPermissionExtractor` (cache, then lock, then MainClient, then `UserPermissionSaver`) → `UserPermissionService` → `cache/UserPermissionRepository` (Redis).
- The Rabbit consumer goes to `UserPermissionSaver` → `RoleService` and `AccessControlMapper` → Redis.

---

## 2. File-Service

### Identity
- Purpose: stores file metadata, file content and a directory tree; provides REST endpoints to upload, download, update and delete (`File-Service/README.md:3,25`).
- `spring.application.name=file-service`, port 8080 (`File-Service/src/main/resources/application.properties:1-2`).
- Spring Boot 3.5.8, Spring Cloud 2025.0.0, Java 21 (`File-Service/pom.xml`, parent and properties). The Dockerfile is identical to Auth's (diffed). CI image is `aappaa/file-service` (`.github/workflows/backend-ci-cd.yml:5`).
- Shared-lib use: `core` (exceptions, `@Facade`, enums `Resource.FILES` and `Action`), `rbac` (`@RequiresPermission` with `PermissionControlAspect`), `messaging` (`BaseRabbitMQConfig`, constants, `OrgCreatedMessage`, `OrgRootDirCreatedMessage`).
- No Feign and no Redis.

### Inbound REST
- **FileController** `/api/files` (`F/controller/FileController.java:21`). Each endpoint has `@RequiresPermission(FILES, …)`. The aspect reads the `User-Permissions` header that the gateway sets and throws 403 "Insufficient permissions" when the permission is missing (`crm-shared-lib/rbac/.../PermissionControlAspect.java:29,43`).
  - `GET /{fileId}` (:27, READ) returns metadata and children.
  - `GET /{fileId}/content` (:33, READ) downloads the bytes as an attachment with the mime type from the extension (`F/facade/FileFacade.java:~36-52`).
  - `POST /` (:39, CREATE) takes a multipart `CreateFileRequest{name,fileType FILE|DIRECTORY,parentFileId,content}` and creates a file or directory.
  - `PATCH /{fileId}` (:45, UPDATE) updates the name, parent or content.
  - `DELETE /{fileId}?forceDelete=false` (:54, DELETE). A non-empty directory needs `forceDelete=true`.
- Gateway routes: GET/PATCH `/api/files/**` pass through `checkAccessFilter` and `organizationFilesFilter`. The second filter calls Main-Service `GET /api/internal/organizations/{orgId}/files/{fileId}/check` to check that the file belongs to the organization. DELETE/POST pass through `checkAccessFilter` only (`RouteConfig.java:65-78`, `.../clients/OrganizationFilesClient.java:16,29`).
- The README lists `POST /api/internal/files/default` (`README.md:83`). **That endpoint does not exist in the code.** Root directories are created over RabbitMQ instead.

### Inbound messaging
- `@RabbitListener(queues="file-service.create-organization-root-dir")` (`F/service/consumer/CreateRootDirConsumer.java:28`, `RMQC:25`). Payload `OrgCreatedMessage{organizationId,organizationName}`.
  - The producer is Main-Service, on exchange `main-service.events` with key `org.created` (`Main-Service/.../producer/OrganizationCreatedProducer.java:22`). The binding is `MainRMQ:86-89`.
  - The same key also feeds Main's own default task status and priority queues (`MainRMQ:94-105`).
- No scheduled jobs.

### Outbound
- **RabbitMQ publish**: topic exchange `file-service.events` (`RMQC:7`, declared durable in `F/config/RabbitMQConfig.java:17-20`). Routing key `org.root-dir.created` (`RMQC:35`) with payload `OrgRootDirCreatedMessage{organizationId,fileId}` (`CreateRootDirConsumer.java:38`).
  - File-Service declares and binds the reply queue `main-service.create-root-dir-for-organization` (`F/config/RabbitMQConfig.java:31-42`, `RMQC:27`).
  - Main consumes it (`Main-Service/.../consumer/OrganizationRootDirCreatedConsumer.java:24`).
- **DB**: PostgreSQL `jdbc:postgresql://${file.datasource.host}:…/${file.datasource.database}` with `ddl-auto=update` (`application.properties:6-9`). No migrations. Tests use H2.
  - `FileMetadata`: UUID id, name, fullName, fileType, fileExtension, ManyToOne `parentFile`, eager `childrenFiles` with cascade ALL (`F/persistance/entity/FileMetadata.java`).
  - `FileContent`: shares the metadata id through `@MapsId` and holds `@Lob byte[] content` (`F/persistance/entity/FileContent.java:31-32`).
- **Object storage: none.** File bytes are stored in PostgreSQL as LOBs. There is no S3, MinIO or disk.
- Limits: `app.file.max-size=15728640` (15 MiB), enforced by `FileSizeConstraintValidator` (`F/constraint/validator/FileSizeConstraintValidator.java:18`). Multipart allows up to 36 MB (`application.properties:31-33`).
- Infrastructure: Eureka, the k8s client and Prometheus. No profiles.
- The service has no notion of organization. `FileMetadata` has no organization or owner column. Organization scoping is only the root directory id kept by Main plus the gateway check.

### Components
- FileController → `FileFacade` → `FileService` (`F/service/FileService.java`: createFile :58, createDefaultDirectory :79, updateFile :89, deleteFile :110) → `FileMetadataRepository`, `FileContentRepository`, `FileExtensionFactory` and `FileMetadataMapper`.
- CreateRootDirConsumer → `FileService.createDefaultDirectory("Root " + orgName)` (`CreateRootDirConsumer.java:25,33`, `application.properties:25`), then `RabbitTemplate`.

---

## 3. Notification-Service

### Identity
- Purpose: real-time notifications over STOMP WebSockets to users and organizations (`Notification-Service/README.md:5-12`). In the code it also sends **all transactional emails** over SMTP with Thymeleaf templates.
- `spring.application.name=notification-service`, port 8080 (`Notification-Service/src/main/resources/application.properties:1-2`).
- Spring Boot 3.5.8, Java 21 (`pom.xml:8,20`). Starters: web, websocket, amqp, mail, thymeleaf, openfeign, eureka, k8s client, actuator and Prometheus (`pom.xml:36-80`). The README says Java 17+ (`README.md:26`), which is stale.
- The Dockerfile is identical to Auth's. CI image is `aappaa/notification-service`.
- Shared-lib use: `messaging` (`BaseRabbitMQConfig`, constants, message DTOs, `CrmNotification`) and `core` (`AuthorizationRequest`, `AuthResponse`, `UserExistsInOrganizationResponse`, `JwtUtils`, exceptions, `FeignClientConfig`).
- No DB and no Redis.

### Inbound
- **REST: none.** No `@RestController` exists. The README's `/api/test/notifications/**` endpoints (`README.md:67-71`) are not in the code.
- **WebSocket/STOMP**: endpoint `/ws-notifications`, plain and with SockJS, with allowed origins `*` (`N/config/WebSocketsConfig.java:24-28`).
  - Simple broker `/topic`, application prefix `/app`, user prefix `/user` (:39-41).
  - The gateway route `/ws-notifications/**` goes to `lb:ws://notification-service` without the auth filter (`RouteConfig.java:34-37`).
  - `StompAuthChannelInterceptor` (`N/websocket/StompAuthChannelInterceptor.java`) works as follows:
    - CONNECT: reads `Authorization: Bearer` and calls Auth to authorize it (:50-55). The principal becomes the userId. The frame is dropped on Unauthorized.
    - SUBSCRIBE to `/topic/organizations/{id}/notifications`: calls Main to check membership (:85).
    - SEND or MESSAGE from a client: rejected with 403 (:44).
- **RabbitMQ listeners** (queues declared durable in `N/config/RabbitMQConfig.java:17-48`):

| Queue (constant) | Listener | Payload | Producer → exchange / key |
|---|---|---|---|
| `notification-service.send-password` (`RMQC:9`) | `PasswordSenderConsumer.java:24` | `SendPasswordByEmailMessage` | Auth → `auth-service.event` / `user.random-password` |
| `notification-service.send-verification-code` (`RMQC:11`) | `VerificationCodeSenderConsumer.java:23` | `SendVerificationCodeByEmailMessage` | Auth → `auth-service.event` / `user.restore-password` |
| `notification-service.send-invitation-of-organization` (`RMQC:37`; the queue is named with the *routing-key* constant) | `SendInvitationOfOrganizationConsumer.java:27` | `SendInvitationOfOrganizationMessage{email,invitationId,organizationName}` | Main → `main-service.events` / `org-invitation.created` (`InvitationCreatedProducer.java:25`, `MainRMQ:118-121`) |
| `notification-service.task-reminder` (`RMQC:21`) | `TaskReminderConsumer.java:27` | `TaskReminderMessage{taskId,title,emails[],org,status,priority,dueDate}` | Main → `main-service.events` / `org.task.remind` (`TaskReminderProducer.java:39`, `MainRMQ:79-81`), from Main's `TaskReminderJobHandler` |
| `notification-service.send-message` (`RMQC:23`) | `SendMessageConsumer.java:20` | `CrmNotification{recipient{id,type USER/ORGANIZATION},message{title,code,message,details}}` | Main, through the shared `MessagingService`, sent to the **default exchange** with routing key = queue name (`crm-shared-lib/messaging/.../MessagingService.java:73`). Callers: `InvitationProcessorService`, `TaskProcessorService`, `OrganizationUser(Role)AssignmentService` |

- Failures on the send-message queue throw `AmqpRejectAndDontRequeueException` (`SendMessageConsumer.java`). The email consumers rethrow a `RuntimeException` on a `MessagingException` (`N/service/email/EmailService.java`). No DLQ is declared.
- No scheduled jobs.

### Outbound
- **SMTP**: `smtp.gmail.com:587` with STARTTLS and auth, credentials `${smtp.username}`/`${smtp.password}` (`application.properties:10-15`). Sent through `JavaMailSender` with HTML bodies (`EmailService.java:35`). A list of recipients means one email each (:21).
  - Templates: `src/main/resources/templates/{passwordEmailTemplate,verificationCodeEmailTemplate,invitationEmailTemplate,taskReminderEmailTemplate}.html`.
  - Links are built from `app.frontend.url=${frontend.url}` (`application.properties:6`).
  - Task due dates are formatted in `Europe/Kyiv` (`N/service/email/TemplateBuilder.java:68`).
- **WebSocket push**:
  - User messages go to `convertAndSendToUser(userId, "/topic/notifications")`, which a client receives at `/user/topic/notifications` (`N/service/NotificationSender.java:17,42`). The README says `/user/{userId}/queue/notifications`, which does not match the code.
  - Organization messages go to `/topic/organizations/{orgId}/notifications` (:18,56).
  - The broker is in memory (simple broker), so it does not scale across replicas.
- **HTTP (Feign)**, both through Eureka names from `application.properties:7-8`:
  - `AuthClient` → `auth-service` `GET /api/internal/auth/authorize` with an `AuthorizationRequest` body (`N/feign/AuthClient.java:10-13`).
  - `MainClient` → `main-service` `GET /api/internal/organizations/{organizationId}/users/{userId}/exists` (`N/feign/MainClient.java:9-12`). The endpoint is at `Main-Service/.../InternalOrganizationUserController.java:27`.
- **Infrastructure**: Eureka, k8s client, Prometheus. No profiles.

### Components
- Each email consumer goes to `TemplateBuilder` (Thymeleaf `TemplateEngine`), then `EmailService` (`JavaMailSender`).
- `SendMessageConsumer` → `NotificationSender` → `SimpMessagingTemplate`.
- `StompAuthChannelInterceptor` → `AuthClientWrapper` → `AuthClient`, and `MainClient`.

---

## 4. Notable cross-service flows

**F1. Sign-in and authenticated call (sync, HTTP).**
1. The frontend sends `POST /api/auth/sign-in` to the gateway (no filter), which routes it to Auth (`RouteConfig.java:79-84`).
2. `UserService.validateAndGetUserForSignInRequest` checks the BCrypt password (`A/service/AuthenticationService.java:38`). It saves a `RefreshToken` row with deviceInfo parsed by yauaa and deletes the user's expired tokens (:24-29). It signs an HS JWT with `userId` and `login` that expires after 30 minutes (:31).
3. It returns `{accessToken, BEARER, refreshToken}`.
4. Later requests, for example `/api/users/**`: the gateway validates the JWT with the shared secret and adds the `User-Id` and `User-Login` headers (`filters/AuthFilter.java:38-39`). Auth trusts the header (`UserController.java:22-28`).

**F2. Organization-scoped permission resolution (sync and cache).**
1. For `/api/organizations/{id}/**` or `/api/files/**`, the gateway runs `AuthorizeAndCheckAccessFilter`. It extracts the organization id and gets the `UserPermission`: first from Redis (key `organization:{o}:user:{u}:permissions`), otherwise from Auth over WebClient (`UserPermissionService.java:18-20`).
2. On a cache miss, Auth's `UserPermissionExtractor` takes a Redis lock (5 s) and checks the 10 s failure marker. It calls Main `GET /api/internal/organizations/{o}/users/{u}` for the role ids, maps the roles to resource and action pairs, and writes them to Redis with TTL 10 h (`UserPermissionExtractor.java:29-87`).
3. The gateway serializes the permissions into the `User-Permissions` header (`AuthorizeAndCheckAccessFilter.java:53-55`). File-Service's `@RequiresPermission` aspect checks that header.
4. Async invalidation: when roles change, Main publishes `org-user.role-changes` on `main-service.events`. Auth's queue `auth-service.org-user-role-sync` recomputes and overwrites the cache entry.

**F3. Generated password and password reset email (async, AMQP, then SMTP).**
1. If sign-up has `generatePassword=true`, Auth generates a 15-character password, saves the user and publishes `{email,password}` to `auth-service.event` with key `user.random-password` (`A/service/operation/UserCreatorService.java:34-40`).
2. That message reaches queue `notification-service.send-password`. `PasswordSenderConsumer` renders `passwordEmailTemplate` and sends it with Gmail SMTP, subject "🔐Your Generated Password!".
3. Password reset: `POST /api/restore-password-request` makes Auth save a `PasswordRestoreRequest` with a 4-digit code. Only one request per user is allowed within 3 h, otherwise 409. Auth then publishes `{email,code}` with key `user.restore-password` (`A/service/operation/PasswordRecoveryService.java:27-38`).
4. `VerificationCodeSenderConsumer` emails the code.
5. `PUT …/restore-password` checks the code (at most 5 attempts), deletes the request and returns tokens (a login). The `PUT` handler does not change the password itself; the client then calls `PATCH /api/users/{id}/update-password`, which is an inference from the API shape.

**F4. Organization creation, then root directory (async request and reply over AMQP).**
1. Main publishes `OrgCreatedMessage` to `main-service.events` with key `org.created`. It reaches `file-service.create-organization-root-dir`.
2. File-Service creates the directory row "Root <orgName>". It publishes `OrgRootDirCreatedMessage{organizationId,fileId}` to `file-service.events` with key `org.root-dir.created`, which reaches `main-service.create-root-dir-for-organization`.
3. Main stores the root directory id. That id is later used by Main's `/files/{fileId}/check`, which the gateway calls in its `OrganizationFilesFilter`.

**F5. Real-time notification (async AMQP, then WebSocket).**
1. Main's `MessagingService.sendMessageToUser/Organization` sends a `CrmNotification` to queue `notification-service.send-message` on the default exchange.
2. `NotificationSender` pushes it over STOMP to `/user/{id}/topic/notifications` or `/topic/organizations/{id}/notifications`.
3. The client's CONNECT is authorized through Auth `/api/internal/auth/authorize`. An organization topic SUBSCRIBE is checked with Main `/exists`.

Email-only flows from Main also exist: the invitation email (`org-invitation.created`) and the task reminder (`org.task.remind`).

---

## 5. Decisions, risks and notes
- **ADRs: none** in any of the three repos. Rationale lives only in the READMEs.
- **Shared HMAC secret**: Auth and the gateway share `auth.token.signing.key`. There is no asymmetric key or JWKS, so any service holding the key can mint tokens.
  - A test signing key is committed (`Auth-Service/src/test/resources/application-test.properties:15`).
  - Test RabbitMQ credentials `aappaa/aappaapass` are committed (`Notification-Service/src/test/resources/application-test.properties:8-9`).
  - Production secrets are placeholders.
- **Header trust**: Auth's `/api/users/**` and File-Service trust the `User-Id` and `User-Permissions` headers. They rely fully on the gateway. Internal `/api/internal/**` endpoints have no auth; only Auth's `/api/internal` paths are not routed by the gateway, and nothing else enforces that.
- **Feign method mismatch**: Notification declares `GET /api/internal/auth/authorize` with a body (`N/feign/AuthClient.java:13`), but Auth maps `POST` (`InternalAuthController.java:19`). It works only if the Feign HTTP client turns a GET with a body into a POST; the default JDK `HttpURLConnection` does. This is fragile.
- **Possible gateway path bug** (outside my scope; verify): the gateway's `UserPermissionClient` path is `/users/{userId}/organizations/{organizationId}/persmissions` with no `/api/internal` prefix (`Discovery-and-Gateway/api-gateway/.../clients/UserPermissionClient.java:12-13`), while Auth serves `/api/internal/users/…/persmissions`. A cache miss from the gateway may therefore get a 404.
- **Shared-lib version drift**: Auth 0.2.0, File 0.2.1, Notification 0.2.5, Main 0.3.0 (Boot 4, Java 25, Jackson 3).
  - The message DTO classes differ: Auth listens with `com.crm.sharedlib.core.dto.amqp.OrgUserRoleChangedEvent` (only in older tags), while Main sends `messaging.dto.amqp.OrgUserRoleChangedMessage`.
  - Deserialization depends on the converter inferring the type from the listener parameter. There is a risk of type-header mismatch.
- **Exchange naming inconsistency**: `auth-service.event` (singular, local constant marked TODO "move to shared-lib", `A/constants/RabbitMQConstants.java:3`) against `main-service.events` and `file-service.events`.
- **Queue declarations are duplicated**: Notification queues are declared by both Auth and Notification. The invitation queue is declared by Main and Notification. The Auth declaration of `send-verification-code` uses `new Queue(...)`, which is durable by default, so the declarations match.
- **Schemas** in Auth and File-Service are managed only by Hibernate `ddl-auto=update`. There are no migrations.
- **File bytes stored as PostgreSQL LOBs** and served from memory (`ByteArrayResource`). With eager `childrenFiles`, this is a scalability risk. Delete with `forceDelete` removes only direct children's content rows (`FileService.java:117-123`); grandchildren are left to orphan removal.
- **Deployment gap**: `Deployment/docker-compose.dev.yml:157-174` gives file-service no `RABBIT_*` environment variables, although `application.properties:21-24` requires them. `docker-compose.local.yml:193-210` does include them.
- **Logging**: `show-sql=true` and Hibernate bind parameters at TRACE in Auth and File (`Auth application.properties:11,21`). Bind TRACE logs parameter values, including password hashes and refresh tokens.
- **Plaintext password email**: a generated password is sent in plaintext through RabbitMQ and email.
- Notification's README and code disagree (Java 17, test REST endpoints, destination paths). File's README lists a non-existent internal endpoint.
- **Tests** (count of `@Test`): Auth 58 (controller integration tests with RestAssured, H2, and RabbitMQ/Redis Testcontainers); File 19; Notification 12 (consumers and a STOMP integration test). CI runs `mvn clean verify` on JDK 21 (`backend-ci-cd.yml:27-35`).
- Versions are current for the period: Boot 3.5.8 and Cloud 2025.0.0. The shared lib has already moved to Boot 4.

## 6. Unknowns
- Whether the gateway's permission fallback URL actually reaches Auth (see the path point above).
- Whether Main's 0.3.0 Jackson-3 JSON messages deserialize in Auth (0.2.0), File (0.2.1) and Notification (0.2.5). The `__TypeId__` header names classes that may not exist in the consumer.
- How `/api/internal/**` is protected in Kubernetes or ingress. I did not inspect `Deployment/kustomize` or the Helm chart beyond the service names.
- What triggers `TaskReminderJobHandler` in Main (a job framework), and where the frontend subscribes. Both are outside my scope.
- Whether the user's `PUT restore-password` is followed by a password change call (frontend not inspected).
