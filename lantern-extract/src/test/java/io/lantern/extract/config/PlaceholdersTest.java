package io.lantern.extract.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

  private static Resolution resolve(String text, Map<String, String> config) {
    return Placeholders.resolve(text, config::get);
  }

  @Test
  void textWithoutPlaceholdersIsKept() {
    Resolution r = resolve("http://payments:8080", Map.of());
    assertThat(r.value()).isEqualTo("http://payments:8080");
    assertThat(r.resolved()).isTrue();
  }

  @Test
  void placeholdersAreReplacedByTheirResolvedValues() {
    Map<String, String> config =
        Map.of("host", "payments", "port", "8080", "url", "http://${host}:${port}");
    assertThat(resolve("${url}/api/payments", config).value())
        .isEqualTo("http://payments:8080/api/payments");
  }

  @Test
  void aKeyWithoutAValueTakesTheDefault() {
    assertThat(resolve("${missing:fallback}", Map.of()).value()).isEqualTo("fallback");
    assertThat(resolve("${missing:}", Map.of()).value()).isEmpty();
    assertThat(resolve("${missing:${other}}", Map.of("other", "x")).value()).isEqualTo("x");
    assertThat(resolve("${set:fallback}", Map.of("set", "value")).value()).isEqualTo("value");
  }

  @Test
  void aDefaultKeepsTheColonsAfterTheFirst() {
    assertThat(resolve("${url:http://localhost:8080}", Map.of()).value())
        .isEqualTo("http://localhost:8080");
  }

  @Test
  void keysMayHoldPlaceholders() {
    assertThat(
            resolve("${clients.${name}.url}", Map.of("name", "tax", "clients.tax.url", "t"))
                .value())
        .isEqualTo("t");
  }

  @Test
  void anUnresolvablePlaceholderIsKeptWithItsKey() {
    Resolution r = resolve("${TAX_SERVICE_URL}/v2/quotes", Map.of());
    assertThat(r.value()).isEqualTo("${TAX_SERVICE_URL}/v2/quotes");
    assertThat(r.unresolved()).containsExactly("TAX_SERVICE_URL");
    assertThat(r.resolved()).isFalse();
  }

  @Test
  void anUnresolvablePlaceholderInAValueIsReported() {
    Resolution r = resolve("${clients.tax.url}", Map.of("clients.tax.url", "${TAX_SERVICE_URL}"));
    assertThat(r.value()).isEqualTo("${TAX_SERVICE_URL}");
    assertThat(r.unresolved()).containsExactly("TAX_SERVICE_URL");
  }

  @Test
  void aCircularValueIsKeptUnresolved() {
    Resolution r = resolve("${a}", Map.of("a", "x${b}", "b", "${a}"));
    assertThat(r.value()).isEqualTo("x${a}");
    assertThat(r.unresolved()).containsExactly("a");
  }

  @Test
  void anUnclosedPlaceholderIsText() {
    Resolution r = resolve("${a}-${b", Map.of("a", "1"));
    assertThat(r.value()).isEqualTo("1-${b");
    assertThat(r.resolved()).isTrue();
  }

  @Test
  void anEscapedPlaceholderIsText() {
    Resolution r = resolve("\\${a}-${a}", Map.of("a", "1"));
    assertThat(r.value()).isEqualTo("${a}-1");
    assertThat(r.unresolved()).isEmpty();
  }

  @Test
  void theKeysUsedAreReported() {
    Resolution r = resolve("${url}?p=${missing:x}", Map.of("url", "${host}", "host", "h"));
    assertThat(r.value()).isEqualTo("h?p=x");
    assertThat(r.used()).containsExactly("host", "url");
  }
}
