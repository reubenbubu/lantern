package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.derive.ArchitectureModel;
import io.lantern.derive.Knowledge;
import io.lantern.derive.attributes.Types;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.Outcome;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.checks.SupportJudge;
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

  /** The links a unit's relationships are drawn from, as the container diagram draws them. */
  static final List<String> LINKS =
      List.of(
          Types.CALLS_HTTP,
          Types.SENDS_MAIL,
          Types.USES_IDENTITY_PROVIDER,
          Types.PUBLISHES,
          Types.CONSUMES,
          Types.USES_DATABASE,
          Types.ACCESSES_DATA_OF);

  private static final Set<String> ENTRY_POINTS =
      Set.of(
          Types.ENDPOINT,
          Types.MESSAGE_LISTENER,
          Types.STOMP_MAPPING,
          Types.WEBSOCKET_ENDPOINT,
          Types.SCHEDULED,
          Types.BACKGROUND_JOB);

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
      List<String> unknown,
      List<java.util.function.LongFunction<List<Fact>>> flowFacts,
      Map<String, Verdict> verdicts) {

    /**
     * The support judge's verdict on a sentence it checked (ADR 0101), by the key it judged it
     * under: the element's ID, a component's by its main type, or the system's for the purpose.
     *
     * @param text the sentence it judged, so only that text takes the verdict
     */
    public record Verdict(String text, io.lantern.harness.checks.SupportJudge.Support support) {}

    /** A run's outcome without verdicts. */
    public Result(
        Wordings wordings,
        int asked,
        int reused,
        List<EntityId> rejected,
        List<EntityId> skipped,
        List<LlmText> texts,
        List<String> unknown,
        List<java.util.function.LongFunction<List<Fact>>> flowFacts) {
      this(wordings, asked, reused, rejected, skipped, texts, unknown, flowFacts, Map.of());
    }

    /** A run's outcome without flow judgements. */
    public Result(
        Wordings wordings,
        int asked,
        int reused,
        List<EntityId> rejected,
        List<EntityId> skipped,
        List<LlmText> texts,
        List<String> unknown) {
      this(wordings, asked, reused, rejected, skipped, texts, unknown, List.of());
    }

    public Result {
      Objects.requireNonNull(wordings, "wordings");
      rejected = List.copyOf(rejected);
      skipped = List.copyOf(skipped);
      texts = List.copyOf(texts);
      unknown = List.copyOf(unknown);
      flowFacts = List.copyOf(flowFacts);
      verdicts = Map.copyOf(verdicts);
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
      byOrigin.put(
          io.lantern.harness.LlmFacts.origin(FlowJudgements.SIGNIFICANCE), new ArrayList<>());
      byOrigin.put(io.lantern.harness.LlmFacts.origin(FlowJudgements.NARRATIVE), new ArrayList<>());
      byOrigin.put(io.lantern.harness.LlmFacts.origin(PurposeRole.ROLE), new ArrayList<>());
      Map<String, Fact> unique = new LinkedHashMap<>();
      for (LlmText text : texts) {
        Fact fact =
            DescribeRole.fact(text.described(), text.worded(), text.role(), text.model(), snapshot);
        Verdict verdict = verdicts.get(judgedKey(text).toString());
        if (fact.predicate().equals(io.lantern.harness.LlmFacts.RESPONSIBILITY)
            && verdict != null
            && verdict.text().equals(text.worded().text())) {
          fact = io.lantern.harness.LlmFacts.withSupport(fact, verdict.support());
        }
        if (unique.putIfAbsent(fact.id(), fact) == null) {
          byOrigin.get(io.lantern.harness.LlmFacts.origin(text.role())).add(fact);
        }
      }
      Map<String, Fact> flows = new LinkedHashMap<>();
      Verdict purpose = verdicts.get(RepoSections.SYSTEM.toString());
      flowFacts.forEach(
          f ->
              f.apply(snapshot)
                  .forEach(
                      fact -> {
                        if (purpose != null
                            && fact.predicate().equals(io.lantern.harness.LlmFacts.PURPOSE)
                            && purpose.text().equals(fact.attributes().path("text").asString(""))) {
                          fact = io.lantern.harness.LlmFacts.withSupport(fact, purpose.support());
                        }
                        flows.putIfAbsent(fact.id(), fact);
                      }));
      flows
          .values()
          .forEach(
              fact -> byOrigin.computeIfAbsent(fact.origin(), o -> new ArrayList<>()).add(fact));
      return byOrigin;
    }
  }

  /**
   * The key a Describe text's responsibility sentence is judged under: its element, or a
   * component's main type.
   */
  static EntityId judgedKey(LlmText text) {
    String field = text.worded().field();
    if (field.startsWith(DescribeRole.COMPONENTS + ".")) {
      EntityId main =
          text.described()
              .componentMains()
              .get(field.substring(DescribeRole.COMPONENTS.length() + 1));
      if (main != null) {
        return main;
      }
    }
    return text.described().element();
  }

  /**
   * The facts without the responsibilities, intents, summaries, group names, flow judgements,
   * purpose and key fact the LLM roles wrote.
   */
  public static List<Fact> withoutTexts(Collection<Fact> facts) {
    return facts.stream().filter(f -> !io.lantern.harness.LlmFacts.isText(f)).toList();
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
  public static final RoleSpec FLOWS = FlowJudgements.NARRATIVE;

  /** The role that chooses which flows each scope draws. */
  public static final RoleSpec FLOW_SIGNIFICANCE = FlowJudgements.SIGNIFICANCE;

  /** The role that words the system's purpose. */
  public static final RoleSpec PURPOSE = PurposeRole.ROLE;

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
    // What Describe wrote in an earlier run is not its input: shown, it would change every item
    // it is about, and the model would word from its own words.
    facts = withoutTexts(facts);
    repoFacts = withoutTexts(repoFacts);
    ArchitectureModel systemModel = new ArchitectureModel(facts);
    Knowledge knowledge = systemModel.knowledge();
    Assembler assembler = new Assembler(systemModel, Set.of(), null, "The system");
    C4Views c4 =
        new C4Views(knowledge, assembler, RepoSections.SYSTEM, C4Views.units(knowledge), Set.of());
    Set<Fact> alternatives = c4.alternativeLinks().keySet();
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
    List<EntityId> nothingToWord = new ArrayList<>();
    for (EntityId unit : units) {
      List<DescribeRole.Described> items = describeItems.unit(unit);
      if (items.isEmpty()) {
        nothingToWord.add(unit);
      }
      unitItems.addAll(items);
    }
    // Under a budget, the system's purpose has first claim on its group's share, ahead of the flows
    // (#659): one cheap call that writes the report's first paragraph. It is asked last, on the
    // units' sentences, so its claim is planned now on a prompt of their expected size.
    RoleSession purposeSession = harness.session(PURPOSE);
    long purposePrefix =
        Tokens.estimate(
            io.lantern.harness.checks.CommonPrompt.SYSTEM
                + PurposeRole.TASK
                + DescribeRole.SCHEMA_HEADING
                + PurposeRole.SCHEMA);
    Optional<PurposeRole.Asked> expected =
        purposeSession.underShare()
            ? expectedPurpose(knowledge, unitItems, describeItems.elements())
            : Optional.empty();
    expected.ifPresent(
        a ->
            purposeSession.claim(
                a.item().subject(), Tokens.estimate(a.item().rendering()), purposePrefix));
    Tally tally = new Tally();
    // A unit whose facts show nothing to word from is not asked about, and is unknown.
    nothingToWord.forEach(unit -> tally.unknown.add(unit.toString()));
    Map<String, DescribeRole.Described> bySubject = new java.util.concurrent.ConcurrentHashMap<>();
    unitItems.forEach(d -> bySubject.put(d.item().subject(), d));
    RoleSession unitSession = harness.session(DescribeRole.UNITS);
    unitSession.plan(
        unitItems.stream().map(DescribeRole.Described::item).toList(),
        Tokens.estimate(io.lantern.harness.checks.CommonPrompt.SYSTEM + DescribeRole.UNIT_TASK));
    Map<String, Outcome> unitOutcomes =
        unitSession.askPackets(
            unitItems.stream().map(DescribeRole.Described::item).toList(),
            DescribeRole.units(bySubject));
    DescribeRole.SummaryFacts summaryFacts = describeItems.summaryFacts();
    Map<EntityId, DescribeRole.Answered> unitAnswers = new TreeMap<>(Knowledge.BY_ID);
    List<PurposeRole.Said> sentences = new ArrayList<>();
    for (DescribeRole.Described described : unitItems) {
      Outcome outcome = unitOutcomes.get(described.item().subject());
      List<DescribeRole.Worded> worded =
          tally.add(described, outcome, DescribeRole.UNITS, unitSession, summaryFacts);
      for (DescribeRole.Worded text : worded) {
        if (text.field().equals(DescribeRole.UNIT)) {
          unitAnswers.put(
              described.element(), new DescribeRole.Answered(text.text(), text.cited()));
          sentences.add(
              new PurposeRole.Said(
                  described.element(),
                  described.name(),
                  new DescribeRole.Answered(text.text(), text.cited())));
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
    // The purpose's item shows the people's sentences and the external systems.
    List<PurposeRole.Said> people = new ArrayList<>();
    Map<String, Fact> externals = new java.util.TreeMap<>();
    for (DescribeRole.Described described : elementItems) {
      List<DescribeRole.Worded> worded =
          tally.add(
              described,
              elementOutcomes.get(described.item().subject()),
              DescribeRole.ELEMENTS,
              elementSession,
              summaryFacts);
      if (described.element().type().equals(Types.PERSON)) {
        worded.stream()
            .filter(t -> t.field().equals(DescribeRole.RESPONSIBILITY_FIELD))
            .forEach(
                t ->
                    people.add(
                        new PurposeRole.Said(
                            described.element(),
                            described.name(),
                            new DescribeRole.Answered(t.text(), t.cited()))));
      } else if (described.element().type().equals(Types.EXTERNAL_SYSTEM)) {
        firstFact(described).ifPresent(f -> externals.putIfAbsent(described.name(), f));
      }
    }
    // Then the flows: which are worth drawing in each scope, then the narrative of each one drawn,
    // from the units' sentences and the interactions' phrases (knowledge-build epic, section 11.5;
    // ADR 0108).
    Map<String, List<String>> chosen =
        flows(repoFacts, knowledge, assembler, c4, unitAnswers, tally);
    // The system's purpose and key fact rest on all of these, so they are asked about last
    // (knowledge-build epic, section 11.6; ADR 0109).
    Optional<PurposeRole.Asked> purpose =
        PurposeRole.item(sentences, people, externals, tally.systemFlowTitles(), claims(knowledge));
    if (purpose.isEmpty()) {
      expected.ifPresent(
          a ->
              purposeSession.notAsked(
                  a.item().subject(), "no unit or person was worded and no README states a claim"));
    }
    purpose.ifPresent(
        asked -> {
          if (expected.isEmpty()) {
            purposeSession.plan(List.of(asked.item()), purposePrefix);
          }
          Outcome outcome = PurposeRole.ask(purposeSession, asked);
          count(tally, outcome);
          if (outcome == null || outcome.answer() == null) {
            if (outcome != null && outcome.kind() == Outcome.Kind.DROPPED) {
              tally.rejected.add(RepoSections.SYSTEM);
            } else if (outcome == null
                || outcome.kind() == Outcome.Kind.NOT_ASKED
                || outcome.kind() == Outcome.Kind.FAILED) {
              tally.skipped.add(RepoSections.SYSTEM);
            }
            return;
          }
          Map<String, PurposeRole.Worded> worded = PurposeRole.worded(asked, outcome.answer());
          PurposeRole.Worded said = worded.get(PurposeRole.PURPOSE_FIELD);
          if (said != null) {
            tally.responsibilities.put(RepoSections.SYSTEM, said.marked());
            tally.judgeable.put(
                RepoSections.SYSTEM,
                new SupportJudge.Text(RepoSections.SYSTEM.toString(), said.text(), said.lines()));
          }
          PurposeRole.Worded key = worded.get(PurposeRole.KEY_FACT);
          if (key != null) {
            tally.keyFact = key.marked();
          }
          String model = purposeSession.model().provider() + "/" + purposeSession.model().model();
          tally.flowFacts.add(snapshot -> PurposeRole.facts(worded, model, snapshot));
        });
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
    Map<String, Result.Verdict> stored = new java.util.TreeMap<>();
    tally.judgeable.forEach(
        (element, text) -> {
          SupportJudge.Support verdict = verdicts.get(text.key());
          if (verdict != null) {
            stored.put(element.toString(), new Result.Verdict(text.text(), verdict));
          }
        });
    return new Result(
        new Wordings(
            tally.responsibilities,
            tally.phrases,
            tally.flows,
            documented,
            unsupported,
            chosen,
            tally.keyFact),
        tally.asked,
        tally.reused,
        tally.rejected,
        tally.skipped,
        tally.texts,
        tally.unknown,
        tally.flowFacts,
        stored);
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
    // The flow judgements, purpose and key fact, made into claims once the snapshot is known.
    final List<java.util.function.LongFunction<List<Fact>>> flowFacts = new ArrayList<>();
    // The keys of the flows the system's documents draw, in order.
    final List<String> systemFlows = new ArrayList<>();
    // The system's key fact, with citation markers, if the model wrote one.
    String keyFact;
    int asked;
    int reused;

    /** The titles of the flows the system's documents draw, in order, as the model named them. */
    List<String> systemFlowTitles() {
      List<String> titles = new ArrayList<>();
      for (String key : systemFlows) {
        Wordings.Flow flow = flows.get(key);
        if (flow != null && flow.title() != null && !titles.contains(flow.title())) {
          titles.add(flow.title());
        }
      }
      return titles;
    }

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
          // A person's lines keep their labels: one fact stands for all of a person's uses.
          if (!text.about().type().equals(Types.PERSON)) {
            phrases.put(
                interaction.id(),
                new Wordings.Phrase(text.text(), text.cited().stream().map(Fact::id).toList()));
          }
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
                .filter(f -> f.predicate().equals(Types.KIND))
                .forEach(f -> documented.add(f.id()));
          }
        }
      }
      if (described.asksUnit()
          && !said.contains(DescribeRole.UNIT)
          && !said.contains(DescribeRole.RESPONSIBILITY_FIELD)) {
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
  }

  // ---------------------------------------------------------------------------------------------
  // The question

  /** A scope whose documents draw flows: the system's, or a repository's. */
  private record Scope(
      String key,
      String title,
      Knowledge knowledge,
      Assembler assembler,
      C4Views c4,
      SortedSet<EntityId> units) {

    SequenceFlows flows(List<String> judged) {
      return new SequenceFlows(knowledge, assembler, c4, units, judged);
    }
  }

  /**
   * The scopes whose documents draw flows: the system, from its facts, and each repository with a
   * unit, from the facts its documents are composed from.
   */
  private static List<Scope> scopes(
      Collection<Fact> repoFacts, Knowledge knowledge, Assembler assembler, C4Views c4) {
    List<Scope> scopes = new ArrayList<>();
    scopes.add(
        new Scope(
            ArchitectureModel.SYSTEM_SCOPE,
            "the system",
            knowledge,
            assembler,
            c4,
            C4Views.units(knowledge)));
    ArchitectureModel repoModel = new ArchitectureModel(repoFacts);
    Knowledge repoKnowledge = repoModel.knowledge();
    Assembler repoAssembler = new Assembler(repoModel, Set.of(), null, "The system");
    SortedSet<String> repos = new TreeSet<>();
    repoFacts.forEach(f -> repos.add(f.evidence().repo()));
    for (String repo : repos) {
      SortedSet<EntityId> units = C4Views.units(repoKnowledge, repoKnowledge.modules(repo));
      if (units.isEmpty()) {
        continue;
      }
      C4Views repoViews =
          new C4Views(repoKnowledge, repoAssembler, RepoSections.SYSTEM, units, Set.of(repo));
      scopes.add(
          new Scope(
              repo, "the repository " + repo, repoKnowledge, repoAssembler, repoViews, units));
    }
    return scopes;
  }

  /**
   * Chooses the flows each scope draws and narrates those (knowledge-build epic, section 11.5; ADR
   * 0108): one significance item per scope over its candidate flows, one for each shape, in two
   * passes for a scope of more than {@value FlowJudgements#ONE_PASS}; then one narrative item for
   * each flow a scope draws, the scopes the model did not judge drawing their highest ranked.
   *
   * @return the flows judged significant for each scope answered, in the model's order
   */
  private Map<String, List<String>> flows(
      Collection<Fact> repoFacts,
      Knowledge knowledge,
      Assembler assembler,
      C4Views c4,
      Map<EntityId, DescribeRole.Answered> responsibilities,
      Tally tally) {
    List<Scope> scopes = scopes(repoFacts, knowledge, assembler, c4);
    RoleSession significance = harness.session(FlowJudgements.SIGNIFICANCE);
    Map<Scope, List<SequenceFlows.Candidate>> candidates = new LinkedHashMap<>();
    for (Scope scope : scopes) {
      List<SequenceFlows.Candidate> found = scope.flows(null).candidates();
      if (!found.isEmpty()) {
        candidates.put(scope, found);
      }
    }
    // A large scope is judged unit by unit first, each keeping its best, then as a whole.
    Map<Scope, List<FlowJudgements.Asked>> firstPass = new LinkedHashMap<>();
    candidates.forEach(
        (scope, found) -> {
          // Each unit's flows in rank order (ADR 0047), so one left unjudged passes on its best.
          List<FlowJudgements.Asked> byUnit =
              FlowJudgements.firstPass(
                  scope.key(),
                  scope.flows(null).byRank(found),
                  responsibilities,
                  scope.assembler()::name);
          if (!byUnit.isEmpty()) {
            firstPass.put(scope, byUnit);
          }
        });
    // A large scope first, its first pass and then its second, which rests on the first and is
    // expected to show the best of each unit; then the other scopes.
    List<RoleSession.Planned> planned = new ArrayList<>();
    firstPass.forEach(
        (scope, unitItems) -> {
          List<SequenceFlows.Candidate> expected = new ArrayList<>();
          unitItems.forEach(
              unitItem -> {
                planned.add(RoleSession.Planned.of(unitItem.item()));
                expected.addAll(FlowJudgements.kept(unitItem, null));
              });
          FlowJudgements.Asked second =
              FlowJudgements.significance(
                  scope.key(), scope.title(), expected, responsibilities, scope.assembler()::name);
          planned.add(
              RoleSession.Planned.predicted(scope.key(), Tokens.estimate(second.item().rendering()))
                  .after(unitItems.stream().map(a -> a.item().subject()).toList()));
        });
    Map<Scope, FlowJudgements.Asked> items = new LinkedHashMap<>();
    candidates.forEach(
        (scope, found) -> {
          if (!firstPass.containsKey(scope)) {
            FlowJudgements.Asked asked =
                FlowJudgements.significance(
                    scope.key(), scope.title(), found, responsibilities, scope.assembler()::name);
            items.put(scope, asked);
            planned.add(RoleSession.Planned.of(asked.item()));
          }
        });
    significance.plan(
        planned,
        0,
        Tokens.estimate(
            io.lantern.harness.checks.CommonPrompt.SYSTEM + FlowJudgements.SIGNIFICANCE_TASK));
    String sigModel = significance.model().provider() + "/" + significance.model().model();
    Map<FlowJudgements.Asked, Outcome> firstOutcomes =
        askAll(significance, firstPass.values().stream().flatMap(List::stream).toList(), false);
    firstPass.forEach(
        (scope, unitItems) -> {
          List<SequenceFlows.Candidate> kept = new ArrayList<>();
          for (FlowJudgements.Asked unitItem : unitItems) {
            Outcome outcome = firstOutcomes.get(unitItem);
            count(tally, outcome);
            kept.addAll(FlowJudgements.kept(unitItem, order(unitItem, outcome)));
          }
          FlowJudgements.Asked asked =
              FlowJudgements.significance(
                  scope.key(), scope.title(), kept, responsibilities, scope.assembler()::name);
          items.put(scope, asked);
        });
    Map<FlowJudgements.Asked, Outcome> judged =
        askAll(significance, List.copyOf(items.values()), false);
    Map<String, List<String>> chosen = new LinkedHashMap<>();
    items.forEach(
        (scope, asked) -> {
          Outcome outcome = judged.get(asked);
          List<String> order = order(asked, outcome);
          count(tally, outcome);
          if (order == null) {
            LOG.info(
                "Flows of {} chosen by rank: significance {}",
                scope.key(),
                outcome == null ? "not asked" : outcome.kind() + " " + outcome.reason());
            return;
          }
          chosen.put(scope.key(), order);
          String stored = outcome.answer();
          tally.flowFacts.add(
              snapshot ->
                  FlowJudgements.judged(
                          asked, stored, scope.key(), sigModel, scope.knowledge(), snapshot)
                      .facts());
        });
    // The narrative of each flow a scope draws, each flow once.
    Map<String, FlowJudgements.Asked> narratives = new LinkedHashMap<>();
    Map<String, SequenceFlows.Narrated> narrated = new LinkedHashMap<>();
    Map<String, Scope> scopeOf = new LinkedHashMap<>();
    for (Scope scope : scopes) {
      for (SequenceFlows.Narrated flow : scope.flows(chosen.get(scope.key())).narrated()) {
        if (scope.key().equals(ArchitectureModel.SYSTEM_SCOPE)) {
          tally.systemFlows.add(flow.key());
        }
        if (narrated.putIfAbsent(flow.key(), flow) == null) {
          scopeOf.put(flow.key(), scope);
          narratives.put(
              flow.key(),
              FlowJudgements.narrative(
                  flow,
                  responsibilities,
                  tally.phrases,
                  scope.knowledge()::fact,
                  scope.assembler()::name));
        }
      }
    }
    RoleSession narrative = harness.session(FlowJudgements.NARRATIVE);
    narrative.plan(
        narratives.values().stream().map(FlowJudgements.Asked::item).toList(),
        Tokens.estimate(
            io.lantern.harness.checks.CommonPrompt.SYSTEM + FlowJudgements.NARRATIVE_TASK));
    String narModel = narrative.model().provider() + "/" + narrative.model().model();
    Map<FlowJudgements.Asked, Outcome> outcomes =
        askAll(narrative, List.copyOf(narratives.values()), true);
    narratives.forEach(
        (key, asked) -> {
          Outcome outcome = outcomes.get(asked);
          count(tally, outcome);
          EntityId flow = narrated.get(key).candidate().flow();
          if (outcome == null || outcome.answer() == null) {
            settle(tally, outcome, flow);
            return;
          }
          Scope scope = scopeOf.get(key);
          FlowJudgements.narrative(asked, outcome.answer(), flow, narModel, scope.knowledge(), 0)
              .ifPresent(n -> tally.flows.put(key, n.flow()));
          String stored = outcome.answer();
          tally.flowFacts.add(
              snapshot ->
                  FlowJudgements.narrative(
                          asked, stored, flow, narModel, scope.knowledge(), snapshot)
                      .map(n -> List.of(n.fact()))
                      .orElse(List.of()));
        });
    return chosen;
  }

  /** The flows a significance answer orders, by ID; null if it has no answer. */
  private static List<String> order(FlowJudgements.Asked asked, Outcome outcome) {
    if (outcome == null
        || (outcome.kind() != Outcome.Kind.CACHED && outcome.kind() != Outcome.Kind.ANSWERED)
        || outcome.answer() == null) {
      return null;
    }
    return FlowJudgements.order(asked, outcome.answer());
  }

  /** Asks each item alone, all at once, and returns each one's outcome; none if it has none. */
  private static Map<FlowJudgements.Asked, Outcome> askAll(
      RoleSession session, List<FlowJudgements.Asked> items, boolean narrative) {
    // By identity: two items may show the same.
    Map<FlowJudgements.Asked, Outcome> outcomes =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (FlowJudgements.Asked asked : items) {
        futures.add(
            executor.submit(
                () -> {
                  Outcome outcome = FlowJudgements.ask(session, asked, narrative);
                  if (outcome != null) {
                    outcomes.put(asked, outcome);
                  }
                }));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while asking about flows", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
    return outcomes;
  }

  /**
   * Records a flow whose narrative has no answer, as for an element: one dropped is rejected, one
   * not asked or failed is skipped, and one the model left out is neither.
   */
  private static void settle(Tally tally, Outcome outcome, EntityId flow) {
    if (outcome == null) {
      tally.skipped.add(flow);
      return;
    }
    switch (outcome.kind()) {
      case DROPPED -> tally.rejected.add(flow);
      case OMITTED -> {
        if (!"omitted".equals(outcome.reason())) {
          tally.skipped.add(flow);
        }
      }
      case NOT_ASKED, FAILED -> {
        LOG.warn("Left flow {} as it was, could not narrate it: {}", flow, outcome.reason());
        tally.skipped.add(flow);
      }
      default -> {}
    }
  }

  private static void count(Tally tally, Outcome outcome) {
    if (outcome == null) {
      return;
    }
    switch (outcome.kind()) {
      case CACHED -> tally.reused++;
      case DROPPED -> {
        if (outcome.repeatedDrop()) {
          tally.reused++;
        } else {
          tally.asked++;
        }
      }
      case ANSWERED -> tally.asked++;
      case OMITTED -> {
        if ("omitted".equals(outcome.reason())) {
          tally.asked++;
        }
      }
      default -> {}
    }
  }

  /** A sentence of the length a unit's responsibility sentence is expected to have. */
  private static final String EXPECTED_SENTENCE =
      "Handles the requests of its domain, stores their records and calls the services it"
          + " depends on, publishing the events other units consume.";

  /** How many facts a unit's responsibility sentence is expected to cite. */
  private static final int EXPECTED_CITES = 3;

  /**
   * The purpose's item as it is expected to be once every unit is worded: each unit with a sentence
   * of the expected length and as many of its first facts as a sentence is expected to cite, and
   * the READMEs' claims. Its size is what the purpose's claim on its share is planned on.
   */
  private static Optional<PurposeRole.Asked> expectedPurpose(
      Knowledge knowledge, List<DescribeRole.Described> items, Collection<EntityId> elements) {
    List<PurposeRole.Said> units = new ArrayList<>();
    List<PurposeRole.Said> people = new ArrayList<>();
    Map<String, Fact> externals = new java.util.TreeMap<>();
    for (DescribeRole.Described described : items) {
      if (described.asksUnit()) {
        List<Fact> facts = new ArrayList<>();
        described.refs().lines().keySet().stream()
            .filter(ref -> ref.startsWith("F"))
            .forEach(ref -> described.refs().thing(ref).ifPresent(t -> facts.add((Fact) t)));
        List<Fact> sorted = byLine(facts);
        units.add(
            new PurposeRole.Said(
                described.element(),
                described.name(),
                new DescribeRole.Answered(
                    EXPECTED_SENTENCE,
                    sorted.subList(0, Math.min(EXPECTED_CITES, sorted.size())))));
      }
    }
    // Each person with a sentence of the expected length, each external system with a fact of
    // the size of a call to it (the first unit fact, about it, stands in for its size), and as many
    // flow
    // titles as a scope draws by rank.
    Fact sample =
        units.stream().flatMap(u -> u.answered().cited().stream()).findFirst().orElse(null);
    for (EntityId element : elements) {
      if (element.type().equals(Types.PERSON)) {
        people.add(
            new PurposeRole.Said(
                element,
                element.toString(),
                new DescribeRole.Answered(EXPECTED_SENTENCE, List.of())));
      } else if (element.type().equals(Types.EXTERNAL_SYSTEM) && sample != null) {
        // Its own fact, the size of the sample, so none is shown "as above".
        externals.put(
            element.toString(),
            Fact.create(
                element,
                sample.predicate(),
                sample.object(),
                sample.attributes(),
                sample.origin(),
                sample.evidence(),
                sample.confidence(),
                sample.validity()));
      }
    }
    List<String> flows = java.util.Collections.nCopies(7, "Customer places an order");
    return PurposeRole.item(units, people, externals, flows, claims(knowledge));
  }

  /**
   * Each repository's README claims, of its README with the fewest folders, by repository and path,
   * repositories in order (ADR 0060).
   */
  private static Map<String, List<Fact>> claims(Knowledge knowledge) {
    SortedSet<String> repos = new TreeSet<>();
    knowledge.withPredicate(Types.CLAIM).forEach(f -> repos.add(f.evidence().repo()));
    Map<String, List<Fact>> claims = new LinkedHashMap<>();
    for (String repo : repos) {
      List<Fact> readme = readmeClaims(repo, knowledge);
      if (!readme.isEmpty()) {
        claims.put(repo + " (" + readme.getFirst().evidence().path() + ")", readme);
      }
    }
    return claims;
  }

  /** The first fact an element's item shows, in the order of its lines. */
  private static Optional<Fact> firstFact(DescribeRole.Described described) {
    List<Fact> facts = new ArrayList<>();
    described.refs().lines().keySet().stream()
        .filter(ref -> ref.startsWith("F"))
        .forEach(
            ref ->
                described
                    .refs()
                    .thing(ref)
                    .filter(Fact.class::isInstance)
                    .ifPresent(t -> facts.add((Fact) t)));
    return byLine(facts).stream().findFirst();
  }

  /** The citation markers of the facts, as summaries have them. */
  private static String markers(List<Fact> facts) {
    StringBuilder text = new StringBuilder();
    facts.forEach(f -> text.append(" [fact:").append(f.id()).append(']'));
    return text.toString();
  }

  /** What the other end of a line does with the data store or destination at this end. */
  static String use(C4Views.Neighbour neighbour) {
    if (!neighbour.incoming()) {
      return switch (neighbour.relationship()) {
        case Types.CONSUMES ->
            neighbour.via() == null
                ? "receives messages from it"
                : "receives messages from it through " + neighbour.via();
        case Types.ROUTES_TO -> "receives messages it routes";
        default -> "is reached from it";
      };
    }
    return switch (neighbour.relationship()) {
      case Types.CALLS_HTTP -> "calls it";
      case Types.SENDS_MAIL -> "sends mail through it";
      case Types.USES_IDENTITY_PROVIDER -> "uses it as its identity provider";
      case Types.PUBLISHES -> "sends messages to it";
      case Types.USES_DATABASE -> "connects to it";
      case Types.ACCESSES_DATA_OF -> "reads or writes tables of it";
      case Types.ROUTES_TO -> "routes messages to it";
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
      for (EntityId method : knowledge.parts(type, Types.METHOD)) {
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
        knowledge.withPredicate(Types.CLAIM).stream()
            .filter(
                f ->
                    f.subject().type().equals(Types.README)
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
      case Types.CALLS_HTTP -> "calls";
      case Types.SENDS_MAIL -> "sends mail through";
      case Types.USES_IDENTITY_PROVIDER -> "uses as its identity provider";
      case Types.PUBLISHES -> "sends messages to";
      case Types.CONSUMES -> "receives messages from";
      case Types.USES_DATABASE -> "connects to";
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
}
