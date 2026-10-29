package com.example.cache;

import org.springframework.cache.annotation.Cacheable;

/** A cache annotation alone says nothing about Redis: no Redis dependency is involved here. */
public class Lookups {

  @Cacheable("names")
  public String name(long id) {
    return "n" + id;
  }
}
