package io.lantern.link.archfixture.matchers;

/** A matcher that only lists the deployments' containers, which the rule allows. */
public final class RedisLinker {

  public String link() {
    return new Deployments().all();
  }
}
