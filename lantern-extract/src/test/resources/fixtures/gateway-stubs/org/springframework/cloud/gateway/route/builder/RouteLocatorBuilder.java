package org.springframework.cloud.gateway.route.builder;

import java.util.function.Function;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

public class RouteLocatorBuilder {
  public Builder routes() {
    return new Builder();
  }

  public static class Builder {
    public Builder route(String id, Function<PredicateSpec, Buildable<Route>> fn) {
      return this;
    }

    public Builder route(Function<PredicateSpec, Buildable<Route>> fn) {
      return this;
    }

    public RouteLocator build() {
      return null;
    }
  }
}
