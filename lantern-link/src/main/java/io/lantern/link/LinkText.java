package io.lantern.link;

import io.lantern.core.fact.Fact;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The text matching the linker does: placeholders resolved against a unit's config, URLs taken
 * apart, paths matched against endpoint templates and routing keys against AMQP binding patterns.
 */
final class LinkText {

  private LinkText() {}

  // ---------------------------------------------------------------------------------------------
  // Placeholders

  /**
   * A text with its placeholders resolved.
   *
   * @param text the text, a placeholder nothing resolves kept as written
   * @param unresolved the keys of the placeholders kept
   * @param masked whether a secret was substituted into it, so it must not be used
   * @param used the config facts it used
   */
  record Resolved(String text, Set<String> unresolved, boolean masked, List<Fact> used) {}

  /**
   * Resolves {@code ${key}} and {@code ${key:default}} as Spring does, nested ones included, taking
   * each key's value from the lookup: a config fact whose object is the value as its view resolved
   * it, with the placeholders it kept in {@code unresolved}.
   */
  static Resolved resolve(String text, Function<String, Fact> lookup) {
    Set<String> unresolved = new LinkedHashSet<>();
    List<Fact> used = new ArrayList<>();
    boolean[] masked = {false};
    String result = resolve(text, lookup, unresolved, used, masked, 0);
    return new Resolved(result, unresolved, masked[0], used);
  }

  private static String resolve(
      String text,
      Function<String, Fact> lookup,
      Set<String> unresolved,
      List<Fact> used,
      boolean[] masked,
      int depth) {
    if (depth > 10) {
      return text;
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      int start = text.indexOf("${", i);
      if (start < 0) {
        out.append(text, i, text.length());
        break;
      }
      int end = closing(text, start);
      if (end < 0) {
        out.append(text, i, text.length());
        break;
      }
      out.append(text, i, start);
      String inner = text.substring(start + 2, end);
      int colon = separator(inner);
      String key =
          resolve(
              colon < 0 ? inner : inner.substring(0, colon),
              lookup,
              unresolved,
              used,
              masked,
              depth + 1);
      Fact value = lookup.apply(key);
      if (value != null) {
        used.add(value);
        if (LinkIndex.flag(value, "masked")) {
          masked[0] = true;
        }
        unresolved.addAll(LinkIndex.texts(value, "unresolved"));
        out.append(value.object().value());
      } else if (colon >= 0) {
        out.append(
            resolve(inner.substring(colon + 1), lookup, unresolved, used, masked, depth + 1));
      } else {
        unresolved.add(key);
        out.append("${").append(key).append('}');
      }
      i = end + 1;
    }
    return out.toString();
  }

  /** The index of the brace closing the placeholder at {@code start}, or -1. */
  private static int closing(String text, int start) {
    int depth = 0;
    for (int i = start; i < text.length(); i++) {
      if (text.startsWith("${", i)) {
        depth++;
        i++;
      } else if (text.charAt(i) == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /** The index of the colon between a placeholder's key and default, outside nested ones. */
  private static int separator(String inner) {
    int depth = 0;
    for (int i = 0; i < inner.length(); i++) {
      if (inner.startsWith("${", i)) {
        depth++;
        i++;
      } else if (inner.charAt(i) == '}') {
        depth--;
      } else if (inner.charAt(i) == ':' && depth == 0) {
        return i;
      }
    }
    return -1;
  }

  // ---------------------------------------------------------------------------------------------
  // URLs

  /**
   * A URL taken apart.
   *
   * @param scheme the scheme in lower case, or null when the text does not start with one
   * @param host the host in lower case, or null when not known (a placeholder or expression)
   * @param port the port as written, or null
   * @param path the path, without query and fragment; "" for none; null when not known
   */
  record Url(String scheme, String host, String port, String path) {

    /** Whether the host is this machine, so the URL names no service by its host. */
    boolean local() {
      return host != null
          && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }
  }

  private static final Pattern SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://.*");

  /**
   * Takes a URL apart. Text that does not start with a scheme has no host; its path is what follows
   * a leading placeholder or expression ({@code ${base}/api}, {@code {url}/api}), or the text
   * itself if it starts with a slash.
   */
  static Url parse(String text) {
    if (text == null) {
      return new Url(null, null, null, null);
    }
    if (SCHEME.matcher(text).matches()) {
      int colon = text.indexOf("://");
      String scheme = text.substring(0, colon).toLowerCase(Locale.ROOT);
      String rest = text.substring(colon + 3);
      int slash = authorityEnd(rest);
      String authority = slash < 0 ? rest : rest.substring(0, slash);
      String path = slash < 0 ? "" : stripQuery(rest.substring(slash));
      authority = authority.substring(authority.lastIndexOf('@') + 1);
      if (authority.contains("${") || authority.contains("{") || authority.isEmpty()) {
        return new Url(scheme, null, null, path);
      }
      String host = authority;
      String port = null;
      int portColon = authority.lastIndexOf(':');
      if (portColon > authority.lastIndexOf(']')) {
        host = authority.substring(0, portColon);
        port = authority.substring(portColon + 1);
      }
      return new Url(scheme, host.toLowerCase(Locale.ROOT), port, path);
    }
    String rest = text;
    if (rest.startsWith("${")) {
      int end = closing(rest, 0);
      rest = end < 0 ? "" : rest.substring(end + 1);
    } else if (rest.startsWith("{")) {
      int end = rest.indexOf('}');
      rest = end < 0 ? "" : rest.substring(end + 1);
    }
    return new Url(null, null, null, rest.startsWith("/") ? stripQuery(rest) : null);
  }

  /**
   * Where the authority after a scheme ends: at a slash, query or fragment outside braces, or at an
   * expression ({@code {path}}, not a {@code ${...}} placeholder) that follows the host, as in a
   * URL concatenated from a base and a path ({@code http://localhost:8888{path}}); -1 if it runs to
   * the end.
   */
  private static int authorityEnd(String rest) {
    int depth = 0;
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '{') {
        if (depth == 0 && i > 0 && rest.charAt(i - 1) != '$') {
          return i;
        }
        depth++;
      } else if (c == '}') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0 && "/?#".indexOf(c) >= 0) {
        return i;
      }
    }
    return -1;
  }

  /** A path without its query and fragment; a {@code ?} or {@code #} in braces is kept. */
  private static String stripQuery(String path) {
    int depth = 0;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0 && (c == '?' || c == '#')) {
        return path.substring(0, i);
      }
    }
    return path;
  }

  // ---------------------------------------------------------------------------------------------
  // Paths

  /**
   * How well a request path matches an endpoint's path template: the number of literal segments
   * that match, or -1 when it does not match. A template variable ({@code {id}}, {@code {id:\d+}})
   * or {@code *} matches any one segment, a trailing {@code **} any rest; a variable or expression
   * in the request path ({@code {productId}}) matches any one segment of the template.
   */
  static int pathMatch(String request, String template) {
    List<String> r = segments(request);
    List<String> t = segments(template);
    int score = 0;
    for (int i = 0; i < t.size(); i++) {
      String ts = t.get(i);
      if (ts.equals("**") && i == t.size() - 1) {
        return score;
      }
      if (i >= r.size()) {
        return -1;
      }
      String rs = r.get(i);
      boolean variable = isVariable(rs) || isVariable(ts) || ts.equals("*");
      if (!variable && !rs.equals(ts)) {
        return -1;
      }
      if (!variable) {
        score++;
      }
    }
    return r.size() == t.size() ? score : -1;
  }

  /** Joins a prefix such as a context path and a path with one slash between. */
  static String join(String prefix, String path) {
    if (prefix == null || prefix.isBlank() || prefix.equals("/")) {
      return path;
    }
    String p = prefix.startsWith("/") ? prefix : "/" + prefix;
    p = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    return p + (path.startsWith("/") ? path : "/" + path);
  }

  private static boolean isVariable(String segment) {
    return segment.contains("{") || segment.contains("${");
  }

  /** A path's segments, split at slashes outside braces, so an expression stays one segment. */
  private static List<String> segments(String path) {
    List<String> segments = new ArrayList<>();
    StringBuilder segment = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth = Math.max(0, depth - 1);
      }
      if (c == '/' && depth == 0) {
        if (!segment.isEmpty()) {
          segments.add(segment.toString());
        }
        segment.setLength(0);
      } else {
        segment.append(c);
      }
    }
    if (!segment.isEmpty()) {
      segments.add(segment.toString());
    }
    return segments;
  }

  // ---------------------------------------------------------------------------------------------
  // Routing keys and topic patterns

  /**
   * Whether a routing key matches an AMQP topic binding pattern as the broker matches it: words
   * split on dots, {@code *} matching exactly one word and {@code #} zero or more.
   */
  static boolean topicMatches(String pattern, String key) {
    return topicMatches(pattern.split("\\.", -1), 0, key.split("\\.", -1), 0);
  }

  private static boolean topicMatches(String[] p, int i, String[] k, int j) {
    if (i == p.length) {
      return j == k.length;
    }
    if (p[i].equals("#")) {
      for (int skip = j; skip <= k.length; skip++) {
        if (topicMatches(p, i + 1, k, skip)) {
          return true;
        }
      }
      return false;
    }
    if (j == k.length) {
      return false;
    }
    return (p[i].equals("*") || p[i].equals(k[j])) && topicMatches(p, i + 1, k, j + 1);
  }

  /** Whether a Kafka topic name matches a listener's topic pattern, a Java regular expression. */
  static boolean regexMatches(String pattern, String topic) {
    try {
      return Pattern.compile(pattern).matcher(topic).matches();
    } catch (PatternSyntaxException e) {
      return false;
    }
  }
}
