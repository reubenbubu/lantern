package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.compose.JdbcWordingStore;
import io.lantern.compose.WordingAnswer;
import io.lantern.core.fact.EntityId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The LLM's wordings of units are kept in the database (ADR 0049). The database is shared with
 * other tests, so each test uses keys of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WordingStoreIT {

  @Autowired JdbcWordingStore store;

  private static String key() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    return HexFormat.of().toHexDigits(a.getMostSignificantBits())
        + HexFormat.of().toHexDigits(a.getLeastSignificantBits())
        + HexFormat.of().toHexDigits(b.getMostSignificantBits())
        + HexFormat.of().toHexDigits(b.getLeastSignificantBits());
  }

  private static WordingAnswer answer(String key, String text) {
    return new WordingAnswer(
        key,
        EntityId.parse("service:g:shop"),
        Map.of(
            WordingAnswer.UNIT,
            new WordingAnswer.Item(text, List.of("F1", "F2")),
            "R1",
            new WordingAnswer.Item("Charges cards", List.of("F7"))),
        "1",
        "fake",
        "m");
  }

  @Test
  void anAnswerIsStoredAndReadBack() {
    WordingAnswer answer = answer(key(), "Takes orders.");

    store.save(answer);

    assertThat(store.find(answer.key())).contains(answer);
    assertThat(store.find(key())).isEmpty();
  }

  @Test
  void anAnswerWithoutItemsIsStoredToo() {
    WordingAnswer empty =
        new WordingAnswer(key(), EntityId.parse("service:g:shop"), Map.of(), "1", "fake", "m");

    store.save(empty);

    assertThat(store.find(empty.key())).contains(empty);
  }

  @Test
  void savingTheSameKeyTwiceKeepsTheFirst() {
    String key = key();
    WordingAnswer first = answer(key, "First.");
    store.save(first);
    store.save(answer(key, "Second."));

    assertThat(store.find(key)).contains(first);
  }
}
