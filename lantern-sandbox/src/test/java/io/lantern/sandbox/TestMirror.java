package io.lantern.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The Maven mirror the tests that run sandbox builds use (ADR 0030): the one set in the Lantern
 * properties file that the system property {@code lantern.test.properties}, or else the environment
 * variable {@code LANTERN_TEST_PROPERTIES}, names, or none, so builds download from Maven Central.
 */
final class TestMirror {

  private TestMirror() {}

  /** The configured mirror, or null if there is none. */
  static MavenMirror configured() {
    String file = System.getProperty("lantern.test.properties");
    if (file == null || file.isBlank()) {
      file = System.getenv("LANTERN_TEST_PROPERTIES");
    }
    if (file == null || file.isBlank()) {
      return null;
    }
    try {
      return MavenMirror.from(Path.of(file));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the test properties " + file, e);
    }
  }
}
