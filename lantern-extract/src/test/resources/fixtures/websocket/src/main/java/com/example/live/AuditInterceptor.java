package com.example.live;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;

public class AuditInterceptor implements ChannelInterceptor {

  @Override
  public void postSend(Message<?> message, MessageChannel channel, boolean sent) {}

  @Override
  public void afterSendCompletion(
      Message<?> message, MessageChannel channel, boolean sent, Exception ex) {}
}
