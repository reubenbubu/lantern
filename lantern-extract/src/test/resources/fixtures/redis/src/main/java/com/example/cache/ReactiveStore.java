package com.example.cache;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

public class ReactiveStore {

  private final ReactiveRedisTemplate<String, String> reactive;

  ReactiveStore(ReactiveRedisTemplate<String, String> reactive) {
    this.reactive = reactive;
  }

  Object permission(String user) {
    return reactive.opsForValue().get("perm:" + user);
  }

  Object options() {
    // Not an operation on data: ScanOptions is Spring Data Redis, not Redis use.
    return ScanOptions.scanOptions().match("x*").build();
  }
}
