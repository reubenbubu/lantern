package com.example.live;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class Pusher {

  private static final String USER_TOPIC = "/topic/notifications";
  private static final String ORG_TOPIC = "/topic/organizations/%d/notifications";

  private final SimpMessagingTemplate template;
  private final String audit = "/topic/audit";

  public Pusher(SimpMessagingTemplate template) {
    this.template = template;
  }

  public void toUser(Long userId, Object payload) {
    template.convertAndSendToUser(userId.toString(), USER_TOPIC, payload);
  }

  public void toOrganization(Long organizationId, Object payload) {
    template.convertAndSend(ORG_TOPIC.formatted(organizationId), payload);
  }

  public void toRoom(String room, Object payload) {
    template.convertAndSend("/topic/rooms/" + room, payload);
  }

  public void toAudit(Object payload) {
    template.convertAndSend(audit, payload);
  }

  public void toChosen(String destination, Object payload) {
    template.convertAndSend(destination, payload);
  }

  public void toDefault(Object payload) {
    template.convertAndSend(payload);
  }
}
