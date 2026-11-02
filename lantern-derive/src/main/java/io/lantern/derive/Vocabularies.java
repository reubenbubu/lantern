package io.lantern.derive;

import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.Extractors;
import io.lantern.harness.LlmFacts;
import io.lantern.link.LinkVocabularies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * Every vocabulary of this version of Lantern by source, in the order they are applied: the
 * built-in types, then those of the extractors, the linker, derivation and the LLM roles. The
 * generated {@link io.lantern.derive.attributes.Types} names each of their types (ADR 0114).
 */
public final class Vocabularies {

  private Vocabularies() {}

  /** The vocabularies by source, each type's parent built in or from a source before. */
  public static SequencedMap<String, List<VocabularyType>> bySource() {
    SequencedMap<String, List<VocabularyType>> sources = new LinkedHashMap<>();
    sources.put(BuiltInTypes.SOURCE, BuiltInTypes.definitions());
    sources.putAll(Extractors.vocabularies());
    sources.putAll(LinkVocabularies.bySource());
    sources.putAll(Derivation.vocabularies());
    sources.put(LlmFacts.SOURCE, LlmFacts.vocabulary());
    return sources;
  }

  /** All of them as one vocabulary, at revision 1. */
  public static Vocabulary standard() {
    return Standard.VOCABULARY;
  }

  /** Read once, when first asked for. */
  private static final class Standard {
    static final Vocabulary VOCABULARY = read();

    private static Vocabulary read() {
      List<VocabularyType> types = new ArrayList<>();
      bySource().values().forEach(types::addAll);
      return new Vocabulary(1, types);
    }
  }
}
