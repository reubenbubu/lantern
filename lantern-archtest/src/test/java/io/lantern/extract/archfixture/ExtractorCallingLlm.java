package io.lantern.extract.archfixture;

import io.lantern.llm.archfixture.FakeLlmClient;

/** Test fixture: an extractor that wrongly depends on the LLM module. */
public class ExtractorCallingLlm {

  public String extract() {
    return new FakeLlmClient().complete();
  }
}
