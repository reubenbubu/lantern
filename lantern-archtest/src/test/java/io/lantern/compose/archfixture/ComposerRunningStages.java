package io.lantern.compose.archfixture;

import io.lantern.extract.archfixture.ExtractorCallingLlm;
import io.lantern.investigate.archfixture.FakeInvestigator;

/** Test fixture: a composer that wrongly reaches into extraction and Investigate. */
public class ComposerRunningStages {

  public String compose() {
    return ExtractorCallingLlm.class.getName() + new FakeInvestigator().investigate();
  }
}
