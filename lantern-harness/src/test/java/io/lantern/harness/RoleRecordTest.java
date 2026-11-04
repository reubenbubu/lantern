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

  @Test
  void anItemThatSettlesLaterInTheRunIsListedOnlyAsItSettled() {
    RoleRecord record = new RoleRecord("describe-unit", "fake", "m");
    // Failed, then asked again and dropped: asked, and without an answer only for the drop (#741).
    record.outcome("a", Outcome.of(Outcome.Kind.FAILED, "f1", null, "timeout"), false);
    record.outcome("a", Outcome.of(Outcome.Kind.DROPPED, "f1", null, "rejected"), false);
    // Dropped, then answered: no longer dropped.
    record.outcome("b", Outcome.of(Outcome.Kind.DROPPED, "f2", null, "rejected"), false);
    record.outcome("b", Outcome.of(Outcome.Kind.ANSWERED, "f2", "{}", null), false);

    assertThat(record.asked()).contains("a", "b");
    assertThat(record.unasked()).isEmpty();
    assertThat(record.failed()).isEmpty();
    assertThat(record.dropped()).containsExactly("a");
    assertThat(record.withoutAnswer()).containsExactly(java.util.Map.entry("a", "rejected twice"));
  }
}
