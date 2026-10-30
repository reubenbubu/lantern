package org.springframework.cloud.gateway.route.builder;

import org.springframework.http.HttpMethod;

public class PredicateSpec extends UriSpec {
  public BooleanSpec path(String... patterns) {
    return null;
  }

  public BooleanSpec method(HttpMethod... methods) {
    return null;
  }

  public BooleanSpec host(String... patterns) {
    return null;
  }
}
