package org.springframework.cloud.gateway.route.builder;

import org.springframework.cloud.gateway.filter.GatewayFilter;

public class GatewayFilterSpec extends UriSpec {
  public GatewayFilterSpec filter(GatewayFilter filter) {
    return this;
  }

  public GatewayFilterSpec filters(GatewayFilter... filters) {
    return this;
  }

  public GatewayFilterSpec stripPrefix(int parts) {
    return this;
  }

  public GatewayFilterSpec prefixPath(String prefix) {
    return this;
  }
}
