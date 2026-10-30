package io.lantern.link;

import java.util.Objects;

/**
 * The Investigate role's verified proposals for one prompt, cached by the prompt's hash (ADR 0084).
 *
 * @param key the cache key: the SHA-256 of the prompt version, provider, model and prompt
 * @param scope what was investigated: a unit's ID, or {@code system}
 * @param proposals the proposals that passed the checks, as a JSON array
 * @param promptVersion the version of the prompt
 * @param provider the provider that answered
 * @param model the model that answered
 */
public record Investigation(
    String key,
    String scope,
    String proposals,
    String promptVersion,
    String provider,
    String model) {

  public Investigation {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(proposals, "proposals");
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
  }
}
