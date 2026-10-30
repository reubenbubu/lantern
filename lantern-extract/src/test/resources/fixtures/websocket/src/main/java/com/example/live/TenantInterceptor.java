package com.example.live;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

/** An interceptor through the application's own base class, registered nowhere. */
public class TenantInterceptor extends AuthInterceptor {

  @Override
  public void postSend(Message<?> message, MessageChannel channel, boolean sent) {}
}
