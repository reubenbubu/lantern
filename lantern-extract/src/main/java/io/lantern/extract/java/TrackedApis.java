package io.lantern.extract.java;

import java.util.List;
import java.util.Map;

/**
 * The library APIs whose calls the analyzers record as {@link ModuleStructure.Invocation}s, with
 * their arguments as {@link Expression}s, for the extractors that read them. Only these are
 * recorded, so the analysis stays small: an extractor that needs another API adds its package here.
 */
final class TrackedApis {

  /** Package prefixes of the tracked types. */
  private static final List<String> PACKAGES =
      List.of(
          // HTTP clients (the HTTP client extractor): RestTemplate, RestClient and their builders.
          "org.springframework.web.client.",
          "org.springframework.boot.web.client.",
          "org.springframework.boot.restclient.",
          // WebClient
          "org.springframework.web.reactive.function.client.",
          // Jakarta (and javax) REST client API
          "jakarta.ws.rs.client.",
          "javax.ws.rs.client.",
          // Spring's HTTP interface proxies: HttpServiceProxyFactory (the adapters are in the
          // RestClient and WebClient packages above).
          "org.springframework.web.service.",
          // java.net.http requests
          "java.net.http.",
          // OkHttp requests
          "okhttp3.",
          // Apache HttpClient 4 and 5 requests and request builders, and 4's fluent API (5's is in
          // org.apache.hc.client5.http.fluent)
          "org.apache.http.client.methods.",
          "org.apache.http.client.fluent.",
          "org.apache.hc.client5.http.",
          "org.apache.hc.core5.http.io.support.",
          // Messaging (the messaging extractor): Spring Kafka and Kafka's producer, JMS templates,
          // and RabbitMQ templates and declarations (queues, exchanges, bindings and builders).
          "org.springframework.kafka.core.",
          "org.apache.kafka.clients.producer.",
          "org.springframework.jms.core.",
          "org.springframework.amqp.",
          // Spring Cloud Stream: StreamBridge, and the legacy Source and Processor bindings.
          "org.springframework.cloud.stream.");

  private static final String REGISTRY = "org.springframework.web.service.registry.";
  private static final String GROUPS = REGISTRY + "HttpServiceGroupConfigurer$Groups";
  private static final String GROUP = REGISTRY + "HttpServiceGroup";

  /**
   * Stands, in {@link #lambdaParameters(String, String, int)}, for the first type argument of the
   * receiver's type ({@code CB} of {@code Groups<CB>}).
   */
  static final String TYPE_ARGUMENT = "<type argument>";

  /**
   * The functional types of tracked APIs whose lambdas are written without parameter types, with
   * the erased type of each parameter and the type argument they give the first one's generic type,
   * so parsing can type them as javac infers them: Spring's HTTP service group configurers ({@code
   * groups -> groups.filterByName("a").forEachClient(...)}).
   */
  private static final Map<String, List<String>> FUNCTIONAL_TYPES =
      Map.of(
          "org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer",
          List.of(GROUPS, "org.springframework.web.client.RestClient$Builder"),
          "org.springframework.web.reactive.function.client.support.WebClientHttpServiceGroupConfigurer",
          List.of(GROUPS, "org.springframework.web.reactive.function.client.WebClient$Builder"));

  /**
   * The methods of tracked types that take a lambda, by type, method and the lambda's number of
   * parameters, with the erased type of each parameter.
   */
  private static final Map<String, List<String>> LAMBDA_METHODS =
      Map.of(
          GROUPS + "#forEachClient/2",
          List.of(GROUP, TYPE_ARGUMENT),
          GROUPS + "#forEachGroup/3",
          List.of(
              GROUP,
              TYPE_ARGUMENT,
              "org.springframework.web.service.invoker.HttpServiceProxyFactory$Builder"),
          GROUPS + "#forEachProxyFactory/2",
          List.of(
              GROUP, "org.springframework.web.service.invoker.HttpServiceProxyFactory$Builder"));

  private TrackedApis() {}

  /**
   * The erased parameter types of a lambda written for a functional type, when this names them;
   * empty otherwise.
   */
  static List<String> lambdaParameters(String functionalType) {
    List<String> types = functionalType == null ? null : FUNCTIONAL_TYPES.get(functionalType);
    return types == null ? List.of() : types.subList(0, 1);
  }

  /** The type argument a functional type gives its lambda's first parameter's type, or null. */
  static String typeArgument(String functionalType) {
    List<String> types = functionalType == null ? null : FUNCTIONAL_TYPES.get(functionalType);
    return types == null ? null : types.get(1);
  }

  /**
   * The erased parameter types of a lambda passed to a method of a tracked type, when this names
   * them, {@link #TYPE_ARGUMENT} for one of the receiver's type argument; empty otherwise.
   */
  static List<String> lambdaParameters(String receiverType, String method, int parameters) {
    return LAMBDA_METHODS.getOrDefault(receiverType + "#" + method + "/" + parameters, List.of());
  }

  /**
   * Whether the values given to a field of this type are recorded: a {@code String}, which may hold
   * a URL or name, or a tracked type, which may be a client built in a constructor.
   */
  static boolean isFollowed(String binaryName) {
    return "java.lang.String".equals(binaryName) || isTracked(binaryName);
  }

  /** Whether calls on a value of this type are recorded. */
  static boolean isTracked(String binaryName) {
    if (binaryName == null) {
      return false;
    }
    for (String p : PACKAGES) {
      if (binaryName.startsWith(p)) {
        return true;
      }
    }
    return false;
  }
}
