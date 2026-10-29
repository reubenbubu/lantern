package com.example.shop.web.remote;

import com.example.api.OrdersApi;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RemoteOrders implements OrdersApi {

  @Override
  public String orders() {
    return "orders";
  }
}
