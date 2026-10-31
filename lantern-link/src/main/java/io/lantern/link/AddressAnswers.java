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
}
