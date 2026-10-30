package org.springframework.web.socket.config.annotation;

import org.springframework.web.socket.WebSocketHandler;

public interface WebSocketHandlerRegistry {
  WebSocketHandlerRegistration addHandler(WebSocketHandler handler, String... paths);
}
