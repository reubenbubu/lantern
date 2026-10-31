package io.lantern.link;

import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Target;
import io.lantern.link.LinkIndex.Unit;
import java.util.List;

/**
 * Where addresses lead, as a matcher asks it (ADR 0042, amendment for #473): worked out by {@link
 * Addresses} in address resolution, and read back from its claims by the matchers ({@link
 * AddressClaims}, #589).
 */
interface AddressAnswers {

  /**
   * Where an address leads in a view: one answer of the config's and the deployments' several.
   *
   * @param from the unit that uses it; null for a frontend
   * @param text the address as the unit's config resolved it
   * @param written the address as written, when config changed it, or null
   */
  Answer resolve(Unit from, String view, String text, String written);

  /** Each answer an address has, the config's first, then each deployment's that fills it in. */
  List<Answer> answers(Unit from, String view, String text, String written);

  /** The units whose {@code spring.application.name} in the view is the name. */
  List<Target> byApplicationName(String name, String view, String matchedBy, double factor);

  /**
   * As {@link #resolve(Unit, String, String, String)}, asked for a fact (a route, a frontend's
   * call), which address resolution cites when it records the question.
   */
  default Answer resolve(
      io.lantern.core.fact.Fact asking, Unit from, String view, String text, String written) {
    return resolve(from, view, text, written);
  }

  /** As {@link #byApplicationName(String, String, String, double)}, asked for a fact. */
  default List<Target> byApplicationName(
      io.lantern.core.fact.Fact asking, String name, String view, String matchedBy, double factor) {
    return byApplicationName(name, view, matchedBy, factor);
  }
}
