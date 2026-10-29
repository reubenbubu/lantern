package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.compose.EndOfLife.Product;
import io.lantern.compose.EndOfLife.Support;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The end-of-life table bundled with Lantern (ADR 0052). */
class EndOfLifeTest {

  @Test
  void readsTheBundledTable() {
    EndOfLife table = EndOfLife.BUNDLED;

    assertThat(table.asOf()).isEqualTo(LocalDate.of(2026, 10, 29));
    assertThat(table.support(Product.JAVA, "21"))
        .isEqualTo(new Support.Until("21", LocalDate.of(2029, 12, 31)));
    assertThat(table.support(Product.JAVA, "1.8"))
        .isEqualTo(new Support.Until("8", LocalDate.of(2030, 12, 31)));
    assertThat(table.support(Product.SPRING_BOOT, "2.7.18"))
        .isEqualTo(new Support.Until("2.7", LocalDate.of(2023, 6, 30)));
    assertThat(table.support(Product.SPRING_FRAMEWORK, "7.0.1"))
        .isEqualTo(new Support.Until("7.0", LocalDate.of(2027, 7, 31)));
    assertThat(table.support(Product.SPRING_BOOT, "3.5.14"))
        .isEqualTo(new Support.Until("3.5", LocalDate.of(2026, 6, 30)));
    assertThat(table.support(Product.SPRING_BOOT, "4.0.0-RC1"))
        .isEqualTo(new Support.Until("4.0", LocalDate.of(2026, 12, 31)));
    assertThat(table.support(Product.SPRING_FRAMEWORK, "5.3.39"))
        .isEqualTo(new Support.Until("5.3", LocalDate.of(2024, 8, 31)));
  }

  @Test
  void aLineOlderThanTheTableHasEndedAndANewerOneIsNotKnown() {
    EndOfLife table = EndOfLife.BUNDLED;

    assertThat(table.support(Product.JAVA, "1.7")).isEqualTo(new Support.Older("7"));
    assertThat(table.support(Product.SPRING_BOOT, "1.2.0")).isEqualTo(new Support.Older("1.2"));
    assertThat(table.support(Product.JAVA, "99")).isEqualTo(new Support.Unknown());
    // Releases Temurin never built: when the next feature release came out.
    assertThat(table.support(Product.JAVA, "12"))
        .isEqualTo(new Support.Until("12", LocalDate.of(2019, 9, 17)));
    assertThat(table.support(Product.JAVA, "9"))
        .isEqualTo(new Support.Until("9", LocalDate.of(2018, 3, 20)));
    assertThat(table.support(Product.SPRING_FRAMEWORK, "4.2.9"))
        .isEqualTo(new Support.Older("4.2"));
    assertThat(table.support(Product.SPRING_BOOT, "${boot.version}"))
        .isEqualTo(new Support.Unknown());
    assertThat(table.support(Product.SPRING_BOOT, null)).isEqualTo(new Support.Unknown());
  }

  @Test
  void aLineBetweenTwoLinesEndedNoLaterThanTheNextNewerOne() {
    EndOfLife table =
        EndOfLife.parse(List.of("as-of,2026-10-29", "java,8,2030-12-31", "java,16,2021-09-30"));

    assertThat(table.support(Product.JAVA, "13"))
        .isEqualTo(new Support.EndedBy("13", LocalDate.of(2021, 9, 30)));
  }

  @Test
  void ordersLinesByTheirNumbers() {
    assertThat(EndOfLife.BY_VERSION.compare("2.10", "2.9")).isPositive();
    assertThat(EndOfLife.BY_VERSION.compare("11", "8")).isPositive();
    assertThat(EndOfLife.BY_VERSION.compare("3.0", "3.0")).isZero();
  }

  @Test
  void refusesAnInvalidTable() {
    for (List<String> invalid :
        List.of(
            List.of("java,21,2029-12-31"),
            List.of("as-of,2026-10-29", "java,21"),
            List.of("as-of,2026-10-29", "cobol,1,2000-01-01"),
            List.of("as-of,2026-10-29", "java,1.8,2030-12-31"),
            List.of("as-of,2026-10-29", "spring-boot,3.5.1,2026-06-30"),
            List.of("as-of,2026-10-29", "java,21,2029-12-31", "java,21,2029-12-31"),
            List.of("as-of,2026-10-29", "java,21,soon"),
            List.of("as-of,2026-10-29", "as-of,2026-10-30"))) {
      assertThatThrownBy(() -> EndOfLife.parse(invalid))
          .as("%s", invalid)
          .isInstanceOf(RuntimeException.class);
    }
  }
}
