package com.example.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class Notifier {

  private static final String REFUNDS = "refunds";

  private final StreamBridge bridge;

  @Value("${stream.dynamic-topic:dynamic.topic}")
  private String dynamicTopic;

  public Notifier(StreamBridge bridge) {
    this.bridge = bridge;
  }

  public void refund(String refund) {
    bridge.send(REFUNDS, refund);
  }

  public void refundThroughRabbit(String refund) {
    bridge.send(REFUNDS, "rabbit1", refund);
  }

  public void notify(String message) {
    bridge.send("notify", message);
  }

  public void dynamic(String message) {
    bridge.send(dynamicTopic, message);
  }

  public void secret(String message) {
    bridge.send("secretOut", message);
  }

  public void chosen(String binding, String message) {
    bridge.send(binding, message);
  }
}
