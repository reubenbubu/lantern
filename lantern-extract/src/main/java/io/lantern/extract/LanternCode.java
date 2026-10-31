package io.lantern.extract;

/**
 * A digest of the code Lantern runs: the JDK's version and its class path, as the extraction cache
 * keys on it (ADR 0092). The linker keys its rules' runs on it too (ADR 0095), so a change to any
 * of Lantern's code runs them again even when no version was bumped.
 */
public final class LanternCode {

  private LanternCode() {}

  /** The digest, computed once per process. */
  public static String digest() {
    return UnitKeys.code();
  }
}
