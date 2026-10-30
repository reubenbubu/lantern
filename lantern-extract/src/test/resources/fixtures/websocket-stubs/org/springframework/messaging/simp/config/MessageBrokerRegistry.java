package org.springframework.messaging.simp.config;

public class MessageBrokerRegistry {
  public MessageBrokerRegistry enableSimpleBroker(String... prefixes) {
    return this;
  }

  public StompBrokerRelayRegistration enableStompBrokerRelay(String... prefixes) {
    return new StompBrokerRelayRegistration();
  }

  public MessageBrokerRegistry setApplicationDestinationPrefixes(String... prefixes) {
    return this;
  }

  public MessageBrokerRegistry setUserDestinationPrefix(String prefix) {
    return this;
  }
}
