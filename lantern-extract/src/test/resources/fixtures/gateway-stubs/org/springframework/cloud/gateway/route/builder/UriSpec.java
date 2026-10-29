package org.springframework.cloud.gateway.route.builder;

import java.net.URI;
import java.util.function.Function;
import org.springframework.cloud.gateway.route.Route;

public class UriSpec {
  public Buildable<Route> uri(String uri) {
    return null;
  }

  public Buildable<Route> uri(URI uri) {
    return null;
  }

  public UriSpec filters(Function<GatewayFilterSpec, UriSpec> fn) {
    return this;
  }
}
