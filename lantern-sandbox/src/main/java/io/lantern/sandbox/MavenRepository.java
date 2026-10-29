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
 * @param url the repository's base URL: {@code https}, an exact host name (no wildcards, no IP
 *     address), no user information, query or fragment; a trailing slash is dropped
 */
public record MavenRepository(String id, URI url) {

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  // A DNS name with at least two labels whose last label is not all digits: no single-label
  // names such as localhost, and no IP addresses.
  private static final Pattern HOST =
      Pattern.compile(
          "([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+(?=[a-z0-9-]*[a-z])[a-z0-9]([a-z0-9-]*[a-z0-9])?");
  private static final Pattern IPV4 =
      Pattern.compile(
          "(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})");
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
    url = checkedUrl("Maven repository " + id, url, false);
  }

  /**
   * Checks a repository or mirror URL and returns it normalized: lower-case host, no trailing
   * slash. It must have an exact host name (no wildcard), an optional port, a plain path, and no
   * user information, query or fragment. A repository URL must be {@code https} with a host name; a
   * mirror URL may also be {@code http}, and its host may also be an IPv4 address in a private
   * network: 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16 (ADR 0030).
   *
   * @param what what the URL is of, for the error message
   * @param url the URL
   * @param mirror whether the URL is a mirror's
   */
  static URI checkedUrl(String what, URI url, boolean mirror) {
    String scheme = url.getScheme() == null ? null : url.getScheme().toLowerCase(Locale.ROOT);
    String host = url.getHost() == null ? null : url.getHost().toLowerCase(Locale.ROOT);
    String path = url.getRawPath() == null ? "" : url.getRawPath();
    if (!("https".equals(scheme) || (mirror && "http".equals(scheme)))
        || host == null
        || !(HOST.matcher(host).matches() || (mirror && isPrivateIpv4(host)))
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
          what
              + (mirror
                  ? " needs an http or https URL with an exact host name or a private-network"
                      + " IPv4 address (no wildcard)"
                  : " needs an https URL with an exact host name (no IP address or wildcard)")
              + " and a plain path, and no user, query or fragment: "
              + url);
    }
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return URI.create(
        scheme + "://" + host + (url.getPort() == -1 ? "" : ":" + url.getPort()) + path);
  }

  /**
   * Whether a host is an IPv4 address, written as four decimal numbers without leading zeros, in
   * 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16. Loopback, link-local (such as the cloud metadata
   * service) and every other address are not.
   */
  private static boolean isPrivateIpv4(String host) {
    Matcher matcher = IPV4.matcher(host);
    if (!matcher.matches()) {
      return false;
    }
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      octets[i] = Integer.parseInt(matcher.group(i + 1));
      if (octets[i] > 255) {
        return false;
      }
    }
    return octets[0] == 10
        || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
        || (octets[0] == 192 && octets[1] == 168);
  }

  /** The {@code host:port} the egress proxy lets builds reach for this repository. */
  public String hostAndPort() {
    return hostAndPort(url);
  }

  /** The {@code host:port} of a checked URL, with the scheme's default port if it names none. */
  static String hostAndPort(URI url) {
    int port = url.getPort();
    return url.getHost() + ":" + (port != -1 ? port : "http".equals(url.getScheme()) ? 80 : 443);
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
