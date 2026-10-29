package com.example.shop.web.v3;

import org.springframework.web.bind.annotation.GetMapping;

@ApiController
public class ItemController {

  @GetMapping("/items")
  public String items() {
    return "items";
  }
}
