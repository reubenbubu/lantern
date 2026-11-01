package io.lantern.extract.archfixture;

import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.ValueLookup;

/** A fixture: an extractor that resolves a placeholder itself, which only value resolution may. */
public final class ExtractorResolvingPlaceholders {

  private ExtractorResolvingPlaceholders() {}

  public static String url(String written) {
    return ConfigExtractor.resolveWithoutConfig(written).value();
  }

  /** A config of its own for the extractors' resolving functions. */
  public abstract static class OwnLookup implements ValueLookup {}
}
