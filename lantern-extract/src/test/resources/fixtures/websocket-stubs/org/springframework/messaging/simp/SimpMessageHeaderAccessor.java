package org.springframework.messaging.simp;

import java.util.List;
import org.springframework.messaging.Message;

public class SimpMessageHeaderAccessor {
  public static SimpMessageHeaderAccessor wrap(Message<?> message) {
    return new SimpMessageHeaderAccessor();
  }

  public List<String> getNativeHeader(String name) {
    return null;
  }
}
