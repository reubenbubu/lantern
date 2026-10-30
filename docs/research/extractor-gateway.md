# Gateway and edge routes in Java systems: what names a route and its target

Research for #390 (Review the Gateway extractor), milestone "Extractor revisit". It leads to an amendment of `docs/adr/0077-spring-cloud-gateway.md` and the gateway row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** A gateway's routes are the lines from the system's front door to its services, so a wrong rule loses those lines or draws them to the wrong box. Read in full: 21 official pages and 6 raw files from 4 open-source projects. Partly read: 2 papers and 3 source files of Code2DFD. The research is generic: it does not read Lantern's test sets.

## Question

Which API gateway and edge setups do Java (mostly Spring) systems use: Spring Cloud Gateway (WebFlux and WebMVC, in code and config), Netflix Zuul, nginx, Traefik, Kubernetes Ingress and Gateway API? In each, what names the route, the request it takes, the target service, and a change to the path? And what do recovery tools do with gateways?

## What we learned

### Spring Cloud Gateway: two servers, several property prefixes

- **Spring Cloud 2025.0 (Gateway 4.3) moved the properties.**
  - WebFlux: `spring.cloud.gateway.*` became `spring.cloud.gateway.server.webflux.*`.
  - WebMVC: `spring.cloud.gateway.mvc.*` became `spring.cloud.gateway.server.webmvc.*`.
  - The old prefixes still work through the properties migrator, so all of them occur in real repositories.
  - https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2025.0-Release-Notes
- **The 5.0 property appendix lists:**
  - `server.webflux.routes`, `server.webmvc.routes`, and `server.webmvc.routes-map`, which keys routes by id;
  - `server.webflux.default-filters`;
  - the discovery locator under WebFlux.
  - https://docs.spring.io/spring-cloud-gateway/reference/appendix.html
- **The docs still mix the prefixes.** The discovery locator page still shows `spring.cloud.gateway.discovery.locator.*`. https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/the-discoveryclient-route-definition-locator.html

### A route

- **What a route has:** an `id`, a `uri`, `predicates`, `filters`, and an optional `order` and `metadata`.
- **How predicates and filters are written:**
  - in shortcut form (`Path=/red/**`);
  - fully expanded (`name` and `args`);
  - and in real config, as shortcuts with named arguments (`CircuitBreaker=name=x,fallbackUri=/fallback`).
  - https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/configuring-route-predicate-factories-and-filter-factories.html, https://raw.githubusercontent.com/spring-petclinic/spring-petclinic-microservices/main/spring-petclinic-api-gateway/src/main/resources/application.yml
- **The `uri` names the target.** The route id does not.
  - `lb://name`: load balanced through discovery.
  - `http(s)://host:port`: a plain host.
  - `ws://`, `wss://` and `lb:ws://`: WebSocket.
  - `forward:`: answered inside the gateway.
  - https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html
- **Filters that change the path downstream:**
  - `StripPrefix=n` drops n segments.
  - `RewritePath` uses a Java regular expression; YAML writes `$\` for `$`.
  - `SetPath` uses a template over the Path predicate's variables.
  - `PrefixPath` adds a prefix.
  - https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/stripprefix.html, https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/rewritepath-factory.html, https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/setpath-factory.html, https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/prefixpath-factory.html
- **The WebFlux Java DSL** is `builder.routes().route(id, r -> r.path(...).filters(...).uri(...))`, with `and()`, `or()` and `negate()`. https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/fluent-java-routes-api.html
- **The WebMVC Java DSL is a different API:**
  - `RouterFunction<ServerResponse>` beans, such as `route("api").GET("/api/**", http()).filter(lb("apiservice"))` or `.before(uri(...))`;
  - path filters through `before(stripPrefix(2))`.
  - https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/java-routes-api.html, https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/loadbalancer.html

### The discovery locator

- **What it does.** `discovery.locator.enabled: true` makes a route for each service the discovery client knows.
- **Its defaults:**
  - predicate `Path='/'+serviceId+'/**'`;
  - filter `RewritePath` that removes the service id;
  - `url-expression` `'lb://'+serviceId`;
  - `include-expression` `true`, which takes every service;
  - `lower-case-service-id` `false`. Eureka registers ids in upper case, so the paths are upper case unless this is set.
- **Custom predicates or filters replace the defaults.** They are SpEL over `serviceId`.
- **Which services it routes to is known only at run time.**
- Real config mixes the locator and explicit routes (piomin's `gateway-service.yml`).
- Sources: the discovery locator page above; https://raw.githubusercontent.com/piomin/sample-spring-microservices-new/master/config-service/src/main/resources/config/gateway-service.yml

### Other route sources

- **Code and runtime sources.** Custom `RouteDefinitionLocator` beans, the Redis route repository, and the actuator's `POST /actuator/gateway/routes/{id}` add routes that config does not show. https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/configuration.html, https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/sharing-routes.html, https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/actuator-api.html
- **Routes often live in a config server's repository.** The gateway's own `application.yml` then holds only `spring.config.import: configserver:`. Examples: piomin's `config/gateway-service.yml` and piggymetrics' `shared/gateway.yml`. https://raw.githubusercontent.com/sqshq/piggymetrics/master/config/src/main/resources/shared/gateway.yml
- **`default-filters` apply to every route.** In petclinic they are a circuit breaker with a `forward:/fallback`, and Retry.

### Netflix Zuul

- **How a route is written:**
  - `zuul.routes.<name>: /path/**`, whose service is the name;
  - or `zuul.routes.<name>.path` with `serviceId` (load balanced) or `url` (a host, or `forward:`).
  - Without `serviceId`, the name is the service.
- **What Zuul strips:**
  - It strips the path's prefix by default; `stripPrefix: false` keeps it.
  - `zuul.prefix` is put in front of every route, and `zuul.stripPrefix` (true by default) strips it.
- **Default routes.** Every discovered service gets `/<serviceId>/**` unless `ignored-services` takes it out (`'*'` takes out all).
- https://cloud.spring.io/spring-cloud-netflix/multi/multi__router_and_filter_zuul.html
- **It is legacy but still met.** Zuul 1 went into maintenance in 2018, with Spring Cloud Gateway as its replacement, but older systems still use it. Piggymetrics routes by `serviceId` with `stripPrefix: false`, plus one `url: http://auth-service:5000`. https://spring.io/blog/2018/12/12/spring-cloud-greenwich-rc1-available-now

### nginx and Traefik

- **nginx `location` with `proxy_pass`:**
  - The target is a host, a `host:port` or an `upstream`.
  - When `proxy_pass` has a URI part, it replaces the matched prefix; without one, the path passes unchanged.
  - With regular-expression locations, `rewrite ... break` or variables, the rule differs.
  - https://nginx.org/en/docs/http/ngx_http_proxy_module.html
- **Traefik routers:**
  - A router has a `rule` (`Host`, `PathPrefix`, `Path`, `Method`, `Header`), a `service` and a `priority`.
  - Routers come from files, Docker labels or Kubernetes resources.
  - The `StripPrefix` middleware drops prefixes.
  - Docker gives each container a router by `defaultRule`, so a route may be written nowhere.
  - https://doc.traefik.io/traefik/reference/routing-configuration/http/routing/rules-and-priority/, https://doc.traefik.io/traefik/reference/routing-configuration/http/middlewares/stripprefix/, https://doc.traefik.io/traefik/reference/routing-configuration/other-providers/docker/

### Kubernetes Ingress and Gateway API

- **Ingress:**
  - Shape: `rules[].host` and `paths[]` with `path`, `pathType` and `backend.service.name` plus port.
  - Path rewrites come from controller annotations, such as ingress-nginx's `rewrite-target`.
  - The API is frozen in favour of Gateway API.
  - https://kubernetes.io/docs/concepts/services-networking/ingress/, https://kubernetes.github.io/ingress-nginx/examples/rewrite/
- **HTTPRoute:** `rules[]` with `matches`, `filters` (`URLRewrite` with `ReplacePrefixMatch` or `ReplaceFullPath`) and weighted `backendRefs`. https://gateway-api.sigs.k8s.io/reference/api-types/httproute/, https://gateway-api.sigs.k8s.io/guides/http-redirect-rewrite/

### Recovery tools

- **Code2DFD:**
  - It finds a gateway from the starter dependency or `@EnableZuulProxy`.
  - It reads only `spring.cloud.gateway.routes` in YAML, and takes each route's **id** as its target, not the `uri`.
  - For Zuul, it matches services whose name is a substring of a path segment.
  - It reads neither the Java DSL, the discovery locator nor the new prefixes.
  - https://arxiv.org/html/2304.12769v1, https://github.com/tuhh-softsec/code2DFD
- **The 2025 comparison of tools** gives no gateway-specific analysis. It finds that loose heuristics cause most false positives. https://doi.org/10.1007/s10664-025-10686-2

## Pitfalls for static analysis

1. **The same settings sit under several prefixes**, old and 2025, WebFlux and WebMVC, with relaxed binding (`lower-case-service-id`, `lowerCaseServiceId`).
2. **The target is the `uri` or `serviceId`, never the route id.**
3. **Routes are often not in the gateway module's own config**: they are in a config server's repository, in either Java DSL, or in runtime sources.
4. **The discovery locator and Zuul's default routes are written nowhere.** They hold for every registered service, minus what `include-expression` or `ignored-services` takes out.
5. **Some targets are no service**: `forward:`, the gateway's own port, external hosts, and fallbacks.
6. **The downstream path depends on each filter's exact rule and on their order**, `default-filters` first.
7. **Matching services by substring gives false lines.** Match names exactly.
8. **Edge proxies often sit in front of the Java gateway**: user → ingress → gateway → service.

## What the extractor reads, and misses

ADR 0077's extractor already reads:

- the WebFlux `RouteLocatorBuilder` DSL;
- config routes under `spring.cloud.gateway.routes`, `.mvc.routes`, `.server.webflux.routes` and `.server.webmvc.routes`, in shortcut and full form, per profile, with placeholders resolved;
- the `uri` as the target;
- gateway and web filter classes.

The route linker reverses `StripPrefix`, `PrefixPath` and `RewritePath`. It reads the discovery locator's `enabled` and `lower-case-service-id` itself, from two config keys. It misses:

1. **The discovery locator as a fact.** Nothing records it, its URL expression, its include expression, or the predicates and filters that replace its defaults.
2. **Netflix Zuul routes.**
3. **The WebMVC `RouterFunction` DSL, `routes-map` and `default-filters`.**
4. **Routes in a config server's repository.**
5. **Edge proxies**: nginx, Traefik, Ingress and HTTPRoute.
6. **Zuul's default routes, and the locator's include expression, in the linker.**

## Options

- **A. Record the discovery locator and Zuul's routes in the extractor.**
  - The locator becomes a fact with its settings, under both prefixes and in relaxed form.
  - Each Zuul route becomes a `gateway-route` with its target (`url`, else `lb://` plus `serviceId` or the name), its path behind `zuul.prefix`, and what Zuul strips as `StripPrefix`. The existing linker then draws Zuul routes like any other.
  - Cheap, and it fits the existing fact.
- **B. Read the WebMVC DSL, `routes-map` and `default-filters`.** Current Spring, but less common so far. It needs a reader for a second Java DSL.
- **C. Read routes from a config server's repository** for the gateway application, as the config extractor already serves such files.
- **D. Model default routes in the linker.** The locator's include expression, Zuul's defaults minus `ignored-services`, and endpoints the locator reaches. These are derived lines and must be marked so.
- **E. Edge proxies.** Many formats, each with its own rewrite rules.
- **F. Runtime-only sources.** State the gap rather than guess.

## Recommendation

Do A now, inside the extractor. The rest becomes issues, each with its own cases first:

- **B:** #458;
- **D, in the linker:** #459;
- **E:** nginx and Traefik in #460; Ingress and HTTPRoute with the deployment extractor (#397);
- **C:** #461.

## What it changes in the bar and the design

- **Design, Static analysis and extractors:** the gateway row also names Zuul routes and the discovery locator. ADR 0077 is amended.
- **Quality bar:** nothing new. Lines from a gateway already come from its routes. Derived routes (the locator, Zuul defaults) are the linker's to mark, in #459.

## Sources read in full

1. Spring Cloud Gateway, predicate and filter configuration: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/configuring-route-predicate-factories-and-filter-factories.html
2. The DiscoveryClient route definition locator: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/the-discoveryclient-route-definition-locator.html
3. Spring Cloud Gateway, configuration properties appendix: https://docs.spring.io/spring-cloud-gateway/reference/appendix.html
4. Spring Cloud 2025.0 release notes: https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2025.0-Release-Notes
5. Fluent Java routes API (WebFlux): https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/fluent-java-routes-api.html
6. Java routes API (WebMVC): https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/java-routes-api.html
7. WebMVC load balancer filter: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/loadbalancer.html
8. WebMVC StripPrefix filter: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/stripprefix.html
9. RewritePath filter: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/rewritepath-factory.html
10. SetPath filter: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/setpath-factory.html
11. PrefixPath filter: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/prefixpath-factory.html
12. Spring Cloud Gateway, configuration: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/configuration.html
13. Global filters: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html
14. Actuator API: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/actuator-api.html
15. Sharing routes: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/sharing-routes.html
16. Spring Cloud Netflix, router and filter (Zuul): https://cloud.spring.io/spring-cloud-netflix/multi/multi__router_and_filter_zuul.html
17. Spring Cloud Greenwich RC1 announcement: https://spring.io/blog/2018/12/12/spring-cloud-greenwich-rc1-available-now
18. Kubernetes, Ingress: https://kubernetes.io/docs/concepts/services-networking/ingress/
19. Gateway API, HTTPRoute: https://gateway-api.sigs.k8s.io/reference/api-types/httproute/
20. Gateway API, HTTP redirects and rewrites: https://gateway-api.sigs.k8s.io/guides/http-redirect-rewrite/
21. ingress-nginx, rewrite: https://kubernetes.github.io/ingress-nginx/examples/rewrite/
22. nginx, `ngx_http_proxy_module` (`proxy_pass`): https://nginx.org/en/docs/http/ngx_http_proxy_module.html
23. Traefik, rules and priority; StripPrefix middleware; Docker provider: https://doc.traefik.io/traefik/reference/routing-configuration/http/routing/rules-and-priority/, https://doc.traefik.io/traefik/reference/routing-configuration/http/middlewares/stripprefix/, https://doc.traefik.io/traefik/reference/routing-configuration/other-providers/docker/
24. Open-source config and code, fetched raw: spring-petclinic-microservices' gateway `application.yml`; piggymetrics' `shared/gateway.yml`; spring-cloud-gateway-sample's `DemogatewayApplication.java`; piomin's `config-service/.../gateway-service.yml` and `gateway-service/.../application.yml`.

## Partly read (weight only for the parts read)

- Code2DFD paper (about 83%): https://arxiv.org/html/2304.12769v1. Its source, the Spring Cloud Gateway and Zuul extractors and the route parsing: https://github.com/tuhh-softsec/code2DFD
- Schneider et al., a comparison of static architecture recovery tools for microservice applications, EMSE 2025 (abstract, research questions, dataset, results, lessons 1–4): https://doi.org/10.1007/s10664-025-10686-2

## Sources not read (carry no weight)

- The ingress-nginx retirement post (title only); Traefik's old routers page (a redirect); Envoy Gateway and other HTTPRoute rewrite guides, Baeldung's Spring Cloud Gateway articles, and mvnrepository change lists: search results only.
- `no://op` as a Spring Cloud Gateway URI: no source was found for it.
