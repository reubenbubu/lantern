package org.springframework.web.server;

import reactor.core.publisher.Mono;

public interface WebFilterChain {
  Mono<Void> filter(ServerWebExchange exchange);
}
