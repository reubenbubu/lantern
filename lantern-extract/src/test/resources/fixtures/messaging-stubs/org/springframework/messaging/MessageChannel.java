package org.springframework.messaging;

/** Stand-in for Spring Messaging's channel. */
public interface MessageChannel {
  boolean send(Message<?> message);
}
