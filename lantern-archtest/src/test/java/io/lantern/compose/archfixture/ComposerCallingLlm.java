package io.lantern.compose.archfixture;

import io.lantern.llm.archfixture.FakeLlmClient;
import io.lantern.summarize.archfixture.FakeRole;

/** Test fixture: a composer that wrongly calls the LLM and asks a role. */
public class ComposerCallingLlm {

  public String compose() {
    return new FakeLlmClient().complete() + new FakeRole().word();
  }
}
