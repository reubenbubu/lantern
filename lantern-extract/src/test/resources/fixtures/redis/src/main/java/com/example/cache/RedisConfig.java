package com.example.cache;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800, redisNamespace = "shop")
public class RedisConfig {

  @Bean
  RedisLockRegistry locks(RedisConnectionFactory factory) {
    return new RedisLockRegistry(factory, "lock");
  }

  @Bean
  RedisRateLimiter limiter() {
    return new RedisRateLimiter(10, 20);
  }

  void use(RedisLockRegistry locks) {
    locks.obtain("orders");
  }
}
