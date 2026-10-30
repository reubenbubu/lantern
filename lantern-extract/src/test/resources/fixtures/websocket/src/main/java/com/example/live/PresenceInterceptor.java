package com.example.live;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

public class PresenceInterceptor implements ExecutorChannelInterceptor {

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
    accessor.getNativeHeader("X-Tenant");
    return message;
  }
}
