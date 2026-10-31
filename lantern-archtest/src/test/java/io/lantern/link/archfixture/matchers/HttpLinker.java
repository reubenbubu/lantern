package io.lantern.link.archfixture.matchers;

/** A matcher that decides where an address leads itself, which the rule rejects. */
public final class HttpLinker {

  public String link(String address) {
    return new Addresses().resolve(address);
  }
}
