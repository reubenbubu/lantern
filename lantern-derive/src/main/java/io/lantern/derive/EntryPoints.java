package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry points of a method, read for the composer (#761): what kind each is and what starts it,
 * so the composer does not read a fact's predicate or value.
 */
public final class EntryPoints {

  private EntryPoints() {}

  /** What kind of entry point a fact states. */
  public enum Kind {
    ENDPOINT(Types.ENDPOINT),
    MESSAGE_LISTENER(Types.MESSAGE_LISTENER),
    STOMP_MAPPING(Types.STOMP_MAPPING),
    WEBSOCKET_ENDPOINT(Types.WEBSOCKET_ENDPOINT),
    SCHEDULED(Types.SCHEDULED),
    BACKGROUND_JOB(Types.BACKGROUND_JOB);

    private final String type;

    Kind(String type) {
      this.type = type;
    }
  }

  /**
   * An entry point.
   *
   * @param fact the fact stating it
   * @param kind its kind
   * @param value what starts it, as stored: the endpoint, the destination, the WebSocket path or
   *     the schedule
   */
  public record EntryPoint(Fact fact, Kind kind, String value) {

    /** What starts it, in words: the endpoint, the destination or the schedule. */
    public String label() {
      return switch (kind) {
        case ENDPOINT -> value;
        case MESSAGE_LISTENER -> "Messages on " + value;
        case STOMP_MAPPING -> "STOMP messages to " + value;
        case WEBSOCKET_ENDPOINT -> "WebSocket " + value;
        default -> Components.jobKind(fact) + " (" + value + ")";
      };
    }

    /** Its kind, in words, as the catalog names it. */
    public String kindName() {
      return switch (kind) {
        case ENDPOINT -> "HTTP endpoint";
        case MESSAGE_LISTENER -> "Message listener";
        case STOMP_MAPPING -> "STOMP message handler";
        case WEBSOCKET_ENDPOINT ->
            Elements.isStomp(fact) ? "STOMP WebSocket endpoint" : "WebSocket endpoint";
        default -> Components.jobKind(fact);
      };
    }
  }

  /** The entry point a fact states, or {@code null} when it states none. */
  public static EntryPoint of(Fact fact) {
    for (Kind kind : Kind.values()) {
      if (kind.type.equals(fact.predicate())) {
        return new EntryPoint(fact, kind, fact.object().value());
      }
    }
    return null;
  }

  /** The entry points of the method, in the order the model holds them. */
  public static List<EntryPoint> of(ArchitectureModel model, EntityId method) {
    List<EntryPoint> result = new ArrayList<>();
    for (Fact fact : model.attributes(method)) {
      EntryPoint entry = of(fact);
      if (entry != null) {
        result.add(entry);
      }
    }
    return result;
  }
}
