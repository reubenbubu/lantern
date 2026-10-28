package io.lantern.sandbox;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Maven repository builds may download from. Builds see it as a dependency repository in their
 * Maven settings, and the egress proxy lets them reach its host and port and nothing else (ADR
 * 0023). Only release artifacts are downloaded from it.
 *
 * @param id the repository ID Maven knows it by, such as {@code central}
 * @param url the repository's base URL: {@code https}, an exact host name (no wildcards), no user
 *     information, query or fragment; a trailing slash is dropped
 */
public record MavenRepository(String id, URI url) {

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  // A DNS name with at least two labels whose last label is not all digits: no single-label
  // names such as localhost, and no IP addresses.
  private static final Pattern HOST =
      Pattern.compile(
          "([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+(?=[a-z0-9-]*[a-z])[a-z0-9]([a-z0-9-]*[a-z0-9])?");
  private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)*/?");
  private static final Pattern ENTRY = Pattern.compile("\\[([0-9]{1,4})]\\.(id|url)");

  /** Maven Central, the only repository builds reach unless configured otherwise. */
  public static final MavenRepository CENTRAL =
      new MavenRepository("central", URI.create("https://repo.maven.apache.org/maven2"));

  /** The configuration key of the list of repositories, in Lantern and test-set properties. */
  public static final String PROPERTY = "lantern.sandbox.maven-repositories";

  public MavenRepository {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(url, "url");
    if (!ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Invalid Maven repository ID: " + id);
    }
    String host = url.getHost() == null ? null : url.getHost().toLowerCase(Locale.ROOT);
    String path = url.getRawPath() == null ? "" : url.getRawPath();
    if (!"https".equals(url.getScheme())
        || host == null
        || !HOST.matcher(host).matches()
        || url.getPort() == 0
        || url.getPort() > 65535
        || url.getRawUserInfo() != null
        || url.getRawQuery() != null
        || url.getRawFragment() != null
        || !PATH.matcher(path).matches()
        || path.contains("/../")
        || path.endsWith("/..")
        || path.contains("/./")
        || path.endsWith("/.")) {
      throw new IllegalArgumentException(
          "Maven repository "
              + id
              + " needs an https URL with an exact host name (no IP address or wildcard) and"
              + " a plain path, and no user, query or fragment: "
              + url);
    }
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    url = URI.create("https://" + host + (url.getPort() == -1 ? "" : ":" + url.getPort()) + path);
  }

  /** The {@code host:port} the egress proxy lets builds reach for this repository. */
  public String hostAndPort() {
    return url.getHost() + ":" + (url.getPort() == -1 ? 443 : url.getPort());
  }

  /**
   * Reads the list of repositories under {@link #PROPERTY} from properties written as Spring writes
   * lists: {@code <PROPERTY>[0].id}, {@code <PROPERTY>[0].url}, {@code [1]} and so on, with no
   * gaps. This is how a test set's {@code lantern.properties} names its repositories.
   *
   * @return the repositories in order, or an empty list if there are none
   */
  public static List<MavenRepository> listFrom(Properties properties) {
    List<MavenRepository> repositories = new ArrayList<>();
    for (int i = 0; ; i++) {
      String prefix = PROPERTY + "[" + i + "].";
      String id = properties.getProperty(prefix + "id");
      String url = properties.getProperty(prefix + "url");
      if (id == null && url == null) {
        break;
      }
      if (id == null || url == null) {
        throw new IllegalArgumentException(prefix + "id and " + prefix + "url must both be set");
      }
      repositories.add(new MavenRepository(id.strip(), URI.create(url.strip())));
    }
    for (String key : properties.stringPropertyNames()) {
      if (key.startsWith(PROPERTY)) {
        Matcher entry = ENTRY.matcher(key.substring(PROPERTY.length()));
        if (!entry.matches() || Integer.parseInt(entry.group(1)) >= repositories.size()) {
          throw new IllegalArgumentException("Unexpected or out-of-order property: " + key);
        }
      }
    }
    return List.copyOf(repositories);
  }
}
