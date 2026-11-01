package io.lantern.link.archfixture.matchers;

/** Stands for the linker's deployments, whose placing of datasources matchers do not call. */
public final class Deployments {

  public String place(String datasource) {
    return datasource;
  }

  public String all() {
    return "";
  }
}
