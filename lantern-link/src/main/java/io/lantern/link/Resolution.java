package io.lantern.link;

import io.lantern.core.fact.EntityId;
import java.util.List;
import java.util.Objects;

/**
 * The LLM resolver's answer about one link static matching left open, cached by the hash of what
 * the model was shown (see {@link LinkResolver}).
 *
 * @param key the cache key: the SHA-256 of the prompt version, provider, model and prompt
 * @param link the stable link ID of the {@code unresolved-link} it answers, for reading the cache
 * @param target the candidate unit the model picked, or {@code null} when it answered unknown
 * @param confidence how sure the model said it is, {@code high}, {@code medium} or {@code low}, or
 *     {@code null} when it answered unknown
 * @param cited the prompt references the answer cites ({@code F1}, {@code C2}), which stand for the
 *     same facts and candidates whenever the prompt, and so the key, is the same; a fact a tool
 *     showed (ADR 0080) is kept by its fact ID, which stands for it while it holds
 * @param rationale the model's reason, in a sentence or two
 * @param promptVersion the version of the prompt
 * @param provider the provider of the model
 * @param model the model's identifier
 */
public record Resolution(
    String key,
    String link,
    EntityId target,
    String confidence,
    List<String> cited,
    String rationale,
    String promptVersion,
    String provider,
    String model) {

  public Resolution {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(link, "link");
    if ((target == null) != (confidence == null)) {
      throw new IllegalArgumentException("A picked target needs a confidence, unknown none");
    }
    cited = List.copyOf(cited);
    Objects.requireNonNull(rationale, "rationale");
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(model, "model");
  }

  /** Whether the model answered unknown, so the link stays open. */
  public boolean unknown() {
    return target == null;
  }
}
