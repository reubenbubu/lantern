package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.InvestigatePrompt.Cite;
import io.lantern.link.InvestigatePrompt.Kind;
import io.lantern.link.InvestigatePrompt.Proposal;
import io.lantern.link.InvestigatePrompt.Read;
import io.lantern.link.InvestigatePrompt.Subject;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * The Investigate role of the design (ADR 0082): the LLM finds what static analysis misses. For
 * each deployable unit, and once for the system as a whole, it shows the model the facts about it
 * and lets it read the repositories' files and query the graph with {@link GraphTools}; the model
 * proposes risks and connections, each citing file:line ranges. {@link ProposalCheck} verifies
 * every citation mechanically; a proposal that fails, or names a unit or element the facts do not
 * hold, is sent back once with the reason and dropped if it fails again. A connection the facts
 * already link is dropped: static extraction wins. What passes becomes a fact of origin {@code
 * llm}, its first citation as evidence and its citations in its attributes, at a confidence below a
 * static fact's: an {@value #RISK} of the first unit it concerns, or an {@value #CONNECTION} from
 * its unit to what it leads to.
 *
 * <p>Verified proposals are cached by the hash of the prompt (version, provider, model, prompt),
 * and reused while every citation still holds. It never fails the run: a conversation refused by a
 * budget or the model's capabilities, or failing in the provider, logs a warning and caches
 * nothing. It runs only for a model that can use tools and when files can be read. Conversations
 * run in parallel on virtual threads, bounded by the client's limits.
 *
 * <p>It runs as the last step of the linker, after {@link LinkResolver}, so it sees every link and
 * its facts are recorded for the whole snapshot like the other steps' (ADR 0039).
 */
public final class Investigator implements LinkStep {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "investigator";

  /** The source of the vocabulary types its facts use. */
  public static final String VOCABULARY_SOURCE = "investigator";

  /** Attribute: a risk the investigator found, its title as the value. */
  public static final String RISK = "investigated-risk";

  /** Relationship: a connection the investigator found, from a unit to what it leads to. */
  public static final String CONNECTION = "INVESTIGATED_CONNECTION";

  /** The scope of the investigation of the system as a whole. */
  public static final String SYSTEM = "system";

  /** The most tokens an answer may take. */
  static final int MAX_OUTPUT_TOKENS = 4_000;

  /** The confidence of a verified proposal, by the model's own: below any static fact's. */
  static final Map<String, Double> CONFIDENCE = Map.of("high", 0.6, "medium", 0.45, "low", 0.3);

  private static final String RESOURCE = "/vocabulary/investigator-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.LLM, NAME, InvestigatePrompt.VERSION);

  private static final Logger LOG = LoggerFactory.getLogger(Investigator.class);

  /** At most this many characters of a rejected answer are logged. */
  private static final int LOGGED_ANSWER = 1_000;

  /** The links between units and what they use, which a connection must not repeat. */
  private static final Set<String> LINKS =
      Set.of(
          CrossRepoLinks.CALLS_HTTP,
          CrossRepoLinks.SENDS_MAIL,
          CrossRepoLinks.PUBLISHES,
          CrossRepoLinks.CONSUMES,
          CrossRepoLinks.SENDS_MESSAGES_TO,
          CrossRepoLinks.USES_DATABASE,
          CrossRepoLinks.ACCESSES_DATA_OF,
          CrossRepoLinks.USES_BROKER,
          CrossRepoLinks.USES_INFRASTRUCTURE);

  /** The code facts a unit's prompt shows, besides its own and its links. */
  private static final Set<String> UNIT_CODE =
      Set.of(
          "endpoint",
          "http-call",
          "http-client",
          "message-send",
          "message-listener",
          "gateway-route",
          "gateway-filter",
          "scheduled",
          "background-job",
          "aspect",
          "main-class");

  /** What the system's prompt shows besides the links. */
  private static final Set<String> SYSTEM_FACTS =
      Set.of(
          CrossRepoLinks.UNRESOLVED_LINK,
          CrossRepoLinks.UNROUTED_ENDPOINT,
          DeployableUnits.VERSION_SKEW,
          DeployableUnits.APPLICATION_NAME,
          DeployableUnits.TECHNOLOGY,
          DeployableUnits.BUNDLES,
          CrossRepoLinks.ROUTES_TO);

  /**
   * What a run of the investigator did.
   *
   * @param facts the facts of the proposals it accepted
   * @param asked how many prompts the model was asked about in this run
   * @param reused how many came from the cache
   * @param rejected how many proposals failed the checks twice
   * @param dropped how many verified proposals repeated what the facts state, or another proposal
   * @param skipped the scopes the model could not be asked about, because a budget or the model's
   *     capabilities refused the call or the provider failed
   */
  public record Result(
      List<Fact> facts, int asked, int reused, int rejected, int dropped, List<String> skipped) {

    public Result {
      facts = List.copyOf(facts);
      skipped = List.copyOf(skipped);
    }
  }

  private final LlmClient client;
  private final InvestigationStore store;
  private final GraphTools.RepoFiles files;
  private final Map<String, String> commits;
  private final AtomicInteger asked = new AtomicInteger();
  private final AtomicInteger reused = new AtomicInteger();

  /**
   * @param client the LLM, with the budgets the run's calls count against
   * @param store where verified proposals are cached
   * @param files the files of the snapshot's repositories at its commits, which the tools read and
   *     the citations are checked against; {@link GraphTools.RepoFiles#NONE} turns it off
   * @param commits the snapshot's commit of each repository, which the facts' evidence records
   */
  public Investigator(
      LlmClient client,
      InvestigationStore store,
      GraphTools.RepoFiles files,
      Map<String, String> commits) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
    this.files = Objects.requireNonNull(files, "files");
    this.commits = Map.copyOf(commits);
  }

  @Override
  public Origin origin() {
    return ORIGIN;
  }

  /** The vocabulary types this step's facts use besides the built-in and the linker's ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = Investigator.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /** How many prompts the model was asked about by every run of this investigator. */
  public int asked() {
    return asked.get();
  }

  /** How many investigations came from the cache in every run of this investigator. */
  public int reused() {
    return reused.get();
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Result result = investigate(facts, snapshot);
    LOG.info(
        "Investigate: {} proposals accepted, {} asked, {} cached, {} rejected, {} dropped as"
            + " repeated, {} skipped",
        result.facts().size(),
        result.asked(),
        result.reused(),
        result.rejected(),
        result.dropped(),
        result.skipped().size());
    return result.facts();
  }

  /**
   * Investigates each deployable unit of a snapshot and the system as a whole.
   *
   * @param facts the facts that hold in the snapshot, the linker's included
   * @param snapshot the snapshot number the facts hold from
   */
  public Result investigate(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    ModelConfig model = client.model(Role.INVESTIGATE);
    LinkIndex index = new LinkIndex(facts);
    if (index.units.isEmpty() || files == GraphTools.RepoFiles.NONE || commits.isEmpty()) {
      return new Result(List.of(), 0, 0, 0, 0, List.of());
    }
    if (!model.supports(Capability.TOOL_USE)) {
      LOG.info(
          "Investigate skipped: its model {}/{} cannot use tools, which it needs to read the code",
          model.provider(),
          model.model());
      return new Result(List.of(), 0, 0, 0, 0, List.of());
    }
    Set<String> repos = new TreeSet<>(commits.keySet());
    List<String> unitLines = new ArrayList<>();
    for (LinkIndex.Unit unit : index.units.values()) {
      Fact name = index.name(unit.id(), null);
      String repo = unit.fact() == null ? null : unit.fact().evidence().repo();
      unitLines.add(
          unit.id()
              + (name == null ? "" : ", named " + name.object().value())
              + (repo == null ? "" : ", in the repository " + repo));
    }
    List<Subject> subjects = new ArrayList<>();
    for (LinkIndex.Unit unit : index.units.values()) {
      subjects.add(
          InvestigatePrompt.unit(unit.id(), unitLines, repos, unitFacts(unit, facts, index)));
    }
    subjects.add(InvestigatePrompt.system(unitLines, repos, systemFacts(facts, index)));

    Known known = new Known(facts, index);
    ProposalCheck check = new ProposalCheck(files, repos);
    GraphTools.Graph graph = new GraphTools.Graph(facts, files);
    String system = InvestigatePrompt.SYSTEM;
    List<Outcome> outcomes = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (Subject subject : subjects) {
        futures.add(executor.submit(() -> answer(subject, model, graph, system, check, known)));
      }
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while investigating", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }

    Map<String, Fact> derived = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    int askedNow = 0;
    int reusedNow = 0;
    int rejected = 0;
    int dropped = 0;
    List<String> skipped = new ArrayList<>();
    for (int i = 0; i < subjects.size(); i++) {
      Subject subject = subjects.get(i);
      Outcome outcome = outcomes.get(i);
      if (outcome.skipped()) {
        skipped.add(subject.scope());
        continue;
      }
      if (outcome.cached()) {
        reusedNow++;
      } else {
        askedNow++;
      }
      rejected += outcome.rejected();
      for (Proposal proposal : outcome.proposals()) {
        if (proposal.kind() == Kind.CONNECTION && known.linked(proposal.from(), proposal.to())) {
          LOG.info(
              "Dropped the {} proposed for {}: the facts already link them",
              proposal.label(),
              subject.scope());
          dropped++;
          continue;
        }
        if (!seen.add(identity(proposal))) {
          dropped++;
          continue;
        }
        Fact fact = fact(proposal, subject, check, outcome.model(), snapshot);
        derived.putIfAbsent(fact.id(), fact);
      }
    }
    this.asked.addAndGet(askedNow);
    this.reused.addAndGet(reusedNow);
    return new Result(
        List.copyOf(derived.values()), askedNow, reusedNow, rejected, dropped, skipped);
  }

  /** What makes two proposals the same: a risk's title, a connection's ends and interaction. */
  private static String identity(Proposal proposal) {
    return proposal.kind() == Kind.RISK
        ? "risk|" + proposal.title().toLowerCase(Locale.ROOT)
        : "connection|" + proposal.from() + "|" + proposal.to() + "|" + proposal.interaction();
  }

  // ---------------------------------------------------------------------------------------------
  // What the prompts show

  /**
   * A unit's facts: its own, the links from and to it, the open links and unrouted endpoints of it,
   * and the facts of its code that show how it is reached and what it reaches.
   */
  private static List<Fact> unitFacts(LinkIndex.Unit unit, List<Fact> facts, LinkIndex index) {
    List<Fact> shown = new ArrayList<>();
    String id = unit.id().toString();
    for (Fact fact : facts) {
      boolean about =
          fact.subject().equals(unit.id())
              || (fact.object().kind() == FactObject.Kind.ENTITY
                  && fact.object().value().equals(id));
      if (!about
          && UNIT_CODE.contains(fact.predicate())
          && index.unitsOf(fact.subject()).contains(unit)) {
        about = true;
      }
      if (about && !fact.predicate().equals(RISK) && !fact.predicate().equals(CONNECTION)) {
        shown.add(fact);
      }
    }
    return shown;
  }

  /** The system's facts: the links, open links, unrouted endpoints, versions and units. */
  private static List<Fact> systemFacts(List<Fact> facts, LinkIndex index) {
    List<Fact> shown = new ArrayList<>();
    for (Fact fact : facts) {
      if (LINKS.contains(fact.predicate())
          || SYSTEM_FACTS.contains(fact.predicate())
          || (fact.predicate().equals(DeployableUnits.DEPLOYABLE_UNIT)
              && index.units.containsKey(fact.subject()))) {
        shown.add(fact);
      }
    }
    return shown;
  }

  /** The units and elements the facts hold, and what links them. */
  private static final class Known {

    private final Set<EntityId> units;
    private final Set<String> entities = new HashSet<>();
    private final Set<String> links = new HashSet<>();

    Known(List<Fact> facts, LinkIndex index) {
      this.units = index.units.keySet();
      for (Fact fact : facts) {
        entities.add(fact.subject().toString());
        if (fact.object().kind() == FactObject.Kind.ENTITY) {
          entities.add(fact.object().value());
          if (LINKS.contains(fact.predicate())) {
            links.add(fact.subject() + "|" + fact.object().value());
            links.add(fact.object().value() + "|" + fact.subject());
          }
        }
      }
    }

    boolean linked(EntityId from, EntityId to) {
      return links.contains(from + "|" + to);
    }

    /** Why a proposal names what the facts do not hold, or null. */
    String problem(Proposal proposal) {
      for (EntityId unit : proposal.units()) {
        if (!units.contains(unit)) {
          return unit + " is not a deployable unit; the units are listed in the prompt.";
        }
      }
      if (proposal.kind() == Kind.CONNECTION) {
        EntityId to = proposal.to();
        if (to.equals(proposal.from())) {
          return "it connects a unit to itself.";
        }
        if (!entities.contains(to.toString())
            && !InvestigatePrompt.NEW_ELEMENTS.contains(to.type())) {
          return to
              + " is not an element the facts hold; a new one is external-system:<name> or"
              + " database:<name>.";
        }
      }
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Asking

  /**
   * What became of one prompt.
   *
   * @param proposals the proposals that passed the checks
   * @param model the provider and model that made them
   * @param cached whether they came from the cache
   * @param skipped whether the model could not be asked
   * @param rejected how many proposals failed the checks twice
   */
  private record Outcome(
      List<Proposal> proposals, String model, boolean cached, boolean skipped, int rejected) {

    static Outcome skippedOne() {
      return new Outcome(List.of(), null, false, true, 0);
    }
  }

  private Outcome answer(
      Subject subject,
      ModelConfig model,
      GraphTools.Graph graph,
      String system,
      ProposalCheck check,
      Known known) {
    String key = key(subject, model, system);
    Investigation cached = store.find(key).orElse(null);
    if (cached != null) {
      List<Proposal> proposals = new ArrayList<>();
      for (JsonNode node : FactJson.parse(cached.proposals())) {
        proposals.add(Proposal.fromJson(node));
      }
      // Reused only while every citation still holds; a file can change while the facts do not.
      if (proposals.stream().allMatch(p -> problem(p, check, known) == null)) {
        return new Outcome(proposals, cached.provider() + "/" + cached.model(), true, false, 0);
      }
    }
    String purpose =
        subject.unit() == null ? "investigate the system" : "investigate " + subject.unit();
    Conversation conversation =
        client.converse(
            new LlmRequest(
                Role.INVESTIGATE,
                purpose,
                system,
                List.of(LlmMessage.user(subject.user())),
                MAX_OUTPUT_TOKENS,
                false),
            graph.investigatorTools());
    try {
      return converse(subject, conversation, purpose, key, model, check, known);
    } finally {
      conversation.end(LlmConversation.Ending.FAILED, "Ended without a checked answer");
    }
  }

  private Outcome converse(
      Subject subject,
      Conversation conversation,
      String purpose,
      String key,
      ModelConfig model,
      ProposalCheck check,
      Known known) {
    try {
      LlmResponse first = conversation.answer();
      List<Read> reads;
      boolean replied = false;
      try {
        reads = InvestigatePrompt.read(first, subject);
      } catch (InvestigatePrompt.Rejected rejected) {
        logRejected(purpose, rejected.getMessage(), first);
        LlmResponse second =
            conversation.reply(
                InvestigatePrompt.ANSWER_RETRY.formatted(rejected.getMessage()),
                purpose + " (retry)");
        replied = true;
        try {
          reads = InvestigatePrompt.read(second, subject);
        } catch (InvestigatePrompt.Rejected again) {
          logRejected(purpose + " (retry)", again.getMessage(), second);
          conversation.end(LlmConversation.Ending.REJECTED, again.getMessage());
          return new Outcome(List.of(), null, false, false, 0);
        }
      }
      List<Proposal> accepted = new ArrayList<>();
      List<String> problems = new ArrayList<>();
      sort(reads, check, known, accepted, problems);
      int rejectedCount = 0;
      if (!problems.isEmpty()) {
        if (replied) {
          rejectedCount = problems.size();
          problems.forEach(p -> LOG.warn("Rejected a proposal of {}: {}", purpose, p));
        } else {
          LlmResponse second =
              conversation.reply(
                  InvestigatePrompt.RETRY.formatted(String.join("\n", problems)),
                  purpose + " (retry)");
          List<String> again = new ArrayList<>();
          int before = accepted.size();
          try {
            sort(InvestigatePrompt.read(second, subject), check, known, accepted, again);
          } catch (InvestigatePrompt.Rejected wrong) {
            logRejected(purpose + " (retry)", wrong.getMessage(), second);
            again.addAll(problems);
          }
          // Those not corrected stay rejected, whether answered again or withdrawn.
          rejectedCount = Math.max(again.size(), problems.size() - (accepted.size() - before));
          again.forEach(p -> LOG.warn("Rejected a proposal of {}: {}", purpose, p));
        }
      }
      conversation.end(
          accepted.isEmpty() && rejectedCount > 0
              ? LlmConversation.Ending.REJECTED
              : LlmConversation.Ending.ANSWERED,
          accepted.isEmpty() && rejectedCount > 0
              ? rejectedCount + " proposals failed the checks twice"
              : null);
      ArrayNode json = FactJson.newObject().arrayNode();
      accepted.forEach(p -> json.add(p.toJson()));
      store.save(
          new Investigation(
              key,
              subject.scope(),
              json.toString(),
              InvestigatePrompt.VERSION,
              model.provider(),
              model.model()));
      return new Outcome(
          accepted, model.provider() + "/" + model.model(), false, false, rejectedCount);
    } catch (LlmException e) {
      LOG.warn("Could not {}: {}", purpose, e.getMessage());
      return Outcome.skippedOne();
    }
  }

  /** Puts each proposal read among the accepted, or its problem among the problems. */
  private static void sort(
      List<Read> reads,
      ProposalCheck check,
      Known known,
      List<Proposal> accepted,
      List<String> problems) {
    for (Read read : reads) {
      String problem =
          read.problem() != null ? read.problem() : problem(read.proposal(), check, known);
      if (problem == null) {
        accepted.add(read.proposal());
      } else {
        problems.add("- " + read.label() + ": " + problem);
      }
    }
  }

  /** Why a proposal fails: it names what the facts do not hold, or a citation fails. */
  private static String problem(Proposal proposal, ProposalCheck check, Known known) {
    String problem = known.problem(proposal);
    return problem != null ? problem : check.problem(proposal);
  }

  private static void logRejected(String purpose, String reason, LlmResponse answer) {
    String text = answer.text();
    if (text.length() > LOGGED_ANSWER) {
      text = text.substring(0, LOGGED_ANSWER) + "... (" + text.length() + " characters)";
    }
    LOG.warn(
        "Rejected the answer to {}: {} The answer: {}",
        purpose,
        reason,
        text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n"));
  }

  /** The cache key: the SHA-256 of the prompt version, provider, model and prompt. */
  static String key(Subject subject, ModelConfig model, String system) {
    String text =
        "prompt "
            + InvestigatePrompt.VERSION
            + "\nprovider "
            + model.provider()
            + "\nmodel "
            + model.model()
            + "\nsystem\n"
            + system
            + "\nuser\n"
            + subject.user();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The facts

  private Fact fact(
      Proposal proposal, Subject subject, ProposalCheck check, String model, long snapshot) {
    Cite first = proposal.cites().getFirst();
    ProposalCheck.File file = check.file(first).orElseThrow();
    Evidence evidence =
        new Evidence(
            first.repo(),
            commits.get(first.repo()),
            first.path(),
            new Evidence.LineRange(first.from(), first.to()),
            file.hash());
    ObjectNode attributes = FactJson.newObject();
    if (proposal.kind() == Kind.RISK) {
      attributes.put("finding", proposal.finding());
      attributes.put("consequence", proposal.consequence());
      attributes.put("impact", proposal.impact());
    } else {
      attributes.put("interaction", proposal.interaction());
      attributes.put("description", proposal.title());
      if (proposal.technology() != null) {
        attributes.put("technology", proposal.technology());
      }
    }
    attributes.put("confidence", proposal.confidence());
    ArrayNode units = attributes.putArray("units");
    proposal.units().forEach(u -> units.add(u.toString()));
    ArrayNode names = attributes.putArray("names");
    proposal.names().forEach(names::add);
    ArrayNode cites = attributes.putArray("cites");
    proposal.cites().forEach(c -> cites.add(c.toString()));
    attributes.put("scope", subject.scope());
    if (model != null) {
      attributes.put("model", model);
    }
    double confidence = CONFIDENCE.get(proposal.confidence());
    if (proposal.kind() == Kind.RISK) {
      return Fact.create(
          proposal.units().getFirst(),
          RISK,
          FactObject.string(proposal.title()),
          attributes,
          ORIGIN,
          evidence,
          confidence,
          Validity.from(snapshot));
    }
    attributes.put(
        "link",
        FoundLinks.linkId(
            CONNECTION + "|" + proposal.from() + "|" + proposal.to() + "|" + proposal.interaction(),
            null));
    return Fact.create(
        proposal.from(),
        CONNECTION,
        FactObject.entity(proposal.to()),
        attributes,
        ORIGIN,
        evidence,
        confidence,
        Validity.from(snapshot));
  }
}
