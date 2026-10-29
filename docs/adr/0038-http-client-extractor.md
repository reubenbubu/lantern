# 0038: HTTP client extractor

Status: accepted (#128, part of #92; extended by #145 and #149), pending the user's review (#24)

## Context

`docs/design.md` (Static analysis and extractors) says the HTTP client extractor reads Feign,
RestTemplate, WebClient and JAX-RS clients and produces outbound calls with the target's service
name, URL or property key, and path, and that the caller of a client interface is the code that
enables or injects it, not the jar that defines it. Following values says extractors follow a
value one hop from a `@Value` field or constructor argument into the client call that uses it.
The linker (#94) matches a call by client name against `spring.application.name`, else by URL or
property key against base URLs, then the path against endpoints (Cross-repo linking), and the
views draw "Calls · JSON/HTTPS" lines and sequence steps from them (#95, #96). The design leaves
open how a value is followed through the code, what the facts look like, and how a URL is recorded
without what must not be.

## Decisions

- **One reading of the code**, as ADR 0035: `HttpClientExtractor` (`lantern-extract`) takes the
  shared `JavaAnalysis`, with its confidence (1.0 compiled, 0.7 parsed). Both analyzers now also
  record, without changing the Java extractor's facts:
  - each field's erased type, and each written method's or constructor's parameters with their
    annotations (none for a record's constructors or implicit members, which parsing cannot see);
  - the chains of calls on a tracked API (`TrackedApis`: Spring's `web.client`, Boot's
    `RestTemplateBuilder`, WebClient, the Jakarta and javax REST client packages, Spring's
    `web.service` (HTTP interface proxies and groups), `java.net.http`, OkHttp and Apache HttpClient
    4 and 5, with their fluent APIs),
    a chain being a call that is not itself what another call is made on and whose innermost
    receiver has a tracked type, read as an `Expression`;
  - the values given to `String` fields and fields of a tracked type: their initializers (unless
    a constant) and assignments to the type's own fields in its constructors and initializer
    blocks.

  An `Expression` folds constants and joins string concatenations, replaces a local variable
  declared in a block and never assigned again by its initializer, and names fields
  (`FieldRef`), the enclosing method's parameters (`Param`), types (`TypeRef`, also for a class
  literal, `Foo.class`) and calls (`Invoke`); anything else, a lambda for one, is `Unknown` with
  its source. Only these APIs are recorded, so the analysis stays small; another extractor adds
  its packages to `TrackedApis`. A chain on a lambda's parameter counts as javac infers the
  parameter's type (#149); parsing takes it as written, else from `TrackedApis`, which names the
  parameters of the group configurers' lambdas (`groups -> groups.filterByName("a")
  .forEachClient((group, builder) -> ...)`, the client builder being the configurer's, or the type
  argument of a `Groups<RestClient.Builder>` parameter); parsing also reads a type named in full
  (`org.apache.http.client.fluent.Request.Get(url)`) as a type, as javac does.
- **Following a value one hop.** A URL part that is a `@Value` field or parameter is its
  placeholder (`${clients.payments.url}`); a field given one value in its constructor, a `@Value`
  parameter or a constant, is that value; a client held in a field is the chain that built it
  (`builder.baseUrl(baseUrl).build()` in the constructor), so its base URL is followed the same
  way. Anything else stays as its source in braces (`{productId}`, `{url}`), listed in
  `expressions`, which also stands for a path variable. A field given different values, or a
  client injected from elsewhere (a `RestTemplate` bean), gives no base URL.
- **Which calls.** Declarative clients: interfaces with `@FeignClient` (Spring Cloud OpenFeign,
  and the older Netflix package) or MicroProfile `@RegisterRestClient`, directly or through
  meta-annotations; their methods mapped with Spring request mappings (as ADR 0035 reads them,
  inherited from interfaces in the analyzed sources, an unmapped HTTP method being `GET` as Feign
  sends it), Feign's `@RequestLine` or Jakarta REST annotations. A Feign client goes to its `url`,
  with `http://` in front of the resolved value when it has no scheme, as Spring Cloud OpenFeign
  adds it, else to `http://<name>` as Spring Cloud LoadBalancer sends it; a MicroProfile client to its
  `baseUri`, else to `${quarkus.rest-client.<configKey>.url:${<configKey>/mp-rest/url}}`.
  Imperative clients: `RestTemplate` (`getForObject` to `optionsForAllow`, `exchange` and
  `execute` with an `HttpMethod`, a `RequestEntity`, and a `rootUri` of its builder), `RestClient`
  and `WebClient` (`create` or `baseUrl`, then `get()` to `options()` or `method(...)`, then
  `uri(...)`) and the Jakarta REST client (`target`, `path`, `request`, then the HTTP method).
  A URL argument may be a `URI`, a `URL`, an OkHttp `HttpUrl` or a `UriComponentsBuilder` made
  from a string.
- **Spring's HTTP interfaces** (#145): interfaces with `@HttpExchange` (or `@GetExchange` and the
  others, which carry it) on the interface or on a method. A method's URL is the interface's URL
  joined with its own, its HTTP method its own or else the interface's (none if neither says).
  The base URL is that of the client behind the proxy: a chain on `HttpServiceProxyFactory`
  (`builderFor`, `builder`, `exchangeAdapter`, then `createClient(Foo.class)`) whose adapter
  (`RestClientAdapter`, `WebClientAdapter` or `RestTemplateAdapter`, `create` or `forClient`)
  wraps a client whose `baseUrl`, `create(url)` or `rootUri` is followed as above, resolved in
  the config of the module that makes the proxy; a call per distinct base URL, and none (the path
  alone) when no proxy is found or its client comes from elsewhere. A URL that is a whole URL
  itself stands alone, as Spring sends it, without the base's `expressions`.
- **Beans made in another method** (#149): a proxy factory (or its builder) or an adapter that the
  code making the proxy takes as a parameter or field, rather than making it, is the bean of its
  type that a method of the same repository's code returns (by its declared return type; an
  `HttpExchangeAdapter` matches any adapter): the only one, else the one whose method is named as
  the parameter or field, as Spring picks it; none if several match otherwise. Its base URL is read
  from that method's code as above, so the chain may run through a factory bean to an adapter
  bean. A client (`RestClient`, `RestTemplate`) injected into an adapter still gives no base URL.
- **HTTP service groups** (#149, Spring Framework 7): `@ImportHttpServices` on a type (repeatable)
  puts into its `group` (`default` if none) the HTTP interfaces in `value` or `types`, and those
  in `basePackages` or the packages of `basePackageClasses` (and sub-packages), across all
  analyzed repositories, as Spring does; without either it imports none. The group's base URL is
  the one the group configurers of the same repository's code set: a chain on the configurer's
  `Groups`, `filterByName("a", ...)` with constant names or nothing (all groups), then
  `forEachClient` or `forEachGroup` with a lambda that calls `baseUrl(url)` on the client builder,
  the URL followed as above. Configurers naming the group win over those for all groups, as a
  configurer runs after Spring Boot's properties, a call per distinct base URL; one with another
  filter (`filter(predicate)`) is not read. Without a configurer it is Spring Boot 4's property
  `${spring.http.serviceclient.<group>.base-url}` (Boot's `HttpServiceClientProperties`),
  resolved in the config of the module of the importing type, and left as written if unset.
- **Request objects** (#145): `java.net.http` (`HttpRequest.newBuilder(uri)` or `.uri(uri)`, then
  `GET()`, `POST(...)` or `method(...)`), OkHttp (`new Request.Builder().url(...)`, then `get()`,
  `post(...)` or `method(...)`) and Apache HttpClient (`new HttpGet(uri)` and the other request
  classes of 4 and 5 classic, `RequestBuilder`, `ClassicRequestBuilder` and `SimpleRequestBuilder`
  with `get(uri)` and the like or `create(method)` and `setUri(uri)`,
  `SimpleHttpRequest.create(method, uri)`, and 5's static factories `ClassicHttpRequests` and
  `SimpleHttpRequests`, `get(uri)` and the like or `create(method, uri)`). A request is recorded
  where it is made: the chain must end in `build()` (or be the constructor or `create` call alone),
  so one kept in a variable before it is finished counts once. An HTTP method not said is `GET`, as
  the builders default, except for Apache's builders, which always say. Where the request is sent is
  not read: the method that builds it is the one recorded as calling. Apache's fluent API (#149),
  4 (`Request.Get(uri)` and the like) and 5 (`Request.get(uri)` and the like, or
  `Request.create(method, uri)`), is the exception: a fluent request is recorded where it is
  executed, a chain from the factory call through `execute()`, or `executor.execute(request)`, so
  one kept in a variable counts once and one only made is not a call.
- **Facts**, attributes with a string object unless said, evidence the call (an imperative client)
  or the mapping (a declarative one), in the module that declares it:
  - `http-call` on the method sending the request (the type for code outside methods; the nearest
    declaration for an inherited client method): `POST http://payments:8080/api/payments`, the
    URL alone when the HTTP method is not known, with `httpMethod`, `path` (after the scheme and
    authority, or after a leading placeholder or expression; absent when one stands for the whole
    URL), `url` and `baseUrl` when the URL
    starts with a scheme, `service` (a Feign client's name, for matching against
    `spring.application.name`), `client` (`feign`, `microprofile-rest-client`, `http-interface`,
    `rest-template`, `rest-client`, `web-client`, `jakarta-rest-client`, `java-http-client`,
    `okhttp` or `apache-http-client`), `clientType` and `annotation` for a declarative client, and
    `expressions`. Placeholders resolve through the code's config as ADR 0035 resolves them
    (`CodeConfig`, shared now with the Spring extractor): the default view, plus a fact per
    profile whose URL or service differs, with `profile`, `written` and `unresolved`; a
    property key nothing resolves stays as written (`${TAX_SERVICE_URL}`).
  - `http-client` on a client interface: `feign`, `microprofile-rest-client` or
    `http-interface`, with `annotation` (for an HTTP interface without one on the interface, that
    of its first mapped method).
  - `ENABLES` from a type with `@EnableFeignClients` to each Feign client it turns on: those in
    `clients`, else those in `value`, `basePackages` or the packages of `basePackageClasses`, else
    in its own package (and sub-packages), across all analyzed repositories, with `annotation`;
    and from a type whose code makes an HTTP interface's proxy to that interface, with `member`
    (the method, constructor or type whose code calls `createClient`) instead; and from a type
    with `@ImportHttpServices` to each HTTP interface it imports, with `annotation`.
  - `INJECTS` from a type to a client interface it takes as a field or constructor parameter,
    with `member`, one fact per member.

  Which container makes a call (the deployable that enables, injects or packages the code) is
  #93's and the linker's to decide, as is whether a client that nothing enables is ever called.
  A `@Profile` gate on the client's class or interface is recorded by the Spring extractor where
  written (ADR 0035); a reader applies it to the class's calls.
- **What a URL never holds.** User information and other credentials (through the persistence
  extractor's `UrlCredentials`), the query and the fragment are left out of every URL and of
  `written`, and credentials of URLs inside `expressions`. A URL a secret was substituted into is
  recorded as written, with `masked: true` and without `path`, `url`, `baseUrl`, `service` or
  `unresolved`.
- **New vocabulary**, applied by the app on startup after the other extractors', with source
  `http-client-extractor`: the attribute types `http-call` and `http-client` and the relationship
  types `ENABLES` and `INJECTS`.
- **Not covered:** groups registered in code (an `AbstractHttpServiceRegistrar`), a group
  configurer whose `baseUrl` is not in its lambda (a method reference, a helper method), a
  lambda's parameter typed by a generic method's inference other than the configurers', or a
  nested type inherited from a supertype and written by its simple name (`Groups` in a class
  implementing a configurer) (both when parsed),
  a bean picked by `@Qualifier` or `@Primary`, a builder changed by separate statements
  (`builder.uri(uri); builder.GET();`), a URL built across methods or from several fields' values, a
  `RestTemplate` bean's `rootUri` set in another class, and Kotlin.
- **project-0.** `Project0HttpIT` checks the cases HTTP-1 to HTTP-5, VAL-1, VAL-2, VAL-5 and the
  extractor side of EXT-1, with the repositories built in the sandbox and read through javac, and
  parsed without builds, and checks both give the same facts. It runs when `lantern.test.project0`
  is set, with the same command as `Project0BuildIT` (see Commands in `CLAUDE.md`) but `-pl
  lantern-extract` and `-Dit.test=Project0HttpIT`.
