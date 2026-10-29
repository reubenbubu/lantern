package com.example.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/v4")
public interface OrdersApi {

  @GetMapping("/orders")
  String orders();
}
