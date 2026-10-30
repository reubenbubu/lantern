package com.example.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
public class RelayConfig implements WebSocketMessageBrokerConfigurer {

  @Value("${relay.host}")
  private String relayHost;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws-relay")
        .addInterceptors(new TokenHandshakeInterceptor())
        .setHandshakeHandler(new UserHandshakeHandler());
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableStompBrokerRelay("/exchange", "/amq/queue")
        .setRelayHost(relayHost)
        .setRelayPort(61613)
        .setVirtualHost("/live")
        .setUserDestinationBroadcast("/topic/unresolved-user");
  }
}
