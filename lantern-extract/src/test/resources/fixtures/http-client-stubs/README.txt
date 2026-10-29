Stand-ins for the HTTP client APIs the http fixture uses (Spring Cloud OpenFeign, Feign, Spring's
HttpMethod, RestTemplate, RestClient and WebClient, Reactor's Mono, the Jakarta REST client API and
MicroProfile's @RegisterRestClient), with the same names and the members the fixture calls, so a
test can compile the fixture without those jars on Lantern's test classpath.
