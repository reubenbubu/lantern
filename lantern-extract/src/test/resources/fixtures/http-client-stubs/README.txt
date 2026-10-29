Stand-ins for the HTTP client APIs the http fixture uses (Spring Cloud OpenFeign, Feign, Spring's
HttpMethod, RestTemplate, RestClient and WebClient, Reactor's Mono, the Jakarta REST client API,
MicroProfile's @RegisterRestClient, Spring's HTTP interfaces, their proxy factory and adapters, and
their groups and group configurers, OkHttp and Apache HttpClient 4 and 5, with their fluent APIs),
with the same names and the members the fixture calls, so a test can compile the fixture without
those jars on Lantern's test classpath.
