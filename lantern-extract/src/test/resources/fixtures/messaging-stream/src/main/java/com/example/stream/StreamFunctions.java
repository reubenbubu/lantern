package com.example.stream;

import java.time.Clock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StreamFunctions {

  @Bean
  public Function<String, String> process() {
    return order -> order;
  }

  @Bean
  public Consumer<String> audit() {
    return line -> {};
  }

  @Bean
  public Supplier<String> publishShipment() {
    return () -> "shipment";
  }

  @Bean
  public Consumer<String> receiveShipment() {
    return shipment -> {};
  }

  @Bean
  public Function<String, String> uppercase() {
    return String::toUpperCase;
  }

  @Bean
  public Supplier<Clock> clock() {
    return Clock::systemUTC;
  }
}
