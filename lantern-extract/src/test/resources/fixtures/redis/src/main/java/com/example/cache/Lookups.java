package com.example.cache;

import org.springframework.cache.annotation.Cacheable;

/** A cached method: a Redis use, since the module's cache provider is Redis (spring.cache.type). */
public class Lookups {

  @Cacheable("names")
  public String name(long id) {
    return "n" + id;
  }
}
