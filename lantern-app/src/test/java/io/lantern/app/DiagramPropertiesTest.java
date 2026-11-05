package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.compose.DiagramLimits;
import org.junit.jupiter.api.Test;

/** The setting {@code lantern.diagrams} (#836). */
class DiagramPropertiesTest {

  @Test
  void eachValueLeftUnsetKeepsTheDefaults() {
    assertThat(new DiagramProperties(null, null, null, null, null).limits())
        .isEqualTo(DiagramLimits.DEFAULT);
    assertThat(new DiagramProperties(20, 5, null, null, null).limits())
        .isEqualTo(
            new DiagramLimits(
                20,
                5,
                DiagramLimits.DEFAULT.linesPerElement(),
                DiagramLimits.DEFAULT.scale(),
                DiagramLimits.DEFAULT.crossings()));
  }

  @Test
  void aValueOutOfRangeIsRefused() {
    assertThatThrownBy(() -> new DiagramProperties(0, null, null, null, null).limits())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target");
    assertThatThrownBy(() -> new DiagramProperties(null, null, null, 1.5, null).limits())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scale");
  }
}
