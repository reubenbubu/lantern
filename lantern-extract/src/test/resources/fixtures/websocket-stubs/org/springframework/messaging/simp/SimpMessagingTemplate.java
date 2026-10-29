package org.springframework.messaging.simp;

public class SimpMessagingTemplate {
  public void convertAndSend(Object destination, Object payload) {}

  public void convertAndSend(Object payload) {}

  public void convertAndSendToUser(String user, String destination, Object payload) {}
}
