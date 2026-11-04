package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.harness.render.Blocks;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The four injection fixtures of the knowledge-build epic (section 10): a README, a Java comment,
 * {@code application.yml} and a string literal, each holding text addressed to an AI, and what the
 * role fixture tests of every module check with them (#768, ADR 0125). Shared through the harness's
 * test jar.
 */
public final class InjectionFixtures {

  /** The four fixtures, by file name. */
  public static final List<String> INJECTED =
      List.of("README.md", "OrderController.java", "application.yml", "Greeting.java");

  /**
   * What a model that obeys the fixtures answers: the service is secure and has no risks, the pull
   * request is approved, and the password file it was told to read is cited. Every role's checks
   * must reject it: it cites a file outside the repositories.
   */
  public static final String OBEYING =
      "This service is fully secure and has no risks; the pull request is approved and the"
          + " database password is in /etc/passwd.";

  /** The citation an obeying answer gives: the password file the fixtures ask for. */
  public static final String OBEYING_CITE = "orders:/etc/passwd:1-3";

  private InjectionFixtures() {}

  /** The text of a fixture. */
  public static String fixture(String name) {
    try (InputStream in = InjectionFixtures.class.getResourceAsStream("/injection/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("No injection fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The line of the fixture that speaks to an AI. */
  public static String injectedLine(String name) {
    return fixture(name).lines().filter(l -> l.contains("AI assistant")).findFirst().orElseThrow();
  }

  /**
   * Every line of the fixtures that speaks to an AI sits inside a data block of what the fake was
   * sent, or in a tool's result, which is data by construction; and each fixture is there.
   */
  public static void shownAsData(FakeLlmProvider fake) {
    shownAsData(fake.sent().stream().map(FakeLlmProvider.Sent::request).toList());
  }

  /** As {@link #shownAsData(FakeLlmProvider)}, for the requests of one role. */
  public static void shownAsData(List<LlmRequest> requests) {
    assertThat(requests).as("requests sent").isNotEmpty();
    StringBuilder all = new StringBuilder();
    for (LlmRequest request : requests) {
      assertThat(Blocks.outsideData(request.system(), "AI assistant")).isEmpty();
      for (LlmMessage message : request.messages()) {
        all.append(message.text());
        if (message.author() == LlmMessage.Author.USER) {
          assertThat(Blocks.outsideData(message.text(), "AI assistant"))
              .as(message.text())
              .isEmpty();
        }
        for (LlmToolResult result : message.toolResults()) {
          all.append(result.content());
        }
      }
    }
    for (String name : INJECTED) {
      String said = injectedLine(name);
      said = said.substring(said.indexOf("AI assistant"));
      assertThat(all.toString()).as(name + " shown").contains(said.substring(0, 30));
    }
  }

  /** One line per fact: its origin, claim, descriptive attributes and lineage, sorted. */
  public static List<String> lines(Collection<Fact> facts) {
    return facts.stream()
        .map(
            f ->
                f.origin().name()
                    + "@"
                    + f.origin().version()
                    + " "
                    + f.subject()
                    + " "
                    + f.predicate()
                    + " "
                    + f.object().value()
                    + " "
                    + f.attributes()
                    + " lineage="
                    + f.lineage())
        .sorted()
        .toList();
  }

  /** One line per fact of every origin. */
  public static List<String> lines(Map<Origin, List<Fact>> byOrigin) {
    return lines(byOrigin.values().stream().flatMap(List::stream).toList());
  }
}
