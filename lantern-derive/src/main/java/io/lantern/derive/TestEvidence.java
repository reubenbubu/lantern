package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.derive.attributes.Types;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The test evidence the model holds for the modules of a repository (ADR 0039's neighbours, #230):
 * the test classes counted in the test sources, the coverage tools the builds run and the minimum
 * coverage they enforce. The brief draws them; what they say is read here, so the composer never
 * reads a fact's fields by name.
 */
public final class TestEvidence {

  /** What JaCoCo's coverage counters measure, as the brief names them. */
  private static final Map<String, String> COUNTERS =
      Map.of(
          "LINE", "lines",
          "BRANCH", "branches",
          "INSTRUCTION", "instructions",
          "METHOD", "methods",
          "CLASS", "classes",
          "COMPLEXITY", "the cyclomatic complexity");

  private TestEvidence() {}

  /**
   * A test class counted in a module's test sources.
   *
   * @param fact the test class's fact
   * @param module the module whose test sources it is in
   * @param tests the number of tests the class has
   * @param architecture whether it is an architecture test
   */
  public record TestClass(Fact fact, EntityId module, int tests, boolean architecture) {}

  /**
   * A coverage tool a module's build runs.
   *
   * @param fact the fact that the build runs the tool
   * @param plugin the Maven plugin's {@code groupId:artifactId}
   * @param minimums the minimum coverage the tool enforces on the whole module, as the brief words
   *     it, such as {@code 80% of lines}
   */
  public record Coverage(Fact fact, String plugin, List<String> minimums) {}

  /** The test classes in the modules' test sources, in the order the model holds them. */
  public static List<TestClass> testClasses(ArchitectureModel model) {
    List<TestClass> result = new ArrayList<>();
    for (Fact fact : model.withPredicate(Types.TESTS)) {
      if (ArchitectureModel.isEntity(fact)) {
        JsonNode tests = fact.attributes().get("tests");
        JsonNode architecture = fact.attributes().get("architecture");
        result.add(
            new TestClass(
                fact,
                ArchitectureModel.object(fact),
                tests == null ? 0 : tests.asInt(),
                architecture != null && architecture.asBoolean()));
      }
    }
    return result;
  }

  /** The coverage tools a module's build runs, by their plugin, with the minimums they enforce. */
  public static List<Coverage> coverage(ArchitectureModel model, EntityId module) {
    List<Coverage> result = new ArrayList<>();
    for (Fact fact : model.attributes(module, Types.COVERAGE_TOOL)) {
      result.add(new Coverage(fact, fact.object().value(), minimums(fact)));
    }
    return result;
  }

  /**
   * The minimum coverage a coverage tool's check enforces on the whole module, as the brief words
   * it: {@code 80% of lines}. Only JaCoCo's bundle-wide covered ratios are read.
   */
  public static List<String> minimums(Fact fact) {
    List<String> found = new ArrayList<>();
    JsonNode limits = fact.attributes().get("limits");
    if (limits == null || !limits.isArray()) {
      return found;
    }
    for (JsonNode limit : limits) {
      String counter = COUNTERS.get(limit.path("counter").asString(""));
      String minimum = limit.path("minimum").asString("");
      if (counter == null
          || !limit.path("element").asString("").equals("BUNDLE")
          || !limit.path("value").asString("").equals("COVEREDRATIO")
          || minimum.isEmpty()) {
        continue;
      }
      try {
        // JaCoCo also takes a percentage, such as 80%.
        String written = minimum.strip();
        BigDecimal ratio =
            written.endsWith("%")
                ? new BigDecimal(written.substring(0, written.length() - 1).strip())
                    .movePointLeft(2)
                : new BigDecimal(written);
        if (ratio.signum() > 0 && ratio.compareTo(BigDecimal.ONE) <= 0) {
          found.add(
              ratio.movePointRight(2).stripTrailingZeros().toPlainString() + "% of " + counter);
        }
      } catch (NumberFormatException e) {
        // Not a ratio; left out.
      }
    }
    return found;
  }
}
