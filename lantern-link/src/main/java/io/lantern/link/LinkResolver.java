package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.attributes.GatewayRouteAttributes;
import io.lantern.extract.attributes.HttpCallAttributes;
import io.lantern.extract.attributes.MessageBindingAttributes;
import io.lantern.extract.attributes.MessageListenerAttributes;
import io.lantern.extract.attributes.MessageSendAttributes;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.Outcome;
import io.lantern.harness.Prompts;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.StoredAnswer;
import io.lantern.harness.checks.CommonPrompt;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.ResolvePrompt.Candidate;
import io.lantern.link.ResolvePrompt.Question;
import io.lantern.link.attributes.UnresolvedLinkAttributes;
import io.lantern.llm.Capability;
import io.lantern.llm.ConversationLimits;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import io.lantern.llm.Tokens;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Resolve role (knowledge-build epic, section 11.1; ADR 0045, ADR 0105) for links static
 * matching leaves open: it picks the target of each {@code unresolved-link} with candidates among
 * them, or answers unknown. Each calling unit's open links are asked in agent conversations of at
 * most four links each, on the LLM harness (ADR 0100): the model explores with read-only tools
 * ({@code facts_about}, {@code search_facts}, {@code read_file}, {@code find_in_code}) and submits
 * its answers through {@code submit}, which checks each at once (the standard checks; a pick among
 * the item's candidates; citations on the caller's side and the candidate's). A pick becomes an
 * observation of origin {@code llm/resolve} on the link claim the static matcher would have written
 * for that candidate, at a lower confidence, with the model's reason; unknown leaves the link open.
 *
 * <p>Each link is an item: its answer is cached under its fingerprint (the role, the prompt
 * version, the model, the tools, the claims it shows and the caller's address claims for its keys),
 * and reused while every file a tool read keeps its blob and every fact it cites holds, so a run
 * over unchanged facts makes no call. Each conversation keeps the role's caps: 6 tool turns, 30,000
 * input tokens in fresh-token equivalents, 4,000 output tokens a turn.
 *
 * <p>It never fails the run. A link whose answer is rejected twice, and one whose conversation a
 * budget or the provider stops, is logged and left with the last pick stored for it if that unit is
 * still a candidate, else open. A model that cannot use tools is not asked: every link stays open.
 *
 * <p>It runs as the last step of the linker, after {@link CrossRepoLinks}, so its facts are
 * recorded for the whole snapshot like the other steps' (ADR 0039).
 */
public final class LinkResolver implements LinkRule.OverFacts {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "resolve";

  /** The most tokens a turn's answer may take, thinking aside. */
  static final int MAX_OUTPUT_TOKENS = 4_000;

  /** The most open links one conversation holds (knowledge-build epic, section 11.1). */
  static final int PACKET_SIZE = 4;

  /** The most facts shown about one candidate, besides its unit's. */
  static final int MAX_CANDIDATE_FACTS = 10;

  /** The confidence of a link the model picks, times that of the call, by the model's own. */
  static final Map<String, Double> CONFIDENCE = Map.of("high", 0.5, "medium", 0.35, "low", 0.2);

  private static final Logger LOG = LoggerFactory.getLogger(LinkResolver.class);

  /** A file range as an answer cites it. */
  private static final Pattern RANGE = Pattern.compile("^[^:\\s]+:[^:\\s]+:\\d+-\\d+$");

  /** A config key a placeholder names: {@code ${clients.tax.url}} or {@code ${TAX_URL:default}}. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:\\s]+)");

  /** The predicates of the links it picks. */
  static final Set<String> LINKS =
      Set.of(CrossRepoLinks.CALLS_HTTP, CrossRepoLinks.SENDS_MESSAGES_TO);

  private static final Origin ORIGIN = new Origin(Origin.Kind.LLM, NAME, ResolvePrompt.VERSION);

  /**
   * What a run of the resolver did.
   *
   * @param facts the links it records, one per link the model picked a candidate for
   * @param asked how many links the model was asked about in this run
   * @param reused how many answers came from the cache
   * @param unknown how many links stay open because the model answered unknown or left them out
   * @param rejected the stable IDs of the links whose answer was rejected twice
   * @param skipped the stable IDs of the links the model could not be asked about, because a budget
   *     or the model's capabilities refused the call or the provider failed
   */
  public record Result(
      List<Fact> facts,
      int asked,
      int reused,
      int unknown,
      List<String> rejected,
      List<String> skipped) {

    public Result {
      facts = List.copyOf(facts);
      rejected = List.copyOf(rejected);
      skipped = List.copyOf(skipped);
    }
  }

  /**
   * The caps of each Resolve conversation (knowledge-build epic, section 10, Agent loop): 6 tool
   * turns, 30,000 input tokens in fresh-token equivalents (each turn reads its history from the
   * prompt cache, #349) and 4,000 output tokens a turn.
   */
  static final ConversationLimits LIMITS = new ConversationLimits(6, 30_000, 4_000);

  /** The role as the harness runs it, before its tools are known (ADR 0100). */
  public static final RoleSpec ROLE =
      RoleSpec.single(
              "resolve",
              Role.RESOLVE,
              Prompts.version(
                  ResolvePrompt.VERSION,
                  "common.system.txt",
                  "resolve.task.txt",
                  "resolve.schema.json"),
              "resolve",
              RunMode.INTERACTIVE,
              300)
          .withPacketSize(PACKET_SIZE)
          .withTools(List.of(), LIMITS);

  private final Harness harness;
  private final GraphTools.RepoFiles files;
  private final AtomicInteger asked = new AtomicInteger();
  private final AtomicInteger reused = new AtomicInteger();

  /**
   * @param harness the LLM harness, with the budgets the run's calls count against and the answer
   *     cache
   */
  public LinkResolver(Harness harness) {
    this(harness, GraphTools.RepoFiles.NONE);
  }

  /**
   * @param harness the LLM harness
   * @param files the files of the snapshot's repositories, which the tools read and search; {@link
   *     GraphTools.RepoFiles#NONE} offers neither
   */
  public LinkResolver(Harness harness, GraphTools.RepoFiles files) {
    this.harness = Objects.requireNonNull(harness, "harness");
    this.files = Objects.requireNonNull(files, "files");
  }

  @Override
  public RuleInfo info() {
    return RuleInfo.of(ORIGIN);
  }

  @Override
  public Set<String> reads() {
    return Rules.union(
        Set.of(EXTRACTED),
        ValueResolution.WRITES,
        DeployableUnits.vocabularyPredicates(),
        CrossRepoLinks.vocabularyPredicates());
  }

  @Override
  public Set<String> writes() {
    return LINKS;
  }

  /** It also consults its answers and the model, so it runs every time. */
  @Override
  public boolean pure() {
    return false;
  }

  /**
   * How many links the model was asked about by every resolve of this resolver, so a run can count
   * its {@link Role#RESOLVE} calls with its summaries and wordings.
   */
  public int asked() {
    return asked.get();
  }

  /** How many answers came from the cache in every resolve of this resolver. */
  public int reused() {
    return reused.get();
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Result result = resolve(facts, snapshot);
    LOG.info(
        "Links left open: {} picked by the LLM, {} unknown, {} asked, {} cached, {} rejected,"
            + " {} skipped",
        result.facts().size(),
        result.unknown(),
        result.asked(),
        result.reused(),
        result.rejected().size(),
        result.skipped().size());
    return result.facts();
  }

  /** A pick as the link records it. */
  private record Picked(
      EntityId target, String confidence, String reason, List<Fact> cited, String model) {}

  /**
   * Resolves the links of a snapshot that static matching left open.
   *
   * @param facts the facts that hold in the snapshot, the linker's included
   * @param snapshot the snapshot number the facts hold from
   */
  public Result resolve(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    // Address resolution's answers are the matchers' (#589), not what the model looks through.
    facts = facts.stream().filter(f -> !f.origin().name().equals(AddressResolution.NAME)).toList();
    LinkIndex index = new LinkIndex(facts);
    Deployments deployments = new Deployments(index);
    Addresses addresses = new Addresses(index, deployments);
    HttpLinker http = new HttpLinker(index, new FoundLinks(), addresses);
    RouteLinker routes = new RouteLinker(index, new FoundLinks(), addresses);
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(f -> byId.putIfAbsent(f.id(), f));
    // A stored answer cites a fact by its claim ID (else its fact ID).
    Map<String, Fact> byKey = new HashMap<>(byId);
    facts.forEach(f -> byKey.putIfAbsent(ResolvePrompt.key(f), f));
    // The repositories each module's code is in, for which side a cited file range is on.
    Map<EntityId, Set<String>> moduleRepos = new HashMap<>();
    for (Fact contains : index.of(BuiltInTypes.CONTAINS)) {
      if (contains.subject().type().equals(BuiltInTypes.MODULE)) {
        moduleRepos
            .computeIfAbsent(contains.subject(), m -> new TreeSet<>())
            .add(contains.evidence().repo());
      }
    }
    List<Question> questions = new ArrayList<>();
    for (Fact unresolved : index.of(CrossRepoLinks.UNRESOLVED_LINK)) {
      String kind = unresolved(unresolved).kind();
      if (Claims.list(unresolved(unresolved).candidates()).isEmpty()
          || !("http".equals(kind) || "message".equals(kind) || "route".equals(kind))) {
        // Nothing to pick from: the link stays open, for the questions queue.
        continue;
      }
      questions.add(question(unresolved, index, moduleRepos, deployments, http, routes, byId));
    }
    if (questions.isEmpty()) {
      return new Result(List.of(), 0, 0, 0, List.of(), List.of());
    }
    ModelConfig model = harness.model(Role.RESOLVE);
    GraphTools.Graph graph = new GraphTools.Graph(facts, files);
    RoleSession session = harness.session(ROLE.withTools(graph.tools().tools(), LIMITS));
    String map = systemMap(index, deployments, facts);
    Map<String, Question> bySubject = new LinkedHashMap<>();
    Map<String, Item> items = new LinkedHashMap<>();
    for (Question question : questions) {
      Item item = item(question);
      bySubject.put(item.subject(), question);
      items.put(item.subject(), item);
    }
    session.plan(items.values(), Tokens.estimate(CommonPrompt.SYSTEM + ResolvePrompt.ROLE + map));
    Map<String, Outcome> outcomes = new LinkedHashMap<>();
    if (!model.supports(Capability.TOOL_USE)) {
      for (String subject : items.keySet()) {
        String reason = "the model cannot use tools, which Resolve answers through";
        session.notAsked(subject, reason);
        outcomes.put(subject, new Outcome(Outcome.Kind.NOT_ASKED, null, null, reason));
      }
    } else {
      outcomes.putAll(converse(session, items, bySubject, map, graph, index, byKey));
    }
    List<Fact> derived = new ArrayList<>();
    int asked = 0;
    int reused = 0;
    int unknown = 0;
    List<String> rejected = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    String modelName = model.provider() + "/" + model.model();
    for (Map.Entry<String, Item> e : items.entrySet()) {
      Question question = bySubject.get(e.getKey());
      Outcome outcome = outcomes.get(e.getKey());
      String link = e.getKey();
      Outcome.Kind kind = outcome == null ? Outcome.Kind.FAILED : outcome.kind();
      switch (kind) {
        case CACHED, ANSWERED -> {
          if (kind == Outcome.Kind.CACHED) {
            reused++;
          } else {
            asked++;
          }
          Optional<Picked> picked =
              outcome.answer() == null
                  ? Optional.empty()
                  : picked(question, outcome.answer(), byKey, modelName);
          if (picked.isEmpty()) {
            unknown++;
          } else {
            derived.add(fact(question, picked.get(), index, byId, snapshot));
          }
        }
        case OMITTED -> {
          asked++;
          unknown++;
        }
        case DROPPED, NOT_ASKED, FAILED -> {
          if (kind == Outcome.Kind.DROPPED) {
            if (outcome.repeatedDrop()) {
              reused++;
            } else {
              asked++;
            }
            rejected.add(link);
          } else {
            skipped.add(link);
            if (outcome != null && kind == Outcome.Kind.FAILED) {
              LOG.warn("Left the link {} open, could not ask about it: {}", link, outcome.reason());
            }
          }
          // Keep what the model picked for this link before, if it is still a candidate, so a
          // failed or refused call does not drop a link that held.
          session
              .latest(link)
              .filter(stored -> !stored.omitted())
              .flatMap(
                  stored ->
                      picked(
                          question,
                          stored.answer(),
                          byKey,
                          stored.provider() + "/" + stored.model()))
              .ifPresent(earlier -> derived.add(fact(question, earlier, index, byId, snapshot)));
        }
      }
    }
    this.asked.addAndGet(asked);
    this.reused.addAndGet(reused);
    return new Result(derived, asked, reused, unknown, rejected, skipped);
  }

  /**
   * Asks the items unit by unit: the cached ones together, the others in packets of at most {@link
   * #PACKET_SIZE} in the order of their fingerprints, each packet one conversation, the
   * conversations in parallel on virtual threads, bounded by the client's limits.
   */
  private Map<String, Outcome> converse(
      RoleSession session,
      Map<String, Item> items,
      Map<String, Question> bySubject,
      String map,
      GraphTools.Graph graph,
      LinkIndex index,
      Map<String, Fact> byKey) {
    Map<EntityId, List<Item>> byUnit =
        new TreeMap<>(java.util.Comparator.comparing(EntityId::toString));
    for (Item item : items.values()) {
      byUnit
          .computeIfAbsent(bySubject.get(item.subject()).caller(), u -> new ArrayList<>())
          .add(item);
    }
    List<List<Item>> packets = new ArrayList<>();
    for (List<Item> unitItems : byUnit.values()) {
      List<Item> sorted = new ArrayList<>(unitItems);
      sorted.sort(java.util.Comparator.comparing(session::sampleFingerprint));
      List<Item> hits = sorted.stream().filter(session::cached).toList();
      List<Item> misses = sorted.stream().filter(item -> !hits.contains(item)).toList();
      // Four to a conversation (or a calibration's size, ADR 0112), the cached ones too: one whose
      // cited facts no longer hold is asked.
      int size = session.spec().packetSize();
      for (List<Item> group : List.of(hits, misses)) {
        for (int i = 0; i < group.size(); i += size) {
          packets.add(group.subList(i, Math.min(group.size(), i + size)));
        }
      }
    }
    java.util.function.Function<EntityId, Set<EntityId>> unitsOf =
        entity -> {
          Set<EntityId> units = new LinkedHashSet<>();
          index.unitsOf(entity).forEach(unit -> units.add(unit.id()));
          return units;
        };
    // A cached answer holds while every fact it cites does.
    java.util.function.Predicate<StoredAnswer> holds =
        stored ->
            stored.omitted()
                || ResolveRole.storedCites(stored.answer()).stream()
                    .allMatch(cite -> RANGE.matcher(cite).matches() || byKey.containsKey(cite));
    Map<String, Outcome> outcomes = new java.util.concurrent.ConcurrentHashMap<>();
    List<ResolveRole> roles = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (List<Item> packet : packets) {
        ResolveRole role = new ResolveRole(bySubject, map, graph, unitsOf);
        roles.add(role);
        futures.add(
            executor.submit(
                () ->
                    outcomes.putAll(
                        session.converse(packet, role, role.toolBox(), role::filesRead, holds))));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while resolving links", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    } finally {
      // The injection screen reads every file a tool showed (ADR 0101).
      for (ResolveRole role : roles) {
        for (GraphTools tools : role.used()) {
          tools.textsShown().forEach(f -> harness.showed(f.repo(), f.path(), f.blob(), f.text()));
        }
      }
    }
    return outcomes;
  }

  /**
   * A stored answer's pick, if it picks a unit that is still one of the link's candidates, with the
   * facts it cites that still hold.
   */
  private static Optional<Picked> picked(
      Question question, String stored, Map<String, Fact> byKey, String model) {
    Optional<EntityId> target;
    try {
      target = ResolveRole.storedPick(stored);
    } catch (RuntimeException e) {
      // An answer stored in an older form.
      return Optional.empty();
    }
    if (target.isEmpty() || question.candidate(target.get()) == null) {
      return Optional.empty();
    }
    var node = FactJson.parse(stored);
    List<Fact> cited = new ArrayList<>();
    for (String cite : ResolveRole.storedCites(stored)) {
      Fact fact = byKey.get(cite);
      if (fact != null && !cited.contains(fact)) {
        cited.add(fact);
      }
    }
    String confidence = node.path("confidence").asString("").toLowerCase(Locale.ROOT);
    return Optional.of(
        new Picked(
            target.get(),
            CONFIDENCE.containsKey(confidence) ? confidence : "low",
            node.path("reason").asString(""),
            cited,
            model));
  }

  // ---------------------------------------------------------------------------------------------
  // The question

  /** A link left open as an item of the harness: the claims its request shows and its text. */
  private static Item item(Question question) {
    List<FingerprintInput> inputs = new ArrayList<>();
    inputs.add(FingerprintInput.of(question.unresolved()));
    question.about().forEach(fact -> inputs.add(FingerprintInput.of(fact)));
    question.address().forEach(fact -> inputs.add(FingerprintInput.of(fact)));
    for (Candidate candidate : question.candidates()) {
      candidate.facts().forEach(fact -> inputs.add(FingerprintInput.of(fact)));
    }
    return new Item(
        linkOf(question.unresolved()),
        question.caller().toString(),
        List.copyOf(new LinkedHashSet<>(inputs)),
        question.canonical());
  }

  private static Question question(
      Fact unresolved,
      LinkIndex index,
      Map<EntityId, Set<String>> moduleRepos,
      Deployments deployments,
      HttpLinker http,
      RouteLinker routes,
      Map<String, Fact> byId) {
    String view = Claims.view(unresolved);
    List<Fact> about = new ArrayList<>();
    for (String id : Claims.list(unresolved(unresolved).facts())) {
      Fact fact = byId.get(id);
      if (fact != null && !about.contains(fact)) {
        about.add(fact);
      }
    }
    Unit caller = index.units.get(unresolved.subject());
    List<Fact> address = address(index, deployments, caller, unresolved, about);
    boolean isHttp = "http".equals(unresolved(unresolved).kind());
    Fact route =
        about.stream()
            .filter(f -> f.predicate().equals(GatewayExtractor.ROUTE))
            .findFirst()
            .orElse(null);
    String call = unresolved.object().value();
    String url = urlOf(call);
    String httpMethod = url.equals(call) ? null : call.substring(0, call.indexOf(' '));
    String path = LinkText.parse(url).path();
    Fact send = about.stream().filter(LinkResolver::isSend).findFirst().orElse(null);
    Map<EntityId, ResolvePrompt.Shown> candidates = new LinkedHashMap<>();
    for (String text : Claims.list(unresolved(unresolved).candidates())) {
      EntityId id = EntityId.parse(text);
      List<Fact> shown = new ArrayList<>();
      unitFacts(index, id, view, shown);
      Unit unit = index.units.get(id);
      if (unit != null) {
        for (Deployments.Container container : deployments.running(unit)) {
          if (!shown.contains(container.fact())) {
            shown.add(container.fact());
          }
        }
      }
      List<Fact> code =
          unit == null
              ? List.of()
              : route != null
                  ? routes.reachedEndpoints(unit, route, view)
                  : isHttp
                      ? http.endpoints(unit, httpMethod, path, view)
                      : receiving(index, unit, send, unresolved.object().value(), view);
      List<Fact> reached = code.stream().limit(MAX_CANDIDATE_FACTS).toList();
      shown.addAll(reached);
      candidates.put(id, new ResolvePrompt.Shown(shown, reached, repos(moduleRepos, unit)));
    }
    return ResolvePrompt.question(
        unresolved, unresolved.subject(), repos(moduleRepos, caller), about, address, candidates);
  }

  /** The repositories a unit's code is in: its own fact's, then its modules'. */
  private static Set<String> repos(Map<EntityId, Set<String>> moduleRepos, Unit unit) {
    Set<String> repos = new TreeSet<>();
    if (unit == null) {
      return repos;
    }
    if (unit.fact() != null) {
      repos.add(unit.fact().evidence().repo());
    }
    for (EntityId module : unit.modules()) {
      repos.addAll(moduleRepos.getOrDefault(module, Set.of()));
    }
    return repos;
  }

  /**
   * The caller's facts that bear on a link's address (knowledge-build epic, section 11.1, block 4):
   * its unit fact, its application name in each view, the deployment containers that run it, and
   * the configuration values of the keys the link's expressions name, in each view.
   */
  private static List<Fact> address(
      LinkIndex index, Deployments deployments, Unit unit, Fact unresolved, List<Fact> about) {
    List<Fact> address = new ArrayList<>();
    if (unit == null) {
      return address;
    }
    if (unit.fact() != null) {
      address.add(unit.fact());
    }
    List<String> views = index.views();
    for (String view : views) {
      Fact name = index.name(unit.id(), view);
      if (name != null && !address.contains(name)) {
        address.add(name);
      }
    }
    for (Deployments.Container container : deployments.running(unit)) {
      if (!address.contains(container.fact())) {
        address.add(container.fact());
      }
    }
    for (String key : keys(unresolved, about)) {
      for (String view : views) {
        for (String candidate : List.of(key, relaxed(key))) {
          Fact value = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, candidate);
          if (value != null && !address.contains(value)) {
            address.add(value);
          }
        }
      }
    }
    return address;
  }

  /** The config keys a link's expressions name: its placeholders, and the keys nothing resolved. */
  private static Set<String> keys(Fact unresolved, List<Fact> about) {
    Set<String> keys = new TreeSet<>(Claims.unresolved(unresolved));
    List<String> texts = new ArrayList<>();
    texts.add(unresolved.object().value());
    about.forEach(fact -> texts.add(io.lantern.harness.render.FactLines.line(fact)));
    for (String text : texts) {
      Matcher placeholder = PLACEHOLDER.matcher(text);
      while (placeholder.find()) {
        keys.add(placeholder.group(1));
      }
    }
    return keys;
  }

  /** An environment variable's key as Spring Boot binds it ({@code TAX_URL} is {@code tax.url}). */
  private static String relaxed(String key) {
    return key.matches("[A-Z0-9_]+") ? key.toLowerCase(Locale.ROOT).replace('_', '.') : key;
  }

  /**
   * Block 3: each unit with its application name and the containers that run it, then the links
   * static analysis found between units, each once.
   */
  private static String systemMap(LinkIndex index, Deployments deployments, List<Fact> facts) {
    List<String> units = new ArrayList<>();
    List<EntityId> ids = new ArrayList<>(index.units.keySet());
    ids.sort(java.util.Comparator.comparing(EntityId::toString));
    for (EntityId id : ids) {
      Unit unit = index.units.get(id);
      StringBuilder line = new StringBuilder(id.toString());
      Fact name = index.name(id, null);
      if (name != null) {
        line.append(", application name ").append(name.object().value());
      }
      Set<String> containers = new TreeSet<>();
      for (Deployments.Container container : deployments.running(unit)) {
        containers.add(container.name() + " (" + container.label() + ")");
      }
      if (!containers.isEmpty()) {
        line.append(", runs in ").append(String.join(", ", containers));
      }
      units.add(line.toString());
    }
    Set<String> links = new TreeSet<>();
    for (Fact fact : facts) {
      if (LINKS.contains(fact.predicate())
          && fact.origin().kind() != Origin.Kind.LLM
          && fact.object().kind() == FactObject.Kind.ENTITY) {
        String protocol = Claims.json(fact).path("protocol").asString(fact.predicate());
        links.add(fact.subject() + " -> " + fact.object().value() + " [" + protocol + "]");
      }
    }
    return ResolvePrompt.systemMap(units, List.copyOf(links));
  }

  /**
   * Whether a fact an open link cites is its send: a {@code message-send}, or the {@code SENDS_TO}
   * relation of value resolution the messaging matcher cites instead (#597).
   */
  static boolean isSend(Fact f) {
    return f.predicate().equals(MessagingExtractor.SEND)
        || (f.predicate().equals(MessagingExtractor.SENDS_TO)
            && f.origin().name().equals(ValueResolution.NAME));
  }

  /** The broker of a send ({@link #isSend}), or null. */
  static String brokerOf(Fact send) {
    if (send == null) {
      return null;
    }
    if (send.predicate().equals(MessagingExtractor.SENDS_TO)) {
      return Claims.of(send, io.lantern.extract.attributes.SendsToAttributes.class).broker();
    }
    MessageSendAttributes sent = Claims.ofOrNull(send, MessageSendAttributes.class);
    return sent == null ? null : sent.broker();
  }

  /** A unit's own fact and its name in the view. */
  private static void unitFacts(LinkIndex index, EntityId id, String view, List<Fact> out) {
    Unit unit = index.units.get(id);
    if (unit != null && unit.fact() != null) {
      out.add(unit.fact());
    }
    Fact name = index.name(id, view);
    if (name != null && !out.contains(name)) {
      out.add(name);
    }
  }

  /**
   * How a candidate unit receives a send: the bindings of the exchange sent to that route to a
   * queue the unit listens on, then those listeners; else every listener of the unit on the same
   * broker.
   */
  private static List<Fact> receiving(
      LinkIndex index, Unit unit, Fact send, String destination, String view) {
    String broker = brokerOf(send);
    List<Fact> listeners = new ArrayList<>();
    for (Fact listener : index.of(MessagingExtractor.LISTENER)) {
      if (index.unitsOf(listener.subject()).contains(unit)
          && index.active(listener.subject(), view)
          && (broker == null
              || broker.equals(Claims.of(listener, MessageListenerAttributes.class).broker()))) {
        listeners.add(listener);
      }
    }
    List<Fact> bindings = new ArrayList<>();
    List<Fact> bound = new ArrayList<>();
    for (Fact binding : index.of(MessagingExtractor.BINDING)) {
      if (!destination.equals(Claims.of(binding, MessageBindingAttributes.class).exchange())) {
        continue;
      }
      for (Fact listener : listeners) {
        if (listener.object().value().equals(binding.object().value())) {
          if (!bindings.contains(binding)) {
            bindings.add(binding);
          }
          if (!bound.contains(listener)) {
            bound.add(listener);
          }
        }
      }
    }
    if (bindings.isEmpty()) {
      return listeners;
    }
    List<Fact> result = new ArrayList<>(bindings);
    result.addAll(bound);
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // The link

  /**
   * The stable ID of a link left open: its claim ID, which stays the same across snapshots and
   * commits (ADR 0095, amendment for #576); its fact ID before it is stored.
   */
  private static String linkOf(Fact unresolved) {
    return unresolved.claimId() != null ? unresolved.claimId() : unresolved.id();
  }

  /**
   * The link the model picked: from the calling or sending unit to the candidate, with the call's
   * code, the candidate's endpoint or listener when the answer cites exactly one, the protocol, the
   * view of the link left open, the facts of both and the model's reason. It is an observation of
   * the link claim static matching would have written for that candidate (knowledge-build epic,
   * section 11.1).
   */
  private static Fact fact(
      Question question, Picked picked, LinkIndex index, Map<String, Fact> byId, long snapshot) {
    Fact unresolved = question.unresolved();
    boolean isRoute = "route".equals(unresolved(unresolved).kind());
    boolean isHttp = isRoute || "http".equals(unresolved(unresolved).kind());
    String predicate = isHttp ? CrossRepoLinks.CALLS_HTTP : CrossRepoLinks.SENDS_MESSAGES_TO;
    Candidate candidate = question.candidate(picked.target());
    List<Fact> cited = picked.cited();
    Fact code = null;
    String codePredicate = isHttp ? SpringExtractor.ENDPOINT : MessagingExtractor.LISTENER;
    List<Fact> ends =
        candidate.facts().stream()
            .filter(f -> cited.contains(f) && f.predicate().equals(codePredicate))
            .toList();
    if (ends.size() == 1) {
      code = ends.getFirst();
    } else {
      // Citing none, or several, the pick reaches the one the linker ranks first, so the link is
      // the claim a static match to it would be (ADR 0105).
      code =
          candidate.reached().stream()
              .filter(f -> f.predicate().equals(codePredicate))
              .findFirst()
              .orElse(null);
    }
    Fact origin = null;
    for (String id : Claims.list(unresolved(unresolved).facts())) {
      Fact fact = byId.get(id);
      if (fact != null
          && (isSend(fact)
              || fact.predicate().equals(HttpClientExtractor.HTTP_CALL)
              || fact.predicate().equals(GatewayExtractor.ROUTE))) {
        origin = fact;
        break;
      }
    }

    ObjectNode attributes = FactJson.newObject();
    String source = Claims.source(unresolved);
    if (source != null) {
      attributes.put("source", source);
    }
    if (code != null) {
      attributes.put("target", code.subject().toString());
    }
    if (isRoute) {
      // A route of a gateway, described as the routes static matching links (ADR 0077).
      if (origin != null) {
        attributes.put("route", origin.object().value());
        GatewayRouteAttributes route = Claims.ofOrNull(origin, GatewayRouteAttributes.class);
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lists.put("paths", route == null ? List.of() : Claims.list(route.paths()));
        lists.put("methods", route == null ? List.of() : Claims.list(route.methods()));
        lists.forEach(
            (key, values) -> {
              if (!values.isEmpty()) {
                ArrayNode array = attributes.putArray(key);
                values.forEach(array::add);
              }
            });
      }
      GatewayRouteAttributes routed = Claims.ofOrNull(origin, GatewayRouteAttributes.class);
      String uri =
          origin == null ? unresolved.object().value() : routed == null ? null : routed.uri();
      if (uri != null) {
        attributes.put("url", uri);
      }
      String scheme =
          uri == null ? null : LinkText.parse(uri.replaceFirst("^lb:(//)?", "http://")).scheme();
      String protocol =
          "ws".equals(scheme) || "wss".equals(scheme)
              ? "WebSocket"
              : "https".equals(scheme) ? "HTTPS" : "HTTP";
      if (code != null) {
        attributes.put("endpoint", code.object().value());
        String controller = Endpoints.of(code).controller();
        if (!protocol.equals("WebSocket")
            && controller != null
            && index.restControllers.contains(new EntityId(BuiltInTypes.TYPE, controller))) {
          protocol = "JSON/" + protocol;
        }
      }
      attributes.put("protocol", protocol);
      attributes.put("matchedBy", "llm");
    } else if (isHttp) {
      HttpCallAttributes call = Claims.ofOrNull(origin, HttpCallAttributes.class);
      String client = call == null ? null : call.client();
      if (client != null) {
        attributes.put("client", client);
      }
      String httpMethod = call == null ? null : call.httpMethod();
      if (httpMethod != null) {
        attributes.put("httpMethod", httpMethod);
      }
      String path = call == null ? null : call.path();
      if (path != null) {
        attributes.put("path", path);
      }
      String protocol =
          "https".equals(LinkText.parse(urlOf(unresolved.object().value())).scheme())
              ? "HTTPS"
              : "HTTP";
      if (code != null) {
        attributes.put("endpoint", code.object().value());
        String controller = Endpoints.of(code).controller();
        if (controller != null
            && index.restControllers.contains(new EntityId(BuiltInTypes.TYPE, controller))) {
          protocol = "JSON/" + protocol;
        }
      }
      attributes.put("protocol", protocol);
      attributes.put("matchedBy", "llm");
    } else {
      // The send's destination, routing key and, when the answer cites one binding, its key and
      // queue: the key attributes static matching writes, so a pick is the same claim (ADR 0105).
      String broker = brokerOf(origin);
      attributes.put("protocol", protocol(broker));
      String kind = null;
      String routingKey = null;
      if (origin != null && origin.predicate().equals(MessagingExtractor.SENDS_TO)) {
        var sent = Claims.of(origin, io.lantern.extract.attributes.SendsToAttributes.class);
        kind = sent.destinationKind();
        routingKey = sent.routingKey();
      } else if (origin != null) {
        MessageSendAttributes sent = Claims.ofOrNull(origin, MessageSendAttributes.class);
        kind = sent == null ? null : sent.destinationKind();
        routingKey = sent == null ? null : sent.routingKey();
      }
      EntityId destination = MessageLinker.destination(broker, kind, unresolved.object().value());
      if (destination != null) {
        attributes.put("destination", destination.toString());
      }
      if (routingKey != null) {
        attributes.put("routingKey", routingKey);
      }
      // The binding that routes to the listener reached: the one cited, else the first.
      Fact listener = code;
      List<Fact> bindings =
          candidate.reached().stream()
              .filter(
                  f ->
                      f.predicate().equals(MessagingExtractor.BINDING)
                          && (listener == null
                              || f.object().value().equals(listener.object().value())))
              .toList();
      List<Fact> citedBindings = bindings.stream().filter(cited::contains).toList();
      Fact binding =
          citedBindings.size() == 1
              ? citedBindings.getFirst()
              : bindings.isEmpty() ? null : bindings.getFirst();
      if (binding != null) {
        EntityId queue = MessageLinker.destination(broker, "queue", binding.object().value());
        if (queue != null) {
          attributes.put("queue", queue.toString());
        }
        String bindingKey = Claims.of(binding, MessageBindingAttributes.class).routingKey();
        if (bindingKey != null) {
          attributes.put("bindingKey", bindingKey);
        }
      }
    }
    String view = Claims.view(unresolved);
    if (view != null) {
      attributes.put("view", view);
    }
    io.lantern.core.vocabulary.AttributeValues.put(
        attributes, "absentIn", unresolved(unresolved).absentIn());
    attributes.put("resolves", linkOf(unresolved));
    attributes.put("reason", picked.reason());
    attributes.put("model", picked.model());
    Set<String> ids = new TreeSet<>();
    ids.add(unresolved.id());
    ids.addAll(Claims.list(unresolved(unresolved).facts()));
    cited.forEach(f -> ids.add(f.id()));
    ArrayNode array = attributes.putArray("facts");
    ids.forEach(array::add);
    double confidence =
        CrossRepoLinks.round(
            unresolved.confidence() * CONFIDENCE.getOrDefault(picked.confidence(), 0.0));
    return Fact.create(
        unresolved.subject(),
        predicate,
        FactObject.entity(picked.target()),
        attributes,
        ORIGIN,
        unresolved.evidence(),
        confidence,
        Validity.from(snapshot));
  }

  /** The URL of a call as an {@code unresolved-link} writes it, without its HTTP method. */
  private static String urlOf(String call) {
    int space = call.indexOf(' ');
    return space > 0 && call.substring(0, space).matches("[A-Z]+")
        ? call.substring(space + 1)
        : call;
  }

  static String protocol(String broker) {
    if (broker == null) {
      return "AMQP";
    }
    return switch (broker) {
      case MessagingExtractor.KAFKA -> "Kafka";
      case MessagingExtractor.JMS -> "JMS";
      default -> "AMQP";
    };
  }

  /** An {@code unresolved-link} fact's attributes. */
  private static UnresolvedLinkAttributes unresolved(Fact unresolved) {
    return Claims.of(unresolved, UnresolvedLinkAttributes.class);
  }
}
