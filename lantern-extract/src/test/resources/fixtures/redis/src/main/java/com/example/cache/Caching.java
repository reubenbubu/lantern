package com.example.cache;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

@Configuration
public class Caching {

  @Bean
  RedisCacheConfiguration cacheConfiguration() {
    return RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10));
  }

  @Bean
  RedisCacheManager cacheManager(RedisConnectionFactory factory) {
    return RedisCacheManager.create(factory);
  }

  void listeners(
      RedisMessageListenerContainer container, ReactiveRedisMessageListenerContainer reactive) {
    container.addMessageListener(null, (Topic) null);
    reactive.receive((Topic) null);
  }
}
