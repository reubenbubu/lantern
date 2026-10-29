package com.example.cache;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@RedisHash(value = "tokens", timeToLive = 600)
public class Token {
  @Id String id;
}
