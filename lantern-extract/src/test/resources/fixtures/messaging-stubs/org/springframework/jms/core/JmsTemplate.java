package org.springframework.jms.core;

/** Stand-in for Spring JMS's template, for compiling the messaging fixture. */
public class JmsTemplate {
  public void convertAndSend(Object message) {}

  public void convertAndSend(String destinationName, Object message) {}

  public void convertAndSend(Destination destination, Object message) {}

  /** Stand-in for jakarta.jms.Destination. */
  public interface Destination {}
}
