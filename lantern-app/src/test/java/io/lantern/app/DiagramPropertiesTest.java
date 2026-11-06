package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.compose.DiagramLimits;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The setting {@code lantern.diagrams} (#836; its defaults in properties, #764). */
class DiagramPropertiesTest {

  private static DiagramLimits bound(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("lantern.diagrams", DiagramProperties.class)
        .limits();
  }

  @Test
  void theDefaultsAreThoseOfThePropertiesFileNoneInCode() throws Exception {
    Properties file = new Properties();
    try (InputStream in =
        DiagramLimits.class.getClassLoader().getResourceAsStream(DiagramLimits.DEFAULTS)) {
      file.load(in);
    }
    // The owner's values of 5 Nov 2026 (#836): 8 elements, 2 more tolerated.
    assertThat(file.getProperty("lantern.diagrams.target")).isEqualTo("8");
    assertThat(file.getProperty("lantern.diagrams.tolerance")).isEqualTo("2");
    assertThat(bound(Map.of()))
        .isEqualTo(DiagramLimits.DEFAULT)
        .isEqualTo(
            new DiagramLimits(
                Integer.parseInt(file.getProperty("lantern.diagrams.target")),
                Integer.parseInt(file.getProperty("lantern.diagrams.tolerance")),
                Integer.parseInt(file.getProperty("lantern.diagrams.lines-per-element")),
                Double.parseDouble(file.getProperty("lantern.diagrams.scale")),
                Integer.parseInt(file.getProperty("lantern.diagrams.crossings")),
                Integer.parseInt(file.getProperty("lantern.diagrams.smallest-group"))));
  }

  @Test
  void eachValueSetOverridesItsDefaultAlone() {
    DiagramLimits d = DiagramLimits.DEFAULT;
    assertThat(bound(Map.of("lantern.diagrams.target", "20")).target()).isEqualTo(20);
    assertThat(bound(Map.of("lantern.diagrams.tolerance", "5")).tolerance()).isEqualTo(5);
    assertThat(bound(Map.of("lantern.diagrams.lines-per-element", "3")).linesPerElement())
        .isEqualTo(3);
    assertThat(bound(Map.of("lantern.diagrams.scale", "0.7")).scale()).isEqualTo(0.7);
    assertThat(bound(Map.of("lantern.diagrams.crossings", "9")).crossings()).isEqualTo(9);
    assertThat(bound(Map.of("lantern.diagrams.smallest-group", "3")))
        .isEqualTo(
            new DiagramLimits(
                d.target(), d.tolerance(), d.linesPerElement(), d.scale(), d.crossings(), 3));
  }

  @Test
  void aValueOutOfRangeIsRefused() {
    assertThatThrownBy(() -> bound(Map.of("lantern.diagrams.target", "0")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target");
    assertThatThrownBy(() -> bound(Map.of("lantern.diagrams.scale", "1.5")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scale");
    assertThatThrownBy(() -> bound(Map.of("lantern.diagrams.smallest-group", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("smallest group");
  }
}
