package io.lantern.compose;

import io.lantern.compose.WordingPrompt.Question;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.derive.Knowledge;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.Outcome;
import io.lantern.harness.Rejected;
import io.lantern.harness.Repair;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.checks.SupportJudge;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.Role;
import io.lantern.llm.Tokens;
import io.lantern.summarize.SourceFiles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.stream.Collectors;
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
 * <p>Each question is an item of the LLM harness (ADR 0100), under one of four roles: units, data
 * stores and destinations, flows, and the system's purpose. Its answer is cached under the item's
 * fingerprint, which hashes the role, its prompt version, the model, the facts the prompt shows
 * (their claim IDs, with the blobs of the files whose code it shows) and the prompt, which shows no
 * fact IDs, so a run over unchanged facts makes no call. Before any question of a role is asked,
 * the harness's plan prints the role's estimate. It never fails the run: an answer rejected twice,
 * a question past its role's share of the run budget, and a call refused by a budget or the model's
 * capabilities or failing in the provider, log a warning and cache nothing, so a later run asks
 * again. Calls run in parallel on virtual threads, bounded by the client's limits.
 */
public final class Describer {

  /** The most entry points of a unit without components the prompt shows. */
  static final int MAX_ENTRY_POINTS = 15;

  /** The most README claims the prompt shows per repository. */
  static final int MAX_CLAIMS = 8;

  /** The most tokens an answer about a flow may take. */
  static final int MAX_FLOW_OUTPUT_TOKENS = 1_500;

  /** The most tokens an answer about the system's purpose may take. */
  static final int MAX_PURPOSE_OUTPUT_TOKENS = 1_000;

  /** The most units the prompt about the system's purpose shows. */
  static final int MAX_PURPOSE_UNITS = 30;

  /** The most facts the prompt about the system's purpose shows under one unit's sentence. */
  static final int MAX_PURPOSE_UNIT_FACTS = 8;

  /** The links a unit's relationships are drawn from, as the container diagram draws them. */
  static final List<String> LINKS =
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
      Wordings wordings,
      int asked,
      int reused,
      List<EntityId> rejected,
      List<EntityId> skipped,
      List<LlmText> texts,
      List<String> unknown) {

    public Result {
      Objects.requireNonNull(wordings, "wordings");
      rejected = List.copyOf(rejected);
      skipped = List.copyOf(skipped);
      texts = List.copyOf(texts);
      unknown = List.copyOf(unknown);
    }

    /** A run's outcome without stored texts, as before the roles stored claims. */
    public Result(
        Wordings wordings, int asked, int reused, List<EntityId> rejected, List<EntityId> skipped) {
      this(wordings, asked, reused, rejected, skipped, List.of(), List.of());
    }

    /**
     * The responsibility and intent claims the run's Describe texts make, of origin {@code llm},
     * each role's facts by its origin.
     *
     * @param snapshot the snapshot they hold from
     */
    public Map<io.lantern.core.fact.Origin, List<Fact>> facts(long snapshot) {
      Map<io.lantern.core.fact.Origin, List<Fact>> byOrigin = new LinkedHashMap<>();
      byOrigin.put(io.lantern.harness.LlmFacts.origin(DescribeRole.UNITS), new ArrayList<>());
      byOrigin.put(io.lantern.harness.LlmFacts.origin(DescribeRole.ELEMENTS), new ArrayList<>());
      Map<String, Fact> unique = new LinkedHashMap<>();
      for (LlmText text : texts) {
        Fact fact =
            DescribeRole.fact(text.described(), text.worded(), text.role(), text.model(), snapshot);
        if (unique.putIfAbsent(fact.id(), fact) == null) {
          byOrigin.get(io.lantern.harness.LlmFacts.origin(text.role())).add(fact);
        }
      }
      return byOrigin;
    }
  }

  /**
   * A text a Describe role wrote, with what it is about.
   *
   * @param role the role
   * @param model the model, as {@code provider/model}
   * @param described the item it answers
   * @param worded the text
   */
  public record LlmText(
      RoleSpec role, String model, DescribeRole.Described described, DescribeRole.Worded worded) {}

  /** The role that narrates the main flows. */
  public static final RoleSpec FLOWS =
      RoleSpec.single(
          "flow-narrative",
          Role.COMPOSE,
          WordingPrompt.FLOW_VERSION,
          "flows-and-purpose",
          RunMode.BULK,
          MAX_FLOW_OUTPUT_TOKENS / 2);

  /** The role that words the system's purpose. */
  public static final RoleSpec PURPOSE =
      RoleSpec.single(
          "purpose",
          Role.INFER_INTENT,
          WordingPrompt.PURPOSE_VERSION,
          "flows-and-purpose",
          RunMode.INTERACTIVE,
          MAX_PURPOSE_OUTPUT_TOKENS / 2);

  private final Harness harness;
  private final SourceFiles sources;
  private final Set<EntityId> alsoJudged;

  /**
   * A describer whose prompts show no code.
   *
   * @param harness the run's LLM harness
   */
  public Describer(Harness harness) {
    this(harness, SourceFiles.NONE);
  }

  /**
   * @param harness the run's LLM harness
   * @param sources where the files the facts were observed in are read from, for the code of
   *     components
   */
  public Describer(Harness harness, SourceFiles sources) {
    this(harness, sources, Set.of());
  }

  private Describer(Harness harness, SourceFiles sources, Set<EntityId> alsoJudged) {
    this.harness = Objects.requireNonNull(harness, "harness");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.alsoJudged = Set.copyOf(alsoJudged);
  }

  /**
   * The same describer, whose support judge also checks the sentences about the given elements,
   * whether the sample takes them or not (ADR 0101).
   */
  public Describer alsoJudging(Set<EntityId> elements) {
    return new Describer(harness, sources, elements);
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
    return describe(facts, repoFacts, Map.of());
  }

  /**
   * As {@link #describe(Collection, Collection)}, with the summaries of the code, which the unit
   * items show (knowledge-build epic, section 11.4).
   *
   * @param summaries the summary of each type, package, module and repository, by its entity
   */
  public Result describe(
      Collection<Fact> facts,
      Collection<Fact> repoFacts,
      Map<EntityId, io.lantern.summarize.Summary> summaries) {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(summaries, "summaries");
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
                evidence.repo() + '\u0000' + evidence.blob() + '\u0000' + evidence.path(),
                f -> {
                  Optional<String> text = sources.shown(evidence);
                  // The injection screen reads every file a prompt showed (ADR 0101).
                  text.ifPresent(
                      t -> harness.showed(evidence.repo(), evidence.path(), evidence.blob(), t));
                  return text;
                });
    Set<String> documented = new TreeSet<>();
    DescribeItems describeItems = new DescribeItems(knowledge, assembler, c4, files, summaries);
    // Units first, frontends included: elements cite the units' responsibilities.
    List<DescribeRole.Described> unitItems = new ArrayList<>();
    SortedSet<EntityId> units = new TreeSet<>(Knowledge.BY_ID);
    units.addAll(C4Views.units(knowledge));
    units.addAll(C4Views.frontends(knowledge));
    units.forEach(unit -> unitItems.addAll(describeItems.unit(unit)));
    List<Question> flowQuestions = flows(repoFacts, knowledge, assembler, c4);
    // Under a budget, the system's purpose has first claim on its group's share, ahead of the flows
    // (#659): one cheap call that writes the report's first paragraph. It is asked last, on the
    // units' sentences, so its claim is planned now on a prompt of their expected size.
    RoleSession purposeSession = harness.session(PURPOSE);
    Optional<Question> expected =
        purposeSession.underShare() ? expectedPurpose(knowledge, unitItems) : Optional.empty();
    expected.ifPresent(
        q ->
            purposeSession.claim(
                q.element().toString(),
                Tokens.estimate(item(q).rendering()),
                Tokens.estimate(q.system())));
    Tally tally = new Tally();
    Map<String, DescribeRole.Described> bySubject = new java.util.concurrent.ConcurrentHashMap<>();
    unitItems.forEach(d -> bySubject.put(d.item().subject(), d));
    RoleSession unitSession = harness.session(DescribeRole.UNITS);
    unitSession.plan(
        unitItems.stream().map(DescribeRole.Described::item).toList(),
        Tokens.estimate(io.lantern.harness.checks.CommonPrompt.SYSTEM + DescribeRole.UNIT_TASK));
    plan(flowQuestions);
    Map<String, Outcome> unitOutcomes;
    List<Asked> flowOutcomes = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Asked>> futures = new ArrayList<>();
      for (Question question : flowQuestions) {
        futures.add(executor.submit(() -> answer(question)));
      }
      unitOutcomes =
          unitSession.askPackets(
              unitItems.stream().map(DescribeRole.Described::item).toList(),
              DescribeRole.units(bySubject));
      for (Future<Asked> future : futures) {
        flowOutcomes.add(future.get());
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
    DescribeRole.SummaryFacts summaryFacts = describeItems.summaryFacts();
    Map<EntityId, DescribeRole.Answered> unitAnswers = new TreeMap<>(Knowledge.BY_ID);
    List<WordingPrompt.UnitSentence> sentences = new ArrayList<>();
    for (DescribeRole.Described described : unitItems) {
      Outcome outcome = unitOutcomes.get(described.item().subject());
      List<DescribeRole.Worded> worded =
          tally.add(described, outcome, DescribeRole.UNITS, unitSession, summaryFacts);
      for (DescribeRole.Worded text : worded) {
        if (text.field().equals(DescribeRole.UNIT)) {
          unitAnswers.put(
              described.element(), new DescribeRole.Answered(text.text(), text.cited()));
          List<Fact> cited = byLine(text.cited());
          sentences.add(
              new WordingPrompt.UnitSentence(
                  described.name(),
                  text.text(),
                  cited.subList(0, Math.min(MAX_PURPOSE_UNIT_FACTS, cited.size()))));
        }
      }
    }
    // Then the data stores, destinations, external systems and people, packed by kind.
    List<DescribeRole.Described> elementItems = new ArrayList<>();
    for (EntityId element : describeItems.elements()) {
      elementItems.add(describeItems.element(element, unitAnswers));
    }
    elementItems.forEach(d -> bySubject.put(d.item().subject(), d));
    RoleSession elementSession = harness.session(DescribeRole.ELEMENTS);
    List<Item> elements = elementItems.stream().map(DescribeRole.Described::item).toList();
    elementSession.plan(
        elements,
        Tokens.estimate(io.lantern.harness.checks.CommonPrompt.SYSTEM + DescribeRole.ELEMENT_TASK));
    Map<String, Outcome> elementOutcomes =
        elementSession.askPackets(elements, DescribeRole.elements(bySubject));
    for (DescribeRole.Described described : elementItems) {
      tally.add(
          described,
          elementOutcomes.get(described.item().subject()),
          DescribeRole.ELEMENTS,
          elementSession,
          summaryFacts);
    }
    for (int i = 0; i < flowQuestions.size(); i++) {
      tally.add(flowQuestions.get(i), flowOutcomes.get(i));
    }
    // The system's purpose rests on the units' sentences, so it is asked about after them.
    Optional<Question> purpose = purpose(knowledge, sentences);
    purpose.ifPresent(
        q -> {
          if (expected.isEmpty()) {
            plan(List.of(q));
          }
          tally.add(q, answer(q));
        });
    if (purpose.isEmpty()) {
      expected.ifPresent(
          q ->
              purposeSession.notAsked(
                  q.element().toString(), "no unit was worded and no README states a claim"));
    }
    LOG.info(
        "Wordings: {} units, data stores, destinations, external systems, people, flows and the"
            + " system asked, {} cached, {} rejected, {} skipped",
        tally.asked,
        tally.reused,
        tally.rejected.size(),
        tally.skipped.size());
    // The support judge checks a sample of the sentences; one it finds unsupported is marked, not
    // dropped (ADR 0101).
    Map<String, SupportJudge.Support> verdicts =
        new SupportJudge(harness)
            .judge(
                List.copyOf(tally.judgeable.values()),
                alsoJudged.stream().map(EntityId::toString).collect(Collectors.toSet()));
    Set<EntityId> unsupported = new TreeSet<>(java.util.Comparator.comparing(EntityId::toString));
    tally.judgeable.forEach(
        (element, text) -> {
          if (verdicts.get(text.key()) == SupportJudge.Support.UNSUPPORTED) {
            unsupported.add(element);
          }
        });
    LOG.info(
        "Support judge: {} sentences checked, {} unsupported", verdicts.size(), unsupported.size());
    tally.documented.forEach(documented::add);
    return new Result(
        new Wordings(tally.responsibilities, tally.phrases, tally.flows, documented)
            .withUnsupported(unsupported),
        tally.asked,
        tally.reused,
        tally.rejected,
        tally.skipped,
        tally.texts,
        tally.unknown);
  }

  /** What the answers of a run add up to. */
  private static final class Tally {
    final Map<EntityId, String> responsibilities = new HashMap<>();
    final Map<EntityId, SupportJudge.Text> judgeable =
        new java.util.TreeMap<>(java.util.Comparator.comparing(EntityId::toString));
    final Map<String, Wordings.Phrase> phrases = new HashMap<>();
    final Map<String, Wordings.Flow> flows = new HashMap<>();
    final List<EntityId> rejected = new ArrayList<>();
    final List<EntityId> skipped = new ArrayList<>();
    final List<LlmText> texts = new ArrayList<>();
    final List<String> unknown = new ArrayList<>();
    final Set<String> documented = new TreeSet<>();
    int asked;
    int reused;

    /**
     * Adds what a Describe item's outcome worded: its texts as claims to store, its sentences and
     * phrases for the composer, and what it left unknown.
     */
    List<DescribeRole.Worded> add(
        DescribeRole.Described described,
        Outcome outcome,
        RoleSpec role,
        RoleSession session,
        DescribeRole.SummaryFacts summaries) {
      List<DescribeRole.Worded> worded = new ArrayList<>();
      EntityId element = described.element();
      if (outcome == null) {
        skipped.add(element);
      } else {
        switch (outcome.kind()) {
          case CACHED, ANSWERED -> {
            if (outcome.kind() == Outcome.Kind.CACHED) {
              reused++;
            } else {
              asked++;
            }
            // A cached omission has no answer: the model left the item out before.
            if (outcome.answer() != null) {
              worded = DescribeRole.worded(described, outcome.answer(), summaries);
            }
          }
          case OMITTED -> {
            if ("omitted".equals(outcome.reason())) {
              asked++;
            } else {
              skipped.add(element);
            }
          }
          case DROPPED -> {
            asked++;
            rejected.add(element);
          }
          case NOT_ASKED, FAILED -> {
            LOG.warn("Left {} as it was, could not word it: {}", element, outcome.reason());
            skipped.add(element);
          }
        }
      }
      String model = session.model().provider() + "/" + session.model().model();
      Set<String> said = new java.util.HashSet<>();
      for (DescribeRole.Worded text : worded) {
        said.add(text.field());
        texts.add(new LlmText(role, model, described, text));
        String markers = markers(text.cited());
        if (text.field().startsWith(DescribeRole.INTERACTIONS + ".")) {
          Fact interaction =
              described
                  .interactions()
                  .get(text.field().substring(DescribeRole.INTERACTIONS.length() + 1));
          phrases.put(
              interaction.id(),
              new Wordings.Phrase(text.text(), text.cited().stream().map(Fact::id).toList()));
        } else {
          EntityId key = element;
          String component =
              text.field().startsWith(DescribeRole.COMPONENTS + ".")
                  ? text.field().substring(DescribeRole.COMPONENTS.length() + 1)
                  : null;
          if (component != null) {
            key = described.componentMains().get(component);
          }
          if (responsibilities.putIfAbsent(key, text.text() + markers) == null) {
            judgeable.put(key, DescribeRole.judged(key.toString(), text));
          }
          if (text.basis() == io.lantern.harness.LlmFacts.Basis.EVIDENCED) {
            text.cited().stream()
                .filter(f -> f.predicate().equals("kind"))
                .forEach(f -> documented.add(f.id()));
          }
        }
      }
      if (described.asksUnit()
          && !said.contains(DescribeRole.UNIT)
          && !said.contains(DescribeRole.RESPONSIBILITY)) {
        unknown.add(element.toString());
      }
      described
          .components()
          .forEach(
              (ref, component) -> {
                if (!said.contains(DescribeRole.COMPONENTS + "." + ref)) {
                  unknown.add(component.toString());
                }
              });
      described
          .interactions()
          .forEach(
              (ref, fact) -> {
                if (!said.contains(DescribeRole.INTERACTIONS + "." + ref)) {
                  unknown.add(
                      described.sources().get(ref) + " " + described.interactionLines().get(ref));
                }
              });
      return worded;
    }

    void add(Question question, Asked outcome) {
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
            resolve(question, outcome.answer(), responsibilities, phrases, judgeable);
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The question

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
  /** A sentence of the length a unit's responsibility sentence is expected to have. */
  private static final String EXPECTED_SENTENCE =
      "Handles the requests of its domain, stores their records and calls the services it"
          + " depends on, publishing the events other units consume.";

  /** How many facts a unit's responsibility sentence is expected to cite. */
  private static final int EXPECTED_CITES = 3;

  /**
   * The prompt about the system's purpose as it is expected to be once every unit is worded: each
   * unit with a sentence of the expected length and as many of its first facts as a sentence is
   * expected to cite. Its size is what the purpose's claim on its share is planned on.
   */
  private static Optional<Question> expectedPurpose(
      Knowledge knowledge, List<DescribeRole.Described> items) {
    List<WordingPrompt.UnitSentence> units = new ArrayList<>();
    for (DescribeRole.Described described : items) {
      if (described.asksUnit()) {
        List<Fact> facts = new ArrayList<>();
        described.refs().lines().keySet().stream()
            .filter(ref -> ref.startsWith("F"))
            .forEach(ref -> described.refs().thing(ref).ifPresent(t -> facts.add((Fact) t)));
        List<Fact> sorted = byLine(facts);
        units.add(
            new WordingPrompt.UnitSentence(
                described.name(),
                EXPECTED_SENTENCE,
                sorted.subList(0, Math.min(EXPECTED_CITES, sorted.size()))));
      }
    }
    return purpose(knowledge, units);
  }

  /** The citation markers of the facts, as summaries have them. */
  private static String markers(List<Fact> facts) {
    StringBuilder text = new StringBuilder();
    facts.forEach(f -> text.append(" [fact:").append(f.id()).append(']'));
    return text.toString();
  }

  private static Optional<Question> purpose(
      Knowledge knowledge, List<WordingPrompt.UnitSentence> sentences) {
    List<WordingPrompt.UnitSentence> units = new ArrayList<>(sentences);
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

  /** The facts by ID, each once. */
  private static Map<String, Fact> byId(List<Fact> facts) {
    Map<String, Fact> result = new LinkedHashMap<>();
    facts.forEach(f -> result.putIfAbsent(f.id(), f));
    return result;
  }

  /** What the other end of a line does with the data store or destination at this end. */
  static String use(C4Views.Neighbour neighbour) {
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
  static List<Fact> entryPoints(EntityId unit, Knowledge knowledge) {
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
  static List<Fact> claims(EntityId unit, Knowledge knowledge) {
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

  static String verb(String predicate) {
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
  static String noun(EntityId element) {
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

  private record Asked(Kind kind, WordingAnswer answer, boolean cached) {}

  /** The role of a question. */
  static RoleSpec spec(Question question) {
    return switch (question.about()) {
      case WordingPrompt.WALKTHROUGH -> FLOWS;
      case WordingPrompt.PURPOSE -> PURPOSE;
      default ->
          throw new IllegalArgumentException("Not a flow or the purpose: " + question.about());
    };
  }

  /**
   * A question as an item of the harness: the claims it shows, the blobs of the files whose code it
   * shows, and its whole prompt.
   */
  static Item item(Question question) {
    List<FingerprintInput> inputs = new ArrayList<>();
    question.facts().values().forEach(fact -> inputs.add(FingerprintInput.of(fact)));
    // The files whose code the prompt shows, by their blobs.
    question
        .code()
        .keySet()
        .forEach(
            ref -> {
              Fact fact = question.facts().get(ref);
              if (fact != null) {
                FingerprintInput.fileOf(fact).ifPresent(inputs::add);
              }
            });
    return new Item(
        question.element().toString(),
        "",
        inputs,
        "prompt "
            + question.version()
            + "\nsystem\n"
            + question.system()
            + "\nuser\n"
            + question.user());
  }

  /** Plans the questions, role by role, before any is asked. */
  private void plan(List<Question> questions) {
    Map<RoleSpec, List<Item>> byRole = new LinkedHashMap<>();
    Map<RoleSpec, Long> prefix = new LinkedHashMap<>();
    for (Question question : questions) {
      byRole.computeIfAbsent(spec(question), r -> new ArrayList<>()).add(item(question));
      prefix.putIfAbsent(spec(question), Tokens.estimate(question.system()));
    }
    byRole.forEach((role, items) -> harness.session(role).plan(items, prefix.get(role)));
  }

  private Asked answer(Question question) {
    RoleSession session = harness.session(spec(question));
    Item item = item(question);
    try {
      return answer(question, session, item);
    } finally {
      // Whatever ended it, the items waiting for the warm-up item go on.
      session.releaseWarmUp(item);
    }
  }

  private Asked answer(Question question, RoleSession session, Item item) {
    String key = session.fingerprint(item);
    String purpose =
        switch (question.about()) {
          case WordingAnswer.UNIT -> "word unit " + question.element();
          case WordingPrompt.WALKTHROUGH -> "narrate flow " + question.name();
          case WordingPrompt.PURPOSE -> "word the system's purpose";
          default -> "word element " + question.element();
        };
    Outcome outcome =
        session.ask(
            item,
            calls -> {
              Map<String, WordingAnswer.Item> items =
                  calls.answer(
                      new LlmRequest(
                          role(question),
                          purpose,
                          question.system(),
                          List.of(LlmMessage.user(question.user())),
                          maxTokens(question),
                          false),
                      response -> {
                        try {
                          return WordingPrompt.check(response, question);
                        } catch (WordingPrompt.Rejected rejected) {
                          throw new Rejected(rejected.getMessage());
                        }
                      },
                      Repair.withMessage(
                          rejected -> WordingPrompt.RETRY.formatted(rejected.getMessage())));
              return io.lantern.harness.Answer.of(WordingJson.write(items));
            });
    return switch (outcome.kind()) {
      case CACHED, ANSWERED ->
          new Asked(
              Kind.ANSWERED,
              new WordingAnswer(
                  key,
                  question.element(),
                  WordingJson.read(outcome.answer()),
                  question.version(),
                  session.model().provider(),
                  session.model().model()),
              outcome.kind() == Outcome.Kind.CACHED);
      case DROPPED -> new Asked(Kind.REJECTED, null, false);
      case OMITTED, NOT_ASKED, FAILED -> {
        LOG.warn(
            "Left {} as it was, could not {}: {}", question.element(), purpose, outcome.reason());
        yield new Asked(Kind.SKIPPED, null, false);
      }
    };
  }

  private static int maxTokens(Question question) {
    return flow(question) ? MAX_FLOW_OUTPUT_TOKENS : MAX_PURPOSE_OUTPUT_TOKENS;
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
      Map<String, Wordings.Phrase> phrases,
      Map<EntityId, SupportJudge.Text> judgeable) {
    answer
        .items()
        .forEach(
            (ref, item) -> {
              if (ref.equals(question.about())) {
                sentence(question, item)
                    .ifPresent(
                        s -> {
                          responsibilities.put(question.element(), s);
                          judgeable.put(
                              question.element(), judged(question, question.element(), item));
                        });
              } else if (question.components().containsKey(ref)) {
                EntityId type = question.components().get(ref);
                sentence(question, item)
                    .ifPresent(
                        s -> {
                          if (responsibilities.putIfAbsent(type, s) == null) {
                            judgeable.put(type, judged(question, type, item));
                          }
                        });
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

  /**
   * A responsibility sentence as the support judge sees it (ADR 0101): the text, and each fact it
   * cites as the prompt showed it.
   */
  private static SupportJudge.Text judged(
      Question question, EntityId element, WordingAnswer.Item item) {
    List<String> cited = new ArrayList<>();
    List<String> lines = question.user().lines().map(String::strip).toList();
    for (String cite : item.cites()) {
      String opening = "[" + cite + "] ";
      lines.stream()
          .filter(line -> line.startsWith(opening))
          .findFirst()
          .filter(line -> !cited.contains(line))
          .ifPresent(cited::add);
    }
    return new SupportJudge.Text(element.toString(), item.text(), cited);
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
