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

  /** A small JPA entity. */
  static final String ORDER =
      """
      package p;

      /** An order a customer placed. */
      @Entity
      public class Order {

        @Id private Long id;

        /** The total in cents. */
        public long total() {
          return 1;
        }
      }
      """;

  /** A fact about {@code type:p.Order} observed in the given file. */
  static Fact fact(String predicate, FactObject object, String repo, String path) {
    return Fact.create(
        EntityId.parse("type:p.Order"),
        predicate,
        object,
        FactJson.newObject(),
        new Origin(Origin.Kind.STATIC, "test", "1"),
        Evidence.of(repo, path, null, "0".repeat(64)),
        1.0,
        Validity.from(1));
  }

  /** A fact about {@code type:p.Order} observed in {@link #PATH}. */
  static Fact fact(String predicate, FactObject object) {
    return fact(predicate, object, "r", PATH);
  }

  static Fact member(String member) {
    return fact("CONTAINS", FactObject.entity(EntityId.parse(member)));
  }

  private static SummaryNode order(List<Fact> facts) {
    return new SummaryNode(EntityId.parse("type:p.Order"), SummaryLevel.TYPE, facts, List.of());
  }

  private static final Fact KIND = fact("kind", FactObject.string("class"));
  private static final Fact TOTAL = member("method:p.Order#total()");

  @Test
  void aTypeShowsItsWholeSourceFileCitedAsItsKindFact() {
    List<Excerpts.Excerpt> excerpts =
        Excerpts.of(order(List.of(KIND, TOTAL)), e -> Optional.of(ORDER));

    assertThat(excerpts)
        .containsExactly(new Excerpts.Excerpt(KIND.id(), PATH, ORDER.stripTrailing()));
  }

  @Test
  void aTypeDeclaredInTwoFilesShowsEachFileOnce() {
    Fact other = fact("kind", FactObject.string("class"), "r", "other/" + PATH);
    Fact sameFileOtherKind = fact("kind", FactObject.string("interface"));

    List<Excerpts.Excerpt> excerpts =
        Excerpts.of(
            order(List.of(other, KIND, sameFileOtherKind)), e -> Optional.of(e.path() + " text"));

    String first =
        KIND.id().compareTo(sameFileOtherKind.id()) < 0 ? KIND.id() : sameFileOtherKind.id();
    assertThat(excerpts)
        .containsExactly(
            new Excerpts.Excerpt(other.id(), "other/" + PATH, "other/" + PATH + " text"),
            new Excerpts.Excerpt(first, PATH, PATH + " text"));
  }

  @Test
  void aFileThatCannotBeReadGivesNoExcerpt() {
    assertThat(Excerpts.of(order(List.of(KIND, TOTAL)), e -> Optional.empty())).isEmpty();
    assertThat(Excerpts.of(order(List.of(KIND)), e -> Optional.of("  \n"))).isEmpty();
    assertThat(Excerpts.of(order(List.of(TOTAL)), e -> Optional.of(ORDER))).isEmpty();
  }

  @Test
  void onlyTypesHaveExcerpts() {
    SummaryNode pkg =
        new SummaryNode(
            EntityId.parse("package:g:a:p"), SummaryLevel.PACKAGE, List.of(KIND), List.of());

    assertThat(Excerpts.of(pkg, e -> Optional.of(ORDER))).isEmpty();
  }
}
