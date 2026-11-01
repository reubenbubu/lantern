package io.lantern.link.archfixture.matchers;

/** A matcher that places a datasource in the deployments itself, which the rule rejects. */
public final class DataLinker {

  public String link(String datasource) {
    return new Deployments().place(datasource);
  }
}
