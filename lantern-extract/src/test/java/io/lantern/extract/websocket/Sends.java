package io.lantern.extract.websocket;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.extract.messaging.MessagingExtractor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The extractor's facts with each STOMP send as linking records it (ADR 0094, #559): its {@code
 * stomp-send} fact instead of the {@code SENDS_TO} fact written, so the tests check them as they
 * checked the extractor's facts before. {@code ValueViewsTest} checks that linking gives the same.
 */
final class Sends {

  private Sends() {}

  static List<Fact> resolved(List<Fact> written, long snapshot) {
    List<Fact> facts = new ArrayList<>();
    for (Fact f : written) {
      if (!f.predicate().equals(MessagingExtractor.SENDS_TO)) {
        facts.add(f);
      }
    }
    facts.addAll(
        WebSocketExtractor.sends(
            written,
            new Origin(Origin.Kind.STATIC, WebSocketExtractor.NAME, WebSocketExtractor.VERSION),
            snapshot));
    return facts.stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(f -> f.attributes().toString())
                .thenComparing(Fact::id))
        .toList();
  }
}
