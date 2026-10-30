# HTTP clients between Java services: how the target is named

Research for #389 (Review the HTTP client extractor), milestone "Extractor revisit". It leads to an amendment of `docs/adr/0038-http-client-extractor.md` and the HTTP client row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** The note decides how a service's outbound HTTP calls name the service they reach, which is what draws the connections between containers. A wrong rule loses an edge or draws one to the wrong box. I read 19 official pages and papers in full, and the client code and config of 4 open-source projects, fetched raw. The research is generic: it does not read Lantern's test sets.

## Question

Which HTTP client libraries and styles do Java (mostly Spring) services use to call each other? For each, how is the target service named: a literal URL, a base URL on a builder, `@Value` or `@ConfigurationProperties`, a service-discovery name, a Feign name or URL, an HTTP service group, a Kubernetes DNS name? Where is the client configured, including auth, `@LoadBalanced` beans, resilience and timeouts? And what does ADR 0038's extractor miss?

## What we learned

### RestTemplate, RestClient and WebClient

- **RestTemplate is deprecated in Spring Framework 7** in favour of `RestClient`, but existing services use it heavily. https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- **Where the base URL is set.**
  - `RestTemplateBuilder.rootUri(...)`, `setUriTemplateHandler(new DefaultUriBuilderFactory(base))`, `RestClient.builder().baseUrl(...)`, `WebClient.builder().baseUrl(...)`, `.uriBuilderFactory(...)` and `create(url)`.
  - Boot auto-configures builders, not clients. So a client bean always comes from a `@Bean` method or from `builder.build()` in a constructor, and builders are stateful and shared. https://docs.spring.io/spring-boot/reference/io/rest-client.html, https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html, https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html
- **`@LoadBalanced` makes the URL's host a service name.**
  - On a `@Bean` method that makes a `RestTemplate`, `RestClient.Builder` or `WebClient.Builder`, "the URI must use a virtual host name (a service name, not a host name)". Spring Cloud LoadBalancer resolves it through discovery.
  - `@LoadBalanced` is also a qualifier. A service can have a plain `@Primary` client and a load-balanced one, and pick the second with `@LoadBalanced` at the injection point.
  - A WebClient can instead get a `ReactorLoadBalancerExchangeFilterFunction` as a filter.
  - https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html
- **The `lb://` scheme** names a load-balanced service in Spring Cloud LoadBalancer's HTTP service group integration (`spring.http.serviceclient.<group>.base-url: lb://cards`). A group with no base URL goes to `http://<group>`, load balanced. https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html
- **Real code.**
  - kbastani's catalog service injects `@LoadBalanced RestTemplate` and calls `String.format("http://inventory-service/v1/products/%s", id)`.
  - eazybytes' accounts service (Boot 4) uses `@ImportHttpServices(group = "cards")` with `lb://cards` and `@HttpServiceFallback`.
  - FTGO's gateway reads its targets from a `@ConfigurationProperties` bean that docker-compose sets through environment variables.
- **Auth.** RestClient uses `requestInterceptor(new OAuth2ClientHttpRequestInterceptor(...))` with a `clientRegistrationId(...)` attribute; WebClient uses `ServletOAuth2AuthorizedClientExchangeFilterFunction`; HTTP interfaces use `@ClientRegistrationId`. https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html, https://docs.spring.io/spring-security/reference/features/integrations/rest/http-service-client.html

### OpenFeign: feature-complete

- Spring Cloud OpenFeign is "now considered feature-complete" and points to HTTP service clients. https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html
- **The URL is resolved in order:**
  1. the annotation's `url`;
  2. else `spring.cloud.openfeign.client.config.<contextId>.url` (not load balanced), where `contextId` defaults to the name and `default` applies to all clients;
  3. else load balanced on `name`.
- `contextId` is a bean name, not a service id (`FeignClientFactoryBean`). https://github.com/spring-cloud/spring-cloud-openfeign/tree/main/spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign

### MicroProfile and Quarkus REST clients

- `@RegisterRestClient(baseUri, configKey)`. The config key `mp-rest/uri` overrides `baseUri` and wins over `mp-rest/url`, and a fully-qualified key overrides the `configKey` one. https://download.eclipse.org/microprofile/microprofile-rest-client-4.0/microprofile-rest-client-spec-4.0.html
- Quarkus adds `quarkus.rest-client.<key>.url` and `.uri`, and `override-uri` beats both. `stork://name` is a discovery name that config maps to another application. https://quarkus.io/guides/rest-client

### Other clients

- **Retrofit** resolves a relative URL against its base the way an HTML link does: a leading `/` keeps only the base's host. Lantern does not read Retrofit. https://github.com/square/retrofit/blob/trunk/retrofit/src/main/java/retrofit2/Retrofit.java
- **java.net.http, OkHttp and Apache HttpClient** build a request object with its URL. They sit under Spring's clients as request factories, so they appear directly only in services that do not use Spring's clients.

### Discovery and naming

- **Kubernetes.** The service id is the Kubernetes Service's name, independent of `spring.application.name`. In SERVICE mode, `service-a.<namespace>.svc.<domain>` is called. https://docs.spring.io/spring-cloud-kubernetes/reference/discovery-client.html, https://docs.spring.io/spring-cloud-kubernetes/reference/load-balancer.html
- **Docker Compose.** The host is the compose service's name.

### Resilience

- Resilience4j's `@CircuitBreaker(name, fallbackMethod)` and the other annotations name a logical backend under `resilience4j.<kind>.instances.<name>`. https://resilience4j.readme.io/docs/getting-started-3
- Spring Cloud Circuit Breaker's `create(id).run(() -> call, fallback)` puts the call in a lambda. https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-circuitbreaker.html

### Prior art

- **Code2DFD** finds HTTP calls with keyword rules. Its circuit-breaker extractor "marks all outgoing flows from a service it detects as being guarded by the circuit breaker, which is not always correct". https://arxiv.org/abs/2304.12769
- **The 2024 comparison of recovery tools** found only 3 of 8 tools useful on connections, the best at F1 0.87, and named heuristics as the main cause of false positives. https://arxiv.org/abs/2412.08352

## Pitfalls for static analysis

- **The meaning of a host depends on the client.** `http://stores` is a service name under `@LoadBalanced` (on the bean or at the injection point), a load-balancer filter or `lb://`, and a DNS name otherwise.
- **The base URL lives outside the call**: in a `@Bean` method, a constructor, Boot or Feign properties, MicroProfile keys, or a `@ConfigurationProperties` getter, each with its own precedence.
- **An unset group base URL is a service**, not unknown, under Spring Cloud LoadBalancer.
- **The name called is not always the name that serves**: Kubernetes Service names, Stork mappings, and Feign's `contextId`.
- **URLs built in ways folding does not see**: `String.format`, `uri(builder -> ...)` lambdas, `DefaultUriBuilderFactory`, a per-call `UriBuilderFactory` parameter.
- **Calls inside lambdas and wrappers**, such as circuit breakers.
- **Tag resilience and auth per call, never per service**, as Code2DFD's false positives show.

## What the extractor misses today

ADR 0038's extractor already reads Feign `name`, `url` and `path`, MicroProfile `baseUri` and `configKey`, HTTP interfaces and service groups with their configurers and base URLs, clients made in `@Bean` methods and picked by qualifier or `@Primary`, and the request objects of `java.net.http`, OkHttp and Apache HttpClient. It misses:

1. **`@LoadBalanced`**: neither the client bean nor the call records it, so a reader cannot tell a service name from a host.
2. **`lb://` URLs**, whose host is a service.
3. **A group with no base URL under Spring Cloud LoadBalancer**, which goes to `http://<group>`.
4. **Feign's property URL**, keyed by `contextId` with `default`, which overrides the load-balanced name.
5. **MicroProfile and Quarkus precedence** (`uri` over `url`, `override-uri`, the fully-qualified key) and `stork://` names.
6. **`@ConfigurationProperties` getters**, `String.format` URLs and `DefaultUriBuilderFactory` bases.
7. **Retrofit**, manual Feign builders, `DiscoveryClient.getInstances(...)` and groups registered through `AbstractHttpServiceRegistrar`.
8. **Auth, resilience and timeouts** on calls.
9. **The client bean itself**: a `@Bean` method that makes a client or builder has no fact unless a call is followed to it.

## Options

- **A. Record discovery on the call and the bean.** `loadBalanced` and `service` (the URL's host) on a call through a load-balanced client or to an `lb://` URL; a fact on each client bean. Cheap: it uses the existing bean picking, and the linker already prefers `service`.
- **B. Treat every undotted host as a service in the linker only.** No extractor change, but it loses the evidence and cannot tell a compose DNS name from a discovery id.
- **C. Resolve more config sources**: Feign properties, group defaults, MicroProfile precedence, `@ConfigurationProperties`. Each is a precise rule; `@ConfigurationProperties` changes the shared value following.
- **D. Record auth, resilience and timeouts per call.** No rule of the bar asks for it yet.
- **E. Add libraries**: Retrofit, manual Feign, `DiscoveryClient`.

## Recommendation

Do A now, inside this extractor:

1. **`http-client-bean`**: every `@Bean` method that makes a RestTemplate, RestClient or WebClient, or a builder of one, at the method, with its type, `loadBalanced`, and the base URL its code sets.
2. **`loadBalanced` and `service` on `http-call`**: for a call through a client that is load balanced (the injection point's `@LoadBalanced`, the bean's, or that of the builder bean it is made from), and for an `lb://` URL, the URL's host is recorded as the service.

Later issues, each with a project-0 case first: the config sources of option C except `@ConfigurationProperties` (Feign's property URL, group defaults under the load balancer, MicroProfile and Quarkus precedence and Stork); `@ConfigurationProperties` getters, which the messaging research also asks for (#447); a load-balancer filter, `String.format` and `DefaultUriBuilderFactory`; the other libraries; and auth, resilience and timeouts per call.

## What it changes in the bar and the design

- **Design, Static analysis and extractors**: the HTTP client row names client beans and load-balanced clients, whose hosts are service names; ADR 0038 is amended.
- **Quality bar**: nothing. Auth and resilience on connections would be a new rule, proposed only with its own issue and evidence.

## Sources read in full

- Spring Framework, REST Clients: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Spring Framework, WebClient configuration: https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html
- Spring Framework, URI Links: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html
- Spring Boot, Calling REST Services: https://docs.spring.io/spring-boot/reference/io/rest-client.html
- Spring Cloud Commons, Common Abstractions: https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html
- Spring Cloud Commons, Spring Cloud LoadBalancer: https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html
- Spring Cloud Commons, Spring Cloud Circuit Breaker: https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-circuitbreaker.html
- Spring Cloud OpenFeign reference: https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html
- Spring Cloud OpenFeign source, `FeignClient` and `FeignClientFactoryBean`: https://github.com/spring-cloud/spring-cloud-openfeign/tree/main/spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign
- Spring Security, Authorized Client Features: https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html
- Spring Security, HTTP Service Client integration: https://docs.spring.io/spring-security/reference/features/integrations/rest/http-service-client.html
- Spring Cloud Kubernetes, DiscoveryClient and LoadBalancer: https://docs.spring.io/spring-cloud-kubernetes/reference/discovery-client.html, https://docs.spring.io/spring-cloud-kubernetes/reference/load-balancer.html
- Resilience4j, Spring Boot getting started: https://resilience4j.readme.io/docs/getting-started-3
- MicroProfile Rest Client 4.0 specification: https://download.eclipse.org/microprofile/microprofile-rest-client-4.0/microprofile-rest-client-spec-4.0.html
- Quarkus, REST Client guide: https://quarkus.io/guides/rest-client
- Retrofit introduction and `Retrofit.Builder.baseUrl` source: https://lysine.dev/retrofit/, https://github.com/square/retrofit/blob/trunk/retrofit/src/main/java/retrofit2/Retrofit.java
- Schneider and Scandariato, Automatic Extraction of Security-Rich Dataflow Diagrams for Microservice Applications written in Java (Code2DFD), JSS 2023 (approach and evaluation): https://arxiv.org/abs/2304.12769
- Schneider et al., Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications, EMSE 2025 (tools, connection results, lessons learned): https://arxiv.org/abs/2412.08352
- Open-source code, fetched raw from GitHub: microservices-patterns/ftgo-application (the API gateway's `OrderDestinations`, `OrderConfiguration`, `OrderServiceProxy`, `application.properties`, `docker-compose.yml`); kbastani/spring-cloud-event-sourcing-example (`CatalogServiceV1`, `CatalogServiceApplication`, `AccountApplication`, `UserClientV1`); eazybytes/microservices at 4.1.0 (`AccountsApplication`, `CardsClient`, `CardsFallback`, `HttpClientObservabilityConfig`, `application.yml`); quarkusio/quarkus-quickstarts (`stork-kubernetes-quickstart`, `rest-client-quickstart`).

## Sources not read (carry no weight)

- The MicroDepGraph paper, known here only through the comparison above; the Spring Cloud Circuit Breaker 5.1 page; repository records of the comparison paper: search results only.
