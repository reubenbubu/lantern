package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.link.JdbcResolutionStore;
import io.lantern.link.Resolution;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The LLM resolver's answers are kept in the database, saved even when the transaction the resolver
 * runs in rolls back. The database is shared with other tests, so each test uses keys of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResolutionStoreIT {

  @Autowired JdbcResolutionStore store;
  @Autowired PlatformTransactionManager transactions;

  private static String key() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    return HexFormat.of().toHexDigits(a.getMostSignificantBits())
        + HexFormat.of().toHexDigits(a.getLeastSignificantBits())
        + HexFormat.of().toHexDigits(b.getMostSignificantBits())
        + HexFormat.of().toHexDigits(b.getLeastSignificantBits());
  }

  @Test
  void aPickAndAnUnknownAreStoredAndReadBack() {
    Resolution pick =
        new Resolution(
            key(),
            "0123456789abcdef",
            EntityId.parse("service:g:tax"),
            "high",
            List.of("F3", "C2"),
            "Only tax serves it.",
            "1",
            "fake",
            "m");
    Resolution unknown =
        new Resolution(
            key(), "fedcba9876543210", null, null, List.of(), "No way to tell.", "1", "fake", "m");

    store.save(pick);
    store.save(unknown);

    assertThat(store.find(pick.key())).contains(pick);
    assertThat(store.find(unknown.key())).contains(unknown);
    assertThat(store.find(key())).isEmpty();
  }

  @Test
  void anAnswerOutlivesTheTransactionItWasSavedIn() {
    Resolution pick =
        new Resolution(
            key(),
            "0123456789abcdef",
            EntityId.parse("service:g:tax"),
            "low",
            List.of("C1"),
            "Paid for.",
            "1",
            "fake",
            "m");
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    transaction.executeWithoutResult(
        status -> {
          store.save(pick);
          status.setRollbackOnly();
        });

    assertThat(store.find(pick.key())).contains(pick);
  }

  @Test
  void savingTheSameKeyTwiceKeepsTheFirst() {
    String key = key();
    Resolution first = new Resolution(key, "l", null, null, List.of(), "First.", "1", "fake", "m");
    store.save(first);
    store.save(new Resolution(key, "l", null, null, List.of(), "Second.", "1", "fake", "m"));

    assertThat(store.find(key)).contains(first);
  }
}
