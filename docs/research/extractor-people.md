# People in the code: what shows a human user, and where to cite it

Research for #396 (People extractor review, milestone "Extractor revisit"). It tests the plan in `docs/adr/0074-people-from-the-code.md` (with its amendments for #267 and #365) and `lantern-extract/.../people/PeopleExtractor.java` (VERSION 2) against the bar's rule that people come only from human-facing evidence (`docs/quality-bar.md`, "What the code can and cannot tell").

**Depth: high.** The note decides which patterns the extractor reads, which ones it must refuse, and at which line each is cited. That is a choice between approaches (roles as actors, one generic user, or account-qualified people), not a framework detail. I read 16 sources in full or in their relevant sections: C4, Spring Security, Keycloak, RFC 6749, the Jakarta Annotations spec, Auth0, Code2DFD, UCRBench, Sun et al., REC-MVC, the jQAssistant Spring plugin's rules, JHipster's sample app and Spring PetClinic. I also grepped development sets project-0 to project-4 file by file. Held-out sets 5 to 7 were not opened.

## Question

What in a Java system's code reliably shows the people who use it? Candidates are security roles, user and account types, sign-up and invitation flows, GUIs and their screens, and notification recipients. What never shows a person? At which source line should a static extractor record each, so that the evidence cites the declaring line?

## What we learned

**What the authorities say a person is**

- C4 counts roles as people: "people (actors, roles, personas, named individuals, etc) use the software systems that we build" (https://c4model.com/abstractions). The system context shows "people (users, actors, roles, or personas)" and the software systems directly connected, for "everyone, technical and non-technical" (https://c4model.com/diagrams/system-context).
  - The bar is stricter than C4 on purpose: a role alone makes no person. C4 itself does not forbid one person per role.
- Keycloak's admin guide: "A role typically applies to one type of user. For example, an organization may include admin, user, manager, and employee roles." Groups are collections of users. Default roles "automatically assign user role mappings when a user is created". Composite roles make users inherit their parts. "Each OIDC client has a built-in service account", used with `grant_type=client_credentials`. User registration is a realm switch (Realm settings > Login > User Registration) that adds a Register link to the login page. Source: https://www.keycloak.org/docs/latest/server_admin/index.html (sections "Assigning permissions using roles and groups", "Using default roles", "Using a service account", "Enabling user registration").
- The realm representation fields that carry these facts are `registrationAllowed`, `defaultRole`, `clientRoles`, `serviceAccountsEnabled`, `serviceAccountClientId`, `publicClient` and `standardFlowEnabled` (https://www.keycloak.org/docs-api/latest/rest-api/index.html).
- OAuth 2.0 (RFC 6749 §1.3.4, §4.4): client credentials are used "when the client is acting on its own behalf". Only confidential clients may use them. Scopes are strings "defined by the authorization server" that limit an access request (https://www.rfc-editor.org/rfc/rfc6749).
  - So a client-credentials client is software, and a scope is what an app may do, not who a person is.
- Auth0 says the same. Scopes "define the specific actions applications can be allowed to do on a user's behalf". In machine-to-machine calls "no user exists" and "user consent is never requested". A user's role decides which scopes are granted (https://auth0.com/docs/get-started/apis/scopes, https://auth0.com/docs/get-started/apis/scopes/api-scopes).

**Spring Security: what the code says about roles**

- Roles are authorities with a prefix. "If there is an authorization rule that requires … a role of "USER", Spring Security will by default look for … "ROLE_USER"". `GrantedAuthorityDefaults` changes the prefix, and `RoleHierarchy` makes one role imply others, such as ADMIN ⇒ STAFF ⇒ USER ⇒ GUEST (https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html).
- `hasRole` is "a shortcut for hasAuthority that prefixes ROLE_". `@Secured` is legacy, and `@RolesAllowed`/`@PermitAll`/`@DenyAll` need `jsr250Enabled`. Class-level checks apply to all methods and method-level ones override them. Checks can come through **meta-annotations** (`@IsAdmin` carrying `@PreAuthorize("hasRole('ADMIN')")`), through **templated** meta-annotations (`@HasRole("ADMIN")` with `'{value}'`), and through **custom beans** (`@PreAuthorize("@authz.decide(#root)")`) whose roles are invisible in the annotation (https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html).
- A resource server turns a JWT's `scope`/`scp` into `SCOPE_` authorities, checked with `hasAuthority('SCOPE_messages')`. `setAuthoritiesClaimName("authorities")` and `setAuthorityPrefix("ROLE_")` can turn a claim into roles, so a JWT converter bean decides whether token values are roles or scopes (https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
- Anonymous: "there is no real conceptual difference between a user who is 'anonymously authenticated' and an unauthenticated user". `ROLE_ANONYMOUS` and `IS_AUTHENTICATED_ANONYMOUSLY` are access attributes, not a kind of user (https://docs.spring.io/spring-security/reference/servlet/authentication/anonymous.html).
- `httpSecurity.oauth2Login()` (and `spring.security.oauth2.client.registration.*`) gives a browser login page that redirects to Google and the like. That is a human sign-in, unlike `oauth2ResourceServer()`, which only checks tokens (https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html).

**Jakarta Annotations 3.0 (JSR-250)** (https://jakarta.ee/specifications/annotations/3.0/annotations-spec-3.0.pdf, §3.10 to §3.15)

- `@RolesAllowed` lists "security roles permitted to access method(s)", and a method-level value overrides the class-level one.
- `@DeclareRoles` declares roles "that could be tested (i.e., by calling isUserInRole)". It is a declaration site the plan does not read.
- `@RunAs` "defines the security role of the application during execution". It is the application's identity, never a person.

**Tools and papers**

- **Code2DFD** (Schneider et al., arXiv 2304.12769, https://arxiv.org/pdf/2304.12769) has one external-entity stereotype `user` ("Represents the human user of the application"). "A client is added to the DFD implicitly to represent the human user when either a gateway or web application is identified."
  - That is one generic user per gateway or web app, with no roles and no kinds. It is coarser than Lantern's plan, and its gateway trigger contradicts the bar (an API is for software).
- **REC-MVC** (Albassam 2021, https://tuengr.com/V12A/12A13T.pdf, §4.1) treats every RBAC role as a human actor: "each role r ∈ R … is initially part of the actors set A". Controllers "without an Authorize attribute are associated with the default User". It derives actor specialisation from permission subsets (Pᵢ ⊂ Pⱼ ⇒ aᵢ specialises aⱼ).
  - This is the "roles are people" approach the bar rejected in #365. Its subset rule is a usable way to order roles (admin above user), not to make people.
- **Sun et al., USENIX Security 2011** (https://www.usenix.org/legacy/events/sec11/tech/full_papers/Sun.pdf): "Most web applications have at least three types of roles: the role for administrators, the role for normal logged-in users and the role for public or anonymous users". "Intended accesses for each role are reflected in explicit links shown in the presentation layer". Their analysis still needs developers to specify the roles and entry points.
  - Screens (the presentation layer) are the reliable per-role signal. Role names come from a specification, not from inference.
- **UCRBench** (Xiao et al., arXiv 2512.13360, https://arxiv.org/pdf/2512.13360) recovered use cases from nine Java projects with LLMs. Actor accuracy was 62 to 78. "LLMs still struggle with inferring actors … frequently fail to identify more specific roles embedded in the codebase, such as admin, student, or passenger. The models tend to default to generic actors". Models also "confuse user-level and developer-level actors".
  - This supports deciding people statically from declared evidence, not leaving them to the LLM.
- **jQAssistant's Spring plugin** has a single `spring-security.xml` rule, which only labels `@EnableWebSecurity`-style configuration classes. It models no roles, users or actors (read from https://github.com/jqassistant-plugin/jqassistant-spring-plugin, `src/main/resources/META-INF/jqassistant-rules/spring-security.xml`). There is no prior art to copy there.

**Real projects**

- **JHipster sample app** (https://github.com/jhipster/jhipster-sample-app) shows the full human pattern in one place:
  - `AuthoritiesConstants` declares `ROLE_ADMIN`, `ROLE_USER` and `ROLE_ANONYMOUS`. The last is a false positive.
  - `AccountResource` has `@PostMapping("/register")`, which calls `mailService.sendActivationEmail(user)`, plus `/activate` and `/account/reset-password/*`.
  - `UserService.registerUser` grants `AuthoritiesConstants.USER` to every self-registered account. This is the role that names the self-registering person.
  - `SecurityConfiguration` uses `permitAll` on `/api/register` and `hasAuthority(ADMIN)` on both `/api/admin/**` and `/management/**`. ADMIN guards actuator endpoints and is still a human role, because Angular routes `admin` and `user-management` carry `data: { authorities: ['ROLE_ADMIN'] }` with `canActivate`.
  - Liquibase seeds `user.csv` with demo users `admin` and `user`. Seeds are not people.
  - `@PreAuthorize("hasAuthority(\"" + AuthoritiesConstants.ADMIN + "\")")` only works when constants are folded. Lantern's `ModuleStructure.Annotation` already folds them.
- **Spring PetClinic** (https://github.com/spring-projects/spring-petclinic) has no Spring Security, no roles and no sign-in. It does have Thymeleaf screens under `templates/owners`, `pets` and `vets`, served by `@Controller`s.
  - A server-rendered GUI is human-facing evidence (someone at the front desk uses it). `Owner` and `Vet` are domain records of people who never log in, not users. Today's extractor sees nothing here because it only reads JS routers.

## Evidence from the test sets (project-0 to project-4)

**project-0** has no frontend, no templates, no security annotations, no role types and no sign-up. It has no people, which is correct and must stay that way.

**project-1** (Enterprise-Spring-Microservices-Template)
- Sign-up and sign-in endpoints:
  - `profile-service/.../controller/RegistrationController.java:54` `@PostMapping("/register")` (class `@RequestMapping("/api/public")`, Javadoc "Public endpoints for user self-registration"), and `:71` `/confirm`. **Known miss.**
  - `bff/.../BffController.java:128` `/login` and `:151` `/login/success`.
- A trap: `keycloak-admin-service/.../AdminController.java:100-101` `@PostMapping("/users/register")` with `@PreAuthorize("hasRole('INTERNAL_SERVICE')")`. Its Javadoc says it is called by profile-service "using a client-credentials token". The linker's last-segment rule (`register`) would wrongly count it as a person's sign-up.
- Admin provisioning: `AdminProfileController.java:23-24` and `AdminController.java:42-43` (`POST /users` + `hasRole('ADMIN')`).
- Sign-in DSL: `bff/.../SecurityConfig.java:94` `.oauth2Login(...)`.
- Realm `keycloak/realm-config/realm-export.json`:
  - realm roles ADMIN, USER and INTERNAL_SERVICE
  - users `admin` (ADMIN), `user` (default roles), `service-account-internal-client` (INTERNAL_SERVICE) and `service-account-admin-service-client`
  - clients `admin-service-client` and `internal-client` (service accounts), `bff-client` (standard flow), `mobile-client` (public, standard flow) and `test-client`
  - `registrationAllowed` unset
- Frontend `angular-ui/src/app/app.routes.ts`: `login`, `register` and `confirm` at 5-16, and the guarded shell `canActivate: [authGuard]` at 17-40.
- Mail: profile-service `EmailService` (confirmation mail) and `AdminController.java:121` `executeActionsEmail`.
- Reference people: Web user, New visitor and Administrator. Administrator has "no admin screen", only the realm role ADMIN.

**project-2** (Event-Management-platform)
- `auth-service/.../api/AuthController.java:36-37` `@PostMapping("/register")` + `@Operation(summary = "Register an attendee account")`, and `:45` `/login`. **Known miss.**
- `auth-service/.../user/Role.java:3-8` `enum Role { ATTENDEE, ORGANIZER, EVENT_STAFF, ADMIN }`. It types `UserAccount.java:49-51` (`@Enumerated Set<Role> roles` on a persisted account with a password hash).
- Every new account gets the attendee role: `UserAccount.java:79` and `:84` `Set.of(Role.ATTENDEE)`, for password and OAuth sign-up.
- Social sign-in: `AuthSecurityConfiguration.java:96` `http.oauth2Login(...)`.
- 15+ `@PreAuthorize` role checks (`BookingController.java:30` ATTENDEE, `TicketTypeController.java:24` and `EventController.java:63..117` ORGANIZER/ADMIN, `TicketScanController.java:25` EVENT_STAFF...).
- Six role checks through the project's own `AuthenticatedActor.hasRole(String)`, which today's `securityMethod` ignores because the callee is not `springframework.security`:
  - `payment-service/.../PaymentController.java:11`
  - `RefundStateService.java:14` (3 calls)
  - `PaymentQueryService.java:14` (2 calls)
  - and `attendee-service/.../AuthenticatedActor.java:30-32`, with `isAdmin/isOrganizer/isEventStaff` as `roles.contains("…")`
- `BootstrapAdminInitializer` (an `ApplicationRunner` gated by `platform.auth.bootstrap-admin.enabled`) seeds an admin. It is a seed, not a person, but it shows ADMIN is held by a human.
- The frontend is a React page without a router (a landing page), so it contributes no routes.
- A naming trap: `attendee-service/.../domain/Registration.java` is an attendee's registration for an event, not an account sign-up.
- Reference people: Attendee ("Role ATTENDEE, granted to every self-registered account"), Organizer, Event staff and Administrator. That is four people, each named by a role.

**project-3** (mediahub)
- `mediahub-user-service/.../domain/Role.java:3-6` `enum Role { USER, ADMIN }`. **Known miss.** The enum is skipped by `concept()`, and no annotation checks it.
- It types `AppUser.java:33-35` (`@Enumerated Role role` on an `@Entity` with `email` and `password`). The default is `Role.USER` at `:48` (`@PrePersist`).
- No backend sign-up exists. The frontend posts to `/api/v1/auth/register` (`auth.service.ts:46`), which nothing implements.
- Routes: `app.routes.ts:13-23` (`login`, `register` and five `canActivate: [authGuard]` routes). `auth.service.ts:10` has `role: 'USER' | 'ADMIN'` and `:73-74` `isAdmin()`.
- Reference people: Viewer (USER) and Operator (ADMIN, an API client with no screen).

**project-4** (CRM)
- `Auth-Service/.../AuthController.java:22` `/sign-in` and `:33` `/sign-up`.
- Invitations: `OrganizationInvitationController.java:20-23` and `:34`.
- `enums/RoleType.java` `{ ADMIN, MEMBER, USER }` types `OrganizationRole.java:34-36`. Auth-Service's `Role` is an `@Entity`: roles are data, with `@RequiresPermission(resource, action)`.
- `Notification-Service/src/main/resources/templates/*.html` holds four Thymeleaf files: `invitationEmailTemplate`, `verificationCodeEmailTemplate`, `passwordEmailTemplate` and `taskReminderEmailTemplate`.
  - They are **email templates, not screens**. They show recipients (an invited user, a signing-up user, a member), sent from `EmailService.java:32` `helper.setTo(to)`.
- Frontend routes `app.routes.ts:32-37` with `authenticationGuard`.

**Counts across sets 0 to 4**
- Sign-up endpoints: 4 human-facing (p1:54, p2:36, p4:33, plus p1 `/confirm`) and 1 service-only (p1 `/users/register`).
- Role enums typing an account field: 3 (p2, p3, p4).
- Default role granted on account creation: 2 sets (p2 `UserAccount:79,84`, p3 `AppUser:48`).
- `oauth2Login()`: 2. `oauth2ResourceServer()`: 9. Custom `hasRole` call sites: 6.
- Role-guarded frontend routes (`data.roles`): 0. Login-guarded routes: 3 sets.
- `SCOPE_` checks: 0. Server-side GUI templates: 0 (4 email templates).

**A possible map issue.** project-2 `NotificationController.java:45` cites the method line; the `@PreAuthorize` is at `:44`. Check whether the role fact's evidence overlaps 45, and fix the citation (from the reference's facts) if not.

## What the extractor misses today

From reading `PeopleExtractor.java`, `PeopleLinker.java` and ADR 0074:

1. **No `person-evidence` fact at sign-up and sign-in lines.** Sign-up is found only in the linker, over the spring extractor's `endpoint` facts (`PeopleLinker.accountEndpoints`, `SIGN_UP`/`SIGN_IN` last segment). Coverage needs a `people` fact on the cited line (ADR 0089), so project-1 `RegistrationController:54` and project-2 `AuthController:36-37` are misses even when the linker sees them.
2. **"register" is taken at face value.** project-1 `/users/register` is guarded by a service-account-only role and called with client credentials, yet the segment rule counts it. Nothing checks whether a sign-up endpoint is open (`permitAll`, no access check, under a public prefix) or guarded by a role.
3. **Enums are dropped outright** (`concept()` returns for `ENUM`), and no role is read from types. So `enum Role` typing an account entity's field (p2, p3, p4) is invisible. This is project-3's miss.
4. **Role checks other than Spring's are missed.** These are:
   - a project's own `hasRole(String)` / `roles.contains("X")` (p2 payment-service)
   - `HttpServletRequest.isUserInRole`, Jakarta `SecurityContext.isCallerInRole`
   - `@DeclareRoles`
   - meta-annotations such as `@IsAdmin`, which the spring extractor's `access-check` already follows (ADR 0035, amendment for #383) and the people extractor does not
5. **The role granted on creation is not read.** `Set.of(Role.ATTENDEE)` and JHipster's `authorityRepository.findById(AuthoritiesConstants.USER)` name the self-registering person. That is how the reference names project-2's Attendee.
6. **Human sign-in DSLs are not recorded:** `formLogin()`, `oauth2Login()`, `saml2Login()`, `webAuthn()` and `oneTimeTokenLogin()`.
7. **From Keycloak, only `roles.realm` is read**, with service-account-only marking. Not read:
   - `defaultRole`/`default-roles-*` composites, which say what every new user gets
   - `registrationAllowed`, which means self-registration through Keycloak's own pages
   - realm users who are not service accounts, which show a role is held by humans
   - public, standard-flow clients, which are human-facing apps (`bff-client`, `mobile-client`); identity's `identity-client` fact already has this, and the linker can use it
8. **Frontend routes are recorded without their guards or role data** (`canActivate`, `data: { authorities | roles }`). Server-side GUIs (Thymeleaf, JSP or FreeMarker views returned by `@Controller`) are not read at all, which leaves PetClinic-style systems with no people.
9. **Notification recipients are not read.** Mail to a user's address (`setTo(user.getEmail())`, Keycloak `executeActionsEmail`) shows people receive messages. It is not enough to make a person, but it can describe one.
10. **The references split people by role more than the #365 amendment allows.** p2's four people and p1's Administrator are each a role held by human accounts. The amendment merges roles into the person whose screens or endpoints they guard, so it will under-split p2 into one "User" or "Attendee".

## Options

**A. Keep the plan; record only sign-up and sign-in endpoint lines as `account` evidence.**
- Fixes the p1 and p2 misses cheaply.
- Leaves p3, the `/users/register` false positive and the under-splitting.

**B. Account-qualified people (recommended).** The extractor records declared human-facing evidence, each at its declaring line, with the attributes the linker needs to refuse software. Roles become kinds of people only when the evidence ties them to human accounts:
- a role enum typing an account entity
- a role granted on self-registration or account creation
- a realm role held by non-service users or by the default role
- a route guarded by the role
- Pros:
  - meets the three misses
  - matches all four references (p2's four people; p1's Administrator through the realm user `admin` holding ADMIN; p3's Viewer through `AppUser.role`)
  - keeps the bar's rule that a role on its own makes no person
- Cons:
  - more patterns (enum-field linkage, default-role calls)
  - seeds become supporting evidence, which needs care

**C. Roles are actors (REC-MVC).**
- Simple and high recall.
- Contradicts the bar (#365): every API-only role, such as INTERNAL_SERVICE, would become a person unless realm data says otherwise. Rejected.

**D. One generic user per gateway or web app (Code2DFD).**
- Contradicts the bar: a gateway is not human-facing.
- Gives no kinds, so p2 scores 1 of 4. Rejected.

**E. Leave people to Investigate (LLM).**
- UCRBench shows LLMs default to generic actors and confuse developers with users. Keep the LLM to wording and proposals only. Rejected as the primary path.

## Decided for #396

The extractor records the evidence. Splitting people by role is linking, and it refines a rule the user decided in #365, so it waits for the user's answer on #437.

People extractor version 3 adds:

- **`account`** at the mapping annotation of a sign-up, sign-in, confirmation or password-reset endpoint of a controller.
  - The full path is the class prefix plus the method's path, and the evidence runs through an `@Operation` right after the mapping.
  - `action`, `summary` and the `guard` roles are recorded, also from a custom annotation that carries the check.
  - A Keycloak realm with `registrationAllowed: true` also gives an `account` fact (`sign-up`).
- **`role-type`** on each constant of an enum that types the roles of an account, at the constant's line.
  - An account is a kept type with a password field or that implements `UserDetails`.
  - The enum must type a field of that account named like a role field, or be named like a role itself. A collection of it qualifies when both hold, since the element type is erased.
  - Constants that name software (`SYSTEM`, `SERVICE`...) are skipped.
  - It is a kind of its own, so it makes no person until #437 decides.
- **`role`** is also read from:
  - `@DeclareRoles`
  - a custom annotation that carries `@PreAuthorize`, `@Secured` or `@RolesAllowed`
  - `hasRole`, `hasAnyRole`, `isUserInRole` and `isCallerInRole` calls on any receiver, the system's own included
- Browser sign-in DSLs (`formLogin`, `oauth2Login`...) are already the Identity extractor's `http-security` facts (#395), so they are not recorded twice.
- **Deferred:**
  - to #438: granted roles, realm default roles and which realm roles humans hold, route guards and roles, server-rendered screens, and mail recipients
  - to #437: the linker's use of all of it

## Recommendation (proposed by the research)

Option B. Concretely, the extractor (VERSION 3) adds these kinds, each cited at the line that declares it:

- **`account`** at the mapping annotation of a sign-up, sign-in, activation or password-reset endpoint.
  - Range: from the mapping line through an `@Operation` line right after it (p2's 36-37).
  - `action`: sign-up, sign-in, confirm or reset, read from the full path (class prefix plus method).
  - `summary`: the OpenAPI summary.
  - `guard`: the roles of any access check on it. The linker drops an endpoint guarded only by a role that is not human (p1 `/users/register`).
- **`sign-in`** at the `formLogin`/`oauth2Login`/`saml2Login`/`webAuthn`/`oneTimeTokenLogin` call line, with `method`. `oauth2ResourceServer` and `httpBasic` are not recorded.
- **`role`** extended to:
  - each constant line of an enum whose type is a field of a persisted type that is an account (it implements `UserDetails`, or has a password, password-hash or credential field, or is the type `loadUserByUsername` returns). Each constant fact cites its own line, with the field's type and line as attributes. Coverage cites the enum declaration (p3 `Role.java:3-6`), which the constants overlap.
  - role string arguments of any method named `hasRole`, `hasAnyRole`, `isUserInRole`, `isCallerInRole` or `roles.contains`, on any receiver, at the call line
  - `@DeclareRoles` values
  - meta-annotated checks, resolved through `annotationTypes()`
- **`granted-role`** at the call or assignment that gives a role to an account being created, such as `Set.of(Role.ATTENDEE)` in the account's factory or constructor, a `@PrePersist` default, or `authorities.add(findById(USER))`.
  - The linker names the self-registering person by this role ("Attendee"), else "User".
- **Keycloak realm:**
  - `registrationAllowed: true` → `account` (sign-up) at that line
  - the default role's composites → `granted-role`
  - per realm role: `heldByUsers` when a non-service user or a group holds it (a seed shows who holds a role but is no person itself)
- **`route`** keeps its line and adds:
  - `guarded: true` when the route or a parent has `canActivate`/`canMatch` or a guard component
  - `roles` from `data.roles`/`data.authorities`
- **`screen`** at a `@Controller` method that returns a view whose template exists under `templates/` or `WEB-INF/`. Templates used only by mail code are not screens.
- **`recipient`** (optional, supporting only) at a mail send whose address comes from an account (`user.getEmail()`, `executeActionsEmail`).

**Rule:** People come only from human-facing evidence, and each piece is recorded at the line that declares it:
- a sign-up, sign-in, confirm or reset endpoint that is open to the public or to signed-in users (the mapping line)
- a browser sign-in (`formLogin`, `oauth2Login`, `saml2Login`, `webAuthn` or `oneTimeTokenLogin`, at the call)
- Keycloak self-registration (`registrationAllowed`)
- a frontend route or a server-rendered screen
- a membership or invitation type

A role makes a separate kind of person only when that evidence ties it to human accounts:
- it is a constant of the role type of an account entity
- it is granted when an account is created
- it is a realm role held by non-service users or by the default role
- it guards a screen

Otherwise it is only what those people may do. Never a person:
- `ROLE_ANONYMOUS`, `IS_AUTHENTICATED_*`, `permitAll` and `authenticated`
- `SCOPE_` authorities and other scopes
- `@RunAs` roles
- roles that only service accounts or client-credentials clients hold, or that only guard actuator, management or internal endpoints with no screen
- seeded, demo, bootstrap and test users (they only show that a role is held by humans)
- enum values naming software (SYSTEM, SERVICE, INTERNAL, BOT)
- domain records of people who never sign in (owners, customers, patients)
- an endpoint named "register" that a role guards for a service
- an email template

## Sources read in full

These were read in full or in the named sections, through WebFetch, curl to text, or cloned code.
- C4 abstractions: https://c4model.com/abstractions
- C4 system context diagram: https://c4model.com/diagrams/system-context
- Spring Security, authorization architecture: https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html
- Spring Security, method security: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html (first 100k characters: @PreAuthorize, @Secured, JSR-250, class level, meta-annotations, templates, custom beans)
- Spring Security, OAuth2 resource server JWT: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html (authorities sections)
- Spring Security, OAuth2 login: https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html
- Spring Security, anonymous authentication: https://docs.spring.io/spring-security/reference/servlet/authentication/anonymous.html
- Keycloak Server Administration Guide: https://www.keycloak.org/docs/latest/server_admin/index.html (sections: Enabling user registration, Assigning permissions using roles and groups, Creating a realm role, Client roles, Composite roles, Default roles, Role scope mappings, Using a service account)
- Keycloak Admin REST API representations: https://www.keycloak.org/docs-api/latest/rest-api/index.html (field lookups only)
- RFC 6749 §1.3.4, §3.3 and §4.4: https://www.rfc-editor.org/rfc/rfc6749
- Jakarta Annotations 3.0 §3.10 to §3.15: https://jakarta.ee/specifications/annotations/3.0/annotations-spec-3.0.pdf
- Auth0 scopes: https://auth0.com/docs/get-started/apis/scopes and https://auth0.com/docs/get-started/apis/scopes/api-scopes
- Code2DFD paper: https://arxiv.org/pdf/2304.12769
- UCRBench: https://arxiv.org/pdf/2512.13360
- Sun et al., USENIX Security 2011 (introduction, sections 2 and 3): https://www.usenix.org/legacy/events/sec11/tech/full_papers/Sun.pdf
- Albassam, REC-MVC (sections 4 and 5): https://tuengr.com/V12A/12A13T.pdf
- jQAssistant Spring plugin rules (cloned): https://github.com/jqassistant-plugin/jqassistant-spring-plugin
- JHipster sample app (cloned: AuthoritiesConstants, AccountResource, UserService, MailService, SecurityConfiguration, Liquibase data, Angular routes): https://github.com/jhipster/jhipster-sample-app
- Spring PetClinic (cloned: build, templates, controllers): https://github.com/spring-projects/spring-petclinic

## Sources not read or read only in part (carry no weight)

- The remaining ~22k characters of the Spring method-security page, which cover the authorization expressions reference.
- The search result on Sun et al. that summarised RoleCast; the RoleCast paper itself was not read.
- Di Lucca et al. (WCRE 2000) and Miranda et al. (JSEP 2018) on use-case recovery; only search snippets were seen.
- DRacv (ScienceDirect 2025) and "Static analysis of RBAC in J2EE" (ResearchGate); only titles were seen.
- The tessl.io mirror of the Jakarta annotations docs; not used.
- The Code2DFD repository's source; only the paper was read.