package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExcerptsTest {

  static final String PATH = "src/main/java/p/Order.java";

  /** A JPA entity with Javadoc, a field, a short and a long method, and a Lombok getter. */
  static final String ORDER =
      """
      package p;

      import jakarta.persistence.Entity;

      /**
       * An order a customer placed.
       */
      @Entity
      @Getter
      public class Order {

        @Id private Long id;

        /** The total in cents. */
        public long total() {
          return 1;
        }

        public void ship(
            String address) {
      %s  }
      }
      """
          .formatted("    step();\n".repeat(25));

  private static final List<String> LINES = ORDER.lines().toList();

  /** A fact about {@code type:p.Order} observed at the given lines of {@link #PATH}. */
  static Fact fact(String predicate, FactObject object, int start, int end) {
    return Fact.create(
        EntityId.parse("type:p.Order"),
        predicate,
        object,
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        new Evidence(
            "r", TestFacts.COMMIT, PATH, new Evidence.LineRange(start, end), "0".repeat(64)),
        1.0,
        Validity.from(1));
  }

  static Fact member(String member, int start, int end) {
    return fact("CONTAINS", FactObject.entity(EntityId.parse(member)), start, end);
  }

  private static SummaryNode order(List<Fact> facts) {
    return new SummaryNode(EntityId.parse("type:p.Order"), SummaryLevel.TYPE, facts, List.of());
  }

  private static final Fact KIND = fact("kind", FactObject.string("class"), 8, 47);
  private static final Fact TOTAL = member("method:p.Order#total()", 15, 17);

  @Test
  void aTypeShowsItsWholeSourceFileCitedAsItsKindFact() {
    List<Excerpts.Excerpt> excerpts =
        Excerpts.of(order(List.of(KIND, TOTAL)), e -> Optional.of(LINES));

    assertThat(excerpts)
        .containsExactly(
            new Excerpts.Excerpt(KIND.id(), PATH, 1, LINES.size(), String.join("\n", LINES)));
  }

  @Test
  void aFileThatCannotBeReadGivesNoExcerpt() {
    assertThat(Excerpts.of(order(List.of(KIND, TOTAL)), e -> Optional.empty())).isEmpty();
    assertThat(Excerpts.of(order(List.of(TOTAL)), e -> Optional.of(LINES))).isEmpty();
  }

  @Test
  void onlyTypesHaveExcerpts() {
    SummaryNode pkg =
        new SummaryNode(
            EntityId.parse("package:g:a:p"), SummaryLevel.PACKAGE, List.of(KIND), List.of());

    assertThat(Excerpts.of(pkg, e -> Optional.of(LINES))).isEmpty();
  }
}
