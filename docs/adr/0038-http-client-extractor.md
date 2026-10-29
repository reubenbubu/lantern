# 0038: HTTP client extractor

Status: accepted (#128, part of #92), pending the user's review (#24)

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
    `RestTemplateBuilder`, WebClient and the Jakarta and javax REST client packages), a chain
    being a call that is not itself what another call is made on and whose innermost receiver has
    a tracked type, read as an `Expression`;
  - every value given to a field: its initializer (unless a constant) and assignments to the
    type's own fields in its constructors and initializer blocks.

  An `Expression` folds constants and joins string concatenations, replaces a local variable
  declared in a block and never assigned again by its initializer, and names fields
  (`FieldRef`), the enclosing method's parameters (`Param`), types (`TypeRef`) and calls
  (`Invoke`); anything else, a lambda for one, is `Unknown` with its source. Only these APIs are
  recorded, so the analysis stays small; another extractor adds its packages to `TrackedApis`.
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
  else to `http://<name>` as Spring Cloud LoadBalancer sends it; a MicroProfile client to its
  `baseUri`, else to `${quarkus.rest-client.<configKey>.url:${<configKey>/mp-rest/url}}`.
  Imperative clients: `RestTemplate` (`getForObject` to `optionsForAllow`, `exchange` and
  `execute` with an `HttpMethod`, a `RequestEntity`, and a `rootUri` of its builder), `RestClient`
  and `WebClient` (`create` or `baseUrl`, then `get()` to `options()` or `method(...)`, then
  `uri(...)`) and the Jakarta REST client (`target`, `path`, `request`, then the HTTP method).
  A URL argument may be a `URI` or a `UriComponentsBuilder` made from a string.
- **Facts**, attributes with a string object unless said, evidence the call (an imperative client)
  or the mapping (a declarative one), in the module that declares it:
  - `http-call` on the method sending the request (the type for code outside methods; the nearest
    declaration for an inherited client method): `POST http://payments:8080/api/payments`, the
    URL alone when the HTTP method is not known, with `httpMethod`, `path` (after the scheme and
    authority, or after a leading placeholder or expression), `url` and `baseUrl` when the URL
    starts with a scheme, `service` (a Feign client's name, for matching against
    `spring.application.name`), `client` (`feign`, `microprofile-rest-client`, `rest-template`,
    `rest-client`, `web-client` or `jakarta-rest-client`), `clientType` and `annotation` for a
    declarative client, and `expressions`. Placeholders resolve through the code's config as ADR
    0035 resolves them (`CodeConfig`, shared now with the Spring extractor): the default view,
    plus a fact per profile whose URL or service differs, with `profile`, `written` and
    `unresolved`; a property key nothing resolves stays as written (`${TAX_SERVICE_URL}`).
  - `http-client` on a client interface: `feign` or `microprofile-rest-client`, with
    `annotation`.
  - `ENABLES` from a type with `@EnableFeignClients` to each Feign client it turns on: those in
    `clients`, else those in `value`, `basePackages` or the packages of `basePackageClasses`, else
    in its own package (and sub-packages), across all analyzed repositories, with `annotation`.
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
- **Not covered:** Spring's HTTP interfaces (`@HttpExchange`), `java.net.http.HttpClient`, OkHttp
  and Apache clients, a URL built across methods or from several fields' values, a `RestTemplate`
  bean's `rootUri` set in another class, and Kotlin.
- **project-0.** `Project0HttpIT` checks the cases HTTP-1 to HTTP-5, VAL-1, VAL-2, VAL-5 and the
  extractor side of EXT-1, with the repositories built in the sandbox and read through javac, and
  parsed without builds, and checks both give the same facts. It runs when `lantern.test.project0`
  is set, with the same command as `Project0BuildIT` (see Commands in `CLAUDE.md`) but `-pl
  lantern-extract` and `-Dit.test=Project0HttpIT`.
