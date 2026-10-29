package io.lantern.sandbox;

import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoSettings;
import java.nio.file.Path;

/**
 * Runs one sandbox build in a process of its own, for {@link BuildSandboxIT} to kill.
 *
 * <p>Arguments: the builds directory, the repository's name and path, the commit, and the owner
 * label's key and value.
 */
final class SandboxProcess {

  private SandboxProcess() {}

  public static void main(String[] args) {
    SandboxSettings settings =
        SandboxSettings.defaults(Path.of(args[0]))
            .withOwnerLabel(args[4], args[5])
            .withMinFreeDiskBytes(0)
            .withMavenMirror(TestMirror.configured());
    new BuildSandbox(settings)
        .build(new Repo(args[1], Path.of(args[2]), RepoSettings.DEFAULT), args[3]);
  }
}
