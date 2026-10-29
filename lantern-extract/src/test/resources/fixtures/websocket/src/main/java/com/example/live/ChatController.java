package com.example.live;

import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("/chat")
public class ChatController {

  @MessageMapping("/say")
  @SendTo("/topic/chat")
  public String say(String text) {
    return text;
  }

  @MessageMapping("/whisper")
  @SendToUser("/queue/whispers")
  public String whisper(String text) {
    return text;
  }

  @SubscribeMapping("/history")
  public String history() {
    return "";
  }

  @Live
  public String custom(String text) {
    return text;
  }

  @ServiceActivator
  public String activated(String text) {
    return text;
  }

  public String notMapped() {
    return "";
  }
}
