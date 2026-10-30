package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.link.Investigation;
import io.lantern.link.JdbcInvestigationStore;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Investigate role's verified proposals are kept in the database (ADR 0084), saved even when
 * the transaction the investigator runs in rolls back. The database is shared with other tests, so
 * each test uses keys of its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InvestigationStoreIT {

  @Autowired JdbcInvestigationStore store;
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
  void proposalsAreStoredReadBackAndReplaced() {
    String key = key();
    Investigation first =
        new Investigation(
            key, "system", "[{\"kind\": \"risk\", \"title\": \"T\"}]", "1", "fake", "m");

    store.save(first);
    assertThat(store.find(key)).isPresent();
    assertThat(store.find(key).orElseThrow().proposals()).contains("\"title\": \"T\"");
    assertThat(store.find(key())).isEmpty();

    store.save(new Investigation(key, "system", "[]", "1", "fake", "m"));
    assertThat(store.find(key).orElseThrow().proposals()).isEqualTo("[]");
  }

  @Test
  void anInvestigationOutlivesTheTransactionItWasSavedIn() {
    Investigation saved = new Investigation(key(), "service:g:a", "[]", "1", "fake", "m");
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    transaction.executeWithoutResult(
        status -> {
          store.save(saved);
          status.setRollbackOnly();
        });

    assertThat(store.find(saved.key())).contains(saved);
  }
}
