package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.FileRead;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.JdbcAnswerStore;
import io.lantern.harness.StoredAnswer;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * The LLM harness's answers are kept in the database (ADR 0100). The database is shared with other
 * tests, so each test uses fingerprints and subjects of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnswerStoreIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired JdbcAnswerStore store;

  private static String fingerprint() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    return HexFormat.of().toHexDigits(a.getMostSignificantBits())
        + HexFormat.of().toHexDigits(a.getLeastSignificantBits())
        + HexFormat.of().toHexDigits(b.getMostSignificantBits())
        + HexFormat.of().toHexDigits(b.getLeastSignificantBits());
  }

  private static StoredAnswer answer(String fingerprint, String subject, String json) {
    return new StoredAnswer(
        fingerprint,
        "resolve",
        subject,
        "3",
        "fake",
        "m",
        json == null,
        json,
        List.of(new FileRead("shop", "src/A.java", "a".repeat(40))),
        List.of(
            FingerprintInput.blob("shop", "src/A.java", "a".repeat(40)),
            FingerprintInput.text("level type")));
  }

  @Test
  void anAnswerIsStoredAndReadBack() {
    StoredAnswer answer = answer(fingerprint(), "link-" + UUID.randomUUID(), "{\"target\":\"x\"}");

    store.save(answer);

    StoredAnswer read = store.find(answer.fingerprint()).orElseThrow();
    assertThat(JSON.readTree(read.answer())).isEqualTo(JSON.readTree(answer.answer()));
    assertThat(read.filesRead()).isEqualTo(answer.filesRead());
    assertThat(read.inputs()).isEqualTo(answer.inputs());
    assertThat(read.subject()).isEqualTo(answer.subject());
    assertThat(store.find(fingerprint())).isEmpty();
  }

  @Test
  void anOmittedAnswerIsStoredToo() {
    StoredAnswer omitted = answer(fingerprint(), "link-" + UUID.randomUUID(), null);

    store.save(omitted);

    assertThat(store.find(omitted.fingerprint()))
        .hasValueSatisfying(a -> assertThat(a.omitted()).isTrue());
  }

  @Test
  void savingTheSameFingerprintTwiceKeepsTheFirst() {
    String fingerprint = fingerprint();
    String subject = "link-" + UUID.randomUUID();
    store.save(answer(fingerprint, subject, "{\"n\":1}"));
    store.save(answer(fingerprint, subject, "{\"n\":2}"));

    assertThat(store.find(fingerprint).orElseThrow().answer()).contains("1");
  }

  @Test
  void theLatestAnswerForASubjectIsTheOneStoredLast() {
    String subject = "link-" + UUID.randomUUID();
    store.save(answer(fingerprint(), subject, "{\"n\":1}"));
    StoredAnswer last = answer(fingerprint(), subject, "{\"n\":2}");
    store.save(last);

    assertThat(store.latest("resolve", subject).orElseThrow().fingerprint())
        .isEqualTo(last.fingerprint());
    assertThat(store.latest("summarize", subject)).isEmpty();
  }
}
