package io.lantern.app;

import io.lantern.sandbox.MavenRepository;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Build sandbox settings under {@code lantern.sandbox}; the other sandbox limits keep their
 * defaults (ADR 0016).
 *
 * @param mavenCacheVolume the Docker volume all sandbox builds share as their Maven cache
 * @param mavenRepositories the Maven repositories builds may download from, each with an {@code id}
 *     and an {@code url}, in the order Maven tries them; the egress proxy lets builds reach their
 *     hosts and no others (ADR 0023). Maven Central only if not set.
 */
@ConfigurationProperties("lantern.sandbox")
public record SandboxProperties(String mavenCacheVolume, List<MavenRepository> mavenRepositories) {

  public SandboxProperties {
    mavenRepositories =
        mavenRepositories == null || mavenRepositories.isEmpty()
            ? List.of(MavenRepository.CENTRAL)
            : List.copyOf(mavenRepositories);
  }
}
