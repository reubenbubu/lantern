package com.example.live;

import org.springframework.messaging.core.MessageSendingOperations;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
@SendToUser("/queue/rooms")
public class RoomController {

  private final MessageSendingOperations<String> messages;

  public RoomController(MessageSendingOperations<String> messages) {
    this.messages = messages;
  }

  /** The class's @SendToUser applies. */
  @MessageMapping("/rooms/join")
  public String join(String room) {
    messages.convertAndSend("/topic/rooms/joined", room);
    return room;
  }

  /** A bare @SendToUser yields to the class's, which names a destination. */
  @MessageMapping("/rooms/ack")
  @SendToUser(broadcast = false)
  public String ack(String room) {
    return room;
  }

  /** The method's @SendTo, which names a destination, wins over the class's @SendToUser. */
  @MessageMapping("/rooms/announce")
  @SendTo("/topic/rooms")
  public String announce(String room) {
    return room;
  }

  /** Nothing returned, so nothing is sent. */
  @MessageMapping("/rooms/ping")
  public void ping(String room) {}
}
