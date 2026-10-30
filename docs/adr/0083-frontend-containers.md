# 0083: Frontend containers

Status: accepted (#267), amends ADR 0042 and ADR 0074, amended for #331 below, pending the user's
review (#24)

## Context

The design's container view has one box per frontend, built from the Frontend extractor's facts
(Static analysis, Initial extractors), and ADR 0070 moved frontends into Phase 2. project-4's
Angular CRM-Frontend (its own repository, no Maven build) and project-1's angular-ui (a folder of
a Maven repository) were absent from the system reports, so the people that ADR 0074 infers had no
container to use, and the reference's lines from the browser to the gateway or BFF were missing.
A browser does not call a service the way a service does: it calls the page's own origin, which
the nginx serving the app (or the development server) passes on, or an absolute URL from an
environment file, often `localhost` and a port that a compose file publishes.

## Decisions

- **The Frontend extractor (`frontend`, version 1)** reads every repository of the snapshot, with
  or without a build, from git at the commit; it builds and runs nothing and reads the code as
  text, without a parser. Folders `node_modules/`, `dist/`, `target/`, `e2e/`, `coverage/`, test
  fixtures and test sources (`*.spec.*`, `*.test.*`) are skipped.
  - A frontend is a `package.json` whose dependencies name Angular (`@angular/core`), Vue (`vue`,
    `nuxt`) or React (`react`, `next`); one inside another frontend's folder is part of it. It is
    a `frontend:<repo>` entity, or `frontend:<repo>/<folder>` when the manifest is in a folder.
  - `frontend-app` (value: the framework) records the manifest's name without its npm scope (the
    name documents show, else the folder's or repository's), the folder, the framework's
    major.minor from the range asked for, the libraries Lantern knows (UI kits, Vite, axios,
    STOMP.js, SockJS...), the last stage's base image of the folder's Dockerfile with the
    product it is (`nginx`) and its exposed ports, and a technology line such as
    `Angular 19.2 · nginx`.
  - `frontend-call` (value: method and URL) is each request the code makes from the browser:
    Angular's `HttpClient` (`get`, `post`... and `request('PUT', url)` on a field typed or
    injected as `HttpClient`), `fetch` (its `method`), axios and axios instances (with their
    `baseURL`), and a socket it opens (`new WebSocket`, `new SockJS`, a STOMP client's
    `brokerURL`). Its URL is evaluated as far as the file says: string and template literals,
    `+`, constants and fields of the file written on one line, and environment values; anything
    else is `{name}`, and the query is left out. A URL that is only an unknown value is not
    recorded. Comments are blanked out first, keeping offsets, so cited lines stay right.
  - **Environments.** Angular's `environments/environment*.ts` (simple `key: 'value'` entries),
    the default build's being the file that the configuration the folder's Dockerfile builds
    (`ng build --configuration x`), else `angular.json`'s default configuration, puts in place of
    `environment.ts`; or the dotenv files of Vite and Create React App (`.env.production` over
    `.env` by default, the others over `.env`), read through `import.meta.env.X` and
    `process.env.X`. A call's URL is the default build's; the others' URLs, where they differ,
    are its `alternates`.
  - `frontend-proxy` (value: the path prefix) is each prefix the frontend's own server passes on:
    the nginx configs in its folder (`location` prefixes with a `proxy_pass` without variables;
    regular-expression locations are skipped), and its development server (Angular's
    `proxyConfig` file or `proxy.conf.json`, Vite's `server.proxy`, a manifest's `proxy`).
- **Linking (ADR 0042 amended).** The linker (`cross-repo-links`) makes each call a `CALLS_HTTP`
  link from the frontend:
  - a path of the page's origin goes through the proxy whose prefix matches, nginx before the
    development server (nginx serves the built app) and the longest prefix first; nginx replaces
    the prefix with the path of a `proxy_pass` URL that has one. A path no proxy takes is the
    page's own and links nothing;
  - the URL's host is a unit when it is the unit's `spring.application.name`, its artifact ID, or
    a name of a deployment container that runs it (ADR 0082); `localhost`, a loopback or
    private-network address is the unit whose compose container publishes that port on the host
    (`8080:8080`; a container port alone is published on a random one), else the only unit whose `server.port` it is, since a browser reaches a
    published port, not a container's own; a host with a domain that names no unit is an external
    system;
  - the default build's URL is tried first, then the alternates;
  - the link records the URL as it reaches the target, its path, `JSON/HTTP` or `WebSocket` (the
    external system's scheme for one), `matchedBy` (`host`, `artifact-id`, `container-host`,
    `published-port`, `port`, `external`) and the `proxy` it went through. A proxy no call goes
    through, to a target no call reaches, is a link of its own: the server passes requests on. A
    development server's proxy counts only when no nginx serves the frontend, since it says
    nothing about the deployed page.
  - A compose container whose build context is a frontend's folder runs it: a `deployed-in` fact
    on the frontend, and, when all such containers have one name, the frontend's `display-name`,
    since that is the name it runs under (the compose service `frontend` of
    `event-management-platform-frontend`).
- **Container diagrams (ADR 0074 amended).** A frontend is a container of its repository's scope,
  drawn first, with its technology line, the tag Single-Page App in the C4 report, and a
  description from its facts (framework, version, server and the units it calls), citing them.
  Each person whose `person-basis` names a frontend's repository among its `frontends` gets a
  line to the frontend whose folder holds a route it cites, labelled with the screens
  (`Uses the screens /login, /registration`) and the technology `Web browser`. The context's
  counts add Frontends. A frontend is no deployable unit: it has no components, and the LLM
  words neither it nor its lines, which keep their static labels.

## Consequences

- project-4's CRM-Frontend (`crm-frontend`, Angular 19.2 · nginx) calls api-gateway, published
  on 8080 by the compose files, over HTTP and its STOMP socket; project-1's angular-ui goes
  through its nginx's `/bff/` to the BFF. The organization member and the invited user use
  CRM-Frontend's screens; project-1's User uses angular-ui's.
- Drawing the frontend and people can take a container view over 15 elements, which then splits
  per container (project-4's does).
- `cross-repo-links` is raised to version 10; `frontend` starts at 1.

## Not verified

URLs built in other files (a base URL service, an interceptor that adds a prefix), constants
over several lines, axios interceptors, `HttpClient` used through a wrapper, routers' lazy
modules and SSR frameworks are not followed; a monorepo's workspace packages are folded into the
outermost frontend. Kubernetes and Helm containers are not tied to frontends, only compose builds in the frontend's
own repository (a deployment repository that builds `../frontend` is not followed). A call that
links nothing is dropped, not listed among the links left open. Minified and vendored scripts
(`*.min.js`, `assets/`, `public/`, `vendor/`) are not read.
The flows of the sequence diagrams start in the browser since #331 (ADR 0047).

## Amendment: browser navigations and the endpoint a call reaches (#331)

Status: accepted, pending the user's review (#24). The flows start in the browser now (ADR 0047,
amendment for #331), which needs the endpoint each call reaches, and project-1's sign-in is a
navigation of the browser, not a request from the page.

- **Navigations** (`frontend`, version 2): `window.location.href = url`, `location.assign(url)`
  and `location.replace(url)` are a `frontend-call` with the method `GET` and `navigation: true`,
  their URL evaluated as for other calls. The link keeps `navigation`.
- **The endpoint reached** (`cross-repo-links`, version 12): a call linked to a unit names the
  endpoint it reaches, as an HTTP call's URL is matched with endpoints behind the unit's context and
  servlet paths (a socket with the unit's WebSocket endpoints): the unit's own first, since a
  gateway's own controllers answer before its routes; else, when the unit is a gateway, the route
  that takes the request, by its Path and Method predicates (the lowest `order`, then the pattern
  with most literal segments, then the route's id, since the order of the routes in the code is not
  kept), and the endpoint of the unit that route sends it to, by the path as that unit sees it
  through `StripPrefix` and `PrefixPath`. A route that rewrites the path otherwise names none. The
  link records `reaches` (the endpoint's handler, or the method that registers the WebSocket
  endpoint), `reachesUnit`, `reachesEndpoint` and `throughRoute` (the route's id), and cites the
  endpoint's and the route's facts. A proxy's own link names none.
