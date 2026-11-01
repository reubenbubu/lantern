package io.lantern.link;

import io.lantern.core.vocabulary.VocabularyType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/** The vocabulary types of the linker's steps, by vocabulary source. */
public final class LinkVocabularies {

  private LinkVocabularies() {}

  /**
   * Each step's types by its vocabulary source, in the order they are applied, after the
   * extractors' ({@code Extractors.vocabularies()}).
   */
  public static SequencedMap<String, List<VocabularyType>> bySource() {
    SequencedMap<String, List<VocabularyType>> sources = new LinkedHashMap<>();
    sources.put(DeployableUnits.VOCABULARY_SOURCE, DeployableUnits.vocabulary());
    sources.put(CrossRepoLinks.VOCABULARY_SOURCE, CrossRepoLinks.vocabulary());
    sources.put(ClaimChecks.VOCABULARY_SOURCE, ClaimChecks.vocabulary());
    return sources;
  }
}
