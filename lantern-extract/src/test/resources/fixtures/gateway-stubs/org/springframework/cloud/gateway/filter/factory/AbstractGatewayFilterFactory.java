package org.springframework.cloud.gateway.filter.factory;

import org.springframework.cloud.gateway.filter.GatewayFilter;

public abstract class AbstractGatewayFilterFactory<C> {
  public abstract GatewayFilter apply(C config);
}
