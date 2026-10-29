package org.springframework.messaging;

/** Stand-in for Spring Messaging's message. */
public interface Message<T> {
  T getPayload();
}
