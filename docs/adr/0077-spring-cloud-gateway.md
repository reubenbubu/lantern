# 0077: Spring Cloud Gateway: routes, filters and lb:// links

Status: accepted (#257), amends ADR 0042, pending the user's review (#24)

## Context

The gateway is a system's front door, yet Lantern drew no lines from it: project-1 and project-2
define their routes in config (`spring.cloud.gateway.server.webflux.routes`), project-4 in code
(`RouteLocatorBuilder` in `RouteConfig`), and neither is read. Their filters (`GatewayFilter`,
`GlobalFilter`, `WebFilter`) are where the gateway's work starts, but no extractor saw them, so
the gateway's component diagram missed them. ADR 0042 left "gateways and routes" uncovered. The
references of project-1, project-2 and project-4 (#251) draw every route as a gateway-to-service
line, and project-4's reference names an endpoint no route reaches.

## Decisions

- **A gateway extractor.** `GatewayExtractor` (`lantern-extract`, name `gateway`, version 1) reads
  the code as the Java extractor read it and config through the config extractor, and makes two
  attribute types, applied by the app after the messaging extractor's, with source
  `gateway-extractor`:
  - `gateway-route`, valued with the route's id (`route-<n>` for one built without an id). Its
    subject is the method that builds it (code) or the module whose config defines it (config).
    Attributes: `definedIn` (`java` or `config`), `uri` (placeholders resolved against the
    module's config; `written` and `unresolved` as for an HTTP call; a URI that used a secret is
    `masked`, kept as written, without `uri`), `paths` and `methods` (from the Path and Method
    predicates), `predicates` and `filters` in config's shortcut form (`Path=/a/**,/b`,
    `StripPrefix=1`, a filter object of the code by its class's simple name), `filterTypes` (the
    filter classes of the code a route in code applies) and `order`. A route is recorded in the
    default view, and again with `profile` for each profile under which it differs.
  - `gateway-filter`, a child of `entry-point`, on a class that implements, directly or through
    classes of the code, `GlobalFilter` (`global`), `GatewayFilter` (`gateway`), a gateway filter
    factory (`factory`) or WebFlux's `WebFilter` (`web`), with the library type in `implements`.
    Only a class that is a bean (`@Component`, or what a `@Bean` method returns) or that a route
    in code applies counts, so an abstract base class or a filter nothing uses is left out.
- **Routes in code** are the chains on `RouteLocatorBuilder` (`builder.routes().route(id, r ->
  ...).build()`): each `route` call's lambda is read as a chain of predicate calls (`path`,
  `method`, `host` and the like; `or` and `negate` kept), `filters(f -> ...)` and `uri(...)`. A
  URI is followed one hop from a `@Value` field or parameter, as an HTTP call's URL is (ADR 0038),
  and its placeholders, and those of its paths, are resolved through config per view.
  To read lambdas, the Java analyzers now give a lambda as an expression of its own,
  `Expression.Lambda`, with its body when that is one expression or one `return`, its source as
  before, and its lines, which are the route's evidence; and a chain's receivers no longer count
  towards the depth an expression is followed to, so a gateway with many routes is read whole.
  `org.springframework.cloud.gateway.` is a tracked API. No other extractor's facts change.
- **Routes in config** are read under every key Spring Cloud Gateway's versions and servers use:
  `spring.cloud.gateway.routes`, `spring.cloud.gateway.mvc.routes` and
  `spring.cloud.gateway.server.webflux.routes` or `server.webmvc.routes`, with predicates and
  filters in shortcut form or as `name` and `args` (a predicate's args are all its values, so
  `Path` with two `patterns` has both). A route in config without an `id` is skipped. The evidence is the lines of the route's keys
  in the file that sets its id.
- **Route links.** A new linker part, `RouteLinker`, runs after the HTTP one in `CrossRepoLinks`
  (version 3). Each route of a unit's code or config, in each view, is a `CALLS_HTTP` link from the
  gateway with `route`, `paths`, `methods`, `url` (the URI) and `source` (the route's method or
  module), so every view, the Brief and the wording treat it as the gateway calling the service.
  `protocol` is `WebSocket` for `ws://`, `wss://` and `lb:ws://`, `HTTPS` for `https://`, else
  `HTTP`. The target is found by, in order: a load-balanced URI's name against
  `spring.application.name` (`service-name`, factor 1.0); for this machine (`localhost`,
  `127.0.0.1`, `[::1]`, `0.0.0.0`), the port against each other unit's `server.port` in the view,
  8080 when it sets none (`port`, 0.8), as project-1 and project-2 point routes at
  `http://localhost:8081` by default; otherwise the host as for an HTTP call (`host` 0.9, a
  Kubernetes service's first label, `artifact-id` 0.8), a host with a domain no unit has being an
  external system. `forward:` and `no://op` routes answer in the gateway and are no line. A route
  no unit matches stays an `unresolved-link` of kind `route` (`unknown-host`, `no-match`,
  `ambiguous`, `unresolved-placeholder`), which the LLM resolver does not take, since it picks
  among endpoints of a call. Identity and stable IDs follow ADR 0042, keyed by the route's code and
  id. The link is the hook for #261: an identity provider's WebSocket path is a `CALLS_HTTP` link
  with `protocol: WebSocket` and its `route`.
- **Endpoints no route reaches** (`unrouted-endpoint`, an attribute of the gateway unit, for the
  findings page, #273). For each unit a gateway routes to, each endpoint of that unit (behind its
  context and servlet paths) that none of the gateway's routes to it reaches, by method and path,
  is recorded with the endpoint (`endpoint`, `target`), the facts of the endpoint and the routes,
  and `calledBy`, the units whose linked HTTP calls reach it, since an internal endpoint is often
  meant to be reached only so. A route's paths are taken as the target sees them, through
  `StripPrefix` and `PrefixPath`; a unit reached by a route with `RewritePath` or `SetPath` is not
  checked. A pattern's `**` or `{*rest}` takes any rest, `*` and a variable one segment, a wildcard segment as
  a wildcard; a literal segment of a pattern does not take a variable of the endpoint, so
  `/api/organizations/invitations/**` does not reach `/api/organizations/{id}/preview`.
- **Components.** The class that builds routes in code is an entry point, "Gateway routes", and so
  is each gateway filter ("Gateway filter", "Global gateway filter", "Gateway filter factory",
  "Web filter"); the routes a module's config defines are a component of their own, named
  "Gateway routes" and keyed by the module. A route component has a line to each filter
  component its routes apply ("Applies on routes ..."), and the route links start from it. On the
  container diagram a route link reads "Routes" and its first path.

## Amends

- **ADR 0042:** "gateways and routes" is no longer uncovered, and a request's port is compared
  with the target's `server.port` for gateway routes to this machine (not for HTTP calls).

## Not covered

Routes in a library's code take the config of the library's own module, not of each unit that
bundles it; routes from a `RouteDefinitionLocator`, discovery locators
(`spring.cloud.gateway.discovery.locator`) and Spring Cloud Gateway MVC's `RouterFunction` DSL;
weights between routes; filters made by a `@Bean` method that returns a lambda; and path rewrites
other than `StripPrefix` and `PrefixPath`, for which endpoints are not checked. When endpoints
no route reaches are found, route order and blocking routes (`no://op`, `SetStatus`) are not
considered: an endpoint a broader route reaches counts as reached even if an earlier route blocks
it. A unit that sets no `server.port` is taken to listen on 8080, and a route to `http://localhost`
without a port matches no unit (`no-match`).

## Evidence

`GatewayRoutesIT` (`lantern-link`) reads project-1, project-2 and project-4 at their pins without
builds and checks every route of their references as a line with its protocol, and project-4's
`GET /api/organizations/{organizationId}/preview` as unrouted; its command is in Commands in
`CLAUDE.md`.
