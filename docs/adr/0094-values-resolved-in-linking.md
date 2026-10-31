# 0094: Values written in code are resolved in linking, one kind at a time

Status: accepted (#556); a step of #519 toward #522

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 7, "Values are emitted as
written") moves placeholder resolution out of the extractors. Seven extractors resolve `${...}`
against their module's config per profile (`CodeConfig`). The linker's `ValueResolution` rule
(#522) is to take that over, and it depends on the link rules of #521.

On the development sets most HTTP calls, gateway routes and datasources, and project-2's messaging,
hold placeholders. If #519 stopped the extractors resolving in one step, the documents would lose
those links until #522 lands, two large changes later, with no unchanged scorecard to check each
step against. The owner set the order #519, #521, #522 on 2026-10-31.

## Decisions

- **#519 is cut into slices**, each of which keeps the fake-model scorecards unchanged:
  - the link-side lookup (#556, this ADR);
  - HTTP requests and addresses (#557);
  - gateway routes (#558);
  - message destinations (#559);
  - datasources and data stores (#560);
  - the data config joined with code: JPA naming and Redis settings (#567);
  - the SQL settings, kept in extraction by the owner's choice (#569);
  - endpoints and Spring's scheduled values (#519 itself), which closes the issue.

  Each slice makes one kind of fact keep its values as written and moves resolving it to the
  linker.
- **The lookup** (`io.lantern.link.CodeValues`) resolves a text from code against the config of
  the code's module, in the default view and each profile, as `CodeConfig` did:
  - It reads the module's own `config-value` facts. A config repository's files and code's
    defaults are left out, as `CodeConfig` leaves them out.
  - It falls back to the config of the only module of the repository that has config, else to the
    placeholders' defaults.
  - It looks up each key's value as written in the view (the profile's fact, else the default's),
    so placeholders nest as Spring nests them (`ConfigExtractor.resolve`).
  - A profile that sets any item of a list hides the default's other items, as Spring replaces a
    list whole.
  - For this to be exact, the config extractor (version 8) records a profile's value also where it
    is written differently but resolves the same, and every item of a list the profile sets
    (one with an item it holds differently from the default view, or not at all). Before, a
    profile's facts held only the values that resolve differently, which does not say which list
    items a profile keeps.
  - A value a secret was substituted into is kept as written and masked. The secret is a key the
    config extractor names a secret, a key the identity extractor finds holding a JWT secret, or a
    key whose value the config extractor masked whole.
- **Known gaps**, from what the facts record:
  - A module that has config files but no values has no facts, so its code takes its repository's
    only other config, where `CodeConfig` took the empty one.
  - The lookup masks a value that uses a key the config extractor masked for holding a credential
    or a cipher; `CodeConfig` masked only on a secret key's name.
  - A value recorded with what looked like a URL's credentials removed is used as recorded. An
    e-mail address reads as user info (#561).
- `CodeValuesTest` checks the lookup against `CodeConfig` on fixtures covering:
  - profiles, defaults and nested values, and a profile value written differently that resolves
    the same;
  - lists a profile replaces, shortens, or changes in one item only;
  - a config server's own config beside the files it serves;
  - name-based and JWT secrets, and URLs with credentials;
  - escapes, and a module without config.

  `CodeValuesIT` checks every config key of every module of the development sets, as a
  placeholder, in every view.
- **What #522 adds.** #522 turns the lookup into the `ValueResolution` rule:
  - deployment environments as views;
  - `RESOLVES_TO` claims with lineage to the config facts used;
  - an architecture rule that only it resolves placeholders.

## Amendment (#557): the first link step

Each slice adds its kind to one link step, `ValueViews`, which runs before every other. It resolves
the values its kind's facts keep as written, through the lookup, and records the facts the
extractor recorded before: the same subject, predicate, object, attributes, evidence and
confidence, with origin `values`. Every later link step and the composer read those facts as before,
and the analysis run gives the summarizer the step's facts too (it summarizes before linking), so
a slice changes no document. The facts' IDs change with their origin, so a real run asks the LLM
again where a prompt cites one; they carry no lineage to the facts they come from until #522. The HTTP client's requests are the first kind
(ADR 0038, amendment for #557). Gateway routes are the second (ADR 0077, amendment for #558): routes
built in code are resolved like requests, and the routes config defines are read from the config
facts, through the gateway extractor's own reading of config. Message destinations are the third
(ADR 0037 and ADR 0040, amendments for #559): sends, listeners, bindings and declarations are
relationships to destination entities with their values as written, Spring Cloud Stream functions
and bindings are recorded as the code names them, and the step resolves and binds them through the
messaging extractor's own code, with the module's config read from its facts. STOMP pushes become
relationships to destinations too (ADR 0080, amendment for #559). #522 replaces the step's facts
with `RESOLVES_TO` claims, and the readers move to the entities with #521. Datasources and data
stores are the fourth (ADR 0036, amendment for #560): read in the step from the config facts
through the persistence extractor's reading, each a `data-source` entity connected to its address
as written. The JPA naming strategy and Redis's settings, which join config with code, are the fifth
(#567): the extractors record what the code says, and the step applies the config. The SQL
settings stay in extraction (#569, below). Endpoints and Spring's scheduled values are the last
(ADR 0035, amendment for #519): an endpoint is an `http-endpoint` entity its method handles, its
paths as written. With it, no extractor but SQL reads config: `CodeConfig` reads files only in
the extractors' tests, which resolve through the same functions linking calls.

Extraction coverage counts the step's facts as their extractor's (ADR 0089, amendment for #558; the messaging and STOMP facts from #559).

## Consequences

- The lookup is used from #557 on. The config extractor's extra profile facts are the only output
  change of #556; the fake-model scorecards are unchanged (#556's PR).
- On the development sets the lookup gives what `CodeConfig` gives for every key and view pair
  but two, #561's e-mail address, which no code value uses.

## Amendment (#569): the SQL extractor keeps reading config

The SQL extractor is the one extractor that still reads config, by the owner's choice of
2026-10-31. Its config does not add to facts after the fact; it decides how the migrations are
read:
- which vendor's copy of the scripts (from the datasource URL);
- Flyway's placeholders, substituted into the script text, which can change table names;
- Liquibase's contexts and parameters;
- which plain scripts run.

A schema is built by applying the migrations in order, and linking has no SQL files, so it cannot
apply these afterwards. The extractor reads the module's config (the `prod` profile's where there
is one, ADR 0034) as part of one reading of the migrations, cached per snapshot. Reading every
variant as written and choosing in linking, or building the schema in linking, were the options
set aside: both are large, and the second breaks caching per file.

## Amendment (#587): value resolution writes RESOLVES_TO

The first link step becomes the value resolution rule of the knowledge-build epic
(`docs/epics/001-knowledge-build.md`, sections 7 and 8), `ValueResolution`, still under the origin
name `values`. Besides the facts above, it writes what each expression-keyed entity is:

- `RESOLVES_TO` from a destination keyed by its module and expression
  (`destination:<module>:<broker>:<kind>:<expression>`) to the literal destination
  (`destination:<broker>:<kind>:<name>`), and from an address so keyed
  (`address:<module>:<expression>`) to the literal address, `address:<host>`: the host and port of
  the URL a request's expression resolves to (with `http://` first for a Feign client, as
  OpenFeign adds it), or the server of the JDBC URL a datasource's resolves to. These are the keys
  the extractors give a destination or host written out, so a resolved and a literal one are one
  entity.
- One claim for the default view and one for each profile whose config gives another literal,
  `profile` its key attribute, as for links until #590 names it `view`. The default view's claim
  lists in `exceptProfiles` the profiles whose config gives another literal or none, as a link's
  does (#590 names it `absentIn`).
- A placeholder's own default (`${orders.topic:orders}`) resolves an expression too; that claim
  cites no config value, and its evidence is only where the expression is written.
- None where a placeholder stays unresolved, a secret is used, config changes nothing (a STOMP
  destination with a `{}` segment is scoped for that, not for config) or no literal host results.
  Deployment environments as views, which resolve most of what is left, are #588's.
- `resolved`, the text config gave (a URL or a JDBC URL), is descriptive: URLs that differ only
  in their path are one address (epic, Key attributes).
- Lineage: `facts` lists the `config-value` facts used, so the claim's lineage is their claims;
  the evidence is where the expression is written, then each config value used (role `config`).

The matchers still read the facts above until address resolution (#589) moves them onto these
claims. On project-0 to project-4 every claim names the destination or host the matchers read in
that view, every view in which they read a literal has its claim, and every other derived fact is
unchanged (#587's PR).

## Amendment (#588): deployment environments are views

A deployment that runs a unit is a view of value resolution (epic, section 8, Views): the
container's environment is a config overlay, an environment variable setting the key it binds to
as Spring Boot's relaxed binding does (`DB_HOST` sets `db.host`), before the config files and a
placeholder's default.

- `ValueResolution` now runs after `DeployableUnits` and reads the units, their modules and names
  (`deployable-unit`, `BUNDLES`, `application-name`) to know which containers run a module's unit
  (`Deployments`, ADR 0082). It does not read what the protocol rules write about units, which
  read its facts.
- For each expression-keyed destination or address and each container that runs its unit, it
  resolves the expression with the container's environment over the config of the profile the
  container makes active (`SPRING_PROFILES_ACTIVE`, when it names exactly one of the module's
  profiles), else the default view. A container that activates several of them falls back to the
  default view: overlaying them in Spring's order is left until a test set needs it. Where the environment sets nothing the expression uses, the deployment's
  literal is that profile's.
- A deployment whose literal is not the default view's gets its own claim, with the key attribute
  `deployment`: `<repository>|<platform>|<environment>[|<compose file>]|<container>`. The default
  view's claim lists those deployments in `exceptDeployments`, descriptive like `exceptProfiles`;
  #590 merges `profile` and `deployment` into `view`, and both lists into `absentIn`.
- A deployment's claim cites the config values used and the container's `deployment-container`
  fact, also when the literal comes from the profile the container activates. Deployment
  environments hold no secrets (the deployment extractor leaves them out), and a value that uses a
  key naming a secret is not resolved in a deployment's view, as in the others.
- Filling from an environment lives in one place, `EnvironmentValues`: address matching
  (`Addresses`, ADR 0042) and the placing of datasources (`Deployments.place`) call it instead of
  filling placeholders themselves. Until address resolution (#589) moves the matchers onto the
  claims, they call it per container as before, so every link is unchanged.
- The rules carried from #516 hold in every view: a list a profile sets replaces the default's
  whole (`CodeValues`), and no base URL goes before a URL whose start the code computes. On
  project-8, `Project8ValuesIT` checks #516's routes, bindings, the followed `URI` field and the
  datasources in every view; the generated client of item 8 exists only after a build, and the
  real run's facts show its call unchanged in every view (#588's PR).

