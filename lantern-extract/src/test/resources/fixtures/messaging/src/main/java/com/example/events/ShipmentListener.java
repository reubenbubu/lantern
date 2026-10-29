package com.example.events;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

@Component
public class ShipmentListener {

  @JmsListener(destination = "${events.jms.shipments}")
  @SendTo("shipments.acks")
  public String dispatched(String id) {
    return id;
  }

  @JmsListener(destination = "${events.jms.returns}")
  public void returned(String id) {}
}
