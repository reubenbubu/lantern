package org.springframework.cloud.gateway.filter;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public interface GlobalFilter {
  Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain);
}
