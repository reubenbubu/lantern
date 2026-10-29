package com.example.events;

import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/** Sends to the audit queue, only when the audit profile is active. */
@Component
@Profile("audit")
public class AuditSender {

  private final JmsTemplate jms;

  public AuditSender(JmsTemplate jms) {
    this.jms = jms;
  }

  public void orderPlaced(long id) {
    jms.convertAndSend("audit.orders", Long.toString(id));
  }
}
