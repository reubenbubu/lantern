package io.lantern.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Lantern service. */
@SpringBootApplication
public class LanternApplication {

  public static void main(String[] args) {
    SpringApplication.run(LanternApplication.class, args);
  }
}
