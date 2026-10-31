package io.lantern.link.archfixture.matchers;

/** Stands for the linker's address helper, for the rule that matchers do not call it. */
public final class Addresses {

  public String resolve(String address) {
    return address;
  }
}
