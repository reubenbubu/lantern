package org.springframework.web.socket.config.annotation;

public interface StompWebSocketEndpointRegistration {
  StompWebSocketEndpointRegistration setAllowedOrigins(String... origins);

  StompWebSocketEndpointRegistration setAllowedOriginPatterns(String... patterns);

  StompWebSocketEndpointRegistration withSockJS();

  StompWebSocketEndpointRegistration addInterceptors(
      org.springframework.web.socket.server.HandshakeInterceptor... interceptors);

  StompWebSocketEndpointRegistration setHandshakeHandler(
      org.springframework.web.socket.server.HandshakeHandler handler);
}
