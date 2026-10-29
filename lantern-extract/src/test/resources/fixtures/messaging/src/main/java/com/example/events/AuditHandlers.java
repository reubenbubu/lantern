package com.example.events;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@KafkaListener(topics = Topics.AUDIT, groupId = "audit")
public class AuditHandlers {

  @KafkaHandler
  public void text(String event) {}

  @KafkaHandler
  public void number(Long event) {}

  public void notAHandler(String event) {}
}
