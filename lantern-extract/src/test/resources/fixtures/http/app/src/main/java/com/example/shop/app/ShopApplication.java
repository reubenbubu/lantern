package com.example.shop.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.example.shop.clients.feign")
public class ShopApplication {

  public static void main(String[] args) {}
}
