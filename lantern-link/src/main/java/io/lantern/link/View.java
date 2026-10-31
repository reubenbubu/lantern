package io.lantern.link;

import java.util.Objects;

/**
 * A view a link rule derives in: the default one, a Spring profile or, with #522, a deployment
 * environment (knowledge-build epic, section 8, Views). Until #522 runs rules per view, the linker
 * runs each rule once in the default view and the rules find the links of every profile themselves,
 * as before (ADR 0095).
 *
 * @param name the view's name: {@code default}, or the profile's
 */
public record View(String name) {

  /** The default view: no profile active. */
  public static final View DEFAULT = new View("default");

  public View {
    Objects.requireNonNull(name, "name");
  }
}
