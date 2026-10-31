package io.lantern.link.archfixture;

import io.lantern.compose.archfixture.FakeComposer;

/** Test fixture: a linker that wrongly depends on a later stage, composition. */
public class LinkerReadingComposer {

  public String link() {
    return new FakeComposer().compose();
  }
}
