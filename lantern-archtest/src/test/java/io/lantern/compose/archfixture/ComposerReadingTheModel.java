package io.lantern.compose.archfixture;

import io.lantern.core.fact.EntityId;
import io.lantern.derive.ArchitectureModel;

/** Composer code that reads only the model's typed queries. */
public class ComposerReadingTheModel {

  public String responsibility(ArchitectureModel model, EntityId id) {
    return model.responsibility(id).map(ArchitectureModel.Text::text).orElse("");
  }
}
