package com.example.live;

import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
public class AuthInterceptor implements ChannelInterceptor {

  private static final Pattern ORGANIZATION =
      Pattern.compile("^/topic/organizations/(\\d+)/notifications$");

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    String token = accessor.getFirstNativeHeader("Authorization");
    if (token != null) {
      accessor.setUser(() -> token);
    }
    String destination = accessor.getDestination();
    if (destination != null && !ORGANIZATION.matcher(destination).matches()) {
      return null;
    }
    return message;
  }
}
