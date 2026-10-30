# Spring and Jakarta entry points: what the Spring extractor reads

Research for #383, the review of the Spring / Jakarta extractor in the milestone "Extractor revisit".

**Depth: high.** The extractor feeds entry points, components and security to every view, and this review sets what it reads next.

## Question

Which Spring and Jakarta EE constructs define a service's entry points, its components, its registrations with infrastructure and its security checks in practice? Which of them does the extractor not read yet? And why do the three Spring needs on project-3 and project-4 stay unmet?

## What we learned

**Service discovery**
- `@EnableDiscoveryClient` is optional. "`@EnableDiscoveryClient` is no longer required. You can put a `DiscoveryClient` implementation on the classpath" and the app registers. Registration can be turned off in four ways: `@EnableDiscoveryClient(autoRegister=false)`, `spring.cloud.service-registry.auto-registration.enabled=false`, `spring.cloud.discovery.enabled=false`, or turning off the blocking or reactive client alone. The implementations are Eureka, Consul and Zookeeper. https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html
- For Eureka, "by having `spring-cloud-starter-netflix-eureka-client` on the classpath, your application automatically registers". The server is found through `eureka.client.serviceUrl.defaultZone`, which is case-sensitive: it is a map key, so it is not `default-zone`. `eureka.client.enabled=false` turns the client off. The server is the eureka-server starter plus `@EnableEurekaServer`. A server sets `registerWithEureka: false` and `fetchRegistry: false`. `@EnableEurekaClient` appears nowhere in the current docs. https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html
- Code2DFD, the best tool in the nine-tool comparison, takes the union of three signals. Its Eureka detector finds the server by `@EnableEurekaServer`. It draws a client → server flow for every service where the keyword `EnableEurekaClient`, `EnableDiscoveryClient` or `spring-cloud-starter-netflix-eureka-client` appears, or whose config has a Eureka URL (`eureka_connected`). It also has detectors for `@EnableConfigServer`, `@EnableAdminServer` (and `spring.boot.admin.url`) and Consul. Each flow is traced to the line where the signal was found. https://github.com/tuhh-softsec/code2DFD (`technology_specific_extractors/eureka/eur_entry.py`, `core/parse_files.py`)

**Endpoints and other entry points**
- `@GetMapping` and its siblings are composed `@RequestMapping` annotations. If an element carries several `@RequestMapping`s, including composed ones, "only the first mapping will be used". `@HttpExchange` on an interface that a `@RestController` implements is "supported as an alternative to `@RequestMapping` for server side handling". Handlers can also be registered in code with `registerMapping`. Spring 7 adds a `version` attribute for API versions. https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html
- Functional endpoints are `RouterFunction<?>` beans. They are built with `route().GET(path, handler::m)`, `path(...)` and `nest(...)`, with `filter` and `before` to guard them. `RouterFunctionMapping` "detects all `RouterFunction<?>` beans" and checks their routes in declaration order. https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html
- On the client side, `@HttpExchange` interfaces become proxies through `HttpServiceProxyFactory`. Spring 7 adds `@ImportHttpServices(group=..., types=...)`, groups configured per host, and `HttpServiceGroupConfigurer`. https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- At runtime the actuator `mappings` endpoint lists `dispatcherServlets`, `servletFilters`, `servlets` and `dispatcherHandlers`. Each handler is a `handlerMethod` (class, name, descriptor) or a `handlerFunction`, with its patterns and methods. So filters and functional routes are first-class entry points to Spring itself. https://docs.spring.io/spring-boot/api/rest/actuator/mappings.html
- JobRunr has more creation forms than the extractor reads:
  - `BackgroundJob.enqueue(() -> svc.m())`
  - `BackgroundJob.<Svc>enqueue(x -> x.m())`
  - `BackgroundJobRequest.enqueue(new Req())`
  - an injected `JobScheduler` or `JobRequestScheduler`
  - the `aJob().withName(..).withJobRequest(..)` builder passed to `create`
  - stream forms for bulk jobs

  https://www.jobrunr.io/en/documentation/background-methods/enqueueing-jobs
- ShedLock "is not and will never be full-fledged scheduler, it's just a lock". `@SchedulerLock` wraps an existing `@Scheduled` method in an AOP proxy. It needs a lock store (JDBC, Redis, Mongo, ZooKeeper…), which is a data dependency. https://github.com/lukas-krecan/ShedLock
- `@ApplicationModuleListener` in Spring Modulith is `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener`. `@Externalized` event types are published to Kafka, AMQP, JMS or a Spring Messaging channel. https://docs.spring.io/spring-modulith/reference/events.html

**Security checks**
- Method security is off until `@EnableMethodSecurity`. The docs say Spring Boot's security starter "does not activate method-level authorization by default". `@Secured` needs `securedEnabled = true`, and `@RolesAllowed` needs `jsr250Enabled = true`. Method security supports meta-annotations (`@IsAdmin` carrying `@PreAuthorize`) and templated ones. A class-level annotation applies to every method, and a method-level one overrides it. If a class inherits conflicting annotations from two interfaces, startup fails. Unannotated methods are not secured. Pointcut-based rules (`AspectJExpressionPointcut`, `<protect-pointcut>`) also exist. https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html (read in part: the claims above are from the part read)

**How other tools find these**
- In the nine-tool comparison (arXiv 2412.08352), Code2DFD scored best, with F1 0.86 (precision 0.93, recall 0.80). Four tools combined reached 0.91. ContextMap finds only Feign and `@HttpExchange` connections. The authors' lessons:
  - Deployment files give precision.
  - Recall needs "deeper source analysis", namely annotations, imports and dependencies.
  - Heuristics tied to a project layout cause false positives.

  The ground truth for endpoints is `@RequestMapping`, its variants and `@RepositoryRestResource`. https://arxiv.org/html/2412.08352v1
- jQAssistant's Spring plugin labels components through `ANNOTATED_BY|OF_TYPE*`, which follows meta-annotations. Its `spring-mvc` rules cover only `@RestController` and `@ControllerAdvice`, with no request mappings. Its security rule only labels `@EnableWebSecurity` and `@EnableMethodSecurity`-style classes as configuration. It also has rules for Feign, Batch and Modulith. https://github.com/jqassistant-plugin/jqassistant-spring-plugin (`META-INF/jqassistant-rules/*.xml`)
- The Cerny et al. review puts service discovery in the "operation view", next to containerization and monitoring. It shows MicroDepGraph drawing a `discovery_microservice` node. It also lists Prophet, which reads Spring and Enterprise Java component constructs. https://arxiv.org/abs/2207.02988
- ModARO (2026) points out that earlier static dependency-graph recovery works only for Java Spring. It links services through observable traits (host, port, path) and resolves them after aggregation, which is the same idea as Lantern's linker. https://arxiv.org/abs/2602.08166

## Evidence from the test sets (project-0 to project-4)

Counts below are from a grep of `*.java` files.

- **Absent from all five sets:** `@HttpExchange`, `RouterFunction`, GraphQL, gRPC, RSocket `@MessageMapping`, Spring Integration, Spring Batch, ShedLock, `@EventListener` and `@TransactionalEventListener`, Modulith, `HandlerInterceptor`, `HandlerMethodArgumentResolver`, JAX-RS `@Path`, OpenAPI-generator interfaces, `@Secured` and `@RolesAllowed`.
- **Discovery:**
  - `@EnableDiscoveryClient` appears in project-3 five times (user, media, subscription, gateway, config-server) and in project-4 once (`Main-Service/.../MainServiceApplication.java:12`).
  - project-4's Auth-, File- and Notification-Service register through the eureka-client starter alone (for example `File-Service/pom.xml:81`, with `eureka.client.serviceUrl.defaultZone` in `application.properties:4`).
  - `@EnableEurekaServer` is in project-3 `DiscoveryServerApplication.java:8` and project-4 `eureka-service`. `@EnableConfigServer` is in project-3 `ConfigServerApplication.java:9`.
  - project-3 services take their Eureka URL from the config repo (`mediahub-config-repo/application.yml:1-4`) and from compose environment variables.
  - project-4's kustomize dev overlay turns the Eureka client off.
- **Method security:**
  - `@PreAuthorize` appears 3 times in project-1, on methods.
  - It appears 10 times in project-2, on classes (for example `InventoryController.java:27`) and on methods.
  - `@EnableMethodSecurity` is in project-1's shared `ResourceServerSecurityAutoConfiguration.java:40` and in each project-2 service.
  - `authorizeHttpRequests` and `authorizeExchange` appear in project-1 and project-2.
  - The coverage maps count these needs as met, but only because a `bean` or `stereotype` fact covers the same lines. Nothing reads the rule.
- **Property gates:** project-2 has 13 `@ConditionalOnProperty` gates on scheduled jobs, outbox relays, consumers and the choice of email or SMS adapter, for example `LocalSmsAdapter.java:14` and `TicketHoldExpiryJob.java:13`.
- **Aspects:** project-4 has `@Aspect` in `crm-shared-lib/rbac/.../PermissionControlAspect.java:19-23` (`@Before("@annotation(requiresPermission)")`) and in Main-Service's `OrganizationMembershipControlAspect`.

**The three misses** (baseline coverage report of #394, all "partial", "only java there"):
1. **project-3 `UserServiceApplication.java:9`** (and media, subscription). The extractor reads nothing of `@EnableDiscoveryClient`. `main-class` cites the `@SpringBootApplication` line (8), not line 9.
2. **project-4 `TaskProcessorService.java:137`** (`BackgroundJobRequest.schedule(at, new TaskReminderJobRequest(id))`). The extractor understands this call. It is the `scheduledBy` attribute of the `background-job` fact on `TaskReminderJobHandler.run`. But that fact's evidence is the handler, so no Spring fact sits on the calling line. This is an evidence gap, not a reading gap.
3. **project-4 `FileController.java:28`** (`@RequiresPermission`). What enforces it is the AspectJ aspect `PermissionControlAspect` above, not an interceptor or argument resolver. The extractor already records `aspect` and 66 `ADVISES` relations (ADR 0079). Their evidence is the advice annotation in crm-shared-lib, and the endpoint fact cites `@GetMapping` on line 27, so nothing covers line 28.

## What the extractor misses today

From `SpringExtractor.java` (VERSION 4) and ADRs 0035 and 0079:

1. **Registrations.** It does not read `@EnableDiscoveryClient` or its `autoRegister`, `@EnableEurekaServer`, `@EnableConfigServer` or `@EnableAdminServer`. (`DeployableUnits` knows the Eureka server only from its starter.)
2. **Where a job is scheduled.** It has no fact at the scheduling call, only the `scheduledBy` attribute.
3. **More JobRunr forms.** `JOBRUNR_SCHEDULERS` holds only `BackgroundJobRequest` and `JobRequestScheduler`. `BackgroundJob` and `JobScheduler` lambda jobs and `aJob()` builders are not followed, which ADR 0035 already lists.
4. **Security checks.** It does not read `@PreAuthorize`, `@PostAuthorize`, `@Secured`, `@RolesAllowed` or `@PermitAll` (directly or through meta-annotations, at class or method level), or whether `@EnableMethodSecurity` turns them on. Nor does it read a custom guard annotation as a check on the method it sits on: ADVISES links the aspect to the method but cites only the aspect.
5. **`@Conditional*` gates**, above all `@ConditionalOnProperty`.
6. **Constructs absent from the sets:** `RouterFunction` beans, server-side `@HttpExchange` controllers, `@EventListener`, `@TransactionalEventListener` and `@ApplicationModuleListener`, ShedLock, GraphQL, gRPC, RSocket, Integration, Batch, and `@ApplicationPath` and sub-resource locators (both already excluded by ADR 0035).

## Options

- **A. Close the three misses, narrowly.**
  - A `discovery-client` fact on the main class at `@EnableDiscoveryClient`.
  - A `SCHEDULES` relation from the calling method to the job method, with the call's lines as evidence.
  - A second ADVISES-style fact on the advised method, at the annotation the pointcut matched.
  - Cheap, and it meets coverage. It leaves `@PreAuthorize`, starter-only registration and property gates unread, which the sets show are common.
- **B. A to the full extent of what the sets show.**
  - Registrations: the client and server facts above, plus `autoRegister`.
  - Access checks: an `access-check` fact on each guarded method or class from three sources: the method-security annotations (meta-annotations followed, class level applied to methods, and recorded as `enabled: false` when no `@EnableMethodSecurity` or flag in the module turns them on), aspect-matched annotations, and HTTP matchers.
  - Property gates: a `property-gate` fact for `@ConditionalOnProperty`, beside `profile-gate`.
  - JobRunr: `BackgroundJob` and `JobScheduler` lambdas followed where the expression holds the invoked method.
  - Medium cost. Every rule has evidence in project-1, -2 or -4.
- **C. Relax the coverage match** so an ADVISES fact also covers its target's lines. This changes the measure, not what is extracted, and ADR 0089 warns against it ("a diagnostic, not a target").
- **D. Also add RouterFunction, HttpExchange, events, Modulith, GraphQL and gRPC now.** This follows design principle 9, but no test set exercises them, so nothing would show the code is right. Adding project-0 cases first would.

## Recommendation

**Take B, trimmed; put D and the rest of B into `Later` with project-0 cases; reject C.**

Discovery should follow what Spring and Code2DFD do: registration is the union of the client starter (the Maven extractor's fact), `@EnableDiscoveryClient` and the client config, minus the switches that turn it off. The Spring extractor records only the code part: the annotation and its `autoRegister`, and the server annotations. The linker joins the three signals and draws the line to the unit with `@EnableEurekaServer` or the eureka-server starter. In project-4, three of the four services that register have no annotation, so the annotation alone must never be required.

For security, record an access check where it is written, and record separately what turns method security on, so the linker can tell an enforced check from a dead one. A `@PreAuthorize` that no `@EnableMethodSecurity` turns on is not a check. An aspect selected by `@annotation` applies exactly where that annotation sits.

**Decided for #383:**

- `discovery-client` (with `autoRegister`) and `infrastructure-server` (`eureka`, `config`, `admin`) on the class that carries the annotation, at its line.
- `method-security` on the class that turns it on, with the three flags and their defaults (`@EnableGlobalMethodSecurity` defaults `prePostEnabled` to false). `access-check` on each guarded method or bean class, meta-annotations followed. Whether a check is enforced is the linker's: it needs the unit's classes, which the extractor does not group.
- `advised-by` on the advised method, at the annotation an aspect's pointcut selects through `@annotation`, `@within` or `@target`, following named pointcuts. This is the same link as `ADVISES` (ADR 0079), seen from the method; `ADVISES` is unchanged.
- `SCHEDULES` from the method that schedules a job to the job method, at the scheduling call.
- `property-gate` for `@ConditionalOnProperty`, beside `profile-gate`.
- Not read, as no development set has them: access-check annotations on interfaces, and Boot 3.5's repeated `@ConditionalOnProperty` and `@ConditionalOnBooleanProperty`.
- Deferred to `Later`, each with a project-0 case: HTTP security matchers as access checks; JobRunr `BackgroundJob` and `JobScheduler` lambdas and the `aJob()` builder; RouterFunction, server-side `@HttpExchange`, events and Modulith, GraphQL and gRPC.

The quality-bar line the research suggests (a security check is shown only where something enforces it) belongs with the work that first draws access checks, not with this extraction-only change.

## Sources read in full

- Spring Cloud Commons, Common Abstractions: https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html
- Spring Cloud Netflix reference: https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html
- JobRunr, Enqueueing jobs: https://www.jobrunr.io/en/documentation/background-methods/enqueueing-jobs
- Spring Framework, Mapping Requests: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html
- Spring Framework, Functional Endpoints: https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html
- Spring Framework, REST Clients and HTTP Service clients: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring Boot actuator `mappings` API: https://docs.spring.io/spring-boot/api/rest/actuator/mappings.html
- Spring Modulith, Application Events: https://docs.spring.io/spring-modulith/reference/events.html
- ShedLock README: https://github.com/lukas-krecan/ShedLock
- Schneider et al., Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications, arXiv 2412.08352: https://arxiv.org/html/2412.08352v1
- Manglaras et al., Distributed Architecture Reconstruction of Polyglot and Multi-Repository Microservice Projects, arXiv 2602.08166: https://arxiv.org/abs/2602.08166
- Cerny et al., Microservice Architecture Reconstruction and Visualization Techniques: A Review, arXiv 2207.02988 (body read in full, reference list in part): https://arxiv.org/abs/2207.02988
- Code2DFD source, Eureka detector `eur_entry.py`: https://github.com/tuhh-softsec/code2DFD
- jQAssistant Spring plugin rules `spring-mvc.xml` and `spring-security.xml`: https://github.com/jqassistant-plugin/jqassistant-spring-plugin

## Sources not read or read only in part (carry no weight)

- Spring Security, Method Security: the first 100k of 122k characters (https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- Code2DFD's Consul, config, admin and HTTP-security detectors and `parse_files.py`, and the jQAssistant `spring-component`, `spring-boot`, `spring-batch` and `spring-modulith` rules: read in part
- jQAssistant manual (https://jqassistant.github.io/jqassistant/current/): first half only, with no Spring section
- MicroDepGraph README (https://github.com/clowee/MicroDepGraph): says nothing of its method
- Not read:
  - Prophet
  - Bushong et al. (CLOSER 2022, ASE 2021)
  - Spring Security `authorizeHttpRequests` docs
  - Spring for GraphQL, gRPC, RSocket, Spring Integration, Spring Batch, Quartz and db-scheduler docs
  - JobRunr `@Job` and recurring-job pages
  - Spring Cloud Kubernetes, Consul and Zookeeper discovery docs
