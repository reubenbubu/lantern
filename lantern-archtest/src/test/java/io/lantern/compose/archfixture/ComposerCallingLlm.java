package io.lantern.compose.archfixture;

import io.lantern.harness.archfixture.FakeHarness;
import io.lantern.link.archfixture.FakeResolver;
import io.lantern.llm.archfixture.FakeLlmClient;
import io.lantern.summarize.archfixture.FakeRole;

/** Test fixture: a composer that wrongly calls the LLM, the harness and roles. */
public class ComposerCallingLlm {

  public String compose() {
    return new FakeLlmClient().complete()
        + new FakeHarness().ask()
        + new FakeRole().word()
        + new FakeResolver().resolve();
  }
}
