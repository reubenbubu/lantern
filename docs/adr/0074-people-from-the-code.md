# 0074: People inferred from the code

Status: accepted (#268), amended for #365 and #396, amended by ADR 0083 (#267), amended for #574

## Context

The reference system contexts of project-1 to project-4 each show people (project-4: an
organization member and an invited user), and Lantern's said "the code names no people". Sign-up,
sign-in, roles, memberships, invitations and frontends make users evident (ADR 0070). People cannot
be read from a library call the way a database can; they have to be inferred, and the design says
unknown users are marked unknown, not invented.

## Decisions

- **Evidence first, people second.** The `people` extractor (new) records `person-evidence` facts,
  each citing its line, and decides nothing about who the people are. The kinds, in the value:
  - `role`: a role named in `@PreAuthorize` / `@PostAuthorize` (`hasRole`, `hasAnyRole`, and
    `hasAuthority` only for `ROLE_` authorities, since other authorities are permissions), in
    `@Secured`, in `@RolesAllowed`, in a Spring Security `hasRole`-like call with the role written
    out (read when the code is compiled), or in a Keycloak realm export (`roles.realm`, without
    the roles every realm has). `ROLE_` is dropped.
  - `membership`: a type about members of something, kept or run by the system (an annotation, an
    `@Entity`, a Spring bean or controller): the word `Member` or `Membership` ends its name or is
    followed only by what a type does about members (`Control`, `Aspect`, `Service`...). The word
    before it, unless it says what the type does (`Requires`, `Check`...), is the scope:
    `RequiresOrganizationMembership` gives `Organization`.
  - `invitation`: such a type with the word `Invitation` in its name (not an enum or a request body).
  - `route`: a path of a router in a frontend, in a file that imports Angular's, React's or Vue's
    router (`path: '...'`, `<Route path="...">`), recorded on `repo:<name>`.
  Test sources and `node_modules` are skipped.
- **The linker makes the people** (`PeopleLinker`, a step of `cross-repo-links`): one `person:<slug>`
  entity per kind of person, with `display-name` and a `person-basis` fact (value: `role`, `member`,
  `invited` or `account`) that holds one sentence in the code's own words, the first facts it
  cites, the repositories whose files show it and the frontends that have screens for it:
  - *(replaced: since the amendment for #365 roles alone make no person)* each distinct role is a person, unless it is for software: a Keycloak realm role that only
    service accounts hold (`serviceAccountOnly`, read from the realm's users), or, without a
    realm role, one whose every check guards only actuator, management or internal endpoints. This
    is judged by how a role is used, never by its name;
  - each remaining role is a person, named by the role in words (`EVENT_STAFF` is Event staff), the
    same role written in different ways being one; at most 12, the most often checked;
  - each membership scope is a person, `Organization member`, or `Member` without a scope;
  - invitations make `Invited user`;
  - sign-up and sign-in (an `endpoint` whose last path segment is `sign-in`, `login`, `sign-up`,
    `register`... or a route with such a segment) are added to the member; with no member they make
    `User`, who signs up and in. A code with roles but no member and no sign-in makes no `User`.
  A frontend has screens for a person when a route says so: invitation routes for the invited user,
  sign-in and sign-up routes for the member or user, a route segment equal to a role's name.
  A code with none of this has no people. Confidence is 0.7: it is an inference, however direct.
- **Always inferred.** Every person's description is the linker's sentence opened with `Inferred:`
  (emphasized), citing its facts; the context diagram's note says the people are inferred from the
  code and that who else uses the system is not known. The LLM does not word or find people here;
  Investigate (#269) may propose more, with citations that Lantern verifies.
- **Where people are drawn.** The system context shows each person with a `USES` line to the system,
  labelled `Uses, through <frontend>` when a frontend has screens for them, and a People count. A
  repository's context shows the people its own files show. Container diagrams do not yet: a
  frontend is no container until #267, which links the people to it using the `route` facts and the
  `frontends` of `person-basis`.

## Consequences

- project-4's context shows `Organization member` and `Invited user`, linked to CRM-Frontend by the
  line's label. project-0 has no sign of users and is unchanged.
- The names come from the code, so they are as good as its words: a project that checks `ADMIN` has
  an Admin. A role name that is a permission in disguise, or a type named `Member` for another
  reason, makes a wrong person; it is marked inferred and cites the line.
- The extractor is new (`people`, version 2 after the service-account rule) and `cross-repo-links` moves to version 7.

## Amendment (#267): people in the container diagrams

Frontends are containers now (ADR 0083). Each person gets a line in the container diagrams to the
frontend whose folder holds a route it cites, among the repositories its `person-basis` names as
`frontends`, labelled with those screens; the system context's line is unchanged.

## Amendment (#365): people only from human-facing evidence, and an unknown caller

Decided with the user (#364, `docs/quality-bar.md`, What the code can and cannot tell): a GUI is
for people, an API is for software. Implemented by #365.

- **Roles alone make no person.** A role the code checks no longer becomes a person by itself.
  People come only from human-facing evidence: memberships, invitations, accounts, sign-up and
  sign-in, and a frontend's routes. A role becomes what those people may do: it is shown with the
  person whose screens or endpoints it guards, or, when that cannot be told, with every person of
  the system. A code with roles and no human-facing evidence has no people.
- **Unknown caller.** An API is there to be called. An entry point that nothing in scope reaches
  and no person uses (an HTTP or WebSocket endpoint, a queue or topic consumed but never published
  to) is drawn as called by one `Unknown caller`, marked unknown: an actor outside the system on
  the context diagram, with a line to each container that has such entry points on the container
  diagrams. A scheduled job is not one; an endpoint a gateway route reaches counts as reached, and
  the gateway's own routes are then the entry points. Once the questions queue and annotations
  exist (#170, Phase 3), each unknown caller is a question, and an answer replaces it with the real
  caller.

## Amendment (#396): account endpoints, role types and more role checks

Research: [`docs/research/extractor-people.md`](../research/extractor-people.md). The extractor
records each piece of human-facing evidence at the line that declares it. The linker uses none of
the new kinds yet (#437); since #365 is still open and the linker still makes a person of each role
it checks, it leaves out the new role sources that check nothing (`@DeclareRoles`) or check on the
system's own types (`anyReceiver`) until #437. `people` version 3 adds two kinds and more role sources:

- **`account`**: an endpoint of a controller whose last path segment is a sign-up, sign-in,
  confirmation or password-reset one (`register`, `login`, `confirm`, `reset-password`...).
  - The fact sits at the mapping annotation, through an `@Operation` right after it. The full path
    is the class's `@RequestMapping` prefix plus the method's path.
  - It records `action` (`sign-up`, `sign-in`, `confirm`, `reset`), the OpenAPI `summary`, and the
    `guard` roles, read from method or class, directly or through a custom annotation.
  - A Keycloak realm with `registrationAllowed: true` gives one too, on the repository.
  - The sign-up and sign-in words are the extractor's, which the linker uses.
- **`role-type`**: each constant of an enum that types the roles of a user account, at the
  constant's line.
  - An account is a kept type (`@Entity`, `@Document`...) with a password field, or one that
    implements `UserDetails`.
  - The enum qualifies when it types a field of the account and either the field is named like a
    role field (`role`, `roles`, `authorities`...) or the enum like a role (`*Role*`,
    `*Authorit*`). A collection field (its element type erased) qualifies when both are and it is mapped as
    `@ElementCollection` or `@Enumerated`. Test sources are never looked at.
  - Constants that name software (`SYSTEM`, `SERVICE`, `INTERNAL`, `BOT`, `ANONYMOUS`, `API`) are
    skipped.
  - The linker does not use it yet, so it makes no person.
- **`role`** also comes from `@DeclareRoles` (which declares and guards nothing, so it is no
  `guard`), from a custom annotation that carries a role check (it records `annotation`), and from
  `hasRole`, `hasAnyRole`, `isUserInRole` and `isCallerInRole` called on any receiver, the system's
  own included (`anyReceiver`). A templated role (`'{value}'`) is skipped.
- A confirmation or reset step counts only for an account: not when a path variable comes before it
  (`/orders/{id}/confirm`), unless the path names one (`/users/{id}/activate`).

Browser sign-in (`formLogin`, `oauth2Login`...) is the Identity extractor's `http-security` fact
(ADR 0085, amendment for #395). Not read yet (#438): the role granted when an account is created,
a realm's default role and which realm roles humans hold, route guards and their roles,
server-rendered screens, mail recipients, `roles.contains("X")` checks, and the type
`loadUserByUsername` returns as an account.

## Amendment (#574): people are a link rule of their own

People are no longer a step of `cross-repo-links` but a link rule of their own (`People`, origin
`people`, ADR 0095). It reads `person-evidence` and `endpoint` as their generated attribute records,
not attribute JSON, and writes `person-basis` and `display-name`, whose types the cross-repo links'
vocabulary still defines. The people are the same on project-0 to project-4; `cross-repo-links`
goes to version 18.
