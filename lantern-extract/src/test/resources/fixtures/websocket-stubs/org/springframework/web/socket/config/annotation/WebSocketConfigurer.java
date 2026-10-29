package org.springframework.web.socket.config.annotation;

public interface WebSocketConfigurer {
  void registerWebSocketHandlers(WebSocketHandlerRegistry registry);
}
