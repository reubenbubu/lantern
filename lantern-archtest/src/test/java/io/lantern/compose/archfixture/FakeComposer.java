package io.lantern.compose.archfixture;

import io.lantern.link.archfixture.FakeLinker;

/** Test fixture standing in for the composer, which may read an earlier stage, linking. */
public class FakeComposer {

  public String compose() {
    return new FakeLinker().link();
  }
}
