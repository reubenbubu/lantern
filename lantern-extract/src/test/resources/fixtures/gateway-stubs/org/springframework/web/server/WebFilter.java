package org.springframework.web.server;

import reactor.core.publisher.Mono;

public interface WebFilter {
  Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain);
}
