# 0074: People inferred from the code

Status: accepted (#268), pending the user's review (#24)

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
  - each distinct role is a person, unless it is for software: a Keycloak realm role that only
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
