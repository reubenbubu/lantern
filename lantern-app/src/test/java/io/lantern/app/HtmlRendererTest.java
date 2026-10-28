package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HtmlRendererTest {

  private static final String TEMPLATE =
      "<html><body>" + HtmlRenderer.PLACEHOLDER + "<script>main()</script></body></html>";

  @Test
  void writesTheJsonIntoThePlaceholderWithEveryLessThanEscaped() {
    String html =
        new HtmlRenderer(TEMPLATE)
            .renderJson("{\"title\":\"</script><!-- a < b\",\"type\":\"Foo$$Enhancer$1\"}");

    assertThat(html)
        .isEqualTo(
            "<html><body><script id=\"lantern-document\" type=\"application/json\">"
                + "{\"title\":\"\\u003c/script>\\u003c!-- a \\u003c b\","
                + "\"type\":\"Foo$$Enhancer$1\"}"
                + "</script><script>main()</script></body></html>");
  }

  @Test
  void refusesATemplateWithoutExactlyOnePlaceholder() {
    assertThatThrownBy(() -> new HtmlRenderer("<html></html>"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HtmlRenderer(TEMPLATE + HtmlRenderer.PLACEHOLDER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theBuiltTemplateIsOnTheClassPath() {
    String html = HtmlRenderer.fromClassPath().renderJson("{\"marker\":1}");

    assertThat(html)
        .contains(
            "<script id=\"lantern-document\" type=\"application/json\">{\"marker\":1}</script>")
        .doesNotContain(HtmlRenderer.PLACEHOLDER);
  }
}
