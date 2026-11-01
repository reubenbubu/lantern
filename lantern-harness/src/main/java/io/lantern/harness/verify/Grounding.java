package io.lantern.harness.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that what the LLM wrote names only what the inputs it cites mention (ADR 0068): every
 * code-like name and technology in a text must appear in the text of what it cites, or of what it
 * is about. The check is deterministic and makes no call.
 *
 * <p>A code-like name is a span in backticks, or a word with a capital after a lower-case letter
 * ({@code OrderService}, {@code orderId}, {@code PostgreSQL}), with a dot between letters ({@code
 * com.example.shop}, {@code order.created}), with an underscore inside it ({@code order_items}),
 * ending in {@code ()} ({@code placeOrder()}) or starting with a slash ({@code /api/orders}). A
 * technology is one of a fixed list of databases, brokers, frameworks and platforms, such as Kafka
 * or Redis, written as the list writes it. Plain words, words in capitals such as {@code HTTP} and
 * hyphenated words such as {@code shop-core} are not checked: they are too often plain English; nor
 * are a few plain words and brand names in mixed case, such as {@code SaaS} or {@code GitHub}.
 * Words joined by a hyphen, a slash or a dash ({@code PostgreSQL-backed}, {@code
 * OrderService/OrderRepository}) are checked one by one; a path keeps its slashes.
 *
 * <p>A name appears in a text when the text holds it ignoring case and every character that is not
 * a letter or digit, so {@code OrderService} appears in {@code com.example.OrderService} and {@code
 * order.created} in {@code "order.created"}; a technology also appears when one of its other names
 * does, such as {@code postgres} for PostgreSQL. A trailing {@code 's} is ignored, and a name
 * ending in {@code s} also appears when the text holds it without that {@code s}.
 */
public final class Grounding {

  /** Technologies by how a text names them, each with the names its inputs may give it. */
  private static final Map<String, List<String>> TECHNOLOGIES = technologies();

  private static final Pattern TECHNOLOGY;

  static {
    StringBuilder alternatives = new StringBuilder();
    TECHNOLOGIES.keySet().stream()
        .sorted((a, b) -> b.length() - a.length())
        .forEach(
            name -> alternatives.append(alternatives.isEmpty() ? "" : "|").append(quote(name)));
    TECHNOLOGY = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alternatives + ")(?![\\p{L}\\p{N}_])");
  }

  /** A citation in a summary's text, as the model writes it or as it is stored. */
  private static final Pattern CITATION =
      Pattern.compile("\\[\\s*(?:[FES]\\d|fact:|summary:)[^\\]]*\\]");

  private static final Pattern BACKTICKS = Pattern.compile("`+([^`]+)`+");

  /** A lower-case letter followed by a capital, as in camel case. */
  private static final Pattern CAMEL = Pattern.compile("\\p{Ll}\\p{Lu}");

  /** A call, such as {@code placeOrder()}. */
  private static final Pattern CALL = Pattern.compile("[\\p{L}\\p{N}_$]\\(\\)");

  /** Identifiers joined by dots, each starting with a letter. */
  private static final Pattern DOTTED =
      Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+");

  /** Punctuation around a word that is not part of it. */
  private static final Pattern EDGES =
      Pattern.compile("^[\\s\"'‘’“”(\\[{<*_,;:!?.]+|[\\s\"'‘’“”)\\]}>*_,;:!?.]+$");

  private static final Pattern POSSESSIVE = Pattern.compile("['’]s$");

  /** What joins two words into one token: a hyphen, a slash or a dash. */
  private static final Pattern JOINER = Pattern.compile("[-/\u2010-\u2015]+");

  /** Plain words and brand names written in mixed case, which are not code. */
  private static final Set<String> MIXED_CASE_WORDS =
      Set.of(
          "SaaS",
          "PaaS",
          "IaaS",
          "DevOps",
          "JavaScript",
          "TypeScript",
          "GitHub",
          "GitLab",
          "OpenAPI",
          "OAuth",
          "eCommerce",
          "iOS",
          "macOS",
          "YouTube",
          "LinkedIn",
          "PayPal",
          "WordPress",
          "PowerShell",
          "IntelliJ");

  /** Abbreviations whose dots do not make them code. */
  private static final Set<String> ABBREVIATIONS = Set.of("e.g", "i.e", "etc", "vs", "a.k.a");

  private Grounding() {}

  /**
   * The code-like names and technologies a text names, each once, in order of first mention, as
   * written. Citations in square brackets are not part of the text.
   */
  public static List<String> names(String text) {
    Set<String> names = new LinkedHashSet<>();
    String rest = CITATION.matcher(text == null ? "" : text).replaceAll(" ");
    Matcher quoted = BACKTICKS.matcher(rest);
    StringBuilder unquoted = new StringBuilder();
    while (quoted.find()) {
      String name = quoted.group(1).strip();
      if (!normalized(name).isEmpty()) {
        names.add(name);
      }
      quoted.appendReplacement(unquoted, " ");
    }
    quoted.appendTail(unquoted);
    rest = unquoted.toString();
    Matcher technology = TECHNOLOGY.matcher(rest);
    while (technology.find()) {
      names.add(technology.group());
    }
    for (String word : rest.split("\\s+")) {
      String trimmed = EDGES.matcher(word).replaceAll("");
      // A path keeps its slashes and hyphens; elsewhere they join words, each checked alone.
      String[] tokens = trimmed.startsWith("/") ? new String[] {word} : JOINER.split(word);
      for (String token : tokens) {
        String name = name(token);
        if (name != null) {
          names.add(name);
        }
      }
    }
    return new ArrayList<>(names);
  }

  /** The token as a code-like name, without the punctuation around it, or null if it is none. */
  private static String name(String token) {
    boolean call = CALL.matcher(token).find();
    String bare = token.replace("()", "");
    bare = POSSESSIVE.matcher(EDGES.matcher(bare).replaceAll("")).replaceAll("");
    bare = EDGES.matcher(bare).replaceAll("");
    if (bare.isEmpty() || TECHNOLOGIES.containsKey(bare) || MIXED_CASE_WORDS.contains(bare)) {
      return null;
    }
    if (call
        || CAMEL.matcher(bare).find()
        || DOTTED.matcher(bare).matches()
            && !ABBREVIATIONS.contains(bare.toLowerCase(Locale.ROOT))
            && bare.replace(".", "").length() > 2
        || bare.indexOf('_') > 0 && bare.chars().anyMatch(Character::isLetter)
        || bare.startsWith("/") && bare.length() > 1 && Character.isLetter(bare.charAt(1))) {
      return bare;
    }
    return null;
  }

  /**
   * The names of a text, as {@link #names(String)} finds them, that appear in none of the texts it
   * may rest on.
   *
   * @param text what the LLM wrote
   * @param support the texts of what it cites and of what it is about
   */
  public static List<String> unsupported(String text, List<String> support) {
    String haystack = normalized(String.join(" ", support));
    List<String> missing = new ArrayList<>();
    for (String name : names(text)) {
      if (!appears(name, haystack)) {
        missing.add(name);
      }
    }
    return missing;
  }

  private static boolean appears(String name, String haystack) {
    List<String> forms = new ArrayList<>(TECHNOLOGIES.getOrDefault(name, List.of()));
    forms.add(name);
    for (String form : forms) {
      String needle = normalized(form);
      if (needle.isEmpty()
          || haystack.contains(needle)
          || needle.length() > 3
              && needle.endsWith("s")
              && haystack.contains(needle.substring(0, needle.length() - 1))) {
        return true;
      }
    }
    return false;
  }

  /** The text in lower case with only its letters and digits. */
  private static String normalized(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.toLowerCase(Locale.ROOT)
        .codePoints()
        .filter(Character::isLetterOrDigit)
        .forEach(out::appendCodePoint);
    return out.toString();
  }

  private static String quote(String name) {
    return Pattern.quote(name);
  }

  private static Map<String, List<String>> technologies() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    // Databases and stores.
    map.put("PostgreSQL", List.of("postgres"));
    map.put("Postgres", List.of("postgres"));
    map.put("MySQL", List.of("mysql"));
    map.put("MariaDB", List.of("mariadb"));
    map.put("Oracle", List.of("oracle"));
    map.put("SQL Server", List.of("sqlserver", "mssql"));
    map.put("MongoDB", List.of("mongo"));
    map.put("Mongo", List.of("mongo"));
    map.put("Cassandra", List.of("cassandra"));
    map.put("Redis", List.of("redis"));
    map.put("Memcached", List.of("memcached"));
    map.put("Elasticsearch", List.of("elasticsearch", "elastic"));
    map.put("OpenSearch", List.of("opensearch"));
    map.put("DynamoDB", List.of("dynamodb"));
    map.put("Neo4j", List.of("neo4j"));
    map.put("Couchbase", List.of("couchbase"));
    map.put("Hazelcast", List.of("hazelcast"));
    map.put("H2", List.of("h2"));
    map.put("Flyway", List.of("flyway"));
    map.put("Liquibase", List.of("liquibase"));
    // Brokers.
    map.put("Kafka", List.of("kafka"));
    map.put("RabbitMQ", List.of("rabbit", "amqp"));
    map.put("ActiveMQ", List.of("activemq"));
    map.put("Artemis", List.of("artemis"));
    map.put("SQS", List.of("sqs"));
    map.put("SNS", List.of("sns"));
    map.put("Pub/Sub", List.of("pubsub"));
    // Frameworks and libraries.
    map.put("Spring", List.of("spring"));
    map.put("Hibernate", List.of("hibernate"));
    map.put("JPA", List.of("jpa", "persistence"));
    map.put("JDBC", List.of("jdbc"));
    map.put("gRPC", List.of("grpc"));
    map.put("GraphQL", List.of("graphql"));
    map.put("Quarkus", List.of("quarkus"));
    map.put("Micronaut", List.of("micronaut"));
    map.put("Camel", List.of("camel"));
    map.put("Feign", List.of("feign"));
    map.put("Eureka", List.of("eureka"));
    map.put("Netty", List.of("netty"));
    map.put("Thymeleaf", List.of("thymeleaf"));
    map.put("Keycloak", List.of("keycloak"));
    map.put("Resilience4j", List.of("resilience4j"));
    // Platforms.
    map.put("Docker", List.of("docker"));
    map.put("Kubernetes", List.of("kubernetes", "k8s"));
    map.put("AWS", List.of("aws", "amazon"));
    map.put("S3", List.of("s3"));
    map.put("Azure", List.of("azure"));
    map.put("GCP", List.of("gcp", "googlecloud"));
    map.put("Consul", List.of("consul"));
    map.put("ZooKeeper", List.of("zookeeper"));
    map.put("Prometheus", List.of("prometheus"));
    return map;
  }
}
