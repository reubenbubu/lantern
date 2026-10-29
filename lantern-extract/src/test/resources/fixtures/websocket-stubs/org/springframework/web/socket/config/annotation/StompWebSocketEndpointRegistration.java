package org.springframework.web.socket.config.annotation;

public interface StompWebSocketEndpointRegistration {
  StompWebSocketEndpointRegistration setAllowedOrigins(String... origins);

  StompWebSocketEndpointRegistration setAllowedOriginPatterns(String... patterns);

  StompWebSocketEndpointRegistration withSockJS();
}
