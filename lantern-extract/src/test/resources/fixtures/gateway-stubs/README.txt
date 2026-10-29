Stand-ins for the Spring Cloud Gateway, WebFlux and Reactor types the gateway fixture uses
(RouteLocatorBuilder and its specs, the filter interfaces and factory, ServerWebExchange, WebFilter,
HttpMethod and Mono), with the same names and the members the fixture calls, so a test can compile
the fixture without those jars on Lantern's test classpath.
