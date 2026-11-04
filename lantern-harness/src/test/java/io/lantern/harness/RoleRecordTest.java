package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a role's record lists of each item it settled. */
class RoleRecordTest {

  @Test
  void aFailedItemIsUnaskedAndListedAsFailedApartFromOneNotAsked() {
    RoleRecord record = new RoleRecord("resolve", "fake", "m");

    record.outcome("budget", Outcome.of(Outcome.Kind.NOT_ASKED, null, null, "budget"), false);
    record.outcome("broken", Outcome.of(Outcome.Kind.FAILED, null, null, "timeout"), false);
    record.outcome("asked", Outcome.of(Outcome.Kind.ANSWERED, "f", "{}", null), false);

    assertThat(record.unasked()).containsExactly("budget", "broken");
    // The scorer counts failed items apart from those not asked (#734).
    assertThat(record.failed()).containsExactly("broken");
    assertThat(record.asked()).containsExactly("asked");
  }
}
