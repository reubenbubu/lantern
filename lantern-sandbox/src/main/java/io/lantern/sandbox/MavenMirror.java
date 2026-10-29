package io.lantern.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The one Maven mirror every download of a build goes through, when one is configured: Maven gets
 * it as the mirror of every external repository ({@code external:*}), dependencies and plugins
 * alike, and the egress proxy lets builds reach its host and port and nothing else (ADR 0030).
 *
 * @param id the ID Maven knows the mirror by
 * @param url the mirror's base URL: {@code http} or {@code https}, an exact host name or an IPv4
 *     address in a private network (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), no user
 *     information, query or fragment; a trailing slash is dropped
 */
public record MavenMirror(String id, URI url) {

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  /** The configuration key of the mirror, in Lantern properties: {@code <PROPERTY>.id} and url. */
  public static final String PROPERTY = "lantern.sandbox.maven-mirror";

  public MavenMirror {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(url, "url");
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Invalid Maven mirror ID: " + id);
    }
    url = MavenRepository.checkedUrl("Maven mirror " + id, url, true);
  }

  /** The {@code host:port} the egress proxy lets builds reach for this mirror. */
  public String hostAndPort() {
    return MavenRepository.hostAndPort(url);
  }

  /**
   * Reads the mirror from properties written as Lantern's: {@code <PROPERTY>.id} and {@code
   * <PROPERTY>.url}.
   *
   * @return the mirror, or null if neither is set
   */
  public static MavenMirror from(Properties properties) {
    String id = properties.getProperty(PROPERTY + ".id");
    String url = properties.getProperty(PROPERTY + ".url");
    if (id == null && url == null) {
      return null;
    }
    if (id == null || url == null) {
      throw new IllegalArgumentException(
          PROPERTY + ".id and " + PROPERTY + ".url must both be set");
    }
    return new MavenMirror(id.strip(), URI.create(url.strip()));
  }

  /**
   * Reads the mirror from a properties file, as {@link #from(Properties)} does.
   *
   * @return the mirror, or null if the file sets none
   */
  public static MavenMirror from(Path file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    }
    return from(properties);
  }
}
