package com.example.edge;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration
public class RouteConfig {

  private final AuthFilter authFilter;

  @Value("${app.inventory}")
  private String inventory;

  @Value("${app.ws-path}")
  private String wsPath;

  public RouteConfig(AuthFilter authFilter) {
    this.authFilter = authFilter;
  }

  @Bean
  public RouteLocator routes(
      RouteLocatorBuilder builder, @Value("${app.billing:billing}") String billing) {
    return builder
        .routes()
        .route(
            "inventory",
            r ->
                r.path("/inventory/**")
                    .and()
                    .method(HttpMethod.GET)
                    .filters(f -> f.filter(authFilter).stripPrefix(1).prefixPath("/api"))
                    .uri("lb://" + inventory))
        .route("billing-ws", r -> r.path(wsPath).uri("lb:ws://" + billing))
        .route(
            r -> {
              return r.host("*.example.com").uri(URI.create("https://partner.example.com"));
            })
        .route(
            "checked",
            r ->
                r.path("/checked/**")
                    .filters(f -> f.filters(new AuditFilter(), authFilter))
                    .uri("http://checked:8080"))
        .build();
  }

  @Bean
  BeanMadeFilter beanMadeFilter() {
    return new BeanMadeFilter();
  }
}
