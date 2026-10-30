package org.springframework.web.socket.config.annotation;

public interface WebSocketHandlerRegistration {
  WebSocketHandlerRegistration setAllowedOrigins(String... origins);

  WebSocketHandlerRegistration withSockJS();
}
