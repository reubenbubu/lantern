package io.lantern.summarize;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import java.util.ArrayList;
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
  private static final Fact ID = member("field:p.Order#id", 12, 12);
  private static final Fact TOTAL = member("method:p.Order#total()", 15, 17);
  private static final Fact SHIP = member("method:p.Order#ship(java.lang.String)", 19, 46);
  // Lombok's getter is placed on the annotation's line, inside the declaration's excerpt.
  private static final Fact GETTER = member("method:p.Order#getId()", 9, 9);

  @Test
  void showsTheDeclarationAndMembersWithJavadocAndAnnotations() {
    List<Excerpts.Excerpt> excerpts =
        Excerpts.of(order(List.of(KIND, ID, TOTAL, SHIP, GETTER)), e -> Optional.of(LINES));

    assertThat(excerpts)
        .containsExactly(
            new Excerpts.Excerpt(
                KIND.id(),
                PATH,
                5,
                10,
                """
                /**
                 * An order a customer placed.
                 */
                @Entity
                @Getter
                public class Order {"""),
            new Excerpts.Excerpt(ID.id(), PATH, 12, 12, "@Id private Long id;"),
            new Excerpts.Excerpt(
                TOTAL.id(),
                PATH,
                14,
                17,
                """
                /** The total in cents. */
                public long total() {
                  return 1;
                }"""),
            new Excerpts.Excerpt(
                SHIP.id(),
                PATH,
                19,
                20,
                """
                public void ship(
                    String address) {
                  ..."""));
  }

  @Test
  void signaturesComeBeforeBodiesWhenTheBudgetRunsOut() {
    List<String> lines = new ArrayList<>(List.of("package p;", "public class Order {"));
    List<Fact> facts = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      int start = lines.size() + 1;
      lines.add("  public int method" + i + "() {");
      lines.add("    return " + i + ";");
      lines.add("  }");
      facts.add(member("method:p.Order#method" + i + "()", start, start + 2));
    }
    lines.add("}");
    facts.add(fact("kind", FactObject.string("class"), 2, lines.size()));

    List<Excerpts.Excerpt> excerpts = Excerpts.of(order(facts), e -> Optional.of(lines));

    int chars = excerpts.stream().mapToInt(e -> e.code().length() + 120).sum();
    assertThat(chars).isLessThanOrEqualTo(Excerpts.MAX_TOKENS * 4);
    assertThat(excerpts.getFirst().code()).isEqualTo("public class Order {");
    // Signatures of as many members as fit, then the leftover budget shows the first ones whole.
    assertThat(excerpts.size()).isLessThan(301);
    assertThat(excerpts.get(1).code()).isEqualTo("public int method0() {\n  return 0;\n}");
    assertThat(excerpts.getLast().code())
        .isEqualTo("public int method%d() {\n  ...".formatted(excerpts.size() - 2));
  }

  @Test
  void aFileThatCannotBeReadOrDoesNotReachTheLinesGivesNoExcerpt() {
    assertThat(Excerpts.of(order(List.of(KIND, TOTAL)), e -> Optional.empty())).isEmpty();
    assertThat(Excerpts.of(order(List.of(KIND, TOTAL)), e -> Optional.of(LINES.subList(0, 20))))
        .extracting(Excerpts.Excerpt::fact)
        .containsExactly(TOTAL.id());
  }

  @Test
  void onlyTypesHaveExcerpts() {
    SummaryNode pkg =
        new SummaryNode(
            EntityId.parse("package:g:a:p"), SummaryLevel.PACKAGE, List.of(KIND), List.of());

    assertThat(Excerpts.of(pkg, e -> Optional.of(LINES))).isEmpty();
  }
}
