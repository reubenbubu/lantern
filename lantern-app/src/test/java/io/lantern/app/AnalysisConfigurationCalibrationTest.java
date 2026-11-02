package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.harness.Harness;
import org.junit.jupiter.api.Test;

/** How {@code lantern.harness.calibrate} is read (ADR 0112). */
class AnalysisConfigurationCalibrationTest {

  @Test
  void eachRoleTakesItsSizeUnderTheRunsTrial() {
    assertThat(AnalysisConfiguration.calibrations("", "1")).isEmpty();
    assertThat(AnalysisConfiguration.calibrations(" resolve:1 , support-judge:20", "2"))
        .containsExactly(
            java.util.Map.entry("resolve", new Harness.Calibration(1, "2")),
            java.util.Map.entry("support-judge", new Harness.Calibration(20, "2")));
  }

  @Test
  void aMalformedEntryStopsTheRun() {
    assertThatThrownBy(() -> AnalysisConfiguration.calibrations("resolve", "1"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AnalysisConfiguration.calibrations("resolve:x", "1"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AnalysisConfiguration.calibrations("resolve:0", "1"))
        .isInstanceOf(IllegalArgumentException.class);
    // A misspelled role would otherwise change nothing and look like a recording.
    assertThatThrownBy(() -> AnalysisConfiguration.calibrations("investigate-lead:5", "1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("investigate-leads");
  }
}
