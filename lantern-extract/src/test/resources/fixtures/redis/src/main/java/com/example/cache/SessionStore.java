package com.example.cache;

import java.time.Duration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

public class SessionStore {

  private static final String KEY_PREFIX = "app:session:";

  private final RedisTemplate<String, Object> redisTemplate;
  private final StringRedisTemplate stringRedisTemplate;

  SessionStore(RedisTemplate<String, Object> redisTemplate, StringRedisTemplate stringRedisTemplate) {
    this.redisTemplate = redisTemplate;
    this.stringRedisTemplate = stringRedisTemplate;
  }

  void save(String id, Object session) {
    redisTemplate.opsForValue().set(KEY_PREFIX + id, session, Duration.ofMinutes(30));
  }

  Object load(String id) {
    return redisTemplate.opsForValue().get(KEY_PREFIX + id);
  }

  void remove(String id) {
    redisTemplate.delete(KEY_PREFIX + id);
  }

  boolean locked(String id) {
    return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + id + ":lock"));
  }

  void remember(String user, String field, String value) {
    stringRedisTemplate.opsForHash().put("user:" + user, field, value);
    stringRedisTemplate.convertAndSend("events", value);
  }

  void unknown(String key) {
    redisTemplate.opsForValue().get(key);
  }
}
