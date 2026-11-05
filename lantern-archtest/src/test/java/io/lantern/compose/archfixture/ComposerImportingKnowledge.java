package io.lantern.compose.archfixture;

import io.lantern.core.fact.EntityId;
import io.lantern.derive.Knowledge;
import java.util.Comparator;

/** Composer code that reads the raw fact store instead of the model's queries. */
public class ComposerImportingKnowledge {

  public Comparator<EntityId> order() {
    return Knowledge.BY_ID;
  }
}
