package com.example.shop.web.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** An API interface, as an OpenAPI generator writes them. */
@RequestMapping("/v2")
public interface CatalogApi {

  @GetMapping("/products")
  String products();

  @GetMapping("/health")
  default String health() {
    return "up";
  }
}
