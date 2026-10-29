package org.springframework.web.reactive.function.client.support;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;

@FunctionalInterface
public interface WebClientHttpServiceGroupConfigurer
    extends HttpServiceGroupConfigurer<WebClient.Builder> {}
