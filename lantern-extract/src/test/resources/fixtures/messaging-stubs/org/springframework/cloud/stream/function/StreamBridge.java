package org.springframework.cloud.stream.function;

/** Stand-in for Spring Cloud Stream's bridge, for compiling the messaging-stream fixture. */
public class StreamBridge {
  public boolean send(String bindingName, Object data) {
    return true;
  }

  public boolean send(String bindingName, String binderName, Object data) {
    return true;
  }
}
