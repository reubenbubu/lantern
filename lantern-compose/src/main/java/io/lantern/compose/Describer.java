package io.lantern.compose;

import io.lantern.compose.WordingPrompt.ComponentInput;
import io.lantern.compose.WordingPrompt.Question;
import io.lantern.compose.WordingPrompt.RelationshipInput;
import io.lantern.compose.WordingPrompt.UseInput;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Has the LLM word what extraction found in the C4 views (ADR 0050, docs/design.md: LLM
 * integration, Grounding): a responsibility sentence for each deployable unit and each of its
 * components, and an intent phrase for each of its links to other containers. One call per unit
 * shows extracted facts only: those that make it a unit, its README's claims, its components (when
 * it has more than one, as the component diagram draws them) with their facts, or else its entry
 * points, and its links; the answer cites them. Nothing is added: a unit, component or link the
 * model leaves out keeps its summary or static label. One call per data store, topic, queue,
 * exchange and external system the container diagrams draw asks for its responsibility sentence
 * from its facts, the tables built in it and how the units use it (ADR 0056); one the model leaves
 * out has no description, as before.
 *
 * <p>Answers are cached by the hash of the prompt, which shows no fact IDs, so a run over unchanged
 * facts makes no call. It never fails the run: an answer rejected twice, and a call refused by a
 * budget or the model's capabilities or failing in the provider, log a warning and cache nothing,
 * so a later run asks again. Calls run in parallel on virtual threads, bounded by the client's
 * limits.
 */
public final class Describer {

  /** The most tokens an answer may take. */
  static final int MAX_OUTPUT_TOKENS = 3_000;

  /** The most components of one unit the prompt shows. */
  static final int MAX_COMPONENTS = 25;

  /** The most facts the prompt shows about one component. */
  static final int MAX_COMPONENT_FACTS = 8;

  /** The most entry points of a unit without components the prompt shows. */
  static final int MAX_ENTRY_POINTS = 15;

  /** The most README claims the prompt shows per repository. */
  static final int MAX_CLAIMS = 8;

  /** The most relationships of one unit the prompt shows. */
  static final int MAX_RELATIONSHIPS = 40;

  /** The most tokens an answer about a data store or destination may take. */
  static final int MAX_ELEMENT_OUTPUT_TOKENS = 1_000;

  /** The most facts about a data store or destination itself the prompt shows. */
  static final int MAX_ELEMENT_FACTS = 8;

  /** The most tables of a database the prompt shows. */
  static final int MAX_TABLES = 30;

  /** The most link facts of a data store or destination the prompt shows. */
  static final int MAX_USES = 40;

  /** The links a unit's relationships are drawn from, as the container diagram draws them. */
  private static final List<String> LINKS =
      List.of(
          C4Views.CALLS_HTTP,
          C4Views.PUBLISHES,
          C4Views.CONSUMES,
          C4Views.USES_DATABASE,
          C4Views.ACCESSES_DATA_OF);

  private static final String METHOD = "method";
  private static final String CLAIM = "claim";
  private static final String TABLE = "table";
  private static final Set<String> ENTRY_POINTS =
      Set.of("endpoint", "message-listener", "scheduled");

  private static final Logger LOG = LoggerFactory.getLogger(Describer.class);

  /** At most this many characters of a rejected answer are logged. */
  private static final int LOGGED_ANSWER = 1_000;

  /**
   * What a run did.
   *
   * @param wordings the responsibility sentences and intent phrases, for the composer
   * @param asked how many units, data stores and destinations the model was asked about in this run
   * @param reused how many answers came from the cache
   * @param rejected the units, data stores and destinations whose answer was rejected twice
   * @param skipped the units, data stores and destinations the model could not be asked about,
   *     because a budget or the model's capabilities refused the call or the provider failed
   */
  public record Result(
      Wordings wordings, int asked, int reused, List<EntityId> rejected, List<EntityId> skipped) {

    public Result {
      Objects.requireNonNull(wordings, "wordings");
      rejected = List.copyOf(rejected);
      skipped = List.copyOf(skipped);
    }
  }

  private final LlmClient client;
  private final WordingStore store;

  /**
   * @param client the LLM, with the budgets the run's calls count against
   * @param store where answers are cached
   */
  public Describer(LlmClient client, WordingStore store) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
  }

  /**
   * Words the deployable units of the facts, and the data stores and destinations their diagrams
   * draw.
   *
   * @param facts the facts that hold in a snapshot, the linker's included
   */
  public Result describe(Collection<Fact> facts) {
    Objects.requireNonNull(facts, "facts");
    Knowledge knowledge = new Knowledge(facts);
    Assembler assembler = new Assembler(knowledge, Map.of(), Set.of(), null, "The system");
    C4Views c4 =
        new C4Views(knowledge, assembler, RepoSections.SYSTEM, C4Views.units(knowledge), Set.of());
    Set<Fact> alternatives = c4.alternativeLinks().keySet();
    List<Question> questions = new ArrayList<>();
    for (EntityId unit : C4Views.units(knowledge)) {
      question(unit, knowledge, assembler, c4, alternatives).ifPresent(questions::add);
    }
    for (EntityId element : c4.destinations()) {
      questions.add(question(element, knowledge, assembler, c4));
    }
    ModelConfig model = client.model(Role.INFER_INTENT);
    List<Outcome> outcomes = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (Question question : questions) {
        futures.add(executor.submit(() -> answer(question, model)));
      }
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while wording units", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
    Map<EntityId, String> responsibilities = new HashMap<>();
    Map<String, Wordings.Phrase> phrases = new HashMap<>();
    int asked = 0;
    int reused = 0;
    List<EntityId> rejected = new ArrayList<>();
    List<EntityId> skipped = new ArrayList<>();
    for (int i = 0; i < questions.size(); i++) {
      Question question = questions.get(i);
      Outcome outcome = outcomes.get(i);
      switch (outcome.kind()) {
        case SKIPPED -> skipped.add(question.element());
        case REJECTED -> {
          asked++;
          rejected.add(question.element());
        }
        case ANSWERED -> {
          if (outcome.cached()) {
            reused++;
          } else {
            asked++;
          }
          resolve(question, outcome.answer(), responsibilities, phrases);
        }
      }
    }
    LOG.info(
        "Wordings: {} units, data stores and destinations asked, {} cached, {} rejected, {}"
            + " skipped",
        asked,
        reused,
        rejected.size(),
        skipped.size());
    return new Result(new Wordings(responsibilities, phrases), asked, reused, rejected, skipped);
  }

  // ---------------------------------------------------------------------------------------------
  // The question

  /**
   * The prompt about a unit, or none when it would show nothing to word beyond the facts that make
   * it a unit: no README claims, entry points, components or links.
   */
  private static Optional<Question> question(
      EntityId unit, Knowledge knowledge, Assembler assembler, C4Views c4, Set<Fact> alternatives) {
    List<ComponentInput> components = new ArrayList<>();
    List<C4Views.Component> found = c4.components(unit);
    // Components are drawn only for a unit with more than one (docs/design.md, Diagrams).
    if (found.size() > 1) {
      for (C4Views.Component component : found.subList(0, Math.min(MAX_COMPONENTS, found.size()))) {
        List<Fact> shown = byLine(c4.componentFacts(component));
        components.add(
            new ComponentInput(
                component.main(),
                assembler.name(component.main()),
                component.technology().isEmpty() ? null : component.technology(),
                shown.subList(0, Math.min(MAX_COMPONENT_FACTS, shown.size()))));
      }
    }
    List<Fact> entryPoints = components.isEmpty() ? entryPoints(unit, knowledge) : List.of();
    List<RelationshipInput> relationships = new ArrayList<>();
    for (String predicate : LINKS) {
      for (Fact link : byLine(knowledge.relations(unit, predicate))) {
        if (alternatives.contains(link) || relationships.size() >= MAX_RELATIONSHIPS) {
          continue;
        }
        EntityId other = Knowledge.object(link);
        relationships.add(
            new RelationshipInput(verb(predicate), assembler.name(other), kind(other), link));
      }
    }
    List<Fact> claims = claims(unit, knowledge);
    if (claims.isEmpty()
        && entryPoints.isEmpty()
        && components.isEmpty()
        && relationships.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        WordingPrompt.question(
            unit,
            assembler.name(unit),
            c4.technology(unit),
            byLine(c4.containerFacts(unit)),
            claims,
            entryPoints,
            components,
            relationships));
  }

  /**
   * The prompt about a data store or destination (ADR 0056): the facts about it, the tables of the
   * schemas built in it, and how each of the other ends of its lines uses it, by the link facts of
   * those lines. Uses are listed by what they say, so the order does not depend on fact IDs.
   */
  private static Question question(
      EntityId element, Knowledge knowledge, Assembler assembler, C4Views c4) {
    List<Fact> facts = byLine(knowledge.attributes(element));
    Map<String, List<Fact>> uses = new TreeMap<>();
    SortedSet<EntityId> schemas = new TreeSet<>(Knowledge.BY_ID);
    for (C4Views.Neighbour neighbour : c4.neighbours(element)) {
      String use =
          assembler.name(neighbour.other())
              + " ("
              + kind(neighbour.other())
              + ") "
              + use(neighbour);
      uses.computeIfAbsent(use, u -> new ArrayList<>()).addAll(neighbour.facts());
      for (Fact fact : neighbour.facts()) {
        if (fact.predicate().equals(C4Views.USES_DATABASE)) {
          C4Views.strings(fact, "schemas").forEach(id -> schemas.add(EntityId.parse(id)));
        }
      }
    }
    List<Fact> tables = new ArrayList<>();
    for (EntityId schema : schemas) {
      for (EntityId table : knowledge.parts(schema, TABLE)) {
        tables.addAll(knowledge.containsFacts(schema, table));
      }
    }
    tables = byLine(tables);
    List<UseInput> shown = new ArrayList<>();
    int count = 0;
    for (Map.Entry<String, List<Fact>> entry : uses.entrySet()) {
      List<Fact> links = byLine(new ArrayList<>(byId(entry.getValue()).values()));
      links = links.subList(0, Math.min(links.size(), MAX_USES - count));
      if (links.isEmpty()) {
        break;
      }
      count += links.size();
      shown.add(new UseInput(entry.getKey(), links));
    }
    return WordingPrompt.element(
        element,
        noun(element),
        assembler.name(element),
        c4.technology(element),
        facts.subList(0, Math.min(MAX_ELEMENT_FACTS, facts.size())),
        tables.subList(0, Math.min(MAX_TABLES, tables.size())),
        shown);
  }

  /** The facts by ID, each once. */
  private static Map<String, Fact> byId(List<Fact> facts) {
    Map<String, Fact> result = new LinkedHashMap<>();
    facts.forEach(f -> result.putIfAbsent(f.id(), f));
    return result;
  }

  /** What the other end of a line does with the data store or destination at this end. */
  private static String use(C4Views.Neighbour neighbour) {
    if (!neighbour.incoming()) {
      return switch (neighbour.relationship()) {
        case C4Views.CONSUMES ->
            neighbour.via() == null
                ? "receives messages from it"
                : "receives messages from it through " + neighbour.via();
        case C4Views.ROUTES_TO -> "receives messages it routes";
        default -> "is reached from it";
      };
    }
    return switch (neighbour.relationship()) {
      case C4Views.CALLS_HTTP -> "calls it";
      case C4Views.PUBLISHES -> "sends messages to it";
      case C4Views.USES_DATABASE -> "connects to it";
      case C4Views.ACCESSES_DATA_OF -> "reads or writes tables of it";
      case C4Views.ROUTES_TO -> "routes messages to it";
      default -> "reaches it";
    };
  }

  /**
   * The facts in the order of their lines in the prompt. Fact IDs hold the commit, so an order by
   * ID would change the prompt, and miss the cache, at every commit.
   */
  private static List<Fact> byLine(List<Fact> facts) {
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(Comparator.comparing(WordingPrompt::fact));
    return sorted;
  }

  /** The endpoints, listeners and scheduled jobs of the unit's code, by method ID, at most some. */
  private static List<Fact> entryPoints(EntityId unit, Knowledge knowledge) {
    SortedSet<EntityId> code = new TreeSet<>(Knowledge.BY_ID);
    for (EntityId module : C4Views.codeModules(knowledge, unit)) {
      code.addAll(knowledge.typesWithin(module));
      for (EntityId pkg : knowledge.parts(module, BuiltInTypes.PACKAGE)) {
        code.addAll(knowledge.typesWithin(pkg));
      }
    }
    List<Fact> result = new ArrayList<>();
    for (EntityId type : code) {
      for (EntityId method : knowledge.parts(type, METHOD)) {
        for (Fact fact : byLine(knowledge.attributes(method))) {
          if (ENTRY_POINTS.contains(fact.predicate()) && result.size() < MAX_ENTRY_POINTS) {
            result.add(fact);
          }
        }
      }
    }
    return result;
  }

  /**
   * The claims of the README with the fewest folders in its path in each repository holding the
   * unit's own module, in the order written, at most some.
   */
  private static List<Fact> claims(EntityId unit, Knowledge knowledge) {
    SortedSet<String> repos = new TreeSet<>();
    knowledge.parts(unit, BuiltInTypes.MODULE).forEach(m -> repos.addAll(knowledge.repos(m)));
    List<Fact> result = new ArrayList<>();
    for (String repo : repos) {
      List<Fact> claims =
          knowledge.withPredicate(CLAIM).stream()
              .filter(f -> f.evidence().repo().equals(repo) && f.evidence().path() != null)
              .toList();
      Optional<String> readme =
          claims.stream()
              .map(f -> f.evidence().path())
              .min(
                  Comparator.comparingLong(
                          (String path) -> path.chars().filter(c -> c == '/').count())
                      .thenComparing(Comparator.naturalOrder()));
      claims.stream()
          .filter(f -> f.evidence().path().equals(readme.orElse(null)))
          .sorted(
              Comparator.comparingInt(
                      (Fact f) -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                  .thenComparing(Fact::id))
          .limit(MAX_CLAIMS)
          .forEach(result::add);
    }
    return result;
  }

  private static String verb(String predicate) {
    return switch (predicate) {
      case C4Views.CALLS_HTTP -> "calls";
      case C4Views.PUBLISHES -> "sends messages to";
      case C4Views.CONSUMES -> "receives messages from";
      case C4Views.USES_DATABASE -> "connects to";
      default -> "reads or writes tables of";
    };
  }

  /** What the element is, without an article, such as {@code external system}. */
  private static String noun(EntityId element) {
    String kind = kind(element);
    return kind.substring(kind.indexOf(' ') + 1);
  }

  private static String kind(EntityId other) {
    return switch (other.type()) {
      case BuiltInTypes.SERVICE -> "a deployable unit";
      case C4Views.DATABASE -> "a database";
      case C4Views.TOPIC -> "a topic";
      case C4Views.QUEUE -> "a queue";
      case C4Views.EXCHANGE -> "an exchange";
      case C4Views.EXTERNAL_SYSTEM -> "an external system";
      default -> "a " + other.type();
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Asking

  private enum Kind {
    ANSWERED,
    REJECTED,
    SKIPPED
  }

  private record Outcome(Kind kind, WordingAnswer answer, boolean cached) {}

  private Outcome answer(Question question, ModelConfig model) {
    String key = key(question, model);
    WordingAnswer cached = store.find(key).orElse(null);
    if (cached != null) {
      return new Outcome(Kind.ANSWERED, cached, true);
    }
    boolean unit = question.about().equals(WordingAnswer.UNIT);
    String purpose = (unit ? "word unit " : "word element ") + question.element();
    try {
      List<LlmMessage> messages = new ArrayList<>();
      messages.add(LlmMessage.user(question.user()));
      LlmResponse first = ask(question, purpose, messages);
      Map<String, WordingAnswer.Item> items;
      try {
        items = WordingPrompt.check(first, question);
      } catch (WordingPrompt.Rejected rejected) {
        logRejected(purpose, rejected, first);
        if (first.text().isBlank()) {
          // A conversation cannot hold an empty turn, so the reason goes with the prompt again.
          messages.set(
              0,
              LlmMessage.user(
                  question.user() + "\n" + WordingPrompt.RETRY.formatted(rejected.getMessage())));
        } else {
          messages.add(LlmMessage.assistant(first.text()));
          messages.add(LlmMessage.user(WordingPrompt.RETRY.formatted(rejected.getMessage())));
        }
        LlmResponse second = ask(question, purpose + " (retry)", messages);
        try {
          items = WordingPrompt.check(second, question);
        } catch (WordingPrompt.Rejected again) {
          logRejected(purpose + " (retry)", again, second);
          return new Outcome(Kind.REJECTED, null, false);
        }
      }
      WordingAnswer answer =
          new WordingAnswer(
              key, question.element(), items, question.version(), model.provider(), model.model());
      store.save(answer);
      return new Outcome(Kind.ANSWERED, answer, false);
    } catch (LlmException e) {
      LOG.warn("Left {} as it was, could not {}: {}", question.element(), purpose, e.getMessage());
      return new Outcome(Kind.SKIPPED, null, false);
    }
  }

  private LlmResponse ask(Question question, String purpose, List<LlmMessage> messages) {
    int maxTokens =
        question.about().equals(WordingAnswer.UNIT) ? MAX_OUTPUT_TOKENS : MAX_ELEMENT_OUTPUT_TOKENS;
    return client.call(
        new LlmRequest(Role.INFER_INTENT, purpose, question.system(), messages, maxTokens, false));
  }

  private static void logRejected(
      String purpose, WordingPrompt.Rejected reason, LlmResponse answer) {
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
    String text =
        "prompt "
            + question.version()
            + "\nprovider "
            + model.provider()
            + "\nmodel "
            + model.model()
            + "\nsystem\n"
            + question.system()
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
  // The wordings

  /**
   * Turns an answer's references into what they stand for in the question of the same key: a
   * sentence gets citation markers for the facts it cites, a phrase the IDs of the facts it cites.
   */
  private static void resolve(
      Question question,
      WordingAnswer answer,
      Map<EntityId, String> responsibilities,
      Map<String, Wordings.Phrase> phrases) {
    answer
        .items()
        .forEach(
            (ref, item) -> {
              if (ref.equals(question.about())) {
                sentence(question, item)
                    .ifPresent(s -> responsibilities.put(question.element(), s));
              } else if (question.components().containsKey(ref)) {
                EntityId type = question.components().get(ref);
                sentence(question, item).ifPresent(s -> responsibilities.putIfAbsent(type, s));
              } else if (question.relationships().containsKey(ref)) {
                List<String> cited = new ArrayList<>();
                for (String cite : item.cites()) {
                  Fact fact = question.facts().get(cite);
                  if (fact != null && !cited.contains(fact.id())) {
                    cited.add(fact.id());
                  }
                }
                phrases.put(
                    question.relationships().get(ref).id(),
                    new Wordings.Phrase(item.text(), cited));
              }
            });
  }

  private static Optional<String> sentence(Question question, WordingAnswer.Item item) {
    StringBuilder text = new StringBuilder(item.text());
    boolean cites = false;
    for (String cite : item.cites()) {
      Fact fact = question.facts().get(cite);
      if (fact != null) {
        text.append(" [fact:").append(fact.id()).append(']');
        cites = true;
      }
    }
    return cites ? Optional.of(text.toString()) : Optional.empty();
  }
}
