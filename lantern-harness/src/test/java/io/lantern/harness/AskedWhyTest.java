package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Why an item is asked again: what changed since its subject's last answer (#755). */
class AskedWhyTest {

  private static final FingerprintInput CLAIM =
      new FingerprintInput(FingerprintInput.Kind.CLAIM, "c1", "shop", "src/A.java");
  private static final FingerprintInput OLD_BLOB =
      new FingerprintInput(FingerprintInput.Kind.BLOB, "b1", "shop", "src/A.java");
  private static final FingerprintInput NEW_BLOB =
      new FingerprintInput(FingerprintInput.Kind.BLOB, "b2", "shop", "src/A.java");
  private static final FingerprintInput OTHER_CLAIM =
      new FingerprintInput(FingerprintInput.Kind.CLAIM, "c2", "shop", "src/B.java");
  private static final FingerprintInput OLD_ANSWER =
      new FingerprintInput(FingerprintInput.Kind.ANSWER, "a1", "", "");
  private static final FingerprintInput NEW_ANSWER =
      new FingerprintInput(FingerprintInput.Kind.ANSWER, "a2", "", "");
  private static final FingerprintInput TEXT =
      new FingerprintInput(FingerprintInput.Kind.TEXT, "t1", "", "");

  private static StoredAnswer stored(
      String version, String model, List<FileRead> read, FingerprintInput... inputs) {
    return new StoredAnswer(
        "f", "role", "subject", version, "p", model, false, "{}", read, List.of(inputs));
  }

  private static Optional<String> noFile(String repo, String path) {
    return Optional.empty();
  }

  @Test
  void anItemNeverAskedBeforeIsNew() {
    AskedWhy why =
        AskedWhy.of(Optional.empty(), List.of(CLAIM), "1", "p", "m", AskedWhyTest::noFile);
    assertThat(why.isNew()).isTrue();
  }

  @Test
  void aChangedAnswerNamesTheItemItComesFromAndAnUnchangedOneNothing() {
    FingerprintInput kept = FingerprintInput.answer("same", List.of("summarize package:p"));
    FingerprintInput old = FingerprintInput.answer("old", List.of("summarize-type type:A"));
    StoredAnswer before = stored("1", "m", List.of(), CLAIM, kept, old);
    FingerprintInput reworded = FingerprintInput.answer("new", List.of("summarize-type type:A"));
    AskedWhy why =
        AskedWhy.of(
            Optional.of(before),
            List.of(
                CLAIM,
                // The same text, whatever source it names: not a change (#722).
                FingerprintInput.answer("same", List.of("other")),
                reworded,
                FingerprintInput.answer("unnamed")),
            "1",
            "p",
            "m",
            AskedWhyTest::noFile);
    assertThat(why.answers()).isEqualTo(3);
    assertThat(why.changedAnswers())
        .containsExactly(AskedWhy.UNKNOWN_SOURCE, "summarize-type type:A");
  }

  @Test
  void eachChangedInputIsCountedByWhereItComesFrom() {
    StoredAnswer before = stored("1", "m", List.of(), CLAIM, OLD_BLOB, OTHER_CLAIM, OLD_ANSWER);
    AskedWhy why =
        AskedWhy.of(
            Optional.of(before),
            List.of(CLAIM, NEW_BLOB, OTHER_CLAIM, NEW_ANSWER, TEXT),
            "1",
            "p",
            "m",
            AskedWhyTest::noFile);
    // The unchanged claims are no reason; the file's new text, the answer shown and the text are.
    assertThat(why.isNew()).isFalse();
    assertThat(why.files()).containsExactly("shop:src/A.java");
    assertThat(why.answers()).isEqualTo(2);
    assertThat(why.other()).isEqualTo(1);
    assertThat(why.prompt()).isFalse();
    assertThat(why.model()).isFalse();
  }

  @Test
  void aChangedPromptModelOrFileReadIsAReason() {
    Map<String, String> blobs = Map.of("shop:src/Read.java", "new", "shop:src/Same.java", "same");
    StoredAnswer before =
        stored(
            "1",
            "m",
            List.of(
                new FileRead("shop", "src/Read.java", "old"),
                new FileRead("shop", "src/Same.java", "same")),
            CLAIM);
    AskedWhy why =
        AskedWhy.of(
            Optional.of(before),
            List.of(CLAIM),
            "2",
            "p",
            "other-model",
            (repo, path) -> Optional.ofNullable(blobs.get(repo + ":" + path)));
    assertThat(why.files()).containsExactly("shop:src/Read.java");
    assertThat(why.prompt()).isTrue();
    assertThat(why.model()).isTrue();
  }
}
