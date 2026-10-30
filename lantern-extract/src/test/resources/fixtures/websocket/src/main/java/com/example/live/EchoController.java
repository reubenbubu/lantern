package com.example.live;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
public class EchoController {

  /** Without @SendTo, the return value goes to /topic/echo. */
  @MessageMapping("/echo")
  public String echo(String text) {
    return text;
  }

  /** Without destinations, @SendToUser sends to the user's /queue and the destination. */
  @MessageMapping("/echo/me")
  @SendToUser(broadcast = false)
  public String echoMe(String text) {
    return text;
  }
}
