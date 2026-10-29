package org.springframework.cloud.gateway.route.builder;

public interface Buildable<T> {
  T build();
}
