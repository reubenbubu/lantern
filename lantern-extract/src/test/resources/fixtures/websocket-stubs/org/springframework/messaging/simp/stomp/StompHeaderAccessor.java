package org.springframework.messaging.simp.stomp;

import java.security.Principal;
import org.springframework.messaging.Message;

public class StompHeaderAccessor {
  public static StompHeaderAccessor wrap(Message<?> message) {
    return new StompHeaderAccessor();
  }

  public String getFirstNativeHeader(String name) {
    return null;
  }

  public String getDestination() {
    return null;
  }

  public void setUser(Principal user) {}
}
