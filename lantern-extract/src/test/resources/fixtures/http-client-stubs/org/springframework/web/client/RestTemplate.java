package org.springframework.web.client;

import org.springframework.http.HttpMethod;

public class RestTemplate {
  public <T> T getForObject(String url, Class<T> type, Object... variables) {
    return null;
  }

  public <T> T postForObject(String url, Object request, Class<T> type, Object... variables) {
    return null;
  }

  public void delete(String url, Object... variables) {}

  public <T> T exchange(
      String url, HttpMethod method, Object request, Class<T> type, Object... variables) {
    return null;
  }
}
