package io.lantern.link.archfixture.matchers;

/** A matcher the rule does not cover yet, which may still call the address helper. */
public final class DataLinker {

  public String link(String address) {
    return new Addresses().resolve(address);
  }
}
