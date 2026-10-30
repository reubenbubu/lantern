package org.springframework.web.client;

import org.springframework.http.HttpMethod;

public interface RestClient {
  static RestClient create(String baseUrl) {
    return null;
  }

  static Builder builder() {
    return null;
  }

  Builder mutate();

  Spec get();

  Spec post();

  Spec method(HttpMethod method);

  interface Builder {
    Builder baseUrl(String baseUrl);

    Builder defaultHeader(String name, String... values);

    RestClient build();
  }

  interface Spec {
    Spec uri(String uri, Object... variables);

    Spec uri(java.net.URI uri);

    Spec body(Object body);

    Spec retrieve();

    <T> T body(Class<T> type);
  }
}
