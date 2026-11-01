package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The components of each deployable unit, stored as part of the architecture model (knowledge-build
 * epic, section 9; #523): one per entry point (a controller, listener, scheduled job, gateway
 * filter, STOMP handler or WebSocket registration) or aspect that advises one, holding the beans
 * only it reaches; a bean several entry points reach, or none when it reaches outside the unit, as
 * a component of its own; and the routes a gateway's config defines, one per module.
 *
 * <p>A component is {@code component:<unit>/<anchor>}, keyed on the class (or module) it is built
 * around, so a new endpoint does not rename it (#615). It is {@code PART_OF} its unit, with its
 * {@code role} and {@code technology}, {@code ANCHORED_ON} its anchor, and {@code HAS_MEMBER} (a
 * kind of {@code CONTAINS}) each member class. The {@code PART_OF} fact's lineage is the facts that
 * give the anchor its role.
 */
public final class Components implements LinkRule.OverFacts {

  public static final String NAME = "components";

  /** Raised whenever the components it gives for the same facts change. */
  public static final String VERSION = "2";

  public static final String PART_OF = "PART_OF";
  public static final String ANCHORED_ON = "ANCHORED_ON";

  /**
   * A component's member class: a kind of {@code CONTAINS}, a predicate of its own so the rule does
   * not write what the extractors write and the linker's rules read.
   */
  public static final String HAS_MEMBER = "HAS_MEMBER";

  /** The roles of a component. */
  public static final String ENTRY_POINT = "entry-point";

  public static final String ASPECT_ROLE = "aspect";
  public static final String SHARED = "shared";
  public static final String CONFIG_ROUTES_ROLE = "config-routes";

  /** The technology of the component of the routes a gateway's config defines. */
  public static final String CONFIG_ROUTES = "Gateway routes";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final String METHOD = "method";
  private static final String STEREOTYPE = "stereotype";
  private static final String MAIN_CLASS = "main-class";
  private static final String ENDPOINT = "endpoint";
  private static final String MESSAGE_LISTENER = "message-listener";
  private static final String SCHEDULED = "scheduled";
  private static final String BACKGROUND_JOB = "background-job";
  private static final String GATEWAY_ROUTE = "gateway-route";
  private static final String WEBSOCKET_ENDPOINT = "websocket-endpoint";
  private static final String STOMP_MAPPING = "stomp-mapping";
  private static final String GATEWAY_FILTER = "gateway-filter";
  private static final String HTTP_CLIENT = "http-client";
  private static final String HTTP_CALL = "http-call";
  private static final String MESSAGE_SEND = "message-send";
  private static final String REPOSITORY_FOR = "REPOSITORY_FOR";
  private static final String GENERATED = "generated";
  private static final String READS_TABLE = "READS_TABLE";
  private static final String WRITES_TABLE = "WRITES_TABLE";
  private static final String CONFIGURATION_PROPERTIES = "configuration-properties";
  private static final String INJECTS = "INJECTS";
  private static final String ASPECT = "aspect";
  private static final String ADVISES = "ADVISES";
  private static final String BUNDLES = "BUNDLES";
  private static final String DEPLOYABLE_UNIT = "deployable-unit";
  private static final Set<String> BEAN_STEREOTYPES =
      Set.of("controller", "service", "repository", "component");

  /** Every fact: the extracted ones and those the link rules gave, as the diagrams read them. */
  private static final Set<String> READS = Set.of(EXTRACTED, LINKED);

  @Override
  public RuleInfo info() {
    return new RuleInfo(NAME, VERSION, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    return READS;
  }

  @Override
  public Set<String> writes() {
    return Set.of(PART_OF, ANCHORED_ON, HAS_MEMBER);
  }

  /** The vocabulary types of the components' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/components-types.json");
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    Validity validity = Validity.from(snapshot);
    List<Fact> derived = new ArrayList<>();
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    knowledge.withPredicate(DEPLOYABLE_UNIT).forEach(f -> units.add(f.subject()));
    for (EntityId unit : units) {
      for (Found component : new Finder(knowledge, unit).components()) {
        Evidence evidence = component.basis().getFirst().evidence();
        EntityId id = id(unit, component.anchor());
        ObjectNode part = FactJson.newObject();
        part.put("role", component.role());
        if (component.technology() != null) {
          part.put("technology", component.technology());
        }
        ArrayNode cites = part.putArray("facts");
        component.basis().forEach(f -> cites.add(f.id()));
        derived.add(fact(id, PART_OF, unit, part, evidence, validity));
        derived.add(
            fact(id, ANCHORED_ON, component.anchor(), FactJson.newObject(), evidence, validity));
        for (EntityId member : component.members()) {
          derived.add(fact(id, HAS_MEMBER, member, FactJson.newObject(), evidence, validity));
        }
      }
    }
    return derived;
  }

  /** The component of a unit built around an anchor. */
  public static EntityId id(EntityId unit, EntityId anchor) {
    return new EntityId(BuiltInTypes.COMPONENT, unit + "/" + anchor);
  }

  private static Fact fact(
      EntityId subject,
      String predicate,
      EntityId object,
      ObjectNode attributes,
      Evidence evidence,
      Validity validity) {
    return Fact.create(
        subject, predicate, FactObject.entity(object), attributes, ORIGIN, evidence, 1.0, validity);
  }

  /**
   * A component found: its anchor, its role, its technology (null for none), its members (the
   * anchor among them) and the facts that give the anchor its role, the first giving its evidence.
   */
  record Found(
      EntityId anchor,
      String role,
      String technology,
      SortedSet<EntityId> members,
      List<Fact> basis) {}

  /** Finds the components of one unit, as the component diagrams found them before #523. */
  private static final class Finder {

    private final Knowledge knowledge;
    private final EntityId unit;

    Finder(Knowledge knowledge, EntityId unit) {
      this.knowledge = knowledge;
      this.unit = unit;
    }

    /** The unit's components: config routes, then entry points and aspects, then shared beans. */
    List<Found> components() {
      SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
      for (EntityId module : codeModules(knowledge, unit)) {
        code.addAll(knowledge.typesWithin(module));
        for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
          code.addAll(knowledge.typesWithin(pkg));
        }
      }
      SortedSet<EntityId> entries = new TreeSet<>(Knowledge.BY_ID);
      SortedSet<EntityId> beans = new TreeSet<>(Knowledge.BY_ID);
      for (EntityId type : code) {
        if (knowledge.attribute(type, MAIN_CLASS).isPresent()) {
          continue;
        }
        if (!entryKinds(type).isEmpty()) {
          entries.add(type);
        } else if (isBean(type)) {
          beans.add(type);
        }
      }
      // An aspect that advises an entry point of the unit is a component of its own, built around
      // the beans only it uses: it is where a check such as authorization is decided. One declared
      // outside the unit's code (a shared library the linker did not match to the unit) counts
      // when it selects by annotation, which the unit's code must then use.
      Map<EntityId, Fact> aspects = new TreeMap<>(Knowledge.BY_ID);
      for (Fact fact : knowledge.withPredicate(ADVISES)) {
        EntityId aspect = fact.subject();
        if (!aspects.containsKey(aspect)
            && entries.contains(ownerType(knowledge, Knowledge.object(fact)))
            && (code.contains(aspect) || selectsByAnnotation(aspect))) {
          aspects.put(aspect, fact);
        }
      }
      beans.removeAll(aspects.keySet());
      entries.addAll(aspects.keySet());
      Map<EntityId, Set<EntityId>> uses = typeGraph(code);
      Map<EntityId, SortedSet<EntityId>> reachedBy = new TreeMap<>(Knowledge.BY_ID);
      for (EntityId entry : entries) {
        Set<EntityId> seen = new HashSet<>(Set.of(entry));
        Deque<EntityId> todo = new ArrayDeque<>(List.of(entry));
        while (!todo.isEmpty()) {
          for (EntityId next : uses.getOrDefault(todo.removeFirst(), Set.of())) {
            if (!entries.contains(next) && seen.add(next)) {
              todo.add(next);
              if (beans.contains(next)) {
                reachedBy.computeIfAbsent(next, b -> new TreeSet<>(Knowledge.BY_ID)).add(entry);
              }
            }
          }
        }
      }
      // Which component each bean an entry point reaches belongs to (#271, review of 1D): the
      // entry point, when it alone reaches it; else the bean shared by several entry points that
      // every path to it goes through (its nearest such dominator), as a shared class holds the
      // classes only it reaches; else the bean itself, a shared component of its own.
      Map<EntityId, EntityId> owner = owners(entries, beans, uses, reachedBy);
      List<Found> components = new ArrayList<>();
      // The routes a gateway's config defines, one component per module that defines any.
      for (EntityId module : codeModules(knowledge, unit)) {
        List<Fact> routes =
            knowledge.attributes(module).stream()
                .filter(f -> f.predicate().equals(GATEWAY_ROUTE))
                .toList();
        if (!routes.isEmpty()) {
          components.add(
              new Found(module, CONFIG_ROUTES_ROLE, CONFIG_ROUTES, only(module), routes));
        }
      }
      for (EntityId entry : entries) {
        SortedSet<EntityId> types = new TreeSet<>(Knowledge.BY_ID);
        types.add(entry);
        owner.forEach(
            (bean, of) -> {
              if (of.equals(entry)) {
                types.add(bean);
              }
            });
        if (aspects.containsKey(entry)) {
          components.add(
              new Found(
                  entry, ASPECT_ROLE, "Spring AOP aspect", types, List.of(aspects.get(entry))));
        } else {
          Kinds kinds = entryKinds(entry);
          components.add(
              new Found(
                  entry, ENTRY_POINT, String.join(" · ", kinds.names()), types, kinds.basis()));
        }
      }
      // Code with only data access that no entry point reaches is not drawn (#271), unless no
      // drawn component would then reach the unit's databases: the framework may call it (a
      // UserDetailsService, a data seeder), or the calls to it were not seen.
      boolean dataAccessShown = false;
      for (Found found : components) {
        dataAccessShown |= found.members().stream().anyMatch(this::accessesData);
      }
      List<EntityId> unreachedData = new ArrayList<>();
      for (EntityId bean : beans) {
        SortedSet<EntityId> from = reachedBy.get(bean);
        if (from == null && !reachesOutside(bean) && accessesData(bean)) {
          unreachedData.add(bean);
          continue;
        }
        boolean own = from == null ? reachesOutside(bean) : bean.equals(owner.get(bean));
        if (own) {
          SortedSet<EntityId> members = only(bean);
          owner.forEach(
              (member, of) -> {
                if (of.equals(bean)) {
                  members.add(member);
                }
              });
          components.add(new Found(bean, SHARED, beanKind(bean), members, beanBasis(bean)));
          dataAccessShown |= members.stream().anyMatch(this::accessesData);
        }
      }
      if (!dataAccessShown) {
        for (EntityId bean : unreachedData) {
          components.add(new Found(bean, SHARED, beanKind(bean), only(bean), beanBasis(bean)));
        }
      }
      return components;
    }

    /**
     * The component each bean the entry points reach belongs to: its entry point, or the anchor of
     * the shared component it sits behind, or itself. Dominators are taken in the graph of the code
     * the entry points reach, with a root before every entry point and no path through another
     * entry point (Cooper, Harvey and Kennedy's iterative algorithm).
     */
    private static Map<EntityId, EntityId> owners(
        Set<EntityId> entries,
        Set<EntityId> beans,
        Map<EntityId, Set<EntityId>> uses,
        Map<EntityId, SortedSet<EntityId>> reachedBy) {
      EntityId root = new EntityId("root", "root");
      // Reverse postorder from the root, in ID order so the result does not depend on the input's.
      List<EntityId> post = new ArrayList<>();
      Set<EntityId> visited = new HashSet<>(Set.of(root));
      Map<EntityId, List<EntityId>> successors = new HashMap<>();
      SortedSet<EntityId> starts = new TreeSet<>(Knowledge.BY_ID);
      starts.addAll(entries);
      successors.put(root, new ArrayList<>(starts));
      Deque<EntityId> stack = new ArrayDeque<>(List.of(root));
      Deque<java.util.Iterator<EntityId>> its = new ArrayDeque<>();
      its.push(successors.get(root).iterator());
      while (!stack.isEmpty()) {
        java.util.Iterator<EntityId> it = its.peek();
        if (it.hasNext()) {
          EntityId next = it.next();
          if (visited.add(next)) {
            SortedSet<EntityId> out = new TreeSet<>(Knowledge.BY_ID);
            for (EntityId used : uses.getOrDefault(next, Set.of())) {
              if (!entries.contains(used)) {
                out.add(used);
              }
            }
            successors.put(next, new ArrayList<>(out));
            stack.push(next);
            its.push(successors.get(next).iterator());
          }
        } else {
          post.add(stack.pop());
          its.pop();
        }
      }
      Map<EntityId, Integer> index = new HashMap<>();
      for (int i = 0; i < post.size(); i++) {
        index.put(post.get(i), i);
      }
      Map<EntityId, List<EntityId>> predecessors = new HashMap<>();
      successors.forEach(
          (from, tos) -> {
            for (EntityId to : tos) {
              predecessors.computeIfAbsent(to, t -> new ArrayList<>()).add(from);
            }
          });
      Map<EntityId, EntityId> idom = new HashMap<>();
      idom.put(root, root);
      boolean changed = true;
      while (changed) {
        changed = false;
        for (int i = post.size() - 1; i >= 0; i--) {
          EntityId node = post.get(i);
          if (node.equals(root)) {
            continue;
          }
          EntityId dominator = null;
          for (EntityId pred : predecessors.getOrDefault(node, List.of())) {
            if (idom.containsKey(pred)) {
              dominator = dominator == null ? pred : meet(pred, dominator, idom, index);
            }
          }
          if (dominator != null && !dominator.equals(idom.get(node))) {
            idom.put(node, dominator);
            changed = true;
          }
        }
      }
      Map<EntityId, EntityId> owner = new TreeMap<>(Knowledge.BY_ID);
      for (EntityId bean : reachedBy.keySet()) {
        owner.put(bean, ownerOf(bean, entries, beans, reachedBy, idom, root));
      }
      return owner;
    }

    private static EntityId meet(
        EntityId a, EntityId b, Map<EntityId, EntityId> idom, Map<EntityId, Integer> index) {
      while (!a.equals(b)) {
        while (index.get(a) < index.get(b)) {
          a = idom.get(a);
        }
        while (index.get(b) < index.get(a)) {
          b = idom.get(b);
        }
      }
      return a;
    }

    /**
     * A reached bean's component: up its dominators, the first entry point, or the first bean
     * shared by several entry points; the bean itself when it is shared and nothing above it is.
     */
    private static EntityId ownerOf(
        EntityId bean,
        Set<EntityId> entries,
        Set<EntityId> beans,
        Map<EntityId, SortedSet<EntityId>> reachedBy,
        Map<EntityId, EntityId> idom,
        EntityId root) {
      EntityId at = idom.get(bean);
      while (at != null && !at.equals(root)) {
        if (entries.contains(at)) {
          return at;
        }
        if (beans.contains(at) && reachedBy.getOrDefault(at, new TreeSet<>()).size() > 1) {
          return ownerOf(at, entries, beans, reachedBy, idom, root);
        }
        at = idom.get(at);
      }
      return reachedBy.get(bean).size() == 1 ? reachedBy.get(bean).first() : bean;
    }

    /** Whether an aspect's pointcuts select methods by an annotation. */
    private boolean selectsByAnnotation(EntityId aspect) {
      return knowledge.attributes(aspect).stream()
          .anyMatch(f -> f.predicate().equals(ASPECT) && !strings(f, "annotations").isEmpty());
    }

    /** The roles that make a type an entry point, by name, with the facts that state them. */
    record Kinds(List<String> names, List<Fact> basis) {
      boolean isEmpty() {
        return names.isEmpty();
      }
    }

    /**
     * The roles that make a type an entry point: web controller, listener, scheduled job, gateway
     * filter, the routes of a gateway built in its code, a STOMP handler, or the registration of a
     * WebSocket endpoint.
     */
    private Kinds entryKinds(EntityId type) {
      Set<String> kinds = new LinkedHashSet<>();
      List<Fact> basis = new ArrayList<>();
      for (Fact f : knowledge.attributes(type)) {
        if (f.predicate().equals(GATEWAY_FILTER)) {
          kinds.add(
              switch (f.object().value().toString()) {
                case "global" -> "Global gateway filter";
                case "factory" -> "Gateway filter factory";
                case "web" -> "Web filter";
                default -> "Gateway filter";
              });
          basis.add(f);
        }
      }
      for (Fact f : knowledge.attributes(type)) {
        if (f.predicate().equals(STEREOTYPE) && f.object().value().equals("controller")) {
          kinds.add(
              attribute(f, "rest", "false").equals("true")
                  ? "Spring MVC REST controller"
                  : "Spring MVC controller");
          basis.add(f);
        }
      }
      for (EntityId method : knowledge.parts(type, METHOD)) {
        for (Fact fact : knowledge.attributes(method)) {
          boolean counts = true;
          switch (fact.predicate()) {
            case ENDPOINT -> {
              if (kinds.isEmpty()) {
                kinds.add("HTTP endpoints");
              }
            }
            case MESSAGE_LISTENER ->
                kinds.add(brokerName(attribute(fact, "broker", "message")) + " listener");
            case SCHEDULED, BACKGROUND_JOB -> kinds.add(jobKind(fact));
            case GATEWAY_ROUTE -> kinds.add("Gateway routes");
            case STOMP_MAPPING -> kinds.add("STOMP message handler");
            case WEBSOCKET_ENDPOINT ->
                kinds.add(
                    "stomp".equals(attribute(fact, "protocol", ""))
                        ? "STOMP WebSocket endpoint"
                        : "WebSocket endpoint");
            default -> counts = false;
          }
          if (counts) {
            basis.add(fact);
          }
        }
      }
      return new Kinds(List.copyOf(kinds), basis);
    }

    private boolean isBean(EntityId type) {
      return !beanBasis(type).isEmpty() && !isLeftOut(type);
    }

    /**
     * Whether a bean is left out of components, as the quality bar leaves out mappers and
     * configuration (#271): a mapper MapStruct generates, or a class bound to config by
     * {@code @ConfigurationProperties}.
     */
    private boolean isLeftOut(EntityId type) {
      for (Fact fact : knowledge.attributes(type)) {
        switch (fact.predicate()) {
          case GENERATED -> {
            if (fact.object().value().toString().startsWith("org.mapstruct.")) {
              return true;
            }
          }
          case CONFIGURATION_PROPERTIES -> {
            return true;
          }
          default -> {}
        }
      }
      return false;
    }

    /** The facts that make a type a bean: a bean stereotype, an HTTP client, a repository. */
    private List<Fact> beanBasis(EntityId type) {
      List<Fact> basis = new ArrayList<>();
      for (Fact fact : knowledge.attributes(type)) {
        if ((fact.predicate().equals(STEREOTYPE)
                && BEAN_STEREOTYPES.contains(fact.object().value()))
            || fact.predicate().equals(HTTP_CLIENT)) {
          basis.add(fact);
        }
      }
      basis.addAll(knowledge.relations(type, REPOSITORY_FOR));
      return basis;
    }

    /** What kind of bean a component built around one bean is. */
    private String beanKind(EntityId type) {
      Optional<Fact> client =
          knowledge.attributes(type).stream()
              .filter(f -> f.predicate().equals(HTTP_CLIENT))
              .findFirst();
      if (client.isPresent()) {
        String kind = client.get().object().value().toString();
        return kind.equals("feign") ? "Feign client" : "HTTP client";
      }
      for (Fact fact : knowledge.relations(type, REPOSITORY_FOR)) {
        String base = attribute(fact, "base", "");
        return base.contains(".jpa.") ? "Spring Data JPA repository" : "Spring Data repository";
      }
      return knowledge
          .attribute(type, STEREOTYPE)
          .map(
              s ->
                  switch (s) {
                    case "service" -> "Spring service";
                    case "repository" -> "Spring repository";
                    default -> "Spring component";
                  })
          .orElse(null);
    }

    /**
     * Whether a type's code reaches another container: an HTTP client, HTTP calls or sends. Data
     * access does not count: a repository no entry point reaches is not drawn (#271), and one an
     * entry point reaches shows its data access on that entry point's component.
     */
    private boolean reachesOutside(EntityId type) {
      if (knowledge.attribute(type, HTTP_CLIENT).isPresent()) {
        return true;
      }
      for (EntityId member : withMethods(type)) {
        for (Fact fact : knowledge.about(member)) {
          switch (fact.predicate()) {
            case HTTP_CALL, MESSAGE_SEND -> {
              return true;
            }
            default -> {}
          }
        }
      }
      return false;
    }

    /** The parameter types a constructor's ID names, such as {@code <init>(a.B,c.D)}; none else. */
    private static List<EntityId> constructorParameters(EntityId method) {
      String key = method.key();
      int open = key.indexOf("#<init>(");
      if (open < 0 || !key.endsWith(")")) {
        return List.of();
      }
      String params = key.substring(open + "#<init>(".length(), key.length() - 1);
      List<EntityId> types = new ArrayList<>();
      int depth = 0;
      int start = 0;
      for (int i = 0; i <= params.length(); i++) {
        char c = i < params.length() ? params.charAt(i) : ',';
        if (c == '<') {
          depth++;
        } else if (c == '>') {
          depth--;
        } else if (c == ',' && depth == 0) {
          String param = params.substring(start, i).strip();
          int generic = param.indexOf('<');
          param = (generic < 0 ? param : param.substring(0, generic)).replace("...", "");
          if (!param.isEmpty() && !param.endsWith("]")) {
            types.add(new EntityId(BuiltInTypes.TYPE, param));
          }
          start = i + 1;
        }
      }
      return types;
    }

    /** Whether a type is a repository or its code reads or writes tables. */
    private boolean accessesData(EntityId type) {
      if (!knowledge.relations(type, REPOSITORY_FOR).isEmpty()) {
        return true;
      }
      for (EntityId member : withMethods(type)) {
        if (!knowledge.relations(member, READS_TABLE).isEmpty()
            || !knowledge.relations(member, WRITES_TABLE).isEmpty()) {
          return true;
        }
      }
      return false;
    }

    private List<EntityId> withMethods(EntityId type) {
      List<EntityId> members = new ArrayList<>(List.of(type));
      members.addAll(knowledge.parts(type, METHOD));
      return members;
    }

    /**
     * Which types of the unit's code each uses: the types whose methods it calls and the clients
     * injected into it, and for an interface the types of the code implementing it.
     */
    private Map<EntityId, Set<EntityId>> typeGraph(Set<EntityId> code) {
      Map<EntityId, Set<EntityId>> uses = new TreeMap<>(Knowledge.BY_ID);
      for (EntityId type : code) {
        Set<EntityId> targets = new TreeSet<>(Knowledge.BY_ID);
        for (EntityId member : withMethods(type)) {
          for (Fact fact : knowledge.relations(member, BuiltInTypes.CALLS)) {
            targets.add(ownerType(knowledge, Knowledge.object(fact)));
          }
        }
        knowledge.relations(type, INJECTS).forEach(f -> targets.add(Knowledge.object(f)));
        // What its constructors take: Spring injects them, and a constructor's ID names its
        // parameter types even when its calls could not be resolved without a build (#271).
        for (EntityId method : knowledge.parts(type, METHOD)) {
          targets.addAll(constructorParameters(method));
        }
        targets.remove(type);
        targets.retainAll(code);
        uses.computeIfAbsent(type, t -> new TreeSet<>(Knowledge.BY_ID)).addAll(targets);
        for (Fact fact : knowledge.relations(type, BuiltInTypes.IMPLEMENTS)) {
          EntityId contract = Knowledge.object(fact);
          if (code.contains(contract)) {
            uses.computeIfAbsent(contract, c -> new TreeSet<>(Knowledge.BY_ID)).add(type);
          }
        }
      }
      return uses;
    }
  }

  /** The modules whose code runs in the unit: its own and those of the libraries it bundles. */
  public static SortedSet<EntityId> codeModules(Knowledge knowledge, EntityId unit) {
    SortedSet<EntityId> modules = knowledge.parts(unit, BuiltInTypes.MODULE);
    for (Fact fact : knowledge.relations(unit, BUNDLES)) {
      modules.addAll(knowledge.parts(Knowledge.object(fact), BuiltInTypes.MODULE));
    }
    return modules;
  }

  /** The type a method or field belongs to; any other entity itself. */
  public static EntityId ownerType(Knowledge knowledge, EntityId entity) {
    if (entity.type().equals(METHOD) || entity.type().equals("field")) {
      SortedSet<EntityId> owners = knowledge.wholes(entity, BuiltInTypes.TYPE);
      if (!owners.isEmpty()) {
        return owners.first();
      }
      int hash = entity.key().indexOf('#');
      return hash > 0 ? new EntityId(BuiltInTypes.TYPE, entity.key().substring(0, hash)) : entity;
    }
    return entity;
  }

  /** What kind of scheduled job a fact describes. */
  public static String jobKind(Fact fact) {
    return switch (attribute(fact, "framework", "")) {
      case "jobrunr" -> "JobRunr job";
      case "quartz" -> "Quartz job";
      default -> "Scheduled job";
    };
  }

  private static String brokerName(String broker) {
    return switch (broker) {
      case "kafka" -> "Kafka";
      case "jms" -> "JMS";
      case "rabbitmq" -> "RabbitMQ";
      default -> broker;
    };
  }

  private static String attribute(Fact fact, String name, String otherwise) {
    JsonNode value = fact.attributes().get(name);
    return value == null || value.isNull() ? otherwise : value.asString();
  }

  private static List<String> strings(Fact fact, String name) {
    JsonNode value = fact.attributes().get(name);
    List<String> result = new ArrayList<>();
    if (value != null && value.isArray()) {
      value.forEach(v -> result.add(v.asString()));
    }
    return result;
  }

  private static SortedSet<EntityId> only(EntityId entity) {
    SortedSet<EntityId> set = new TreeSet<>(Knowledge.BY_ID);
    set.add(entity);
    return set;
  }
}
