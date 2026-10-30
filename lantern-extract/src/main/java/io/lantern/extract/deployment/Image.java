package io.lantern.extract.deployment;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a container image reference names, when it is a product Lantern knows: the product, its
 * version from the tag, and the role it plays in a system.
 *
 * @param product the product, such as {@code PostgreSQL}
 * @param version the version the tag starts with, such as {@code 17.5}, or null when it names none
 * @param role what the product is to the system: {@code database}, {@code cache}, {@code broker},
 *     {@code identity}, {@code proxy}, {@code discovery}, {@code observability}, {@code mail},
 *     {@code storage} or {@code runtime}
 */
public record Image(String product, String version, String role) {

  private static final Pattern VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+){0,2})");

  /** Products by the last part of their image's repository name: product and role. */
  private static final Map<String, String[]> PRODUCTS =
      Map.ofEntries(
          Map.entry("postgres", new String[] {"PostgreSQL", "database"}),
          Map.entry("postgresql", new String[] {"PostgreSQL", "database"}),
          Map.entry("postgis", new String[] {"PostgreSQL", "database"}),
          Map.entry("mysql", new String[] {"MySQL", "database"}),
          Map.entry("mysql-server", new String[] {"MySQL", "database"}),
          Map.entry("mariadb", new String[] {"MariaDB", "database"}),
          Map.entry("mongo", new String[] {"MongoDB", "database"}),
          Map.entry("mongodb", new String[] {"MongoDB", "database"}),
          Map.entry("cassandra", new String[] {"Cassandra", "database"}),
          Map.entry("neo4j", new String[] {"Neo4j", "database"}),
          Map.entry("elasticsearch", new String[] {"Elasticsearch", "database"}),
          Map.entry("opensearch", new String[] {"OpenSearch", "database"}),
          Map.entry("redis", new String[] {"Redis", "cache"}),
          Map.entry("redis-server", new String[] {"Redis", "cache"}),
          Map.entry("valkey", new String[] {"Valkey", "cache"}),
          Map.entry("redis-stack", new String[] {"Redis Stack", "cache"}),
          Map.entry("redis-stack-server", new String[] {"Redis Stack", "cache"}),
          Map.entry("memcached", new String[] {"Memcached", "cache"}),
          Map.entry("rabbitmq", new String[] {"RabbitMQ", "broker"}),
          Map.entry("kafka", new String[] {"Kafka", "broker"}),
          Map.entry("cp-kafka", new String[] {"Kafka", "broker"}),
          Map.entry("cp-server", new String[] {"Kafka", "broker"}),
          Map.entry("zookeeper", new String[] {"ZooKeeper", "broker"}),
          Map.entry("cp-zookeeper", new String[] {"ZooKeeper", "broker"}),
          Map.entry("activemq-artemis", new String[] {"ActiveMQ Artemis", "broker"}),
          Map.entry("artemis", new String[] {"ActiveMQ Artemis", "broker"}),
          Map.entry("activemq", new String[] {"ActiveMQ", "broker"}),
          Map.entry("nats", new String[] {"NATS", "broker"}),
          Map.entry("keycloak", new String[] {"Keycloak", "identity"}),
          Map.entry("nginx", new String[] {"nginx", "proxy"}),
          Map.entry("traefik", new String[] {"Traefik", "proxy"}),
          Map.entry("envoy", new String[] {"Envoy", "proxy"}),
          Map.entry("haproxy", new String[] {"HAProxy", "proxy"}),
          Map.entry("consul", new String[] {"Consul", "discovery"}),
          Map.entry("prometheus", new String[] {"Prometheus", "observability"}),
          Map.entry("grafana", new String[] {"Grafana", "observability"}),
          Map.entry("loki", new String[] {"Loki", "observability"}),
          Map.entry("tempo", new String[] {"Tempo", "observability"}),
          Map.entry("zipkin", new String[] {"Zipkin", "observability"}),
          Map.entry("mailhog", new String[] {"MailHog", "mail"}),
          Map.entry("mailpit", new String[] {"Mailpit", "mail"}),
          Map.entry("minio", new String[] {"MinIO", "storage"}),
          Map.entry("eclipse-temurin", new String[] {"Eclipse Temurin", "runtime"}),
          Map.entry("openjdk", new String[] {"OpenJDK", "runtime"}),
          Map.entry("amazoncorretto", new String[] {"Amazon Corretto", "runtime"}),
          Map.entry("node", new String[] {"Node.js", "runtime"}));

  /**
   * The image a reference names if it is a known product, else null.
   *
   * @param reference an image reference as written, such as {@code postgres:17.5-alpine3.22} or
   *     {@code docker.io/bitnami/redis:7}, with any variables already resolved
   */
  public static Image of(String reference) {
    if (reference == null || reference.isBlank() || reference.contains("${")) {
      return null;
    }
    String ref = reference.strip();
    int digest = ref.indexOf('@');
    if (digest >= 0) {
      ref = ref.substring(0, digest);
    }
    int slash = ref.lastIndexOf('/');
    String last = slash < 0 ? ref : ref.substring(slash + 1);
    int colon = last.indexOf(':');
    String name = (colon < 0 ? last : last.substring(0, colon)).toLowerCase(Locale.ROOT);
    String tag = colon < 0 ? "" : last.substring(colon + 1);
    String repository = (slash < 0 ? "" : ref.substring(0, slash + 1)).toLowerCase(Locale.ROOT);
    String[] known = PRODUCTS.get(name);
    if (repository.contains("jaeger")) {
      known = new String[] {"Jaeger", "observability"};
    } else if (name.startsWith("opentelemetry-collector")) {
      known = new String[] {"OpenTelemetry Collector", "observability"};
    } else if (repository.endsWith("mssql/") && name.equals("server")) {
      known = new String[] {"SQL Server", "database"};
    }
    if (known == null) {
      return null;
    }
    Matcher version = VERSION.matcher(tag);
    return new Image(known[0], version.find() ? version.group(1) : null, known[1]);
  }

  /** The product with its version, such as {@code Redis 8.4.0}; the product alone without one. */
  public String technology() {
    return version == null ? product : product + " " + version;
  }
}
