package org.springframework.amqp.core;

/** Stand-in for Spring AMQP's constants, for compiling the messaging fixture. */
public abstract class ExchangeTypes {
  public static final String DIRECT = "direct";
  public static final String TOPIC = "topic";
  public static final String FANOUT = "fanout";
  public static final String HEADERS = "headers";
  public static final String SYSTEM = "system";
}
