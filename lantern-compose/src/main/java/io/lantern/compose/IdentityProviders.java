package io.lantern.compose;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The names people know well-known identity providers by (ADR 0085), so the external system for
 * {@code accounts.google.com} reads as "Google". A host not listed keeps its host as its name.
 */
final class IdentityProviders {

  private static final Map<String, String> BY_HOST =
      Map.of(
          "accounts.google.com", "Google",
          "github.com", "GitHub",
          "www.facebook.com", "Facebook",
          "login.microsoftonline.com", "Microsoft Entra ID");

  private IdentityProviders() {}

  /** The name of a well-known identity provider by its host, if it is one. */
  static Optional<String> name(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    String known = BY_HOST.get(lower);
    if (known != null) {
      return Optional.of(known);
    }
    if (lower.endsWith(".okta.com") || lower.endsWith(".oktapreview.com")) {
      return Optional.of("Okta");
    }
    if (lower.endsWith(".auth0.com")) {
      return Optional.of("Auth0");
    }
    if (lower.startsWith("cognito-idp.") && lower.endsWith(".amazonaws.com")) {
      return Optional.of("Amazon Cognito");
    }
    return Optional.empty();
  }
}
