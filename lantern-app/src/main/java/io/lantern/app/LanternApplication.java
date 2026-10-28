package io.lantern.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the Lantern service. With the arguments {@code analyze [<name>=]<path>...} it runs
 * one analysis and exits with 0 on success and 1 on failure (see {@link AnalyzeCommand}).
 */
@SpringBootApplication
public class LanternApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(LanternApplication.class, args);
    if (AnalyzeCommand.requested(args)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
