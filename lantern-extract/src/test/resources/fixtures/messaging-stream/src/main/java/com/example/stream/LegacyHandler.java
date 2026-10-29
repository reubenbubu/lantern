package com.example.stream;

import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

@Component
public class LegacyHandler {

  private final Source source;
  private final OrderChannels channels;

  public LegacyHandler(Source source, OrderChannels channels) {
    this.source = source;
    this.channels = channels;
  }

  @StreamListener(Processor.INPUT)
  @SendTo(Processor.OUTPUT)
  public String handle(String in) {
    return in;
  }

  public void forward(Message<String> message) {
    source.output().send(message);
  }

  public void order(Message<String> message) {
    channels.ordersOut().send(message);
  }
}
