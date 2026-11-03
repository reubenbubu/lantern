package io.lantern.compose.archfixture;

/** Composer code that copies a predicate's and an entity type's name instead of naming them. */
public class ComposerCopyingTypeNames {

  static final String CALLS_HTTP = "CALLS_HTTP";

  private static final String DATABASE = "database";

  static final String HEADING = "Containers";

  public boolean calls(String predicate) {
    return predicate.equals(CALLS_HTTP) || predicate.equals(DATABASE) || HEADING.isEmpty();
  }
}
