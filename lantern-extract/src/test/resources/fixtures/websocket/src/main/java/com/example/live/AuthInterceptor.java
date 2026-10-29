package com.example.live;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
public class AuthInterceptor implements ChannelInterceptor {

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    return message;
  }
}
