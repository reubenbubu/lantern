package org.springframework.web.reactive.function.client;

import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

public interface WebClient {
  static WebClient create(String baseUrl) {
    return null;
  }

  static Builder builder() {
    return null;
  }

  Spec get();

  Spec method(HttpMethod method);

  interface Builder {
    Builder baseUrl(String baseUrl);

    WebClient build();
  }

  interface Spec {
    Spec uri(String uri, Object... variables);

    Spec retrieve();

    <T> Mono<T> bodyToMono(Class<T> type);
  }
}
