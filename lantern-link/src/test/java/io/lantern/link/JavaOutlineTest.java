package io.lantern.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A Java file's declarations as Investigate's outline tool shows them (#893). */
class JavaOutlineTest {

  private static final List<String> FILE =
      """
      package f;

      @RestController
      @RequestMapping("/api/orders")
      public class OrderController {
        private final String name = "x { not a brace";

        @GetMapping("/{id}")
        public Order get(@PathVariable Long id) {
          if (id == null) {
            return null; // }
          }
          return find(id);
        }

        @Transactional
        void save(
            Order order) {
          repository.save(order);
        }
      }
      """
          .lines()
          .toList();

  @Test
  void listsEachDeclarationWithItsLinesAndAnnotations() {
    List<JavaOutline.Declaration> declarations = JavaOutline.of(FILE);

    assertThat(declarations)
        .extracting(JavaOutline.Declaration::name)
        .containsExactly("OrderController", "get", "save");
    JavaOutline.Declaration type = declarations.getFirst();
    assertThat(type.from()).isEqualTo(3);
    assertThat(type.to()).isEqualTo(21);
    assertThat(type.text())
        .isEqualTo("@RestController @RequestMapping(\"/api/orders\") public class OrderController");
    JavaOutline.Declaration get = declarations.get(1);
    assertThat(get.from()).isEqualTo(8);
    assertThat(get.to()).isEqualTo(14);
    assertThat(get.text())
        .isEqualTo("@GetMapping(\"/{id}\") public Order get(@PathVariable Long id)");
    JavaOutline.Declaration save = declarations.get(2);
    assertThat(save.from()).isEqualTo(16);
    assertThat(save.to()).isEqualTo(20);
    assertThat(save.text()).isEqualTo("@Transactional void save( Order order)");
  }

  @Test
  void aLineIsInTheInnermostDeclarationAroundIt() {
    List<JavaOutline.Declaration> declarations = JavaOutline.of(FILE);

    assertThat(JavaOutline.enclosing(declarations, 13).name()).isEqualTo("get");
    assertThat(JavaOutline.enclosing(declarations, 6).name()).isEqualTo("OrderController");
    assertThat(JavaOutline.enclosing(declarations, 1)).isNull();
  }
}
