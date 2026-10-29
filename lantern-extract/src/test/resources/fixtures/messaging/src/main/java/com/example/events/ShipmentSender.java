package com.example.events;

import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class ShipmentSender {

  private final JmsTemplate jms;

  public ShipmentSender(JmsTemplate jms) {
    this.jms = jms;
  }

  public void dispatched(long id) {
    jms.convertAndSend(Topics.SHIPMENTS, Long.toString(id));
  }
}
