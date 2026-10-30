package org.springframework.messaging.support;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

public interface ChannelInterceptor {
  default Message<?> preSend(Message<?> message, MessageChannel channel) {
    return message;
  }

  default void postSend(Message<?> message, MessageChannel channel, boolean sent) {}

  default void afterSendCompletion(
      Message<?> message, MessageChannel channel, boolean sent, Exception ex) {}
}
