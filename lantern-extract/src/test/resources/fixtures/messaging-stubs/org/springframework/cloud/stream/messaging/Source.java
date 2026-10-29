package org.springframework.cloud.stream.messaging;

import org.springframework.messaging.MessageChannel;

/** Stand-in for Spring Cloud Stream's legacy source binding. */
public interface Source {
  String OUTPUT = "output";

  MessageChannel output();
}
