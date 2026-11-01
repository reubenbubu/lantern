package io.lantern.link;

import io.lantern.core.fact.Fact;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The query layer's reading of one fact's attributes (ADR 0095, amendment for #580): as its type's
 * generated record, or, for the few attributes many types share and code reads whatever the type
 * ({@code profile}, {@code written}, {@code masked}, {@code unresolved}, {@code key}, {@code
 * source}), by those names here alone. The linker's other code reads no attribute by name.
 */
final class Claims {

  private record RecordType(String type, MethodHandle from) {}

  private static final Map<Class<?>, RecordType> TYPES = new ConcurrentHashMap<>();

  private Claims() {}

  /**
   * A fact's attributes as its type's record.
   *
   * @throws IllegalArgumentException if the fact is of another type than the record's
   */
  static <R extends Record> R of(Fact fact, Class<R> record) {
    Objects.requireNonNull(fact, "fact");
    RecordType type = type(record);
    if (!fact.predicate().equals(type.type())) {
      throw new IllegalArgumentException(
          "Fact " + fact.id() + " is a " + fact.predicate() + ", not a " + type.type());
    }
    return read(record, type, fact.attributes());
  }

  /** A fact's attributes as a record, or null when there is no fact or it is of another type. */
  static <R extends Record> R ofOrNull(Fact fact, Class<R> record) {
    return fact == null || !fact.predicate().equals(type(record).type()) ? null : of(fact, record);
  }

  /**
   * A copy of a fact's attributes as JSON, to show or copy whole (a prompt line, a fact rebuilt
   * with other evidence, lineage); never to read one attribute by name.
   */
  static ObjectNode json(Fact fact) {
    return fact.attributes().deepCopy();
  }

  /** The type a record is the attributes of. */
  static String typeOf(Class<? extends Record> record) {
    return type(record).type();
  }

  /**
   * The profile under which alone an extracted fact holds, or null for the default view. A derived
   * fact names its view in {@link #view} instead.
   */
  static String profile(Fact fact) {
    return string(fact, "profile");
  }

  /**
   * The view under which alone a derived fact holds (a profile, or for value resolution's claims a
   * deployment), or null for the default view (ADR 0095, amendment for #590).
   */
  static String view(Fact fact) {
    return string(fact, "view");
  }

  /** What was written where a fact's value was read, if it records it. */
  static String written(Fact fact) {
    return string(fact, "written");
  }

  /** Whether a fact's value is masked as a secret. */
  static boolean masked(Fact fact) {
    return fact.attributes().path("masked").asBoolean(false);
  }

  /** The config key of a config-like fact ({@code config-value}, {@code datasource}), or null. */
  static String key(Fact fact) {
    return string(fact, "key");
  }

  /** Where a config-like fact's value comes from when not from a file ({@code source}), or null. */
  static String source(Fact fact) {
    return string(fact, "source");
  }

  /**
   * The placeholders a fact's value could not resolve ({@code unresolved}), as written; empty for
   * none.
   */
  static java.util.List<String> unresolved(Fact fact) {
    JsonNode value = fact.attributes().get("unresolved");
    java.util.List<String> result = new java.util.ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }

  /** The IDs of the facts a derived fact comes from ({@code facts}); empty for none. */
  static java.util.List<String> cited(Fact fact) {
    return strings(fact, "facts");
  }

  /** Whether a derived fact names the views it is not found in ({@code absentIn}, #590). */
  static boolean namesAbsence(Fact fact) {
    return fact.attributes().has("absentIn");
  }

  private static java.util.List<String> strings(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    java.util.List<String> result = new java.util.ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }

  /**
   * A unit an ambiguous address may lead to, as address resolution records it among its {@code
   * candidates} (#589).
   */
  record Candidate(String unit, String fact, String matchedBy, double factor) {}

  /** The candidates of an address resolution claim, in order; none when absent. */
  static java.util.List<Candidate> candidates(java.util.List<ObjectNode> nodes) {
    java.util.List<Candidate> candidates = new java.util.ArrayList<>();
    if (nodes != null) {
      for (ObjectNode c : nodes) {
        JsonNode fact = c.get("fact");
        candidates.add(
            new Candidate(
                c.path("unit").asString(),
                fact == null || !fact.isString() ? null : fact.stringValue(),
                c.path("matchedBy").asString(),
                c.path("factor").asDouble()));
      }
    }
    return candidates;
  }

  /** A list attribute a record gives, or none when it is absent. */
  static java.util.List<String> list(java.util.List<String> values) {
    return values == null ? java.util.List.of() : values;
  }

  private static String string(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    return value == null || !value.isString() ? null : value.stringValue();
  }

  private static RecordType type(Class<?> record) {
    return TYPES.computeIfAbsent(
        record,
        r -> {
          try {
            String type = (String) r.getField("TYPE").get(null);
            MethodHandle from =
                MethodHandles.publicLookup()
                    .findStatic(r, "from", MethodType.methodType(r, ObjectNode.class));
            return new RecordType(type, from);
          } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(r.getName() + " is not an attribute record", e);
          }
        });
  }

  /** Reads attributes as a record. */
  static <R extends Record> R read(Class<R> record, ObjectNode attributes) {
    return read(record, type(record), attributes);
  }

  private static <R extends Record> R read(Class<R> record, RecordType type, ObjectNode json) {
    try {
      return record.cast(type.from().invoke(json));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("Cannot read attributes as " + record.getName(), e);
    }
  }
}
