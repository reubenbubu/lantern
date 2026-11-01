package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Validity;
import io.lantern.derive.Findings;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which of the injection screen's findings a run keeps (ADR 0101). */
class ScreenFindingsTest {

  private static final String BLOB = "0123456789abcdef0123456789abcdef01234567";

  @Test
  void aFindingOnAFileTheRunDidNotScreenStillHoldsAndOneOnAScreenedFileIsReplaced() {
    Fact readByResolve =
        Findings.injectionSuspected("orders", "config/Foo.java", BLOB, 3, 4, "AI assistant", 1);
    Fact screenedAgain =
        Findings.injectionSuspected("orders", "README.md", BLOB, 5, 5, "AI assistant", 1);

    List<Fact> kept =
        AnalysisRun.stillHolding(
            List.of(readByResolve, screenedAgain), Set.of("orders\u0000README.md"), 2);

    assertThat(kept)
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.subject()).isEqualTo(readByResolve.subject());
              assertThat(f.validity()).isEqualTo(Validity.from(2));
              assertThat(Findings.attribute(f, "lines")).isEqualTo("3-4");
              assertThat(Findings.attribute(f, "quote")).isEqualTo("AI assistant");
            });
  }
}
