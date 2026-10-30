package org.springframework.web.socket.config.annotation;

public interface StompEndpointRegistry {
  StompWebSocketEndpointRegistration addEndpoint(String... paths);
}
