package io.lantern.compose;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The end of free support of each line of the runtimes and frameworks technology currency shows,
 * from the table bundled with Lantern ({@code end-of-life.csv}, ADR 0051), so no network call is
 * needed. A line is a major Java release, or a major and minor version of Spring Boot or Spring
 * Framework.
 */
final class EndOfLife {

  /** The products the table covers. */
  enum Product {
    JAVA("java", "Java"),
    SPRING_BOOT("spring-boot", "Spring Boot"),
    SPRING_FRAMEWORK("spring-framework", "Spring Framework");

    private final String key;
    private final String title;

    Product(String key, String title) {
      this.key = key;
      this.title = title;
    }

    String title() {
      return title;
    }
  }

  /** What the table says about a version. */
  sealed interface Support {
    /** Its line's free support ends, or ended, on the date. */
    record Until(String line, LocalDate end) implements Support {}

    /** Its line is older than every line of the table, so its support has ended. */
    record Older(String line) implements Support {}

    /**
     * Its line falls between two lines of the table (a Java release Temurin never built), so its
     * support ended no later than that of the next newer line, on the date.
     */
    record EndedBy(String line, LocalDate by) implements Support {}

    /** The table does not know its line, or its version could not be read. */
    record Unknown() implements Support {}
  }

  private static final Pattern JAVA = Pattern.compile("(?:1\\.)?(\\d{1,6})(?:[.\\-+_].*)?");
  private static final Pattern MAJOR_MINOR =
      Pattern.compile("(\\d{1,6})\\.(\\d{1,6})(?:[.\\-+_].*)?");

  /** Orders lines by their numbers: 2.10 after 2.9, 11 after 8. */
  static final Comparator<String> BY_VERSION =
      (a, b) -> {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
          int c =
              Integer.compare(
                  i < x.length ? Integer.parseInt(x[i]) : 0,
                  i < y.length ? Integer.parseInt(y[i]) : 0);
          if (c != 0) {
            return c;
          }
        }
        return 0;
      };

  /** The table bundled with Lantern; declared after {@link #BY_VERSION}, which loading uses. */
  static final EndOfLife BUNDLED = load("end-of-life.csv");

  private final LocalDate asOf;
  private final Map<Product, TreeMap<String, LocalDate>> lines;

  EndOfLife(LocalDate asOf, Map<Product, TreeMap<String, LocalDate>> lines) {
    this.asOf = asOf;
    this.lines = lines;
  }

  /** The day the table's dates were last checked. */
  LocalDate asOf() {
    return asOf;
  }

  /**
   * The line a version belongs to: the major release of Java ({@code 1.8} and {@code 8} are 8), the
   * major and minor version of the others; empty if it cannot be read.
   */
  static Optional<String> line(Product product, String version) {
    if (version == null) {
      return Optional.empty();
    }
    String v = version.strip();
    if (product == Product.JAVA) {
      Matcher m = JAVA.matcher(v);
      return m.matches()
          ? Optional.of(String.valueOf(Integer.parseInt(m.group(1))))
          : Optional.empty();
    }
    Matcher m = MAJOR_MINOR.matcher(v);
    return m.matches()
        ? Optional.of(Integer.parseInt(m.group(1)) + "." + Integer.parseInt(m.group(2)))
        : Optional.empty();
  }

  /** What the table says about the version of the product. */
  Support support(Product product, String version) {
    Optional<String> line = line(product, version);
    TreeMap<String, LocalDate> known = lines.getOrDefault(product, new TreeMap<>(BY_VERSION));
    if (line.isEmpty() || known.isEmpty()) {
      return new Support.Unknown();
    }
    LocalDate end = known.get(line.get());
    if (end != null) {
      return new Support.Until(line.get(), end);
    }
    if (BY_VERSION.compare(line.get(), known.firstKey()) < 0) {
      return new Support.Older(line.get());
    }
    String newer = known.higherKey(line.get());
    return newer == null
        ? new Support.Unknown()
        : new Support.EndedBy(line.get(), known.get(newer));
  }

  static EndOfLife load(String resource) {
    try (InputStream in = EndOfLife.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing end-of-life table " + resource);
      }
      return parse(
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static EndOfLife parse(List<String> text) {
    Map<String, Product> products = new HashMap<>();
    for (Product product : Product.values()) {
      products.put(product.key, product);
    }
    LocalDate asOf = null;
    Map<Product, TreeMap<String, LocalDate>> lines = new HashMap<>();
    for (String raw : text) {
      String row = raw.strip();
      if (row.isEmpty() || row.startsWith("#")) {
        continue;
      }
      String[] cells = row.split(",", -1);
      if (cells[0].equals("as-of") && cells.length == 2) {
        if (asOf != null) {
          throw new IllegalArgumentException("The end-of-life table has two as-of dates");
        }
        asOf = LocalDate.parse(cells[1]);
        continue;
      }
      Product product = products.get(cells[0]);
      if (product == null || cells.length != 3 || line(product, cells[1]).isEmpty()) {
        throw new IllegalArgumentException("Invalid end-of-life row: '" + row + "'");
      }
      String line = line(product, cells[1]).orElseThrow();
      if (!line.equals(cells[1])) {
        throw new IllegalArgumentException("Invalid end-of-life row: '" + row + "'");
      }
      if (lines
              .computeIfAbsent(product, p -> new TreeMap<>(BY_VERSION))
              .put(line, LocalDate.parse(cells[2]))
          != null) {
        throw new IllegalArgumentException("End-of-life row listed twice: '" + row + "'");
      }
    }
    if (asOf == null) {
      throw new IllegalArgumentException("The end-of-life table has no as-of date");
    }
    return new EndOfLife(asOf, lines);
  }
}
