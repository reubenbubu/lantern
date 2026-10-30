package org.springframework.messaging.core;

public interface MessageSendingOperations<D> {
  void convertAndSend(D destination, Object payload);
}
