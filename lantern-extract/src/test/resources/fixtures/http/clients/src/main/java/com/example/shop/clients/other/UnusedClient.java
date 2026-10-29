package com.example.shop.clients.other;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/** Outside the packages the application enables. */
@FeignClient("unused")
public interface UnusedClient {

  @GetMapping("/ping")
  String ping();
}
