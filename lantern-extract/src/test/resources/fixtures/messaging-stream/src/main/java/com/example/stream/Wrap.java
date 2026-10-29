package com.example.stream;

import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class Wrap implements Function<String, String> {

  @Override
  public String apply(String text) {
    return "[" + text + "]";
  }
}
