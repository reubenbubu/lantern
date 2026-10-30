package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.ResolvePrompt.Answer;
import io.lantern.link.ResolvePrompt.Candidate;
import io.lantern.link.ResolvePrompt.Question;
import io.lantern.llm.Capability;
import io.lantern.llm.Conversation;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmConversation;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import io.lantern.llm.ToolBox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Resolve role of the design for links static matching leaves open (ADR 0045): for each {@code
 * unresolved-link} with candidates, it shows the model the call or send, the facts it comes from
 * and the candidate units with their facts, and asks it to pick one candidate or answer unknown. A
 * pick becomes a {@code CALLS_HTTP} or {@code SENDS_MESSAGES_TO} fact of origin {@code llm}, at a
 * lower confidence than a static link, with the model's rationale; an answer of unknown leaves the
 * link open. Answers are cached by the hash of the prompt, so a run over unchanged facts makes no
 * call.
 *
 * <p>It never fails the run. An answer that is rejected twice (a target that is not a candidate, a
 * reference not in the prompt, no citation of the candidate it picks), and a call refused by a
 * budget or the model's capabilities or failing in the provider, log a warning and cache nothing,
 * so a later run asks again; the link keeps the last pick stored for it if that unit is still a
 * candidate, else stays open. Calls run in parallel on virtual threads, bounded by the client's
 * limits.
 *
 * <p>When the model can use tools, it may look further than the prompt with {@link GraphTools}: the
 * facts about an entity, a search of the facts, and the lines of a file at the snapshot's commit;
 * an answer may cite the facts they showed (ADR 0081). Each question is one {@link Conversation},
 * logged with its turns and tool calls.
 *
 * <p>It runs as the last step of the linker, after {@link CrossRepoLinks}, so its facts are
 * recorded for the whole snapshot like the other steps' (ADR 0039).
 */
public final class LinkResolver implements LinkStep {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "link-resolver";

  /** The most tokens an answer may take. */
  static final int MAX_OUTPUT_TOKENS = 600;

  /** The most facts shown about one candidate, besides its unit's. */
  static final int MAX_CANDIDATE_FACTS = 10;

  /** The confidence of a link the model picks, times that of the call, by the model's own. */
  static final Map<String, Double> CONFIDENCE = Map.of("high", 0.5, "medium", 0.35, "low", 0.2);

  private static final Logger LOG = LoggerFactory.getLogger(LinkResolver.class);

  /** A fact ID, which a cached answer cites a fact a tool showed by. */
  private static final java.util.regex.Pattern FACT_ID =
      java.util.regex.Pattern.compile("[0-9a-f]{64}");

  private static final Origin ORIGIN = new Origin(Origin.Kind.LLM, NAME, ResolvePrompt.VERSION);

  /** At most this many characters of a rejected answer are logged. */
  private static final int LOGGED_ANSWER = 1_000;

  /**
   * What a run of the resolver did.
   *
   * @param facts the links it records, one per link the model picked a candidate for
   * @param asked how many links the model was asked about in this run
   * @param reused how many answers came from the cache
   * @param unknown how many links stay open because the model answered unknown
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

  private final LlmClient client;
  private final ResolutionStore store;
  private final GraphTools.RepoFiles files;
  private final AtomicInteger asked = new AtomicInteger();
  private final AtomicInteger reused = new AtomicInteger();

  /**
   * @param client the LLM, with the budgets the run's calls count against
   * @param store where answers are cached
   */
  public LinkResolver(LlmClient client, ResolutionStore store) {
    this(client, store, GraphTools.RepoFiles.NONE);
  }

  /**
   * @param client the LLM, with the budgets the run's calls count against
   * @param store where answers are cached
   * @param files the files of the snapshot's repositories, which the tools read when the model can
   *     use tools; {@link GraphTools.RepoFiles#NONE} offers no file reads
   */
  public LinkResolver(LlmClient client, ResolutionStore store, GraphTools.RepoFiles files) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
    this.files = Objects.requireNonNull(files, "files");
  }

  @Override
  public Origin origin() {
    return ORIGIN;
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

  /**
   * Resolves the links of a snapshot that static matching left open.
   *
   * @param facts the facts that hold in the snapshot, the linker's included
   * @param snapshot the snapshot number the facts hold from
   */
  public Result resolve(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    LinkIndex index = new LinkIndex(facts);
    HttpLinker http = new HttpLinker(index, new FoundLinks());
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(f -> byId.putIfAbsent(f.id(), f));
    List<Question> questions = new ArrayList<>();
    for (Fact unresolved : index.of(CrossRepoLinks.UNRESOLVED_LINK)) {
      String kind = LinkIndex.text(unresolved, "kind");
      if (LinkIndex.texts(unresolved, "candidates").isEmpty()
          || !("http".equals(kind) || "message".equals(kind))) {
        // Nothing to pick from: the link stays open, for the questions queue.
        continue;
      }
      questions.add(question(unresolved, index, http, byId));
    }
    ModelConfig model = client.model(Role.RESOLVE);
    // Tools only for a model that can use them; the graph is prepared once, if anything is asked.
    GraphTools.Graph graph =
        model.supports(Capability.TOOL_USE) && !questions.isEmpty()
            ? new GraphTools.Graph(facts, files)
            : null;
    String system = ResolvePrompt.system(model.supports(Capability.TOOL_USE));
    List<Outcome> outcomes = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (Question question : questions) {
        futures.add(executor.submit(() -> answer(question, model, graph, system, byId)));
      }
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while resolving links", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
    List<Fact> derived = new ArrayList<>();
    int asked = 0;
    int reused = 0;
    int unknown = 0;
    List<String> rejected = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (int i = 0; i < questions.size(); i++) {
      Question question = questions.get(i);
      Outcome outcome = outcomes.get(i);
      String link = linkOf(question.unresolved());
      switch (outcome.kind()) {
        case SKIPPED, REJECTED -> {
          if (outcome.kind() == Kind.SKIPPED) {
            skipped.add(link);
          } else {
            asked++;
            rejected.add(link);
          }
          // Keep what the model picked for this link before, if it is still a candidate, so a
          // failed or refused call does not drop a link that held.
          Resolution earlier = store.latest(link).orElse(null);
          if (earlier != null
              && !earlier.unknown()
              && question.candidate(earlier.target().toString()) != null) {
            derived.add(fact(question, earlier, index, byId, snapshot, system));
          }
        }
        case ANSWERED -> {
          if (outcome.cached()) {
            reused++;
          } else {
            asked++;
          }
          Resolution resolution = outcome.resolution();
          if (resolution.unknown() || question.candidate(resolution.target().toString()) == null) {
            unknown++;
          } else {
            derived.add(fact(question, resolution, index, byId, snapshot, system));
          }
        }
      }
    }
    this.asked.addAndGet(asked);
    this.reused.addAndGet(reused);
    return new Result(derived, asked, reused, unknown, rejected, skipped);
  }

  // ---------------------------------------------------------------------------------------------
  // The question

  private static Question question(
      Fact unresolved, LinkIndex index, HttpLinker http, Map<String, Fact> byId) {
    String view = LinkIndex.text(unresolved, "profile");
    List<Fact> about = new ArrayList<>();
    unitFacts(index, unresolved.subject(), view, about);
    for (String id : LinkIndex.texts(unresolved, "facts")) {
      Fact fact = byId.get(id);
      if (fact != null && !about.contains(fact)) {
        about.add(fact);
      }
    }
    boolean isHttp = "http".equals(LinkIndex.text(unresolved, "kind"));
    String call = unresolved.object().value();
    String url = urlOf(call);
    String httpMethod = url.equals(call) ? null : call.substring(0, call.indexOf(' '));
    String path = LinkText.parse(url).path();
    Fact send =
        about.stream()
            .filter(f -> f.predicate().equals(MessagingExtractor.SEND))
            .findFirst()
            .orElse(null);
    Map<EntityId, List<Fact>> candidates = new LinkedHashMap<>();
    for (String text : LinkIndex.texts(unresolved, "candidates")) {
      EntityId id = EntityId.parse(text);
      List<Fact> shown = new ArrayList<>();
      unitFacts(index, id, view, shown);
      Unit unit = index.units.get(id);
      List<Fact> code =
          unit == null
              ? List.of()
              : isHttp
                  ? http.endpoints(unit, httpMethod, path, view)
                  : receiving(index, unit, send, unresolved.object().value(), view);
      code.stream().limit(MAX_CANDIDATE_FACTS).forEach(shown::add);
      candidates.put(id, shown);
    }
    return ResolvePrompt.question(unresolved, about, candidates);
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
    String broker = send == null ? null : LinkIndex.text(send, "broker");
    List<Fact> listeners = new ArrayList<>();
    for (Fact listener : index.of(MessagingExtractor.LISTENER)) {
      if (index.unitsOf(listener.subject()).contains(unit)
          && index.active(listener.subject(), view)
          && (broker == null || broker.equals(LinkIndex.text(listener, "broker")))) {
        listeners.add(listener);
      }
    }
    List<Fact> bindings = new ArrayList<>();
    List<Fact> bound = new ArrayList<>();
    for (Fact binding : index.of(MessagingExtractor.BINDING)) {
      if (!destination.equals(LinkIndex.text(binding, "exchange"))) {
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
  // Asking

  private enum Kind {
    ANSWERED,
    REJECTED,
    SKIPPED
  }

  private record Outcome(Kind kind, Resolution resolution, boolean cached) {}

  private Outcome answer(
      Question question,
      ModelConfig model,
      GraphTools.Graph graph,
      String system,
      Map<String, Fact> byId) {
    String key = key(question, ResolvePrompt.VERSION, model.provider(), model.model(), system);
    Resolution cached = store.find(key).orElse(null);
    // An answer that rests on a fact a tool showed holds only while that fact does; else ask again.
    if (cached != null
        && cached.cited().stream()
            .allMatch(ref -> !FACT_ID.matcher(ref).matches() || byId.containsKey(ref))) {
      return new Outcome(Kind.ANSWERED, cached, true);
    }
    Fact unresolved = question.unresolved();
    String purpose =
        "resolve "
            + LinkIndex.text(unresolved, "kind")
            + " link "
            + unresolved.object().value().replace('\n', ' ')
            + " of "
            + unresolved.subject()
            + (LinkIndex.text(unresolved, "profile") == null
                ? ""
                : " under " + LinkIndex.text(unresolved, "profile"));
    GraphTools tools = graph == null ? null : graph.tools();
    Conversation conversation =
        client.converse(
            new LlmRequest(
                Role.RESOLVE,
                purpose,
                system,
                List.of(LlmMessage.user(question.user())),
                MAX_OUTPUT_TOKENS,
                false),
            tools == null ? ToolBox.NONE : tools);
    try {
      return converse(question, conversation, purpose, key, tools, model);
    } finally {
      // Whatever ended the conversation unchecked, such as a failing store, it is still logged.
      conversation.end(LlmConversation.Ending.FAILED, "Ended without a checked answer");
    }
  }

  private Outcome converse(
      Question question,
      Conversation conversation,
      String purpose,
      String key,
      GraphTools tools,
      ModelConfig model) {
    Fact unresolved = question.unresolved();
    try {
      LlmResponse first = conversation.answer();
      Answer answer;
      try {
        answer = ResolvePrompt.check(first, question, shown(tools));
      } catch (ResolvePrompt.Rejected rejected) {
        logRejected(purpose, rejected, first);
        LlmResponse second =
            conversation.reply(
                ResolvePrompt.RETRY.formatted(rejected.getMessage()), purpose + " (retry)");
        try {
          answer = ResolvePrompt.check(second, question, shown(tools));
        } catch (ResolvePrompt.Rejected again) {
          logRejected(purpose + " (retry)", again, second);
          conversation.end(LlmConversation.Ending.REJECTED, again.getMessage());
          return new Outcome(Kind.REJECTED, null, false);
        }
      }
      conversation.end(LlmConversation.Ending.ANSWERED);
      // A fact a tool showed is kept by its ID, which stands for it whatever its reference was.
      Map<String, Fact> found = shown(tools);
      List<String> cited =
          answer.cited().stream()
              .map(ref -> found.containsKey(ref) ? found.get(ref).id() : ref)
              .toList();
      Resolution resolution =
          new Resolution(
              key,
              linkOf(unresolved),
              answer.target() == null ? null : answer.target().unit(),
              answer.confidence(),
              cited,
              answer.rationale(),
              ResolvePrompt.VERSION,
              model.provider(),
              model.model());
      store.save(resolution);
      return new Outcome(Kind.ANSWERED, resolution, false);
    } catch (LlmException e) {
      LOG.warn("Left the link open, could not {}: {}", purpose, e.getMessage());
      return new Outcome(Kind.SKIPPED, null, false);
    }
  }

  private static Map<String, Fact> shown(GraphTools tools) {
    return tools == null ? Map.of() : tools.shown();
  }

  private static void logRejected(
      String purpose, ResolvePrompt.Rejected reason, LlmResponse answer) {
    String text = answer.text();
    if (text.length() > LOGGED_ANSWER) {
      text = text.substring(0, LOGGED_ANSWER) + "... (" + text.length() + " characters)";
    }
    LOG.warn(
        "Rejected the answer to {}: {} The answer: {}",
        purpose,
        reason.getMessage(),
        text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n"));
  }

  /** The cache key: the SHA-256 of the prompt version, provider, model and prompt. */
  static String key(Question question, ModelConfig model) {
    return key(
        question,
        ResolvePrompt.VERSION,
        model.provider(),
        model.model(),
        ResolvePrompt.system(model.supports(Capability.TOOL_USE)));
  }

  /** The key the prompt would have had for the answer's prompt version and model. */
  private static String key(Question question, Resolution resolution, String system) {
    return key(
        question, resolution.promptVersion(), resolution.provider(), resolution.model(), system);
  }

  private static String key(
      Question question, String version, String provider, String model, String system) {
    String text =
        "prompt "
            + version
            + "\nprovider "
            + provider
            + "\nmodel "
            + model
            + "\nsystem\n"
            + system
            + "\nuser\n"
            + question.user();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The link

  private static String linkOf(Fact unresolved) {
    String link = LinkIndex.text(unresolved, "link");
    return link == null ? unresolved.id() : link;
  }

  /**
   * The link the model picked: from the calling or sending unit to the candidate, with the call's
   * code, the candidate's endpoint or listener when the answer cites exactly one, the protocol, the
   * profiles of the link left open, the facts of both and the model's rationale.
   */
  private static Fact fact(
      Question question,
      Resolution resolution,
      LinkIndex index,
      Map<String, Fact> byId,
      long snapshot,
      String system) {
    Fact unresolved = question.unresolved();
    boolean isHttp = "http".equals(LinkIndex.text(unresolved, "kind"));
    String predicate = isHttp ? CrossRepoLinks.CALLS_HTTP : CrossRepoLinks.SENDS_MESSAGES_TO;
    Candidate candidate = question.candidate(resolution.target().toString());
    List<Fact> cited = new ArrayList<>();
    // The references stand for this prompt's facts only when the answer was given for it; a fact
    // a tool showed is cited by its ID, and counts while it holds.
    List<String> refs =
        resolution.key().equals(key(question, resolution, system)) ? resolution.cited() : List.of();
    for (String ref : refs) {
      Fact fact = question.facts().containsKey(ref) ? question.facts().get(ref) : byId.get(ref);
      if (fact != null && !cited.contains(fact)) {
        cited.add(fact);
      }
    }
    Fact code = null;
    String codePredicate = isHttp ? SpringExtractor.ENDPOINT : MessagingExtractor.LISTENER;
    List<Fact> ends =
        candidate.factRefs().stream()
            .map(question.facts()::get)
            .filter(f -> cited.contains(f) && f.predicate().equals(codePredicate))
            .toList();
    if (ends.size() == 1) {
      code = ends.getFirst();
    }
    Fact origin = null;
    for (String id : LinkIndex.texts(unresolved, "facts")) {
      Fact fact = byId.get(id);
      if (fact != null
          && (fact.predicate().equals(MessagingExtractor.SEND)
              || fact.predicate().equals(HttpClientExtractor.HTTP_CALL))) {
        origin = fact;
        break;
      }
    }

    ObjectNode attributes = FactJson.newObject();
    String source = LinkIndex.text(unresolved, "source");
    if (source != null) {
      attributes.put("source", source);
    }
    if (code != null) {
      attributes.put("target", code.subject().toString());
    }
    if (isHttp) {
      String client = origin == null ? null : LinkIndex.text(origin, "client");
      if (client != null) {
        attributes.put("client", client);
      }
      String httpMethod = origin == null ? null : LinkIndex.text(origin, "httpMethod");
      if (httpMethod != null) {
        attributes.put("httpMethod", httpMethod);
      }
      String path = origin == null ? null : LinkIndex.text(origin, "path");
      if (path != null) {
        attributes.put("path", path);
      }
      String protocol =
          "https".equals(LinkText.parse(urlOf(unresolved.object().value())).scheme())
              ? "HTTPS"
              : "HTTP";
      if (code != null) {
        attributes.put("endpoint", code.object().value());
        String controller = LinkIndex.text(code, "controller");
        if (controller != null
            && index.restControllers.contains(new EntityId(BuiltInTypes.TYPE, controller))) {
          protocol = "JSON/" + protocol;
        }
      }
      attributes.put("protocol", protocol);
      attributes.put("matchedBy", "llm");
    } else {
      String broker = origin == null ? null : LinkIndex.text(origin, "broker");
      attributes.put("protocol", protocol(broker));
    }
    String profile = LinkIndex.text(unresolved, "profile");
    if (profile != null) {
      attributes.put("profile", profile);
    }
    JsonNode except = unresolved.attributes().get("exceptProfiles");
    if (except != null && except.isArray()) {
      attributes.set("exceptProfiles", except.deepCopy());
    }
    attributes.put("resolves", linkOf(unresolved));
    attributes.put("rationale", resolution.rationale());
    attributes.put("model", resolution.provider() + "/" + resolution.model());
    attributes.put(
        "link",
        FoundLinks.linkId(
            predicate + "|resolves=" + linkOf(unresolved) + "|" + resolution.target(), null));
    Set<String> ids = new TreeSet<>();
    ids.add(unresolved.id());
    ids.addAll(LinkIndex.texts(unresolved, "facts"));
    cited.forEach(f -> ids.add(f.id()));
    ArrayNode array = attributes.putArray("facts");
    ids.forEach(array::add);
    double confidence =
        CrossRepoLinks.round(
            unresolved.confidence() * CONFIDENCE.getOrDefault(resolution.confidence(), 0.0));
    return Fact.create(
        unresolved.subject(),
        predicate,
        FactObject.entity(resolution.target()),
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

  private static String protocol(String broker) {
    if (broker == null) {
      return "AMQP";
    }
    return switch (broker) {
      case MessagingExtractor.KAFKA -> "Kafka";
      case MessagingExtractor.JMS -> "JMS";
      default -> "AMQP";
    };
  }
}
