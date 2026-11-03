package io.lantern.link.archfixture;

import io.lantern.llm.archfixture.FakeLlmClient;

/** Test fixture standing in for an LLM role of the linker, such as Resolve. */
public class FakeResolver {

  public String resolve() {
    return new FakeLlmClient().complete();
  }
}
