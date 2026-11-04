package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.types.Types;
import io.lantern.harness.Calls;
import io.lantern.harness.CarryForward;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.ItemDropped;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.Outcome;
import io.lantern.harness.Rejected;
import io.lantern.harness.Repair;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.Checks;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.JsonSchema;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import io.lantern.llm.Tokens;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Summarizes units bottom-up: a unit's summary is written from its facts and the summaries of the
 * units it contains, so each level builds on the one below.
 *
 * <p>A type's prompt also shows its whole source file (see {@link Excerpts}), read through {@link
 * SourceFiles} and shown only when the file has the git blob of the fact's evidence (ADR 0091),
 * with its secrets masked or removed ({@link io.lantern.extract.config.SourceSecrets}) before it is
 * sent. A unit whose inputs do not fit the model's context window is summarized in parts that are
 * then merged (see {@link Prompt}). A module's or repository's prompt also shows its fact sheet
 * ({@link FactSheet}): the entry points, links, data stores and technology of its parts, cited like
 * its own facts.
 *
 * <p>Each summary says whether its purpose is evidenced or inferred ({@link Summary.Purpose}). The
 * model marks its answer; a mark of evidenced is kept only if the answer's first sentence is kept
 * and its first citation cites a README claim, a source file with Javadoc, or a contained summary
 * whose purpose is evidenced. Each summary also has a confidence ({@link Confidence}), from that
 * mark and the share of the answer's sentences kept.
 *
 * <p>Every unit is an item of the LLM harness (ADR 0100): its answer is cached under the item's
 * fingerprint, which hashes the role, the prompt version, the model (provider and identifier), the
 * facts its prompt shows (their claim IDs), the blobs of the files whose text it shows, the text of
 * its contained units' summaries as shown, its entity, level and contained units' entities, and its
 * whole prompt. Fact IDs of earlier commits and commits are not in the prompt, so a new commit that
 * changes nothing a unit's prompt shows makes no call for it, and a contained unit whose summary
 * reads the same leaves its parent's fingerprint as it was. A unit whose fingerprint is in the
 * cache is not sent to the model again; the stored summary is rebound to the current inputs, so it
 * cites the facts and contained summaries of this run (the same prompt lists the same inputs in the
 * same order). When a unit's prompt changes it is summarized again; its new text then changes its
 * parent's prompt, and so on up to the repository. Before any unit is summarized, the harness's
 * plan prints the role's estimate; a unit past the role's share of the run budget is not asked and
 * gets no summary.
 *
 * <p>Every summary must cite the facts or contained summaries it used, in every sentence. A
 * sentence of an answer that cites nothing is left out of it, and logged, without asking again; if
 * that leaves out the first sentence, the summary's purpose counts as inferred. An answer that
 * cites nothing, or cites a reference that was not in the prompt, is sent back once with the
 * reason; if the second answer is no better the unit gets no summary and nothing is stored for it.
 * Both rejections are logged with their reason and the rejected answer. The run goes on: the unit's
 * parent is summarized without it, as if the unit had nothing to summarize, and the result lists
 * the units rejected this way. Only if the first {@value #EARLY_STOP} units to finish are all
 * rejected, and none got a summary, does the run fail with {@link SummaryRejectedException}, since
 * then the model or its settings are likely wrong.
 *
 * <p>Units that do not contain each other are summarized in parallel, each on its own virtual
 * thread; a unit starts once the units it contains are done. At most as many units run at once as
 * the {@code SUMMARIZE} model may have calls in flight. The results do not depend on the order
 * calls finish in: each unit's prompt and key depend only on its own inputs, and the result lists
 * units in the order of a sequential walk (each root's units, children first, siblings in entity ID
 * order). If a unit fails, no further calls are made, the calls already running finish, and the
 * failure of the first failed unit in walk order is thrown; with several failures this may differ
 * from the one a sequential walk would have met first.
 *
 * <p>Not thread-safe; use one summarizer per run.
 */
public final class Summarizer {

  private static final Logger LOG = LoggerFactory.getLogger(Summarizer.class);

  /** How many units rejected in a row from the start of a run make it fail. */
  static final int EARLY_STOP = 10;

  /** How much of a rejected answer is logged. */
  private static final int LOGGED_ANSWER = 1000;

  /** Files read for excerpts that are kept for the next units, which are often in the same file. */
  private static final int CACHED_FILES = 64;

  /** A fact citation in a stored summary's text. */
  private static final Pattern FACT_MARKER = Pattern.compile("\\[fact:([0-9a-f]{64})\\]");

  /**
   * The role that summarizes packages, modules and repositories, as the harness runs it (ADR 0100):
   * on the high-volume tier (knowledge-build epic, section 11.0, Default models).
   */
  public static final RoleSpec ROLE =
      RoleSpec.single("summarize", Role.SUMMARIZE, Prompt.KEY, "summarize", RunMode.BULK, 300)
          .withThinking(640);

  /** The role that summarizes types, the most numerous units: on the bulk tier. */
  public static final RoleSpec TYPES =
      RoleSpec.single(
              "summarize-type", Role.SUMMARIZE_TYPES, Prompt.KEY, "summarize", RunMode.BULK, 300)
          .withThinking(300);

  /** The role that summarizes units of the level. */
  public static RoleSpec role(SummaryLevel level) {
    return level == SummaryLevel.TYPE ? TYPES : ROLE;
  }

  /** The tokens a repair turn may take: the rejections of up to six sentences. */
  private static final long REPAIR_TOKENS = 400;

  /** The tokens a contained unit's summary is expected to add to its parent's prompt. */
  private static final long CHILD_TOKENS = 120;

  private final Harness harness;
  private final SourceFiles sources;

  /**
   * A summarizer whose prompts show no source excerpts.
   *
   * @param harness the run's LLM harness
   */
  public Summarizer(Harness harness) {
    this(harness, SourceFiles.NONE);
  }

  /**
   * @param harness the run's LLM harness
   * @param sources where the files the facts were observed in are read from, for source excerpts
   */
  public Summarizer(Harness harness, SourceFiles sources) {
    this.harness = Objects.requireNonNull(harness, "harness");
    this.sources = Objects.requireNonNull(sources, "sources");
  }

  /**
   * What a run produced.
   *
   * @param summaries the summary of every unit that has one, by entity; a unit without facts and
   *     without summarized parts has none
   * @param generated how many summaries the model wrote in this run
   * @param reused how many came from the store
   * @param rejected the units that have no summary because the model's answer was rejected twice,
   *     in the order {@link #summaries} lists units in
   * @param notAsked the units that have no summary because the role's share of the run budget was
   *     spent, in the same order
   * @param omitted the units that have no summary because the model answered that their inputs show
   *     nothing to summarize, in the same order
   */
  public record Result(
      Map<EntityId, Summary> summaries,
      int generated,
      int reused,
      List<EntityId> rejected,
      List<EntityId> notAsked,
      List<EntityId> omitted) {
    public Result {
      summaries = Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
      rejected = List.copyOf(rejected);
      notAsked = List.copyOf(notAsked);
      omitted = List.copyOf(omitted);
    }

    /** A result with nothing omitted. */
    public Result(
        Map<EntityId, Summary> summaries,
        int generated,
        int reused,
        List<EntityId> rejected,
        List<EntityId> notAsked) {
      this(summaries, generated, reused, rejected, notAsked, List.of());
    }

    /** A result with nothing left unasked. */
    public Result(
        Map<EntityId, Summary> summaries, int generated, int reused, List<EntityId> rejected) {
      this(summaries, generated, reused, rejected, List.of());
    }
  }

  /**
   * Summarizes the given units and everything they contain, for example the trees from {@link
   * SummaryTree#fromFacts}.
   *
   * @throws SummaryRejectedException if the first {@value #EARLY_STOP} units to finish are all
   *     rejected twice
   * @throws io.lantern.llm.LlmException if a call fails or is refused, for example over budget
   */
  public Result summarize(Collection<SummaryNode> roots) {
    Run run = new Run(harness.session(TYPES), harness.session(ROLE));
    Map<EntityId, SummaryNode> units = new LinkedHashMap<>();
    for (SummaryNode root : roots) {
      collect(root, units, new HashSet<>());
    }
    run.plan(units.values());
    Map<EntityId, CompletableFuture<Optional<Summary>>> futures = new LinkedHashMap<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (SummaryNode node : units.values()) {
        List<CompletableFuture<Optional<Summary>>> parts =
            node.children().stream().map(child -> futures.get(child.entity())).toList();
        CompletableFuture<Optional<Summary>> unit =
            CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                .thenApplyAsync(done -> run.visit(node, parts), executor);
        // A unit whose part failed never runs: if it is the warm-up unit, the units waiting for it
        // go on, and stop at the failure.
        unit.whenComplete(
            (summary, failure) -> {
              if (failure != null) {
                run.neverRan(node);
                run.session(node).releaseWarmUp(node.entity().toString());
              }
            });
        futures.put(node.entity(), unit);
      }
      // Waits for every unit, failed or not, before the executor closes.
      CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
          .exceptionally(failure -> null)
          .join();
    }
    for (CompletableFuture<Optional<Summary>> future : futures.values()) {
      Throwable failure = failure(future);
      if (failure != null && !(failure instanceof Skipped)) {
        if (failure instanceof Error error) {
          throw error;
        }
        throw failure instanceof RuntimeException e ? e : new IllegalStateException(failure);
      }
    }
    Map<EntityId, Summary> summaries = new LinkedHashMap<>();
    futures.forEach((entity, future) -> future.join().ifPresent(s -> summaries.put(entity, s)));
    List<EntityId> rejected = units.keySet().stream().filter(run.rejected::contains).toList();
    List<EntityId> notAsked = units.keySet().stream().filter(run.notAsked::contains).toList();
    List<EntityId> omitted = units.keySet().stream().filter(run.omitted::contains).toList();
    return new Result(
        summaries, run.generated.get(), run.reused.get(), rejected, notAsked, omitted);
  }

  /** A sentence of a stored summary's text and the citation markers that end it. */
  private static final Pattern SENTENCE =
      Pattern.compile("(.+?)((?:\\s*\\[(?:fact|summary):[^\\]\\s]+\\])+)", Pattern.DOTALL);

  private static final Pattern ANY_MARKER = Pattern.compile("\\[(fact|summary):([^\\]\\s]+)\\]");

  /**
   * The run's summaries as claims of origin llm (knowledge-build epic, section 11.3, Stored): one
   * {@code (unit, summary, "<level>")} per unit, each sentence with the facts it cites, a contained
   * summary's citation resolved to the facts that summary cites, by the role of its level.
   *
   * @param facts the facts the units were summarized from, which the summaries cite
   * @param snapshot the snapshot they hold from
   */
  public static Map<Origin, List<Fact>> facts(
      Result result, Collection<Fact> facts, long snapshot) {
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(fact -> byId.putIfAbsent(fact.id(), fact));
    Map<String, List<Fact>> citedBy = new HashMap<>();
    Map<Origin, List<Fact>> out = new LinkedHashMap<>();
    // Parts come before the units that contain them, so a part's citations are resolved first.
    for (Summary summary : result.summaries().values()) {
      List<LlmFacts.Sentence> sentences = new ArrayList<>();
      List<Fact> all = new ArrayList<>();
      Matcher sentence = SENTENCE.matcher(summary.text());
      while (sentence.find()) {
        List<Fact> cites = new ArrayList<>();
        Matcher marker = ANY_MARKER.matcher(sentence.group(2));
        while (marker.find()) {
          if (marker.group(1).equals("fact")) {
            Optional.ofNullable(byId.get(marker.group(2))).ifPresent(cites::add);
          } else {
            cites.addAll(citedBy.getOrDefault(marker.group(2), List.of()));
          }
        }
        List<Fact> distinct = cites.stream().distinct().toList();
        all.addAll(distinct);
        sentences.add(new LlmFacts.Sentence(sentence.group(1).strip(), distinct));
      }
      citedBy.put(summary.entity().toString(), all.stream().distinct().toList());
      if (all.isEmpty()) {
        continue;
      }
      RoleSpec role = role(summary.level());
      out.computeIfAbsent(LlmFacts.origin(role), o -> new ArrayList<>())
          .add(
              LlmFacts.summary(
                  role,
                  summary.provider() + "/" + summary.model(),
                  // The key is the item's fingerprint in the answer cache (#753).
                  summary.key(),
                  summary.entity(),
                  summary.level().name().toLowerCase(Locale.ROOT),
                  sentences,
                  summary.purpose() == Summary.Purpose.EVIDENCED
                      ? LlmFacts.Basis.EVIDENCED
                      : LlmFacts.Basis.INFERRED,
                  summary.confidence(),
                  snapshot));
    }
    return out;
  }

  /**
   * Adds the unit and everything it contains to the units, children before parents. A unit met
   * again keeps the node it was first met as.
   */
  private static void collect(
      SummaryNode node, Map<EntityId, SummaryNode> units, Set<EntityId> visiting) {
    if (units.containsKey(node.entity())) {
      return;
    }
    if (!visiting.add(node.entity())) {
      throw new IllegalArgumentException("Units contain each other: " + node.entity());
    }
    for (SummaryNode child : node.children()) {
      collect(child, units, visiting);
    }
    visiting.remove(node.entity());
    units.put(node.entity(), node);
  }

  /** Why the unit failed, as it was thrown, or {@code null} if it did not. */
  private static Throwable failure(CompletableFuture<?> future) {
    if (!future.isCompletedExceptionally()) {
      return null;
    }
    try {
      future.join();
      throw new IllegalStateException("A failed unit has a result");
    } catch (CompletionException e) {
      return e.getCause() == null ? e : e.getCause();
    }
  }

  /** A unit not asked because its role's share of the run budget is spent. */
  private static final class NotAsked extends RuntimeException {
    NotAsked() {
      super("Not asked: the role's budget share is spent", null, false, false);
    }
  }

  private static String lowerFirst(String text) {
    return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
  }

  /** A unit not summarized because another one failed first. */
  private static final class Skipped extends RuntimeException {
    Skipped() {
      super("Not summarized: another unit failed", null, false, false);
    }
  }

  /**
   * A unit as an item of the harness (ADR 0100): the claims its prompt shows, the blobs of the
   * files it shows the text of, each contained unit's summary as the prompt shows it (a hash of its
   * text, so a part re-summarized to the same text leaves the unit's fingerprint as it was), its
   * level, entity and contained units' entities, and its whole prompt.
   */
  static Item item(SummaryNode node, List<Excerpts.Excerpt> excerpts, List<Summary> children) {
    List<FingerprintInput> inputs = new ArrayList<>();
    List<Fact> facts = promptFacts(node);
    facts.forEach(fact -> inputs.add(FingerprintInput.of(fact)));
    // The files whose text the prompt shows, by their blobs.
    for (Excerpts.Excerpt excerpt : excerpts) {
      facts.stream()
          .filter(fact -> fact.id().equals(excerpt.fact()))
          .findFirst()
          .flatMap(FingerprintInput::fileOf)
          .ifPresent(inputs::add);
    }
    StringBuilder names = new StringBuilder("level ").append(node.level());
    names.append("\nentity ").append(node.entity());
    List<Prompt.Item> shown = Prompt.children(children);
    for (int i = 0; i < children.size(); i++) {
      inputs.add(FingerprintInput.answer(Fingerprints.sha256(shown.get(i).text())));
      // The prompt names a contained unit without its full entity ID, which its citation stores.
      names.append("\nchild ").append(children.get(i).entity());
    }
    inputs.add(FingerprintInput.text(names.toString()));
    return new Item(
        node.entity().toString(),
        "",
        inputs,
        Prompt.SYSTEM + "\n" + Prompt.user(node, excerpts, children));
  }

  /** The fingerprint of a unit with the given excerpts and contained summaries, for the model. */
  static String key(
      SummaryNode node,
      List<Excerpts.Excerpt> excerpts,
      List<Summary> children,
      ModelConfig model) {
    return Fingerprints.of(role(node.level()), model, item(node, excerpts, children));
  }

  /**
   * A stored summary made from the same prompt, citing the facts and contained summaries of this
   * run instead of those it was made from. The same prompt lists the same inputs in the same order,
   * so each fact and contained summary takes the place of the one at its position.
   */
  static Summary rebind(Summary stored, SummaryNode node, List<Summary> children) {
    List<String> factIds = promptFacts(node).stream().map(Fact::id).toList();
    List<String> childKeys = children.stream().map(Summary::key).toList();
    if (stored.inputFacts().size() != factIds.size()
        || stored.inputSummaries().size() != childKeys.size()) {
      throw new IllegalStateException(
          "The stored summary "
              + stored.key()
              + " was not made from the inputs of "
              + node.entity());
    }
    Map<String, String> facts = new HashMap<>();
    for (int i = 0; i < factIds.size(); i++) {
      facts.putIfAbsent(stored.inputFacts().get(i), factIds.get(i));
    }
    Map<String, String> summaries = new HashMap<>();
    for (int i = 0; i < childKeys.size(); i++) {
      summaries.putIfAbsent(stored.inputSummaries().get(i), childKeys.get(i));
    }
    String text =
        FACT_MARKER
            .matcher(stored.text())
            .replaceAll(
                m ->
                    Matcher.quoteReplacement(
                        "[fact:" + facts.getOrDefault(m.group(1), m.group(1)) + "]"));
    return new Summary(
        stored.key(),
        stored.entity(),
        stored.level(),
        text,
        stored.purpose(),
        stored.confidence(),
        stored.citedFacts().stream().map(id -> facts.getOrDefault(id, id)).toList(),
        stored.citedSummaries().stream().map(key -> summaries.getOrDefault(key, key)).toList(),
        factIds,
        childKeys,
        stored.promptVersion(),
        stored.provider(),
        stored.model());
  }

  /**
   * The most sentences a unit's summary keeps, as its prompt asks: fewer for a package, module or
   * repository with a single part, which would otherwise restate it.
   */
  static int maxSentences(SummaryNode node, int children) {
    return children == 1 && node.level() != SummaryLevel.TYPE
        ? SummaryLevel.ONE_PART_SENTENCE_COUNT
        : node.level().maxSentences();
  }

  /** The facts a unit's prompt lists, its own and then its fact sheet's, {@code F1} first. */
  static List<Fact> promptFacts(SummaryNode node) {
    List<Fact> facts = new ArrayList<>(node.facts());
    facts.addAll(FactSheet.facts(FactSheet.of(node)));
    return facts;
  }

  /**
   * The most tokens, as {@link io.lantern.llm.Tokens} estimates them, that a unit's user turn may
   * take so that the request still fits the context window with a retry: the system prompt, the
   * output limit with the model's thinking headroom, a rejected answer sent back (up to that limit,
   * and its estimate may exceed it by half) and the retry message come on top.
   */
  static long promptBudget(SummaryNode node, ModelConfig model) {
    long output = node.level().maxOutputTokens() + (long) model.thinking().headroomTokens();
    return model.contextWindow()
        - Tokens.estimate(Prompt.SYSTEM)
        - output
        - output * 3 / 2
        - REPAIR_TOKENS
        - 100;
  }

  /**
   * Splits items into groups, keeping their order, so that each group's size plus the overhead
   * stays within the budget; an item larger than that on its own forms a group by itself.
   */
  static <T> List<List<T>> pack(List<T> items, ToLongFunction<T> size, long overhead, long budget) {
    List<List<T>> groups = new ArrayList<>();
    List<T> group = new ArrayList<>();
    long used = overhead;
    for (T item : items) {
      long itemSize = size.applyAsLong(item);
      if (!group.isEmpty() && used + itemSize > budget) {
        groups.add(group);
        group = new ArrayList<>();
        used = overhead;
      }
      group.add(item);
      used += itemSize;
    }
    if (!group.isEmpty()) {
      groups.add(group);
    }
    return groups;
  }

  private final class Run {
    private final RoleSession types;
    private final RoleSession others;
    private final Set<EntityId> notAsked = ConcurrentHashMap.newKeySet();
    // Each new summary's sentences and what they rest on, by item fingerprint, until stored.
    private final Map<String, String> carried = new ConcurrentHashMap<>();
    private final Set<EntityId> omitted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicInteger reused = new AtomicInteger();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Set<EntityId> rejected = ConcurrentHashMap.newKeySet();
    // The units the plan counted as items: each gets exactly one outcome in the role's record.
    private final Set<EntityId> planned = ConcurrentHashMap.newKeySet();
    // The units the plan expects to ask: only these wait for the warm-up unit. A unit answered
    // from the cache, or with nothing to summarize, may be a part of the warm-up unit, which
    // cannot start before its parts are done.
    private final Set<EntityId> toAsk = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepted = new AtomicBoolean();
    // The units that started: a planned unit that never did, since a part failed, is recorded as
    // not asked, so each planned unit still gets its outcome.
    private final Set<EntityId> started = ConcurrentHashMap.newKeySet();
    // Each role's units take slots of its own model's lane; a batched role's units wait for their
    // batch instead, so every unit whose parts are done joins the next batch.
    private final Map<RoleSession, Semaphore> running = new HashMap<>();
    private final Map<String, Optional<String>> files =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
            return size() > CACHED_FILES;
          }
        };

    Run(RoleSession types, RoleSession others) {
      this.types = types;
      this.others = others;
    }

    /** The session of the role that summarizes the unit. */
    RoleSession session(SummaryNode node) {
      return node.level() == SummaryLevel.TYPE ? types : others;
    }

    /**
     * Plans the run before any unit is asked (ADR 0100): units without parts are items whose
     * fingerprints are known now; a unit with parts is counted as cached when none of its parts'
     * subtrees needs asking, and otherwise as a miss whose prompt is estimated from its facts.
     */
    void plan(Collection<SummaryNode> units) {
      // In tree order, parts before the units that contain them: under a budget, the plan takes
      // the units that fit in this order (#657).
      Map<RoleSession, List<RoleSession.Planned>> inOrder = new LinkedHashMap<>();
      inOrder.put(types, new ArrayList<>());
      inOrder.put(others, new ArrayList<>());
      Map<EntityId, Boolean> missing = new HashMap<>();
      // The summaries the cache holds, so a unit whose parts are all cached is planned as the very
      // item it will be asked as, and its own cache entry checked.
      Map<EntityId, Summary> stored = new HashMap<>();
      for (SummaryNode node : units) {
        boolean childMissing =
            node.children().stream().anyMatch(child -> missing.getOrDefault(child.entity(), false));
        // A unit is planned only if it will have something to summarize: facts of its own, or a
        // part that is planned. One with neither is never asked and is no item.
        boolean anything =
            !node.facts().isEmpty()
                || node.children().stream().anyMatch(child -> planned.contains(child.entity()));
        if (!anything) {
          missing.put(node.entity(), false);
          continue;
        }
        planned.add(node.entity());
        // Under a budget, a unit is not asked while one of its parts waits for a later round: its
        // prompt would lack that part's summary (#661).
        List<String> parts =
            node.children().stream()
                .filter(child -> planned.contains(child.entity()))
                .map(child -> child.entity().toString())
                .toList();
        if (!childMissing) {
          // Its parts' summaries are all in the cache, as the ask will see them.
          List<Summary> children =
              node.children().stream()
                  .map(child -> stored.get(child.entity()))
                  .filter(java.util.Objects::nonNull)
                  .toList();
          Item item = Summarizer.item(node, Excerpts.of(node, this::text), children);
          RoleSession session = session(node);
          inOrder.get(session).add(RoleSession.Planned.of(item).after(parts));
          Optional<String> answer = session.cachedAnswer(item);
          answer.ifPresent(
              json -> stored.put(node.entity(), rebind(SummaryJson.read(json), node, children)));
          boolean miss = !session.cached(item);
          missing.put(node.entity(), miss);
          if (miss) {
            toAsk.add(node.entity());
          }
        } else {
          inOrder
              .get(session(node))
              .add(
                  RoleSession.Planned.predicted(
                          node.entity().toString(),
                          Tokens.estimate(Prompt.SYSTEM)
                              + Tokens.estimate(Prompt.user(node, List.of(), List.of()))
                              + CHILD_TOKENS * node.children().size())
                      .after(parts));
          missing.put(node.entity(), true);
          toAsk.add(node.entity());
        }
      }
      // Types first: the group's queue holds each role's items in the order the roles plan, and a
      // package rests on its types.
      inOrder.forEach((session, items) -> session.plan(items, 0, Tokens.estimate(Prompt.SYSTEM)));
      // Whether a role batches is known once it is planned. Roles on the same model share its
      // slots, so the model's limit holds across both.
      Map<String, Semaphore> lanes = new HashMap<>();
      for (RoleSession session : inOrder.keySet()) {
        ModelConfig model = session.model();
        running.put(
            session,
            session.batching()
                ? new Semaphore(Integer.MAX_VALUE, true)
                : lanes.computeIfAbsent(
                    model.provider() + '\u0000' + model.model(),
                    lane -> new Semaphore(model.limits().maxConcurrency(), true)));
      }
    }

    /**
     * Summarizes a unit whose parts are done, unless another unit has failed. A unit whose summary
     * is rejected twice gets none.
     */
    Optional<Summary> visit(SummaryNode node, List<CompletableFuture<Optional<Summary>>> parts) {
      started.add(node.entity());
      RoleSession session = session(node);
      Semaphore running = this.running.get(session);
      try {
        // The warm-up unit goes first; the others to be asked wait for it before taking a slot,
        // so they never hold the slot it needs.
        if (toAsk.contains(node.entity())) {
          // Under a budget, a unit of a later round waits for it before taking a slot too.
          session.awaitTurn(node.entity().toString());
          session.awaitWarmUp(node.entity().toString());
        }
        running.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        failed.set(true);
        session.releaseWarmUp(node.entity().toString());
        throw new IllegalStateException("Interrupted while summarizing " + node.entity(), e);
      } catch (RuntimeException e) {
        failed.set(true);
        session.releaseWarmUp(node.entity().toString());
        throw e;
      }
      try {
        stopIfFailed();
        List<Summary> children = new ArrayList<>();
        for (CompletableFuture<Optional<Summary>> part : parts) {
          part.join().ifPresent(children::add);
        }
        if (node.facts().isEmpty() && children.isEmpty()) {
          if (planned.contains(node.entity())) {
            // It was planned for its parts' summaries, and none came. If a part was not asked under
            // the budget, neither is it; if its parts were omitted or rejected, nothing is left to
            // summarize, which is an omission, not a unit the budget left out.
            boolean budget =
                node.children().stream().anyMatch(child -> notAsked.contains(child.entity()));
            (budget ? notAsked : omitted).add(node.entity());
            session.notAsked(
                node.entity().toString(),
                budget ? "none of its parts was summarized" : "none of its parts has a summary");
          }
          return Optional.empty();
        }
        try {
          Optional<Summary> summary = summary(node, children);
          accepted.set(true);
          return summary;
        } catch (NotAsked e) {
          notAsked.add(node.entity());
          return Optional.empty();
        } catch (SummaryRejectedException e) {
          rejected.add(node.entity());
          if (!accepted.get() && rejected.size() >= EARLY_STOP) {
            throw new SummaryRejectedException(
                "The first " + EARLY_STOP + " units were all rejected. " + e.getMessage());
          }
          return Optional.empty();
        }
      } catch (Skipped e) {
        if (planned.contains(node.entity())) {
          session.notAsked(node.entity().toString(), "another unit failed first");
        }
        throw e;
      } catch (RuntimeException | Error e) {
        failed.set(true);
        throw e;
      } finally {
        running.release();
        // Whatever ended it, the units waiting for the warm-up unit go on.
        session.releaseWarmUp(node.entity().toString());
      }
    }

    /** Records a planned unit that never started, because one of its parts failed. */
    void neverRan(SummaryNode node) {
      if (planned.contains(node.entity()) && !started.contains(node.entity())) {
        session(node).notAsked(node.entity().toString(), "another unit failed first");
      }
    }

    private void stopIfFailed() {
      if (failed.get()) {
        throw new Skipped();
      }
    }

    /** The unit's summary; empty if the model answered that its inputs show nothing. */
    private Optional<Summary> summary(SummaryNode node, List<Summary> children) {
      RoleSession session = session(node);
      List<Excerpts.Excerpt> excerpts = Excerpts.of(node, this::text);
      Item item = item(node, excerpts, children);
      String key = session.fingerprint(item);
      Outcome outcome =
          session.ask(
              item,
              calls ->
                  generate(calls, key, node, excerpts, children)
                      .map(
                          summary ->
                              io.lantern.harness.Answer.of(
                                  SummaryJson.write(summary, carried.remove(key))))
                      .orElseGet(io.lantern.harness.Answer::omitted));
      return switch (outcome.kind()) {
        case CACHED -> {
          reused.incrementAndGet();
          if (outcome.answer() == null) {
            omitted.add(node.entity());
            yield Optional.empty();
          }
          yield Optional.of(rebind(SummaryJson.read(outcome.answer()), node, children));
        }
        case ANSWERED -> {
          generated.incrementAndGet();
          yield Optional.of(SummaryJson.read(outcome.answer()));
        }
        case OMITTED -> {
          generated.incrementAndGet();
          // The run record counts the summary left out (#741).
          session.fieldLeftOut("summary", false);
          omitted.add(node.entity());
          LOG.info("The model found nothing to summarize in {}", node.entity());
          yield Optional.empty();
        }
        case DROPPED -> {
          // Counted when the model dropped it, not again when the cache repeats the drop, as a
          // packet role counts its fields only from a fresh answer.
          if (!outcome.repeatedDrop()) {
            session.fieldLeftOut("summary", true);
          }
          throw new SummaryRejectedException(
              "The summary of " + node.entity() + " was " + lowerFirst(outcome.reason()));
        }
        case NOT_ASKED -> throw new NotAsked();
        case FAILED -> throw new LlmException(outcome.reason());
      };
    }

    /**
     * The text of the file of a fact's evidence, if it can be read and is the version observed,
     * with its secrets masked or removed. Units run in parallel, so the cache is locked, but not
     * while the file is read.
     */
    private Optional<String> text(Evidence evidence) {
      if (evidence.path() == null || evidence.blob() == null) {
        return Optional.empty();
      }
      String file = evidence.repo() + '\u0000' + evidence.blob() + '\u0000' + evidence.path();
      synchronized (files) {
        Optional<String> known = files.get(file);
        if (known != null) {
          return known;
        }
      }
      Optional<String> read = sources.shown(evidence);
      // The injection screen reads every file a prompt showed (ADR 0101).
      read.ifPresent(
          text -> harness.showed(evidence.repo(), evidence.path(), evidence.blob(), text));
      synchronized (files) {
        files.put(file, read);
      }
      return read;
    }

    /**
     * The unit's summary, from one request or, when its inputs do not fit one, from its parts
     * merged; empty if the model answered that its inputs show nothing to summarize.
     */
    private Optional<Summary> generate(
        Calls calls,
        String key,
        SummaryNode node,
        List<Excerpts.Excerpt> excerpts,
        List<Summary> children)
        throws ItemDropped {
      Inputs inputs = new Inputs(node, excerpts, children);
      String purpose =
          "summarize " + node.level().name().toLowerCase(Locale.ROOT) + " " + node.entity();
      long budget = promptBudget(node, session(node).model());
      String whole =
          Prompt.user(node, inputs.facts, inputs.sheet, inputs.excerpts, inputs.children, null);
      int sentences = maxSentences(node, children.size());
      Answer answer;
      List<List<Prompt.Item>> parts =
          Tokens.estimate(whole) <= budget ? List.of() : inputs.parts(budget);
      if (parts.size() < 2) {
        // A summary asked again keeps each sentence whose cited inputs are unchanged (#722).
        answer =
            answer(
                calls,
                purpose,
                node,
                whole,
                inputs,
                null,
                sentences,
                session(node).earlier(node.entity().toString()).orElse(Map.of()));
      } else {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
          List<Prompt.Item> part = parts.get(i);
          String prompt =
              Prompt.user(
                  node,
                  inputs.only(inputs.facts, part),
                  inputs.only(inputs.sheet, part),
                  inputs.only(inputs.excerpts, part),
                  inputs.only(inputs.children, part),
                  new Prompt.Part(i + 1, parts.size()));
          Set<String> shown = new HashSet<>();
          part.forEach(item -> shown.add(item.ref()));
          String label = " (part " + (i + 1) + " of " + parts.size() + ")";
          Answer partAnswer =
              answer(
                  calls,
                  purpose + label,
                  node,
                  prompt,
                  inputs,
                  shown,
                  SummaryLevel.ONE_PART_SENTENCE_COUNT);
          if (!partAnswer.omitted()) {
            texts.add(partAnswer.text());
          }
        }
        answer =
            texts.isEmpty() ? Answer.OMITTED : merge(calls, purpose, node, inputs, texts, budget);
      }
      if (answer.omitted()) {
        return Optional.empty();
      }
      if (parts.size() < 2 && answer.sentences() != null) {
        carried.put(key, answer.sentences());
      }
      Citations.Checked checked = answer.checked();
      ModelConfig model = session(node).model();
      return Optional.of(
          new Summary(
              key,
              node.entity(),
              node.level(),
              checked.text(),
              answer.purpose(),
              answer.confidence(),
              checked.facts(),
              checked.summaries(),
              inputs.factIds,
              children.stream().map(Summary::key).toList(),
              Prompt.KEY,
              model.provider(),
              model.model()));
    }

    /**
     * Merges the parts' summaries into one; if they do not fit one prompt, merges them in groups
     * first, and so on.
     */
    private Answer merge(
        Calls calls,
        String purpose,
        SummaryNode node,
        Inputs inputs,
        List<String> texts,
        long budget)
        throws ItemDropped {
      int sentences = node.level().maxSentences();
      for (int round = 1; ; round++) {
        String prompt = Prompt.merge(node, texts);
        List<List<String>> groups =
            Tokens.estimate(prompt) <= budget
                ? List.of(texts)
                : pack(
                    texts,
                    text -> Tokens.estimate(text) + 10,
                    Tokens.estimate(Prompt.merge(node, List.of())),
                    budget);
        if (groups.size() == 1 || groups.size() == texts.size()) {
          Answer merged =
              answer(calls, purpose + " (merge)", node, prompt, inputs, cited(texts), sentences);
          return merged;
        }
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
          String label = " (merge " + round + "." + (i + 1) + " of " + groups.size() + ")";
          List<String> group = groups.get(i);
          Answer answer =
              answer(
                  calls,
                  purpose + label,
                  node,
                  Prompt.merge(node, group),
                  inputs,
                  cited(group),
                  sentences);
          if (!answer.omitted()) {
            merged.add(answer.text());
          }
        }
        if (merged.isEmpty()) {
          return Answer.OMITTED;
        }
        texts = merged;
      }
    }

    private static Set<String> cited(List<String> texts) {
      Set<String> refs = new HashSet<>();
      texts.forEach(text -> refs.addAll(Citations.references(text)));
      return refs;
    }

    /**
     * Asks the model and checks its answer sentence by sentence: a sentence the checks reject is
     * left out; an answer the schema rejects, or one left with no sentence, goes back once with
     * every reason.
     *
     * @param allowed the references the prompt shows, or {@code null} for all of the unit's
     * @param sentences the most sentences kept, as the prompt asks
     * @throws ItemDropped if the second answer is rejected too, which {@link #visit} turns into a
     *     unit without a summary
     */
    private Answer answer(
        Calls calls,
        String purpose,
        SummaryNode node,
        String prompt,
        Inputs inputs,
        Set<String> allowed,
        int sentences)
        throws ItemDropped {
      return answer(calls, purpose, node, prompt, inputs, allowed, sentences, Map.of());
    }

    /**
     * As {@link #answer(Calls, String, SummaryNode, String, Inputs, Set, int)}, keeping each of the
     * earlier sentences whose cited inputs are all still shown (#722, ADR 0126).
     *
     * @param earlier the sentences of the summary stored last for the unit, by field
     */
    private Answer answer(
        Calls calls,
        String purpose,
        SummaryNode node,
        String prompt,
        Inputs inputs,
        Set<String> allowed,
        int sentences,
        Map<String, CarryForward.Earlier> earlier)
        throws ItemDropped {
      stopIfFailed();
      LlmRequest request =
          Prompt.request(
              role(node.level()).modelRole(), purpose, prompt, node.level().maxOutputTokens());
      // The first answer is sent back if it lost its first sentence; the retry is taken as it is.
      boolean[] retry = {false};
      return calls.answer(
          request,
          response -> {
            stopIfFailed();
            boolean first = !retry[0];
            retry[0] = true;
            return answer(purpose, response, inputs, allowed, sentences, first, earlier);
          },
          Repair.withMessage(rejected -> rejected.reasons().getFirst()));
    }

    /** A model's text on one line, cut to its first {@value #LOGGED_ANSWER} characters. */
    private static String logged(String text) {
      if (text.length() > LOGGED_ANSWER) {
        text = text.substring(0, LOGGED_ANSWER) + "... (" + text.length() + " characters)";
      }
      return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * The answer's sentences that pass the checks, joined in the form {@link Citations} reads: each
     * sentence followed by the references it cites. Its purpose counts as evidenced only if its
     * first sentence is kept and cites what people wrote.
     *
     * @param first whether this is the first answer, not the one to a repair turn
     * @throws Rejected with the repair turn, if the answer is not one object in the schema, no
     *     sentence passes, or, in the first answer, the first sentence does not pass while others
     *     do: what is left is a fragment, which a description must never show (#275)
     */
    private static Answer answer(
        String purpose,
        LlmResponse response,
        Inputs inputs,
        Set<String> allowed,
        int most,
        boolean first,
        Map<String, CarryForward.Earlier> earlier)
        throws Rejected {
      if (response.cutOff()) {
        throw rejected("", "It was cut off at the length limit; write shorter sentences.");
      }
      JsonNode entry = entry(response.text());
      if (entry == null) {
        String fault = io.lantern.harness.JsonFaults.of(response.text(), Prompt.ITEM);
        throw rejected(
            "",
            "Your answer is not one JSON object with an entry for "
                + Prompt.ITEM
                + " under \"items\""
                + (fault == null ? "." : ": " + fault + "."));
      }
      CheckInputs checks = inputs.checks(allowed);
      // A sentence outside the schema, such as one that cites nothing, or past the count the item
      // asks for, is left out as a sentence the checks reject is; the rest must fit the schema.
      List<Rejection> dropped = new ArrayList<>();
      List<Integer> positions = new ArrayList<>();
      // Sentences past the count the item asks for: left out, but nothing wrong was written.
      int beyond = 0;
      JsonNode written = entry.get("sentences");
      int total = written != null && written.isArray() ? written.size() : 0;
      if (total > 0 && entry.isObject()) {
        ArrayNode fitting = JSON.createArrayNode();
        for (int i = 0; i < total; i++) {
          JsonNode sentence = written.get(i);
          String field = "sentences." + (i + 1);
          List<String> violations = JsonSchema.violations(SENTENCE_SCHEMA, sentence);
          if (!violations.isEmpty()) {
            JsonNode cites = sentence.path("cites");
            String reason =
                cites.isArray() && cites.isEmpty() && violations.size() == 1
                    ? "Cites nothing; every sentence cites the references it rests on."
                    : "Does not match the schema: " + String.join("; ", violations) + ".";
            dropped.add(new Rejection(Prompt.ITEM, field, "Schema", reason));
          } else if (fitting.size() >= most) {
            beyond++;
            dropped.add(
                new Rejection(
                    Prompt.ITEM,
                    field,
                    "Form",
                    "The item asks for at most " + most + " sentences."));
          } else {
            fitting.add(sentence);
            positions.add(i);
          }
        }
        ObjectNode copy = (ObjectNode) entry.deepCopy();
        copy.set("sentences", fitting);
        entry = copy;
      }
      List<Rejection> schema =
          new Checks.Schema()
              .check(new ItemAnswer(Prompt.ITEM, entry, List.of(), List.of()), checks);
      if (!schema.isEmpty()) {
        // With every sentence left out, the reasons they were are what the model must correct.
        boolean noneLeft = !dropped.isEmpty() && positions.isEmpty();
        List<Rejection> all = new ArrayList<>(dropped);
        if (!noneLeft) {
          all.addAll(schema);
        }
        throw new Rejected(Pipeline.repairTurn(all));
      }
      if (new ItemAnswer(Prompt.ITEM, entry, List.of(), List.of()).omitted()) {
        return Answer.OMITTED;
      }
      List<String> kept = new ArrayList<>();
      // The kept sentences as the checks saw them, beside the texts in kept.
      List<CheckedText> keptTexts = new ArrayList<>();
      boolean firstKept = false;
      Set<String> firstCites = Set.of();
      JsonNode sentences = entry.get("sentences");
      for (int n = 0; n < sentences.size(); n++) {
        JsonNode sentence = sentences.get(n);
        int i = positions.get(n);
        String field = "sentences." + (i + 1);
        String text = Citations.withoutCitations(sentence.get("text").asString()).strip();
        // A range such as F1-F3 cites each reference in it; the text keeps it as written.
        List<String> asWritten = new ArrayList<>();
        // A cite written in brackets, such as "[F1]", is the reference inside them.
        sentence
            .get("cites")
            .forEach(cite -> asWritten.add(cite.asString().replaceAll("[\\[\\]]", "").strip()));
        List<String> cites = new ArrayList<>();
        for (String cite : asWritten) {
          Set<String> expanded = Citations.references("[" + cite + "]");
          // What is no reference at all is checked as written, and rejected.
          cites.addAll(expanded.isEmpty() ? Set.of(cite) : expanded);
        }
        CheckedText checked =
            new CheckedText(
                field,
                // The first sentence is what a part without a responsibility shows alone.
                i == 0 ? CheckedText.Kind.LEAD : CheckedText.Kind.PROSE,
                text,
                cites,
                Prompt.SENTENCE_WORDS,
                inputs.subject,
                Set.of());
        List<Rejection> found =
            SENTENCE_CHECKS.check(
                new ItemAnswer(Prompt.ITEM, entry, List.of(checked), List.of()), checks);
        if (text.isEmpty()) {
          found = List.of(new Rejection(Prompt.ITEM, field, "Form", "The sentence is empty."));
        }
        if (!found.isEmpty()) {
          dropped.addAll(found);
          continue;
        }
        if (i == 0) {
          firstKept = true;
          firstCites = new HashSet<>(cites);
        }
        kept.add(text + " [" + String.join(", ", asWritten) + "]");
        keptTexts.add(checked);
      }
      // The sentences the checks left out of this answer, before earlier ones are kept.
      int checkedOut = total - beyond - keptTexts.size();
      List<CheckedText> carried =
          CarryForward.sentences(keptTexts, earlier, checks.references(), "sentences", most);
      if (carried != keptTexts) {
        // An earlier sentence passes the checks again, as what it now is in the summary, against
        // what the item shows now; one that fails is left out.
        List<CheckedText> passing = new ArrayList<>();
        for (CheckedText t : carried) {
          boolean thisTime =
              keptTexts.stream()
                  .anyMatch(k -> k.text().equals(t.text()) && k.cites().equals(t.cites()));
          CheckedText placed =
              new CheckedText(
                  t.field(),
                  passing.isEmpty() ? CheckedText.Kind.LEAD : CheckedText.Kind.PROSE,
                  t.text(),
                  t.cites(),
                  t.words(),
                  t.subjectName(),
                  t.names());
          if (thisTime
              || SENTENCE_CHECKS
                  .check(new ItemAnswer(Prompt.ITEM, entry, List.of(placed), List.of()), checks)
                  .isEmpty()) {
            passing.add(t);
          }
        }
        if (!passing.isEmpty()) {
          // The summary may lead only with the earlier first sentence or the model's own first;
          // any other goes through the lead rules below, as a first sentence left out does.
          CarryForward.Earlier earlierLead = earlier.get("sentences.1");
          String lead = passing.getFirst().text();
          boolean leads =
              (earlierLead != null && lead.equals(earlierLead.text()))
                  || (firstKept && lead.equals(keptTexts.getFirst().text()));
          kept.clear();
          passing.forEach(t -> kept.add(t.text() + " [" + String.join(", ", t.cites()) + "]"));
          keptTexts = new ArrayList<>(passing);
          firstKept = leads;
          firstCites = leads ? new HashSet<>(passing.getFirst().cites()) : Set.of();
        }
      }
      if (kept.isEmpty()) {
        throw new Rejected(Pipeline.repairTurn(dropped));
      }
      if (first && !firstKept) {
        List<Rejection> lead = new ArrayList<>(dropped);
        lead.add(
            new Rejection(
                Prompt.ITEM,
                "sentences.1",
                "Form",
                "The first sentence was left out, so the others would stand without it; write the"
                    + " whole summary again, opening with what the unit is for."));
        throw new Rejected(Pipeline.repairTurn(lead));
      }
      if (!firstKept) {
        // A retried answer that lost its first sentence leads with one written to follow it, which
        // was checked as such: it may lead only if it may be shown alone (#275).
        while (!kept.isEmpty() && Checks.Generic.weakOpening(kept.getFirst())) {
          checkedOut++;
          dropped.add(
              new Rejection(
                  Prompt.ITEM,
                  "sentences",
                  "Generic",
                  "\""
                      + removeFirst(kept, keptTexts)
                      + "\" would lead the summary, but opens with a pronoun or its kind."));
        }
        if (kept.isEmpty()) {
          throw new Rejected(Pipeline.repairTurn(dropped));
        }
      }
      if (!dropped.isEmpty()) {
        LOG.info(
            "Left out {} sentences from the answer to {}: {}",
            total - kept.size(),
            purpose,
            logged(
                String.join(
                    " | ", dropped.stream().map(r -> r.field() + ": " + r.reason()).toList())));
      }
      String text = String.join(" ", kept);
      Citations.Checked checked;
      try {
        checked =
            Citations.check(text, inputs.factIds, inputs.excerptFacts, inputs.summaries, allowed);
      } catch (Citations.Rejected e) {
        throw rejected("", "Your answer " + e.getMessage());
      }
      boolean evidenced = firstKept && firstCites.stream().anyMatch(inputs.evidence::contains);
      return new Answer(
          text,
          checked,
          evidenced ? Summary.Purpose.EVIDENCED : Summary.Purpose.INFERRED,
          Confidence.of(evidenced, kept.size(), checkedOut),
          CarryForward.record(numbered(keptTexts), checks.references()));
    }

    /** The first kept sentence, taken from both lists. */
    private static String removeFirst(List<String> kept, List<CheckedText> keptTexts) {
      keptTexts.removeFirst();
      return kept.removeFirst();
    }

    /** The sentences numbered as stored: {@code sentences.1}, ... */
    private static List<CheckedText> numbered(List<CheckedText> texts) {
      List<CheckedText> numbered = new ArrayList<>();
      for (int i = 0; i < texts.size(); i++) {
        CheckedText t = texts.get(i);
        numbered.add(
            new CheckedText(
                "sentences." + (i + 1),
                t.kind(),
                t.text(),
                t.cites(),
                t.words(),
                t.subjectName(),
                t.names()));
      }
      return numbered;
    }

    /** A rejection of the whole answer, as the repair turn sends it. */
    private static Rejected rejected(String field, String reason) {
      return new Rejected(
          Pipeline.repairTurn(List.of(new Rejection(Prompt.ITEM, field, "Schema", reason))));
    }

    /** The item's entry of an answer envelope, or {@code null} if the answer has none. */
    private static JsonNode entry(String text) {
      // The first complete object; text after it is logged and left out (#744).
      io.lantern.harness.JsonFaults.First first = io.lantern.harness.JsonFaults.first(text);
      JsonNode root;
      if (first != null) {
        root = first.object();
        if (!first.rest().isEmpty()) {
          LOG.info("Left out the text after the answer's JSON object: {}", logged(first.rest()));
        }
      } else {
        try {
          root = JSON.readTree(stripFence(text));
        } catch (RuntimeException e) {
          return null;
        }
      }
      if (root == null || !root.isObject()) {
        return null;
      }
      JsonNode entry = root.path("items").get(Prompt.ITEM);
      return entry == null || entry.isNull() ? null : entry;
    }

    /** The answer without a Markdown code fence around it. */
    private static String stripFence(String text) {
      String stripped = text == null ? "" : text.strip();
      if (stripped.startsWith("```")) {
        int start = stripped.indexOf('\n');
        int end = stripped.lastIndexOf("```");
        if (start > 0 && end > start) {
          return stripped.substring(start + 1, end);
        }
      }
      return stripped;
    }
  }

  /** The checks each sentence passes: every check but the schema's, which the whole answer does. */
  private static final Pipeline SENTENCE_CHECKS =
      new Pipeline(Checks.STANDARD.stream().filter(c -> !(c instanceof Checks.Schema)).toList());

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The schema of one item's answer, as the epic writes it. */
  private static final JsonNode ITEM_SCHEMA = JsonSchema.itemSchema(JSON.readTree(Prompt.SCHEMA));

  /** The schema of one sentence of an item's answer. */
  private static final JsonNode SENTENCE_SCHEMA = ITEM_SCHEMA.at("/properties/sentences/items");

  /**
   * A checked answer.
   *
   * @param text the kept sentences, each followed by the references it cites, as the model wrote
   *     them; {@code null} if the model answered that its inputs show nothing
   * @param checked the answer with references rewritten to stable IDs
   * @param purpose where its first sentence takes the unit's purpose from, as checked
   * @param confidence how far it can be trusted, from its purpose and the share of its sentences
   *     kept ({@link Confidence})
   */
  private record Answer(
      String text,
      Citations.Checked checked,
      Summary.Purpose purpose,
      double confidence,
      String sentences) {

    static final Answer OMITTED = new Answer(null, null, Summary.Purpose.INFERRED, 0, null);

    boolean omitted() {
      return text == null;
    }
  }

  /** A unit's inputs as prompt items, with what their references stand for. */
  private static final class Inputs {
    final List<String> factIds;
    final List<String> excerptFacts;
    final List<Summary> summaries;
    final List<Prompt.Item> facts;
    final List<Prompt.Item> sheet;
    final List<Prompt.Item> excerpts;
    final List<Prompt.Item> children;

    /**
     * The references that can show what a unit is for, as people wrote it: README claims, source
     * files with Javadoc, and parts' summaries whose purpose is evidenced.
     */
    final Set<String> evidence = new HashSet<>();

    /** What each reference stands for, as the prompt writes it. */
    private final Map<String, String> texts = new HashMap<>();

    /** The unit as the prompt names it, which a sentence may name without citing it. */
    final String subject;

    private final SummaryNode node;

    Inputs(SummaryNode node, List<Excerpts.Excerpt> excerpts, List<Summary> children) {
      this.node = node;
      List<FactSheet.Entry> sheet = FactSheet.of(node);
      this.factIds = promptFacts(node).stream().map(Fact::id).toList();
      this.excerptFacts = excerpts.stream().map(Excerpts.Excerpt::fact).toList();
      this.summaries = children;
      this.facts = Prompt.facts(node.facts());
      this.sheet = Prompt.sheet(sheet, node.facts().size());
      this.excerpts = Prompt.excerpts(excerpts);
      this.children = Prompt.children(children);
      this.subject = Prompt.describe(node.level(), node.entity());
      for (List<Prompt.Item> items :
          List.of(this.facts, this.sheet, this.excerpts, this.children)) {
        items.forEach(item -> texts.put(item.ref(), item.text()));
      }
      List<Fact> facts = promptFacts(node);
      for (int i = 0; i < facts.size(); i++) {
        if (facts.get(i).predicate().equals(Types.CLAIM)) {
          evidence.add("F" + (i + 1));
        }
      }
      for (int i = 0; i < excerpts.size(); i++) {
        if (excerpts.get(i).code().contains("/**")) {
          evidence.add("E" + (i + 1));
        }
      }
      for (int i = 0; i < children.size(); i++) {
        if (children.get(i).purpose() == Summary.Purpose.EVIDENCED) {
          evidence.add("S" + (i + 1));
        }
      }
    }

    /**
     * What the checks compare a sentence with: the references the request showed, or all of the
     * unit's.
     */
    CheckInputs checks(Set<String> allowed) {
      Map<String, String> references = new HashMap<>(texts);
      if (allowed != null) {
        references.keySet().retainAll(allowed);
      }
      return new CheckInputs(
          ITEM_SCHEMA,
          references,
          Set.of('F', 'E', 'S'),
          false,
          (repo, path) -> Optional.empty(),
          Map.of(),
          Set.of());
    }

    /** All items split into parts that each fit the budget with a part prompt's own text. */
    List<List<Prompt.Item>> parts(long budget) {
      List<Prompt.Item> all = new ArrayList<>(facts);
      all.addAll(sheet);
      all.addAll(excerpts);
      all.addAll(children);
      // The headings of all sections and of the fact sheet's groups and the longest part number,
      // written once.
      List<Prompt.Item> headings =
          sheet.stream()
              .map(Prompt.Item::heading)
              .distinct()
              .map(heading -> new Prompt.Item("F1", "", heading))
              .toList();
      long overhead =
          Tokens.estimate(
              Prompt.user(
                  node,
                  facts.isEmpty() ? List.of() : List.of(new Prompt.Item("F1", "")),
                  headings,
                  excerpts.isEmpty() ? List.of() : List.of(new Prompt.Item("E1", "")),
                  children.isEmpty() ? List.of() : List.of(new Prompt.Item("S1", "")),
                  new Prompt.Part(all.size(), all.size())));
      return pack(all, item -> Tokens.estimate(item.rendered()) + 1, overhead, budget);
    }

    /** The items of a kind that are in the part. */
    List<Prompt.Item> only(List<Prompt.Item> kind, List<Prompt.Item> part) {
      Set<Prompt.Item> in = new HashSet<>(part);
      return kind.stream().filter(in::contains).toList();
    }
  }
}
