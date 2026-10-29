package com.example.stream;

import org.springframework.cloud.stream.annotation.Output;
import org.springframework.messaging.MessageChannel;

public interface OrderChannels {

  @Output("ordersOut")
  MessageChannel ordersOut();
}
