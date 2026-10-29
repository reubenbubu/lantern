package com.example.events;

import org.springframework.stereotype.Component;

@Component
public class OrderEventsHandler {

  @OrderEvents
  public void onOrder(String event) {}
}
