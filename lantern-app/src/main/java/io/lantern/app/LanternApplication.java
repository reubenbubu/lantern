package io.lantern.app;

import io.lantern.harness.Prompts;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the Lantern service. With the arguments {@code analyze [--system=<name>]
 * [--title=<title>] [<name>=]<path>...} it runs one analysis, and with {@code remove
 * --system=<name> <repo>...} it removes repositories from a system, and with {@code render
 * --system=<name> [--snapshot=<number>]} it writes a system's documents again from the store; each
 * exits with 0 on success and 1 on failure (see {@link AnalyzeCommand}); {@code judge-labels}
 * judges the labelled wording texts with the current prompts (see {@link JudgeLabelsCommand}).
 */
@SpringBootApplication
public class LanternApplication {

  public static void main(String[] args) {
    promptsFolder(args);
    ConfigurableApplicationContext context = SpringApplication.run(LanternApplication.class, args);
    if (AnalyzeCommand.requested(args) || JudgeLabelsCommand.requested(args)) {
      System.exit(SpringApplication.exit(context));
    }
  }

  /**
   * Takes {@code --lantern.prompts.dir=<folder>} from the command line as the prompts folder before
   * any role reads a prompt (ADR 0113); without it, the {@code prompts} folder of the working
   * directory is used if there is one, else the built-in copies.
   */
  static void promptsFolder(String[] args) {
    String option = "--" + Prompts.FOLDER_PROPERTY + "=";
    for (String arg : args) {
      if (arg.startsWith(option)) {
        System.setProperty(Prompts.FOLDER_PROPERTY, arg.substring(option.length()));
      }
    }
  }
}
