package com.example.live;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
public class EchoController {

  /** Without @SendTo, the return value goes to /topic/echo. */
  @MessageMapping("/echo")
  public String echo(String text) {
    return text;
  }
}
