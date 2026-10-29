package io.lantern.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenMirrorTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://10.0.0.1/maven2",
        "http://10.255.255.255:8081/maven2",
        "http://172.16.0.1:8081/repository/all",
        "http://172.31.255.254/repository/all",
        "http://192.168.0.1/repository/all",
        "https://192.168.255.255:8443/repository/all",
        "http://nexus.example.com/repository/all",
        "https://nexus.example.com:8443/repository/all"
      })
  void acceptsHttpAndPrivateNetworkAddresses(String url) {
    assertThat(new MavenMirror("mirror", URI.create(url)).url()).isEqualTo(URI.create(url));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/maven2",
        "http://127.1.2.3:8081/maven2",
        "http://169.254.169.254/latest",
        "http://169.254.1.1/maven2",
        "http://0.0.0.0/maven2",
        "http://8.8.8.8/maven2",
        "http://172.15.0.1/maven2",
        "http://172.32.0.1/maven2",
        "http://192.169.0.1/maven2",
        "http://11.0.0.1/maven2",
        "http://100.64.0.1/maven2",
        "http://224.0.0.1/maven2",
        "http://255.255.255.255/maven2",
        "http://010.0.0.1/maven2",
        "http://10.0.0.256/maven2",
        "http://10.0.0/maven2",
        "http://167772161/maven2",
        "http://[::1]/maven2",
        "http://[fd00::1]/maven2",
        "http://localhost/maven2",
        "http://*.example.com/maven2",
        "ftp://10.0.0.1/maven2",
        "file:///maven2",
        "http://user:secret@10.0.0.1/maven2",
        "http://10.0.0.1/maven2?x=1",
        "http://10.0.0.1/maven2#x",
        "http://10.0.0.1/../maven2",
        "http://10.0.0.1:0/maven2",
        "http://10.0.0.1:99999/maven2"
      })
  void rejectsOtherAddressesAndUrls(String url) {
    assertThatThrownBy(() -> new MavenMirror("mirror", URI.create(url)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repositoriesStillNeedHttpsAndAHostName() {
    assertThatThrownBy(
            () -> new MavenRepository("x", URI.create("http://nexus.example.com/repository/all")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MavenRepository("x", URI.create("https://10.0.0.1/maven2")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalizesAndNamesItsHostAndPort() {
    MavenMirror mirror = new MavenMirror("mirror", URI.create("HTTP://Nexus.Example.COM/all/"));

    assertThat(mirror.url()).isEqualTo(URI.create("http://nexus.example.com/all"));
    assertThat(mirror.hostAndPort()).isEqualTo("nexus.example.com:80");
    assertThat(new MavenMirror("m", URI.create("https://10.0.0.1/all")).hostAndPort())
        .isEqualTo("10.0.0.1:443");
    assertThat(new MavenMirror("m", URI.create("http://10.0.0.1:8081/all")).hostAndPort())
        .isEqualTo("10.0.0.1:8081");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "-x", "a b", "a<b", "a/b"})
  void rejectsInvalidIds(String id) {
    assertThatThrownBy(() -> new MavenMirror(id, URI.create("http://10.0.0.1/all")))
        .hasMessageContaining("ID");
  }

  @Test
  void readsItFromPropertiesOrAFile(@TempDir Path temp) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("lantern.sandbox.maven-mirror.id", " lantern-mirror ");
    properties.setProperty(
        "lantern.sandbox.maven-mirror.url", "http://10.0.0.1:8081/repository/all/");

    MavenMirror expected =
        new MavenMirror("lantern-mirror", URI.create("http://10.0.0.1:8081/repository/all"));
    assertThat(MavenMirror.from(properties)).isEqualTo(expected);
    assertThat(MavenMirror.from(new Properties())).isNull();

    Path file = temp.resolve("local.properties");
    Files.writeString(
        file,
        "lantern.sandbox.maven-mirror.id=lantern-mirror\n"
            + "lantern.sandbox.maven-mirror.url=http://10.0.0.1:8081/repository/all\n");
    assertThat(MavenMirror.from(file)).isEqualTo(expected);

    Properties missingUrl = new Properties();
    missingUrl.setProperty("lantern.sandbox.maven-mirror.id", "lantern-mirror");
    assertThatThrownBy(() -> MavenMirror.from(missingUrl)).hasMessageContaining("both be set");
  }
}
