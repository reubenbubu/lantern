# project-3 (MediaHub): user-, media- and subscription-service

Scope: mediahub-user-service (HEAD `c5698fa`, 2026-03-15), mediahub-media-service (`fab6a4c`, 2026-03-15), mediahub-subscription-service (`8a16d85`, 2026-03-15). Paths are relative to each repository; "US", "MS", "SS" abbreviate them and `…` stands for `src/main/java/com/mediahub/<service>_service`. Config from the config server is in `edge-config-deployment.md` §3.

Common to all three:
- Spring Boot **4.0.3**, Spring Cloud **2025.1.0**, Java 17 (`US/pom.xml:5-10,30-31`; `MS/pom.xml:5-10,30-31`; `SS/pom.xml:5-10,30-31`). Spring Data JPA, validation, `spring-cloud-starter-bootstrap`, `spring-boot-starter-webmvc`, config client, Eureka client, OpenFeign, actuator, Prometheus registry, PostgreSQL driver, Lombok (US `:36-83`, MS `:36-83`, SS `:36-87`). Image `eclipse-temurin:17-jre-alpine` with a pre-built `target/app.jar` (`*/Dockerfile:1-4`).
- **No Spring Security** in any of them; user-service has only `spring-security-crypto` for BCrypt (`US/pom.xml:93`). No endpoint checks a caller's identity or role.
- Responses wrapped in `ApiResponse{success,message,data,timestamp}` (e.g. `US/…/dto/ApiResponse.java:16-43`). A `@RestControllerAdvice` maps ResourceNotFound → 404, BadRequest → 400, DuplicateResource → 409, validation → 422 (US, SS) or 400 (MS), and **any other exception → 500** (`US/…/exception/GlobalExceptionHandler.java:15-51`; `MS/…/exception/GlobalExceptionHandler.java:19-56`; `SS/…/exception/GlobalExceptionHandler.java:15-51`). US and SS put `ex.getMessage()` in the 500 body (`US …:46-50`, `SS …:46-50`); MS logs it and returns a fixed message (`MS …:50-56`).
- Schemas by Hibernate `ddl-auto: update`; no migrations (`CR/*-service.yml:10-13`).
- Only the generated context-load test exists in each (`src/test/java/.../*ApplicationTests.java`).

## 1. user-service (`US`)

**Bootstrap.** `US/src/main/resources/bootstrap.yaml:1-11`: name `user-service`, config URI `${SPRING_CLOUD_CONFIG_URI:http://localhost:8888}`, `fail-fast: true`, 5 retries, profile `dev`. `US/src/main/resources/application.yaml:1-13` also sets a datasource `jdbc:postgresql://localhost:5432/user_db` with `postgres/postgres` defaults; the config server's `user-service.yml` (`mediahub_user` on `user-db`) takes precedence when it is reachable. `@EnableDiscoveryClient @EnableFeignClients` (`US/…/UserServiceApplication.java:8-11`). Port 8081 from config.

**User API** `/api/v1/users` (`US/…/controller/UserController.java:17-68`):
- `POST` create (:24-29) → `UserServiceImpl.createUser`: 409 if the email exists, BCrypt-hashes the password, **stores the role from the request body** (`UserRequestDto.role` is `@NotNull`, `dto/UserRequestDto.java:36-37`), saves (`service/impl/UserServiceImpl.java:27-43`). So anyone can create an ADMIN.
- `GET` all users (:31-35) returns every user's name, email and role (`UserServiceImpl.java:52-57`, `UserResponseDto.java:15-23`).
- `GET /{id}` (:37-41), `PUT /{id}` (:43-48; replaces name, email, password and role, `UserServiceImpl.java:59-76`), `DELETE /{id}` (:50-54; deletes only the user row, `UserServiceImpl.java:78-85`), `PATCH /{id}/role` (:56-61; `UserServiceImpl.java:87-95`), `GET /role/{role}` (:63-67).
- There is **no login, token or "current user" endpoint**; `BCryptPasswordEncoder` (`config/SecurityConfig.java:11-14`) is only used to hash.

**Watch history API** `/api/history` (`US/…/controller/WatchHistoryController.java:10-30`):
- `POST /watch/{userId}/{mediaId}` (:17-23) → `WatchHistoryServiceImpl.registerWatch` (`service/impl/WatchHistoryServiceImpl.java:18-36`): calls `mediaServiceClient.findById(mediaId)`; any exception becomes `RuntimeException("Media service error or media not found")` (:22-29); then saves `WatchHistory{userId, mediaId}` with `watchedAt = now` (:31-35, `domain/WatchHistory.java:14-33`). The user id is not checked.
- `GET /user/{userId}` (:25-29) → newest first (`WatchHistoryServiceImpl.java:38-41`, `repository/WatchHistoryRepository.java:12`). Returns the entity, not an `ApiResponse`.
- `/api/history/**` is not under any gateway route path (`/api/v1/users/**`); it is reachable through the gateway only via the discovery locator (`/USER-SERVICE/api/history/...`).

**Feign client** `MediaServiceClient` → service `media-service`, `GET /api/media/{id}` (`client/MediaServiceClient.java:7-11`). media-service serves `/api/v1/media/{id}` (`MS/…/controller/MediaController.java:21,45`), so **every call fails** (verification: media-service's catch-all handler answers the unmatched path with 500, not 404, `MS/…/exception/GlobalExceptionHandler.java:50-56`), Feign throws, and `registerWatch` always fails with 500 (`WatchHistoryServiceImpl.java:27-29` + `GlobalExceptionHandler.java:46-50`).

**Data** (database `mediahub_user`): `app_users` (id identity, first_name, last_name, unique email, BCrypt password, role USER|ADMIN default USER, created_at, updated_at; `domain/AppUser.java:8-56`, `domain/Role.java:3-6`) and `watch_history` (id, user_id, media_id, watched_at; `domain/WatchHistory.java:7-33`). Repositories `UserRepository` (`repository/UserRepository.java:11-21`) and `WatchHistoryRepository` (`:8-13`).

**README** (`US/README.md`): a task brief. Purpose: "user accounts, authentication data, roles, and … Viewing History" (:3-4); DB port `5433` (:9); asks for `POST /api/v1/users/register`, `GET /api/v1/users/email/{email}` and `/api/v1/users/{id}/history` (:45-51), none of which exist; the history API was built at `/api/history` instead, without the `progress` field (:33-39).

## 2. media-service (`MS`)

**Bootstrap.** `MS/src/main/resources/application.yaml:1-11`: name `media-service`, config URI `${SPRING_CLOUD_CONFIG_URI:http://localhost:8888}`, fail-fast, 5 retries, profile `dev` (in application.yaml, not bootstrap.yaml). `@EnableDiscoveryClient @EnableFeignClients` with no Feign client defined (`MediaServiceApplication.java:8-11`). Port 8082 from config.

**Media API** `/api/v1/media` (`MS/…/controller/MediaController.java:20-77`), messages in French:
- `POST` (:27-34) → `addMedia`: 409 if a media with the same title (case-insensitive) exists, then save (`service/impl/MediaServiceImpl.java:28-37`).
- `GET /search?title&genre` (:36-43) → title contains and/or genre, else all (`MediaServiceImpl.java:79-102`).
- `GET /{id}` (:45-51) → 404 if missing (`MediaServiceImpl.java:39-44`).
- `GET` (:53-59) → **a `Page`** of 10, newest first (`@PageableDefault(size = 10, sort = "createdAt", direction = DESC)`), `MediaServiceImpl.java:46-49`.
- `PUT /{id}` (:61-68; duplicate-title check, `MediaServiceImpl.java:51-68`), `DELETE /{id}` (:70-76; `MediaServiceImpl.java:70-77`).
- Validation on `MediaRequestDto` (title, description ≥ 10, releaseYear ≥ 1900, positive duration, genre, category, rating 0–10; `dto/request/MediaRequestDto.java:17-38`).
- `MediaServiceImpl` is `@Transactional(readOnly = true)` with write methods `@Transactional` (`:20-23,29,52,71`).

**Data** (database `mediahub_media`): table `medias` (id identity, title, description ≤ 2000, release_year, duration, genre, category, rating, created_at, updated_at; `domain/entity/Media.java:11-66`); enums `Genre` ACTION…HORROR (`domain/enums/Genre.java:3-11`), `Category` MOVIE, SERIES, DOCUMENTARY_FILM (`domain/enums/Category.java:3-7`). No video file, URL or thumbnail is stored: the service holds catalogue metadata only. `MediaRepository` (`repository/MediaRepository.java:10-25`), `MediaMapper` (`mapper/MediaMapper.java:8-43`).

**README** (`MS/README.md:3-4`): "the core content catalog … movies, TV shows, genres, categories"; it says the DB port is `5434` (:9) and recommends `/api/v1/media` endpoints (:41-48).

## 3. subscription-service (`SS`)

**Bootstrap.** `SS/src/main/resources/application.yaml:1-11`: name `subscription-service`, config URI, fail-fast, 5 retries, profile `dev`. Also depends on `spring-boot-starter-webflux` (`SS/pom.xml:48`) next to webmvc (`:52`), for WebClient. `@EnableDiscoveryClient @EnableFeignClients` (`SubscriptionServiceApplication.java:8-11`). Port 8083 from config.

**Subscription API** `/api/v1/subscriptions` (`SS/…/controller/SubscriptionController.java:14-62`), all return `ApiResponse` with HTTP 200:
- `POST` (:21-26) → `createSubscription` (`service/impl/SubscriptionServiceImpl.java:26-46`): calls `userClient.getUserById(userId)`; **any** exception (404, user-service down, timeout) becomes 404 "User not found" (:28-32). Builds the subscription with plan, **price and dates from the request**, status ACTIVE (:34-41), saves (:43). No check for an existing active subscription, the media, the dates, or a payment. Not `@Transactional`.
- `GET /{id}` (UUID; :28-33), `GET /user/{userId}` (:35-40), `PUT /{id}/cancel` → status CANCELLED (:42-47; `SubscriptionServiceImpl.java:66-77`).
- `GET /user/{userId}/active` (:49-54) → `hasActiveSubscription` = `findByUserIdAndStatus(userId, ACTIVE).isPresent()` (`SubscriptionServiceImpl.java:79-84`; `repository/SubscriptionRepository.java:15` returns `Optional`). The README calls this "**Crucial**: Verify if a user has an active subscription right now" (`SS/README.md:48`). `endDate` is not compared with now, and nothing ever sets EXPIRED or PENDING_PAYMENT. With **two ACTIVE rows** for a user (possible, since create does not check), the `Optional` query throws `IncorrectResultSizeDataAccessException` → 500 (Spring Data behaviour; not run).
- `GET /media-info/{mediaId}` (:56-61) → `getMediaInfo`: `webClientBuilder.build().get().uri("http://media-service/api/v1/media/{id}")…bodyToMono(Object.class).block()` (`SubscriptionServiceImpl.java:86-94`) through the `@LoadBalanced WebClient.Builder` (`config/WebClientConfig.java:11-15`). The media-service `ApiResponse` is returned wrapped inside another `ApiResponse`.

**Feign clients.**
- `UserClient` → `user-service`, path `/api/v1/users`, `GET /{id}` (`client/UserClient.java:8-13`): matches `UserController` (`US/…/UserController.java:18,37`).
- `MediaClient` → `media-service`, `/api/v1/media/{id}` (`client/MediaClient.java:8-13`): **declared but never used** (no reference outside its file).

**Data** (database `mediahub_subscription`): table `subscriptions` (id UUID, user_id not null, plan_type BASIC|PREMIUM|PRO, status ACTIVE|CANCELLED|EXPIRED|PENDING_PAYMENT, price BigDecimal, start_date, end_date, created_at, updated_at; `domain/Subscription.java:10-52`, `domain/Plan.java:3-7`, `domain/SubscriptionStatus.java:3-8`). `user_id` refers to user-service's user by value; nothing removes subscriptions when a user is deleted.

**README** (`SS/README.md:3-4`): "manages user billing plans, payment status, and verification of access rights … whether a user is allowed to view premium media". Plans with features (:18-22); DB port `5435` (:9). There is no payment provider or payment code anywhere in the system.
