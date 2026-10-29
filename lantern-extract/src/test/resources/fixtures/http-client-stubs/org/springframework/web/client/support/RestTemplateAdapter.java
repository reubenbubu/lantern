package org.springframework.web.client.support;

import org.springframework.web.client.RestTemplate;
import org.springframework.web.service.invoker.HttpExchangeAdapter;

public final class RestTemplateAdapter implements HttpExchangeAdapter {
  public static RestTemplateAdapter create(RestTemplate restTemplate) {
    return null;
  }
}
