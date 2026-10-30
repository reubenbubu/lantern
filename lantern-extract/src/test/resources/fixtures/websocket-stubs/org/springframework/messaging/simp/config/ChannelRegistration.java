package org.springframework.messaging.simp.config;

import org.springframework.messaging.support.ChannelInterceptor;

public class ChannelRegistration {
  public ChannelRegistration interceptors(ChannelInterceptor... interceptors) {
    return this;
  }
}
