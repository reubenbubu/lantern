package io.lantern.app;

import io.lantern.sandbox.MavenMirror;
import io.lantern.sandbox.MavenRepository;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Build sandbox settings under {@code lantern.sandbox}; the other sandbox limits keep their
 * defaults (ADR 0016).
 *
 * @param mavenCacheDir the host directory all sandbox builds share as their Maven cache; a leading
 *     {@code ~} is the home directory of the user running Lantern, and a relative path is resolved
 *     against the working directory. Created if missing.
 * @param mavenRepositories the Maven repositories builds may download from, each with an {@code id}
 *     and an {@code url}, in the order Maven tries them; the egress proxy lets builds reach their
 *     hosts and no others (ADR 0023). Maven Central only by default.
 * @param mavenMirror the one Maven mirror, with an {@code id} and an {@code url}, that every
 *     download of a build goes through, and then the only host builds reach (ADR 0030); none by
 *     default
 */
@ConfigurationProperties("lantern.sandbox")
public record SandboxProperties(
    String mavenCacheDir, List<MavenRepository> mavenRepositories, MavenMirror mavenMirror) {

  public SandboxProperties {
    // The default, Maven Central only, is in application.properties; the sandbox refuses an empty
    // list.
    mavenRepositories = mavenRepositories == null ? List.of() : List.copyOf(mavenRepositories);
  }
}
