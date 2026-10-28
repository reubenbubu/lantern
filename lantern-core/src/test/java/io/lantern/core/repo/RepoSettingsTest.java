package io.lantern.core.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepoSettingsTest {

  @Test
  void settingsRoundTripThroughJson() {
    RepoSettings full = new RepoSettings(11, List.of("ci", "fast"), List.of("docs", "it/e2e"));

    assertThat(RepoRegistry.readSettings(RepoRegistry.writeSettings(full))).isEqualTo(full);
    assertThat(RepoRegistry.writeSettings(RepoSettings.DEFAULT)).isEqualTo("{}");
    assertThat(RepoRegistry.readSettings("{}")).isEqualTo(RepoSettings.DEFAULT);
  }

  @Test
  void nullListsMeanNone() {
    assertThat(new RepoSettings(null, null, null)).isEqualTo(RepoSettings.DEFAULT);
  }

  @Test
  void refusesInvalidSettings() {
    assertThatThrownBy(() -> new RepoSettings(0, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RepoSettings(null, List.of(" "), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RepoSettings(null, List.of(), List.of("a", "a")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repoNamesAndPathsAreChecked() {
    Path abs = Path.of("/srv/repos/orders");

    assertThat(new Repo("org.openwms.common.service_2-x", abs, RepoSettings.DEFAULT).name())
        .isEqualTo("org.openwms.common.service_2-x");
    assertThatThrownBy(() -> new Repo("-orders", abs, RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Repo("a/b", abs, RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Repo("orders", Path.of("orders"), RepoSettings.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
