package io.lantern.compose;

import io.lantern.compose.WordingPrompt.ComponentInput;
import io.lantern.compose.WordingPrompt.Question;
import io.lantern.compose.WordingPrompt.RelationshipInput;
import io.lantern.compose.WordingPrompt.UseInput;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import io.lantern.summarize.SourceFiles;
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
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Has the LLM word what extraction found in the C4 views (ADR 0050, docs/design.md: LLM
 * integration, Grounding): a responsibility sentence for each deployable unit and each of its
 * components, and an intent phrase for each of its links to other containers. One call per unit
 * shows what extraction found: the facts that make it a unit, its README's claims, its components
 * (when it has more than one, as the component diagram draws them) with their facts and the start
 * of their main type's code (ADR 0069), or else its entry points, and its links; the answer cites
 * them. Nothing is added: a unit, component or link the model leaves out keeps its summary or
 * static label. One call per data store, topic, queue, exchange and external system the container
 * diagrams draw asks for its responsibility sentence from its facts, the tables built in it and how
 * the units use it (ADR 0056); one the model leaves out has no description, as before. One call per
 * main flow the documents show asks for its walkthrough and pre- and post-conditions from what
 * starts it and the facts behind its steps (ADR 0058); what the model leaves out keeps its static
 * prose or "Not known from the code.". Last, one call asks for the system's purpose from the units'
 * sentences, with the facts they cite, and the README claims of its repositories (ADR 0060);
 * without a purpose the system's report states it as unknown.
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

  /** The most characters of one component's code the prompt shows. */
  static final int MAX_CODE_CHARS = 4_000;

  /** The most characters of code, of all its components, the prompt about a unit shows. */
  static final int MAX_UNIT_CODE_CHARS = 40_000;

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

  /** The most tokens an answer about a flow may take. */
  static final int MAX_FLOW_OUTPUT_TOKENS = 1_500;

  /** The most tokens an answer about the system's purpose may take. */
  static final int MAX_PURPOSE_OUTPUT_TOKENS = 1_000;

  /** The most units the prompt about the system's purpose shows. */
  static final int MAX_PURPOSE_UNITS = 30;

  /** The most facts the prompt about the system's purpose shows under one unit's sentence. */
  static final int MAX_PURPOSE_UNIT_FACTS = 8;

  /** The links a unit's relationships are drawn from, as the container diagram draws them. */
  private static final List<String> LINKS =
      List.of(
          C4Views.CALLS_HTTP,
          C4Views.SENDS_MAIL,
          C4Views.USES_IDENTITY_PROVIDER,
          C4Views.PUBLISHES,
          C4Views.CONSUMES,
          C4Views.USES_DATABASE,
          C4Views.ACCESSES_DATA_OF);

  private static final String METHOD = "method";
  private static final String CLAIM = "claim";
  private static final String README = "readme";
  private static final String KIND = "kind";
  private static final String TABLE = "table";
  private static final Set<String> ENTRY_POINTS =
      Set.of(
          "endpoint",
          "message-listener",
          "stomp-mapping",
          "websocket-endpoint",
          "scheduled",
          "background-job");

  private static final Logger LOG = LoggerFactory.getLogger(Describer.class);

  /** At most this many characters of a rejected answer are logged. */
  private static final int LOGGED_ANSWER = 1_000;

  /**
   * What a run did.
   *
   * @param wordings the responsibility sentences and intent phrases, for the composer
   * @param asked how many units, data stores, destinations and flows, and the system for its
   *     purpose, the model was asked about in this run
   * @param reused how many answers came from the cache
   * @param rejected the units, data stores and destinations, the entry points of the flows, and the
   *     system for its purpose, whose answer was rejected twice
   * @param skipped the units, data stores and destinations, the entry points of the flows, and the
   *     system for its purpose, the model could not be asked about, because a budget or the model's
   *     capabilities refused the call or the provider failed
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
  private final SourceFiles sources;

  /**
   * A describer whose prompts show no code.
   *
   * @param client the LLM, with the budgets the run's calls count against
   * @param store where answers are cached
   */
  public Describer(LlmClient client, WordingStore store) {
    this(client, store, SourceFiles.NONE);
  }

  /**
   * @param client the LLM, with the budgets the run's calls count against
   * @param store where answers are cached
   * @param sources where the files the facts were observed in are read from, for the code of
   *     components
   */
  public Describer(LlmClient client, WordingStore store, SourceFiles sources) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
    this.sources = Objects.requireNonNull(sources, "sources");
  }

  /**
   * Words the deployable units of the facts, the data stores and destinations their diagrams draw,
   * the main flows of the system's and each repository's documents, and the system's purpose.
   *
   * @param facts the facts that hold in a snapshot, the linker's included
   */
  public Result describe(Collection<Fact> facts) {
    return describe(facts, facts);
  }

  /**
   * As {@link #describe(Collection)}, with the repositories' documents composed from other facts
   * than the system's: their flows are traced from those facts, as the composer will trace them.
   *
   * @param facts the facts that hold in a snapshot, the linker's included, which the system's
   *     documents are composed from
   * @param repoFacts the facts the repositories' documents are composed from; their repositories
   *     are the ones whose flows are narrated
   */
  public Result describe(Collection<Fact> facts, Collection<Fact> repoFacts) {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(repoFacts, "repoFacts");
    Knowledge knowledge = new Knowledge(facts);
    Assembler assembler = new Assembler(knowledge, Map.of(), Set.of(), null, "The system");
    C4Views c4 =
        new C4Views(knowledge, assembler, RepoSections.SYSTEM, C4Views.units(knowledge), Set.of());
    Set<Fact> alternatives = c4.alternativeLinks().keySet();
    List<Question> questions = new ArrayList<>();
    Map<String, Optional<String>> read = new HashMap<>();
    Function<Evidence, Optional<String>> files =
        evidence ->
            read.computeIfAbsent(
                evidence.repo() + '\u0000' + evidence.commit() + '\u0000' + evidence.path(),
                f -> sources.shown(evidence));
    Set<String> documented = new TreeSet<>();
    for (EntityId unit : C4Views.units(knowledge)) {
      question(unit, knowledge, assembler, c4, alternatives, files, documented)
          .ifPresent(questions::add);
    }
    for (EntityId element : c4.destinations()) {
      questions.add(question(element, knowledge, assembler, c4));
    }
    questions.addAll(flows(repoFacts, knowledge, assembler, c4));
    List<Outcome> outcomes = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (Question question : questions) {
        ModelConfig model = client.model(role(question));
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
    Tally tally = new Tally();
    for (int i = 0; i < questions.size(); i++) {
      tally.add(questions.get(i), outcomes.get(i));
    }
    // The system's purpose rests on the units' sentences, so it is asked about after them.
    Optional<Question> purpose = purpose(knowledge, questions, outcomes);
    purpose.ifPresent(q -> tally.add(q, answer(q, client.model(role(q)))));
    LOG.info(
        "Wordings: {} units, data stores, destinations, flows and the system asked, {} cached,"
            + " {} rejected, {} skipped",
        tally.asked,
        tally.reused,
        tally.rejected.size(),
        tally.skipped.size());
    return new Result(
        new Wordings(tally.responsibilities, tally.phrases, tally.flows, documented),
        tally.asked,
        tally.reused,
        tally.rejected,
        tally.skipped);
  }

  /** What the answers of a run add up to. */
  private static final class Tally {
    final Map<EntityId, String> responsibilities = new HashMap<>();
    final Map<String, Wordings.Phrase> phrases = new HashMap<>();
    final Map<String, Wordings.Flow> flows = new HashMap<>();
    final List<EntityId> rejected = new ArrayList<>();
    final List<EntityId> skipped = new ArrayList<>();
    int asked;
    int reused;

    void add(Question question, Outcome outcome) {
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
          if (flow(question)) {
            flows.put(Wordings.flowKey(question.user()), flow(question, outcome.answer()));
          } else {
            resolve(question, outcome.answer(), responsibilities, phrases);
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The question

  /**
   * The prompt about a unit, or none when it would show nothing to word beyond the facts that make
   * it a unit: no README claims, entry points, components or links. Each component shows the start
   * of its main type's code while the unit's share of code lasts (ADR 0069).
   *
   * @param files the text of the file of a fact's evidence as prompts show it, or empty
   * @param documented gets the IDs of the facts above code that holds Javadoc, which count as
   *     evidence when a sentence cites them
   */
  private static Optional<Question> question(
      EntityId unit,
      Knowledge knowledge,
      Assembler assembler,
      C4Views c4,
      Set<Fact> alternatives,
      Function<Evidence, Optional<String>> files,
      Set<String> documented) {
    List<ComponentInput> components = new ArrayList<>();
    List<C4Views.Component> found = c4.components(unit);
    // Components are drawn only for a unit with more than one (docs/design.md, Diagrams).
    if (found.size() > 1) {
      int codeLeft = MAX_UNIT_CODE_CHARS;
      for (C4Views.Component component : found.subList(0, Math.min(MAX_COMPONENTS, found.size()))) {
        List<Fact> shown = byLine(c4.componentFacts(component));
        WordingPrompt.Code code =
            code(component.main(), knowledge, files, Math.min(MAX_CODE_CHARS, codeLeft));
        if (code != null) {
          codeLeft -= code.text().length();
          if (code.documented()) {
            documented.add(code.declaration().id());
          }
        }
        components.add(
            new ComponentInput(
                component.main(),
                c4.componentName(component),
                component.technology().isEmpty() ? null : component.technology(),
                shown.subList(0, Math.min(MAX_COMPONENT_FACTS, shown.size())),
                code));
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

  /**
   * The prompts about the main flows that the system's documents and each repository's show (ADR
   * 0058), each once: the same trace gives the same prompt whichever scope shows it. A repository's
   * flows are traced from the facts its documents are composed from.
   */
  private static List<Question> flows(
      Collection<Fact> repoFacts, Knowledge knowledge, Assembler assembler, C4Views c4) {
    SortedSet<EntityId> all = C4Views.units(knowledge);
    Map<String, Question> questions = new LinkedHashMap<>();
    new SequenceFlows(knowledge, assembler, c4, all)
        .questions()
        .forEach(q -> questions.putIfAbsent(q.user(), q));
    Knowledge repoKnowledge = new Knowledge(repoFacts);
    Assembler repoAssembler = new Assembler(repoKnowledge, Map.of(), Set.of(), null, "The system");
    SortedSet<String> repos = new TreeSet<>();
    repoFacts.forEach(f -> repos.add(f.evidence().repo()));
    for (String repo : repos) {
      SortedSet<EntityId> units = C4Views.units(repoKnowledge, repoKnowledge.modules(repo));
      if (units.isEmpty()) {
        continue;
      }
      C4Views repoViews =
          new C4Views(repoKnowledge, repoAssembler, RepoSections.SYSTEM, units, Set.of(repo));
      new SequenceFlows(repoKnowledge, repoAssembler, repoViews, units)
          .questions()
          .forEach(q -> questions.putIfAbsent(q.user(), q));
    }
    return new ArrayList<>(questions.values());
  }

  /**
   * The prompt about the system's purpose (ADR 0060): the responsibility sentence of each unit the
   * model worded, with the facts it cites, and the claims of each repository's README with the
   * fewest folders; or none when there is neither, so the purpose is stated as unknown without a
   * call. Units are listed by name and READMEs by repository, so the order does not depend on fact
   * IDs.
   */
  private static Optional<Question> purpose(
      Knowledge knowledge, List<Question> questions, List<Outcome> outcomes) {
    List<WordingPrompt.UnitSentence> units = new ArrayList<>();
    for (int i = 0; i < questions.size(); i++) {
      Question question = questions.get(i);
      Outcome outcome = outcomes.get(i);
      if (!question.about().equals(WordingAnswer.UNIT) || outcome.kind() != Kind.ANSWERED) {
        continue;
      }
      WordingAnswer.Item item = outcome.answer().items().get(WordingAnswer.UNIT);
      if (item == null) {
        continue;
      }
      List<Fact> cited = new ArrayList<>();
      for (String cite : item.cites()) {
        Fact fact = question.facts().get(cite);
        if (fact != null && !cited.contains(fact)) {
          cited.add(fact);
        }
      }
      cited = byLine(cited);
      units.add(
          new WordingPrompt.UnitSentence(
              question.name(),
              item.text(),
              cited.subList(0, Math.min(MAX_PURPOSE_UNIT_FACTS, cited.size()))));
    }
    units.sort(
        Comparator.comparing(WordingPrompt.UnitSentence::name)
            .thenComparing(WordingPrompt.UnitSentence::sentence));
    units = units.subList(0, Math.min(MAX_PURPOSE_UNITS, units.size()));
    SortedSet<String> repos = new TreeSet<>();
    knowledge.withPredicate(CLAIM).forEach(f -> repos.add(f.evidence().repo()));
    Map<String, List<Fact>> claims = new LinkedHashMap<>();
    for (String repo : repos) {
      List<Fact> readme = readmeClaims(repo, knowledge);
      if (!readme.isEmpty()) {
        claims.put(repo + " (" + readme.getFirst().evidence().path() + ")", readme);
      }
    }
    if (units.isEmpty() && claims.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(WordingPrompt.purpose(RepoSections.SYSTEM, units, claims));
  }

  /**
   * The start of the type's code, from the file its {@code kind} fact was observed in (ADR 0024),
   * or null when no such file can be read or nothing of it fits. A type declared in two files shows
   * the first, by repository and path, that can be read.
   */
  private static WordingPrompt.Code code(
      EntityId type, Knowledge knowledge, Function<Evidence, Optional<String>> files, int max) {
    List<Fact> declarations =
        knowledge.attributes(type).stream()
            .filter(f -> f.predicate().equals(KIND) && f.evidence().path() != null)
            .sorted(
                Comparator.comparing((Fact f) -> f.evidence().repo())
                    .thenComparing(f -> f.evidence().path())
                    .thenComparing(Fact::id))
            .toList();
    for (Fact declaration : declarations) {
      Optional<String> text = files.apply(declaration.evidence());
      if (text.isPresent()) {
        return WordingPrompt.code(declaration, text.get(), max);
      }
    }
    return null;
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
      case C4Views.SENDS_MAIL -> "sends mail through it";
      case C4Views.USES_IDENTITY_PROVIDER -> "uses it as its identity provider";
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
      result.addAll(readmeClaims(repo, knowledge));
    }
    return result;
  }

  /**
   * The claims of the repository's README with the fewest folders in its path, in the order
   * written, at most some.
   */
  private static List<Fact> readmeClaims(String repo, Knowledge knowledge) {
    List<Fact> claims =
        knowledge.withPredicate(CLAIM).stream()
            .filter(
                f ->
                    f.subject().type().equals(README)
                        && f.evidence().repo().equals(repo)
                        && f.evidence().path() != null)
            .toList();
    Optional<String> readme =
        claims.stream()
            .map(f -> f.evidence().path())
            .min(
                Comparator.comparingLong(
                        (String path) -> path.chars().filter(c -> c == '/').count())
                    .thenComparing(Comparator.naturalOrder()));
    return claims.stream()
        .filter(f -> f.evidence().path().equals(readme.orElse(null)))
        .sorted(
            Comparator.comparingInt(
                    (Fact f) -> f.evidence().lines() == null ? 0 : f.evidence().lines().start())
                .thenComparing(Fact::id))
        .limit(MAX_CLAIMS)
        .toList();
  }

  private static String verb(String predicate) {
    return switch (predicate) {
      case C4Views.CALLS_HTTP -> "calls";
      case C4Views.SENDS_MAIL -> "sends mail through";
      case C4Views.USES_IDENTITY_PROVIDER -> "uses as its identity provider";
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
    return WordingPrompt.kind(other);
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
    String purpose =
        switch (question.about()) {
          case WordingAnswer.UNIT -> "word unit " + question.element();
          case WordingPrompt.WALKTHROUGH -> "narrate flow " + question.name();
          case WordingPrompt.PURPOSE -> "word the system's purpose";
          default -> "word element " + question.element();
        };
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
        switch (question.about()) {
          case WordingAnswer.UNIT -> MAX_OUTPUT_TOKENS;
          case WordingPrompt.WALKTHROUGH -> MAX_FLOW_OUTPUT_TOKENS;
          case WordingPrompt.PURPOSE -> MAX_PURPOSE_OUTPUT_TOKENS;
          default -> MAX_ELEMENT_OUTPUT_TOKENS;
        };
    return client.call(
        new LlmRequest(role(question), purpose, question.system(), messages, maxTokens, false));
  }

  /** Whether the question is about a sequence flow. */
  private static boolean flow(Question question) {
    return question.about().equals(WordingPrompt.WALKTHROUGH);
  }

  /**
   * The role of the call: narrating a flow is composing narrative for a reader (docs/design.md, LLM
   * integration, Roles); responsibilities and intent are inferring intent.
   */
  private static Role role(Question question) {
    return flow(question) ? Role.COMPOSE : Role.INFER_INTENT;
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

  /** A flow's walkthrough and conditions, each with citation markers, or null if left out. */
  private static Wordings.Flow flow(Question question, WordingAnswer answer) {
    Map<String, String> texts = new HashMap<>();
    answer
        .items()
        .forEach(
            (field, item) -> sentence(question, item).ifPresent(text -> texts.put(field, text)));
    // The title is a heading, which holds no citation markers; it is kept when it cites a fact.
    WordingAnswer.Item title = answer.items().get(WordingPrompt.TITLE);
    return new Wordings.Flow(
        texts.get(WordingPrompt.WALKTHROUGH),
        texts.get(WordingPrompt.PRECONDITIONS),
        texts.get(WordingPrompt.POSTCONDITIONS),
        title == null || !texts.containsKey(WordingPrompt.TITLE)
            ? null
            : title.text().replaceAll("[.\\s]+$", ""));
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
