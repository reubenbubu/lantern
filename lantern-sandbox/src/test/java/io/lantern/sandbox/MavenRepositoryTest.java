package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenRepositoryTest {

  private static final String ACTIVITI =
      "https://artifacts.alfresco.com/nexus/content/repositories/activiti-releases";

  @Test
  void centralIsMavenCentral() {
    assertThat(MavenRepository.CENTRAL.id()).isEqualTo("central");
    assertThat(MavenRepository.CENTRAL.url())
        .isEqualTo(URI.create("https://repo.maven.apache.org/maven2"));
    assertThat(MavenRepository.CENTRAL.hostAndPort()).isEqualTo("repo.maven.apache.org:443");
  }

  @Test
  void normalizesHostCaseAndTrailingSlash() {
    MavenRepository repository =
        new MavenRepository("x", URI.create("https://Example.COM:8443/maven2/"));

    assertThat(repository.url()).isEqualTo(URI.create("https://example.com:8443/maven2"));
    assertThat(repository.hostAndPort()).isEqualTo("example.com:8443");
    assertThat(new MavenRepository("x", URI.create("https://example.com/")).url())
        .isEqualTo(URI.create("https://example.com"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://repo.example.com/maven2",
        "https://*.example.com/maven2",
        "https://*/maven2",
        "https://user:secret@repo.example.com/maven2",
        "https://repo.example.com/maven2?x=1",
        "https://repo.example.com/maven2#x",
        "https://repo.example.com/../maven2",
        "https://repo.example.com/a/./maven2",
        "https://repo.example.com/maven%32",
        "https://repo.example.com:0/maven2",
        "https://repo.example.com:99999/maven2",
        "https://192.168.1.1/maven2",
        "https://169.254.169.254/maven2",
        "https://localhost/maven2",
        "https://[::1]/maven2",
        "https://repo-.example.com/maven2",
        "https://repo.example.-com/maven2",
        "file:///var/maven2",
        "https:///maven2",
        "repo.example.com/maven2"
      })
  void rejectsUrlsThatAreNotAnExactHttpsLocation(String url) {
    assertThatThrownBy(() -> new MavenRepository("x", URI.create(url)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "-x", "a b", "a<b", "a/b"})
  void rejectsInvalidIds(String id) {
    assertThatThrownBy(() -> new MavenRepository(id, MavenRepository.CENTRAL.url()))
        .hasMessageContaining("ID");
  }

  @Test
  void readsAListFromProperties() {
    Properties properties = new Properties();
    properties.setProperty("lantern.sandbox.maven-repositories[0].id", "central");
    properties.setProperty(
        "lantern.sandbox.maven-repositories[0].url", "https://repo.maven.apache.org/maven2");
    properties.setProperty("lantern.sandbox.maven-repositories[1].id", " activiti ");
    properties.setProperty("lantern.sandbox.maven-repositories[1].url", ACTIVITI);
    properties.setProperty("lantern.sandbox.maven-cache-volume", "other setting");

    assertThat(MavenRepository.listFrom(properties))
        .containsExactly(
            MavenRepository.CENTRAL, new MavenRepository("activiti", URI.create(ACTIVITI)));
    assertThat(MavenRepository.listFrom(new Properties())).isEmpty();
  }

  @Test
  void rejectsIncompleteOrGappedLists() {
    Properties missingUrl = new Properties();
    missingUrl.setProperty("lantern.sandbox.maven-repositories[0].id", "central");
    assertThatThrownBy(() -> MavenRepository.listFrom(missingUrl))
        .hasMessageContaining("must both be set");

    Properties gap = new Properties();
    gap.setProperty("lantern.sandbox.maven-repositories[0].id", "central");
    gap.setProperty("lantern.sandbox.maven-repositories[0].url", ACTIVITI);
    gap.setProperty("lantern.sandbox.maven-repositories[2].id", "late");
    gap.setProperty("lantern.sandbox.maven-repositories[2].url", ACTIVITI);
    assertThatThrownBy(() -> MavenRepository.listFrom(gap)).hasMessageContaining("[2].id");

    Properties unknown = new Properties();
    unknown.setProperty("lantern.sandbox.maven-repositories[0].id", "central");
    unknown.setProperty("lantern.sandbox.maven-repositories[0].url", ACTIVITI);
    unknown.setProperty("lantern.sandbox.maven-repositories[0].password", "x");
    assertThatThrownBy(() -> MavenRepository.listFrom(unknown)).hasMessageContaining("password");
  }
}
