package com.example.edge;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

@Component
public class HeaderFilterFactory extends AbstractGatewayFilterFactory<HeaderFilterFactory.Config> {

  @Override
  public GatewayFilter apply(Config config) {
    return (exchange, chain) -> chain.filter(exchange);
  }

  public static class Config {}
}
