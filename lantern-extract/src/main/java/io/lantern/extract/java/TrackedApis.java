package io.lantern.extract.java;

import java.util.List;

/**
 * The library APIs whose calls the analyzers record as {@link ModuleStructure.Invocation}s, with
 * their arguments as {@link Expression}s, for the extractors that read them. Only these are
 * recorded, so the analysis stays small: an extractor that needs another API adds its package here.
 */
final class TrackedApis {

  /** Package prefixes of the tracked types. */
  private static final List<String> PACKAGES =
      List.of(
          // HTTP clients (the HTTP client extractor): RestTemplate, RestClient and their builders.
          "org.springframework.web.client.",
          "org.springframework.boot.web.client.",
          "org.springframework.boot.restclient.",
          // WebClient
          "org.springframework.web.reactive.function.client.",
          // Jakarta (and javax) REST client API
          "jakarta.ws.rs.client.",
          "javax.ws.rs.client.");

  private TrackedApis() {}

  /** Whether calls on a value of this type are recorded. */
  static boolean isTracked(String binaryName) {
    if (binaryName == null) {
      return false;
    }
    for (String p : PACKAGES) {
      if (binaryName.startsWith(p)) {
        return true;
      }
    }
    return false;
  }
}
