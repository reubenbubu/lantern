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

  /**
   * What a flow's trigger starts on, in words: its entry point's label, else, for a trigger no
   * entry kind names, its job kind and value.
   */
  public static String label(Fact trigger) {
    EntryPoint entry = of(trigger);
    return entry != null
        ? entry.label()
        : Components.jobKind(trigger) + " (" + trigger.object().value() + ")";
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

  /**
   * The entry points of a deployable unit's own code: of every method of every type its code
   * modules hold, by type and method in ID order (#973).
   */
  public static List<EntryPoint> ofUnit(ArchitectureModel model, EntityId unit) {
    java.util.SortedSet<EntityId> types = new java.util.TreeSet<>(ArchitectureModel.BY_ID);
    for (EntityId module : Elements.codeModules(model, unit)) {
      types.addAll(model.typesWithin(module));
      for (EntityId pkg : model.parts(module, Types.PACKAGE)) {
        types.addAll(model.typesWithin(pkg));
      }
    }
    return ofTypes(model, types);
  }

  /**
   * The entry points of the type a box draws: of the component anchored on it, if one is, else of
   * its own methods (#973).
   */
  public static List<EntryPoint> ofType(ArchitectureModel model, EntityId type) {
    return model
        .componentAnchoredOn(type)
        .map(component -> ofComponent(model, component))
        .orElseGet(() -> ofTypes(model, List.of(type)));
  }

  /**
   * The entry points of a component: of every method of its main type and its other member types
   * (#973).
   */
  public static List<EntryPoint> ofComponent(ArchitectureModel model, EntityId component) {
    java.util.SortedSet<EntityId> types = new java.util.TreeSet<>(ArchitectureModel.BY_ID);
    for (String predicate : List.of(Types.ANCHORED_ON, Types.HAS_MEMBER)) {
      model.relations(component, predicate).forEach(f -> types.add(Knowledge.object(f)));
    }
    return ofTypes(model, types);
  }

  private static List<EntryPoint> ofTypes(
      ArchitectureModel model, java.util.Collection<EntityId> types) {
    List<EntryPoint> result = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (EntityId type : types) {
      for (EntityId method : model.parts(type, Types.METHOD)) {
        for (EntryPoint entry : of(model, method)) {
          if (seen.add(entry.fact().id())) {
            result.add(entry);
          }
        }
      }
    }
    return result;
  }
}
