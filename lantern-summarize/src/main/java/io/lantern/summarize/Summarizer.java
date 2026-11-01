package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.harness.Calls;
import io.lantern.harness.FingerprintInput;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Harness;
import io.lantern.harness.Item;
import io.lantern.harness.ItemDropped;
import io.lantern.harness.Outcome;
import io.lantern.harness.Rejected;
import io.lantern.harness.Repair;
import io.lantern.harness.RoleSession;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.RunMode;
import io.lantern.harness.verify.Grounding;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
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
import java.util.LinkedHashSet;
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
 * facts its prompt shows (their observation IDs, which change with their file's blob), its
 * contained units' summaries (by their fingerprints), its entity, level and contained units'
 * entities, and its whole prompt. Fact IDs of earlier commits and commits are not in the prompt, so
 * a new commit that changes nothing a unit's prompt shows makes no call for it, and a contained
 * unit whose summary reads the same leaves its parent's fingerprint as it was. A unit whose
 * fingerprint is in the cache is not sent to the model again; the stored summary is rebound to the
 * current inputs, so it cites the facts and contained summaries of this run (the same prompt lists
 * the same inputs in the same order). When a unit's prompt changes it is summarized again; its new
 * text then changes its parent's prompt, and so on up to the repository. Before any unit is
 * summarized, the harness's plan prints the role's estimate; a unit past the role's share of the
 * run budget is not asked and gets no summary.
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

  /** The role as the harness runs it (ADR 0100). */
  public static final RoleSpec ROLE =
      RoleSpec.single("summarize", Role.SUMMARIZE, Prompt.VERSION, "summarize", RunMode.BULK, 300);

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
   */
  public record Result(
      Map<EntityId, Summary> summaries,
      int generated,
      int reused,
      List<EntityId> rejected,
      List<EntityId> notAsked) {
    public Result {
      summaries = Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
      rejected = List.copyOf(rejected);
      notAsked = List.copyOf(notAsked);
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
    RoleSession session = harness.session(ROLE);
    ModelConfig model = session.model();
    Run run = new Run(session, model.limits().maxConcurrency());
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
        futures.put(
            node.entity(),
            CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                .thenApplyAsync(done -> run.visit(node, parts), executor));
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
    return new Result(summaries, run.generated.get(), run.reused.get(), rejected, notAsked);
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
   * files it shows the text of, each contained unit's summary as the prompt shows it (its
   * observation hash, so a part re-summarized to the same text leaves the unit's fingerprint as it
   * was), its level, entity and contained units' entities, and its whole prompt.
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
    return Fingerprints.of(ROLE, model, item(node, excerpts, children));
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
        - Tokens.estimate(Prompt.RETRY)
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
    private final RoleSession session;
    private final ModelConfig model;
    private final Set<EntityId> notAsked = ConcurrentHashMap.newKeySet();
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicInteger reused = new AtomicInteger();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Set<EntityId> rejected = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepted = new AtomicBoolean();
    private final Semaphore running;
    private final Map<String, Optional<String>> files =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
            return size() > CACHED_FILES;
          }
        };

    Run(RoleSession session, int maxRunning) {
      this.session = session;
      this.model = session.model();
      this.running = new Semaphore(maxRunning, true);
    }

    /**
     * Plans the run before any unit is asked (ADR 0100): units without parts are items whose
     * fingerprints are known now; a unit with parts is counted as cached when none of its parts'
     * subtrees needs asking, and otherwise as a miss whose prompt is estimated from its facts.
     */
    void plan(Collection<SummaryNode> units) {
      List<Item> known = new ArrayList<>();
      Map<EntityId, Boolean> missing = new HashMap<>();
      int hits = 0;
      int misses = 0;
      long tokens = 0;
      for (SummaryNode node : units) {
        boolean childMissing =
            node.children().stream().anyMatch(child -> missing.getOrDefault(child.entity(), false));
        if (node.children().isEmpty()) {
          if (node.facts().isEmpty()) {
            missing.put(node.entity(), false);
            continue;
          }
          Item item = Summarizer.item(node, Excerpts.of(node, this::text), List.of());
          known.add(item);
          missing.put(node.entity(), !session.cached(item));
        } else if (childMissing) {
          misses++;
          tokens +=
              Tokens.estimate(Prompt.SYSTEM)
                  + Tokens.estimate(Prompt.user(node, List.of(), List.of()))
                  + CHILD_TOKENS * node.children().size();
          missing.put(node.entity(), true);
        } else {
          hits++;
          missing.put(node.entity(), false);
        }
      }
      session.plan(known, hits, misses, tokens, Tokens.estimate(Prompt.SYSTEM));
    }

    /**
     * Summarizes a unit whose parts are done, unless another unit has failed. A unit whose summary
     * is rejected twice gets none.
     */
    Optional<Summary> visit(SummaryNode node, List<CompletableFuture<Optional<Summary>>> parts) {
      try {
        running.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        failed.set(true);
        throw new IllegalStateException("Interrupted while summarizing " + node.entity(), e);
      }
      try {
        stopIfFailed();
        List<Summary> children = new ArrayList<>();
        for (CompletableFuture<Optional<Summary>> part : parts) {
          part.join().ifPresent(children::add);
        }
        if (node.facts().isEmpty() && children.isEmpty()) {
          return Optional.empty();
        }
        try {
          Optional<Summary> summary = Optional.of(summary(node, children));
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
        throw e;
      } catch (RuntimeException | Error e) {
        failed.set(true);
        throw e;
      } finally {
        running.release();
      }
    }

    private void stopIfFailed() {
      if (failed.get()) {
        throw new Skipped();
      }
    }

    private Summary summary(SummaryNode node, List<Summary> children) {
      List<Excerpts.Excerpt> excerpts = Excerpts.of(node, this::text);
      Item item = item(node, excerpts, children);
      String key = session.fingerprint(item);
      Outcome outcome =
          session.ask(
              item,
              calls ->
                  io.lantern.harness.Answer.of(
                      SummaryJson.write(generate(calls, key, node, excerpts, children))));
      return switch (outcome.kind()) {
        case CACHED -> {
          reused.incrementAndGet();
          yield rebind(SummaryJson.read(outcome.answer()), node, children);
        }
        case ANSWERED -> {
          generated.incrementAndGet();
          yield SummaryJson.read(outcome.answer());
        }
        case DROPPED ->
            throw new SummaryRejectedException(
                "The summary of " + node.entity() + " was " + lowerFirst(outcome.reason()));
        case NOT_ASKED -> throw new NotAsked();
        case OMITTED, FAILED -> throw new LlmException(outcome.reason());
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
      synchronized (files) {
        files.put(file, read);
      }
      return read;
    }

    private Summary generate(
        Calls calls,
        String key,
        SummaryNode node,
        List<Excerpts.Excerpt> excerpts,
        List<Summary> children)
        throws ItemDropped {
      Inputs inputs = new Inputs(node, excerpts, children);
      String purpose =
          "summarize " + node.level().name().toLowerCase(Locale.ROOT) + " " + node.entity();
      long budget = promptBudget(node, model);
      String whole =
          Prompt.user(node, inputs.facts, inputs.sheet, inputs.excerpts, inputs.children, null);
      Answer answer;
      List<List<Prompt.Item>> parts =
          Tokens.estimate(whole) <= budget ? List.of() : inputs.parts(budget);
      if (parts.size() < 2) {
        answer = answer(calls, purpose, node, whole, inputs, null);
      } else {
        List<Prompt.Tagged> texts = new ArrayList<>();
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
          texts.add(answer(calls, purpose + label, node, prompt, inputs, shown).tagged());
        }
        answer = merge(calls, purpose, node, inputs, texts, budget);
      }
      Citations.Checked checked = answer.checked();
      return new Summary(
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
          Prompt.VERSION,
          model.provider(),
          model.model());
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
        List<Prompt.Tagged> texts,
        long budget)
        throws ItemDropped {
      for (int round = 1; ; round++) {
        String prompt = Prompt.merge(node, texts);
        List<List<Prompt.Tagged>> groups =
            Tokens.estimate(prompt) <= budget
                ? List.of(texts)
                : pack(
                    texts,
                    text -> Tokens.estimate(text.text()) + 10,
                    Tokens.estimate(Prompt.merge(node, List.of())),
                    budget);
        if (groups.size() == 1 || groups.size() == texts.size()) {
          return answer(calls, purpose + " (merge)", node, prompt, inputs, cited(texts));
        }
        List<Prompt.Tagged> merged = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
          String label = " (merge " + round + "." + (i + 1) + " of " + groups.size() + ")";
          List<Prompt.Tagged> group = groups.get(i);
          merged.add(
              answer(calls, purpose + label, node, Prompt.merge(node, group), inputs, cited(group))
                  .tagged());
        }
        texts = merged;
      }
    }

    private static Set<String> cited(List<Prompt.Tagged> texts) {
      Set<String> refs = new HashSet<>();
      texts.forEach(text -> refs.addAll(Citations.references(text.text())));
      return refs;
    }

    /**
     * Asks the model and checks its answer through the harness, which sends it back once with the
     * reason if it is rejected.
     *
     * @param allowed the references the prompt shows, or {@code null} for all of the unit's
     * @throws ItemDropped if the second answer is rejected too, which {@link #visit} turns into a
     *     unit without a summary
     */
    private Answer answer(
        Calls calls,
        String purpose,
        SummaryNode node,
        String prompt,
        Inputs inputs,
        Set<String> allowed)
        throws ItemDropped {
      stopIfFailed();
      LlmRequest request =
          new LlmRequest(
              Role.SUMMARIZE,
              purpose,
              Prompt.SYSTEM,
              List.of(LlmMessage.user(prompt)),
              node.level().maxOutputTokens(),
              false);
      return calls.answer(
          request,
          response -> {
            stopIfFailed();
            try {
              return answer(purpose, response, inputs, allowed);
            } catch (Citations.Rejected rejected) {
              throw new Rejected(rejected.getMessage());
            }
          },
          Repair.withMessage(rejected -> Prompt.RETRY.formatted(rejected.getMessage())));
    }

    /** A model's text on one line, cut to its first {@value #LOGGED_ANSWER} characters. */
    private static String logged(String text) {
      if (text.length() > LOGGED_ANSWER) {
        text = text.substring(0, LOGGED_ANSWER) + "... (" + text.length() + " characters)";
      }
      return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * The answer without its purpose line and its sentences that cite nothing, checked. The whole
     * answer is checked first, so what is left out cannot hide a reason to reject it. It keeps an
     * evidenced purpose only if its first sentence is kept and cites what can show one; otherwise
     * its purpose counts as inferred.
     */
    private static Answer answer(
        String purpose, LlmResponse response, Inputs inputs, Set<String> allowed) {
      if (response.cutOff()) {
        throw new Citations.Rejected("it was cut off at the length limit; write a shorter one.");
      }
      Prompt.Tagged tagged = Prompt.tagged(response.text());
      Citations.check(
          tagged.text(), inputs.factIds, inputs.excerptFacts, inputs.summaries, allowed);
      Citations.Sentences sentences = Citations.cited(tagged.text(), inputs::unsupported);
      if (sentences.text().isEmpty() && !sentences.unsupported().isEmpty()) {
        throw new Citations.Rejected(
            "every sentence names something that the references it cites do not mention: "
                + names(sentences.unsupported())
                + "; name only what the input you cite shows.");
      }
      if (sentences.text().isEmpty()) {
        // Only a citation split by a blank line inside its brackets gets here.
        throw new Citations.Rejected(
            "none of its sentences ends with the references it is based on.");
      }
      if (!sentences.uncited().isEmpty()) {
        LOG.info(
            "Left out {} sentences without references from the answer to {}: {}",
            sentences.uncited().size(),
            purpose,
            logged(String.join(" | ", sentences.uncited())));
      }
      if (!sentences.unsupported().isEmpty()) {
        LOG.info(
            "Left out {} sentences naming what their references do not mention from the answer"
                + " to {}: {}",
            sentences.unsupported().size(),
            purpose,
            logged(
                String.join(
                    " | ",
                    sentences.unsupported().stream()
                        .map(u -> u.sentence() + " (" + String.join(", ", u.names()) + ")")
                        .toList())));
      }
      Citations.Checked checked =
          Citations.check(
              sentences.text(), inputs.factIds, inputs.excerptFacts, inputs.summaries, allowed);
      boolean evidenced =
          tagged.purpose() == Summary.Purpose.EVIDENCED
              && !sentences.firstLeftOut()
              && Citations.firstReferences(sentences.text()).stream()
                  .anyMatch(inputs.evidence::contains);
      return new Answer(
          sentences.text(),
          checked,
          evidenced ? Summary.Purpose.EVIDENCED : Summary.Purpose.INFERRED,
          Confidence.of(
              evidenced,
              sentences.kept(),
              (int) sentences.uncited().stream().filter(t -> !t.isBlank()).count()
                  + sentences.unsupported().size()));
    }

    /** The names the sentences left out name without support, each once, at most some. */
    private static String names(List<Citations.Unsupported> unsupported) {
      Set<String> names = new LinkedHashSet<>();
      unsupported.forEach(u -> names.addAll(u.names()));
      List<String> listed = names.stream().limit(10).toList();
      return String.join(", ", listed)
          + (names.size() > listed.size()
              ? " and " + (names.size() - listed.size()) + " more"
              : "");
    }
  }

  /**
   * A checked answer.
   *
   * @param text the answer as the model wrote it, with its references, without its purpose line and
   *     its sentences that cite nothing
   * @param checked the answer with references rewritten to stable IDs
   * @param purpose where its first sentence takes the unit's purpose from, as checked
   * @param confidence how far it can be trusted, from its purpose and the share of its sentences
   *     kept ({@link Confidence})
   */
  private record Answer(
      String text, Citations.Checked checked, Summary.Purpose purpose, double confidence) {

    /** The answer's text with its checked purpose mark, as a merge prompt shows it. */
    Prompt.Tagged tagged() {
      return new Prompt.Tagged(purpose, text);
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

    /** The unit as the prompt names it. */
    private final String subject;

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
        if (facts.get(i).predicate().equals(DocsExtractor.CLAIM)) {
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
     * What a sentence names that neither the inputs it cites nor the unit's name mention (ADR
     * 0068).
     */
    List<String> unsupported(String sentence) {
      List<String> support = new ArrayList<>();
      support.add(subject);
      for (String ref : Citations.references(sentence)) {
        String text = texts.get(ref);
        if (text != null) {
          support.add(text);
        }
      }
      return Grounding.unsupported(sentence, support);
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
