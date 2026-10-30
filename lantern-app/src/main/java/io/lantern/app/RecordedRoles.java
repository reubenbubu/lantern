package io.lantern.app;

import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.Role;
import java.util.Objects;
import java.util.Set;

/**
 * A provider whose calls of some roles go through recordings ({@link RecordReplayLlmProvider}) and
 * whose other calls go to the provider itself (ADR 0084, {@code lantern.llm.recordings}). So a run
 * can replay the recorded answers of one role, such as Investigate on a test set, while the other
 * roles are served as usual.
 */
final class RecordedRoles implements LlmProvider {

  private final LlmProvider provider;
  private final LlmProvider recorded;
  private final Set<Role> roles;

  private RecordedRoles(LlmProvider provider, LlmProvider recorded, Set<Role> roles) {
    this.provider = provider;
    this.recorded = recorded;
    this.roles = roles;
  }

  /** The provider with the calls of the settings' roles going through their recordings. */
  static LlmProvider of(LlmProvider provider, LlmProperties.Recordings recordings) {
    Objects.requireNonNull(provider, "provider");
    LlmProvider recorded =
        recordings.mode() == LlmProperties.Recordings.Mode.RECORD
            ? RecordReplayLlmProvider.recording(provider, recordings.directory())
            : RecordReplayLlmProvider.replaying(provider.name(), recordings.directory());
    return new RecordedRoles(provider, recorded, recordings.roles());
  }

  @Override
  public String name() {
    return provider.name();
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    return roles.contains(request.role())
        ? recorded.complete(model, request)
        : provider.complete(model, request);
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    return roles.contains(request.role())
        ? recorded.complete(model, request)
        : provider.complete(model, request);
  }
}
