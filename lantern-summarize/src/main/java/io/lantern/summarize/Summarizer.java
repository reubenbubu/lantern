package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import io.lantern.llm.Tokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

/**
 * Summarizes units bottom-up: a unit's summary is written from its facts and the summaries of the
 * units it contains, so each level builds on the one below.
 *
 * <p>A type's prompt also shows excerpts of its source code (see {@link Excerpts}), read through
 * {@link SourceFiles} and shown only when the file matches the content hash of the fact's evidence.
 * A unit whose inputs do not fit the model's context window is summarized in parts that are then
 * merged (see {@link Prompt}).
 *
 * <p>Every summary is cached under a key that hashes its entity and level, the IDs of its input
 * facts, the source excerpts its prompt shows, the text of its contained units' summaries, the
 * prompt version and the model (provider and identifier). A contained unit's summary counts with
 * both its key and its text. A unit whose key is already in the store is not sent to the model
 * again. When a unit's facts change its key changes, so it is summarized again; its new text then
 * changes its parent's key, and so on up to the repository. Units whose inputs did not change are
 * reused.
 *
 * <p>Every summary must cite the facts or contained summaries it used. An answer that cites
 * nothing, or cites a reference that was not in the prompt, is sent back once with the reason; if
 * the second answer is no better the unit fails with {@link SummaryRejectedException} and nothing
 * is stored for it.
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

  /** Files read for excerpts that are kept for the next units, which are often in the same file. */
  private static final int CACHED_FILES = 64;

  private final LlmClient client;
  private final SummaryStore store;
  private final SourceFiles sources;

  /**
   * A summarizer whose prompts show no source excerpts.
   *
   * @param client the LLM client, with the run's budgets
   * @param store where summaries are cached
   */
  public Summarizer(LlmClient client, SummaryStore store) {
    this(client, store, SourceFiles.NONE);
  }

  /**
   * @param client the LLM client, with the run's budgets
   * @param store where summaries are cached
   * @param sources where the files the facts were observed in are read from, for source excerpts
   */
  public Summarizer(LlmClient client, SummaryStore store, SourceFiles sources) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
    this.sources = Objects.requireNonNull(sources, "sources");
  }

  /**
   * What a run produced.
   *
   * @param summaries the summary of every unit that has one, by entity; a unit without facts and
   *     without summarized parts has none
   * @param generated how many summaries the model wrote in this run
   * @param reused how many came from the store
   */
  public record Result(Map<EntityId, Summary> summaries, int generated, int reused) {
    public Result {
      summaries = Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
    }
  }

  /**
   * Summarizes the given units and everything they contain, for example the trees from {@link
   * SummaryTree#fromFacts}.
   *
   * @throws SummaryRejectedException if the model's summary of a unit is rejected twice
   * @throws io.lantern.llm.LlmException if a call fails or is refused, for example over budget
   */
  public Result summarize(Collection<SummaryNode> roots) {
    ModelConfig model = client.model(Role.SUMMARIZE);
    Run run = new Run(model, model.limits().maxConcurrency());
    Map<EntityId, SummaryNode> units = new LinkedHashMap<>();
    for (SummaryNode root : roots) {
      collect(root, units, new HashSet<>());
    }
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
    return new Result(summaries, run.generated.get(), run.reused.get());
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

  /** A unit not summarized because another one failed first. */
  private static final class Skipped extends RuntimeException {
    Skipped() {
      super("Not summarized: another unit failed", null, false, false);
    }
  }

  /** The cache key of a unit with the given excerpts and contained summaries, for the model. */
  static String key(
      SummaryNode node,
      List<Excerpts.Excerpt> excerpts,
      List<Summary> children,
      ModelConfig model) {
    StringBuilder text = new StringBuilder();
    text.append("lantern-summary\n");
    text.append("prompt ").append(Prompt.VERSION).append('\n');
    text.append("provider ").append(oneLine(model.provider())).append('\n');
    text.append("model ").append(oneLine(model.model())).append('\n');
    text.append("level ").append(node.level()).append('\n');
    text.append("entity ").append(node.entity()).append('\n');
    for (Fact fact : node.facts()) {
      text.append("fact ").append(fact.id()).append('\n');
    }
    for (Excerpts.Excerpt excerpt : excerpts) {
      text.append("excerpt ")
          .append(excerpt.fact())
          .append(' ')
          .append(excerpt.start())
          .append('-')
          .append(excerpt.end())
          .append(' ')
          .append(sha256(excerpt.code()))
          .append('\n');
    }
    for (Summary child : children) {
      text.append("child ")
          .append(child.entity())
          .append(' ')
          .append(child.key())
          .append(' ')
          .append(sha256(child.text()))
          .append('\n');
    }
    return sha256(text.toString());
  }

  private static String oneLine(String value) {
    return value.replace("\\", "\\\\").replace("\n", "\\n");
  }

  private static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * The most tokens, as {@link io.lantern.llm.Tokens} estimates them, that a unit's user turn may
   * take so that the request still fits the context window with a retry: the system prompt, the
   * model's thinking headroom, the answer (its estimate may exceed the output limit by half) and
   * the retry message come on top.
   */
  static long promptBudget(SummaryNode node, ModelConfig model) {
    int output = node.level().maxOutputTokens();
    return model.contextWindow()
        - Tokens.estimate(Prompt.SYSTEM)
        - model.thinking().headroomTokens()
        - output
        - output * 3L / 2
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
    private final ModelConfig model;
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicInteger reused = new AtomicInteger();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Semaphore running;
    private final Map<String, Optional<List<String>>> files =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Optional<List<String>>> eldest) {
            return size() > CACHED_FILES;
          }
        };

    Run(ModelConfig model, int maxRunning) {
      this.model = model;
      this.running = new Semaphore(maxRunning, true);
    }

    /** Summarizes a unit whose parts are done, unless another unit has failed. */
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
        return node.facts().isEmpty() && children.isEmpty()
            ? Optional.empty()
            : Optional.of(summary(node, children));
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
      List<Excerpts.Excerpt> excerpts = Excerpts.of(node, this::lines);
      String key = key(node, excerpts, children, model);
      Optional<Summary> cached = store.find(key);
      if (cached.isPresent()) {
        reused.incrementAndGet();
        return cached.get();
      }
      Summary summary = generate(key, node, excerpts, children);
      store.save(summary);
      generated.incrementAndGet();
      return summary;
    }

    /** A file's lines for a fact's evidence, if it can be read and is the version observed. */
    private synchronized Optional<List<String>> lines(Evidence evidence) {
      if (evidence.path() == null || evidence.contentHash() == null) {
        return Optional.empty();
      }
      String file = evidence.repo() + '\u0000' + evidence.commit() + '\u0000' + evidence.path();
      Optional<List<String>> known = files.get(file);
      if (known == null) {
        known =
            sources
                .read(evidence.repo(), evidence.commit(), evidence.path())
                .filter(content -> sha256(content).equals(evidence.contentHash()))
                .map(content -> new String(content, StandardCharsets.UTF_8).lines().toList());
        files.put(file, known);
      }
      return known;
    }

    private Summary generate(
        String key, SummaryNode node, List<Excerpts.Excerpt> excerpts, List<Summary> children) {
      Inputs inputs = new Inputs(node, excerpts, children);
      String purpose =
          "summarize " + node.level().name().toLowerCase(Locale.ROOT) + " " + node.entity();
      long budget = promptBudget(node, model);
      String whole = Prompt.user(node, inputs.facts, inputs.excerpts, inputs.children, null);
      Citations.Checked checked;
      List<List<Prompt.Item>> parts =
          Tokens.estimate(whole) <= budget ? List.of() : inputs.parts(budget);
      if (parts.size() < 2) {
        checked = answer(purpose, node, whole, inputs, null).checked();
      } else {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
          List<Prompt.Item> part = parts.get(i);
          String prompt =
              Prompt.user(
                  node,
                  inputs.only(inputs.facts, part),
                  inputs.only(inputs.excerpts, part),
                  inputs.only(inputs.children, part),
                  new Prompt.Part(i + 1, parts.size()));
          Set<String> shown = new HashSet<>();
          part.forEach(item -> shown.add(item.ref()));
          String label = " (part " + (i + 1) + " of " + parts.size() + ")";
          texts.add(answer(purpose + label, node, prompt, inputs, shown).text());
        }
        checked = merge(purpose, node, inputs, texts, budget);
      }
      return new Summary(
          key,
          node.entity(),
          node.level(),
          checked.text(),
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
    private Citations.Checked merge(
        String purpose, SummaryNode node, Inputs inputs, List<String> texts, long budget) {
      for (int round = 1; ; round++) {
        String prompt = Prompt.merge(node, texts);
        List<List<String>> groups =
            Tokens.estimate(prompt) <= budget
                ? List.of(texts)
                : pack(
                    texts,
                    text -> Tokens.estimate(text) + 5,
                    Tokens.estimate(Prompt.merge(node, List.of())),
                    budget);
        if (groups.size() == 1 || groups.size() == texts.size()) {
          return answer(purpose + " (merge)", node, prompt, inputs, cited(texts)).checked();
        }
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
          String label = " (merge " + round + "." + (i + 1) + " of " + groups.size() + ")";
          List<String> group = groups.get(i);
          merged.add(
              answer(purpose + label, node, Prompt.merge(node, group), inputs, cited(group))
                  .text());
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
     * Asks the model and checks its answer, sending it back once with the reason if it is rejected.
     *
     * @param allowed the references the prompt shows, or {@code null} for all of the unit's
     * @throws SummaryRejectedException if the second answer is rejected too
     */
    private Answer answer(
        String purpose, SummaryNode node, String prompt, Inputs inputs, Set<String> allowed) {
      List<LlmMessage> messages = new ArrayList<>();
      messages.add(LlmMessage.user(prompt));
      LlmResponse first = ask(purpose, node, messages);
      try {
        return new Answer(first.text(), check(first, inputs, allowed));
      } catch (Citations.Rejected rejected) {
        if (first.text().isBlank()) {
          // A conversation cannot hold an empty turn, so the reason goes with the prompt again.
          messages.set(
              0,
              LlmMessage.user(
                  messages.getFirst().text()
                      + "\n"
                      + Prompt.RETRY.formatted(rejected.getMessage())));
        } else {
          messages.add(LlmMessage.assistant(first.text()));
          messages.add(LlmMessage.user(Prompt.RETRY.formatted(rejected.getMessage())));
        }
        LlmResponse second = ask(purpose + " (retry)", node, messages);
        try {
          return new Answer(second.text(), check(second, inputs, allowed));
        } catch (Citations.Rejected again) {
          throw new SummaryRejectedException(
              "The summary of "
                  + node.entity()
                  + " was rejected twice, the second time because "
                  + again.getMessage());
        }
      }
    }

    private Citations.Checked check(LlmResponse response, Inputs inputs, Set<String> allowed) {
      if (response.cutOff()) {
        throw new Citations.Rejected("it was cut off at the length limit; write a shorter one.");
      }
      return Citations.check(
          response.text(), inputs.factIds, inputs.excerptFacts, inputs.summaries, allowed);
    }

    private LlmResponse ask(String purpose, SummaryNode node, List<LlmMessage> messages) {
      stopIfFailed();
      return client.call(
          new LlmRequest(
              Role.SUMMARIZE,
              purpose,
              Prompt.SYSTEM,
              messages,
              node.level().maxOutputTokens(),
              false));
    }
  }

  /**
   * A checked answer.
   *
   * @param text the answer as the model wrote it, with its references
   * @param checked the answer with references rewritten to stable IDs
   */
  private record Answer(String text, Citations.Checked checked) {}

  /** A unit's inputs as prompt items, with what their references stand for. */
  private static final class Inputs {
    final List<String> factIds;
    final List<String> excerptFacts;
    final List<Summary> summaries;
    final List<Prompt.Item> facts;
    final List<Prompt.Item> excerpts;
    final List<Prompt.Item> children;
    private final SummaryNode node;

    Inputs(SummaryNode node, List<Excerpts.Excerpt> excerpts, List<Summary> children) {
      this.node = node;
      this.factIds = node.facts().stream().map(Fact::id).toList();
      this.excerptFacts = excerpts.stream().map(Excerpts.Excerpt::fact).toList();
      this.summaries = children;
      this.facts = Prompt.facts(node.facts());
      this.excerpts = Prompt.excerpts(excerpts);
      this.children = Prompt.children(children);
    }

    /** All items split into parts that each fit the budget with a part prompt's own text. */
    List<List<Prompt.Item>> parts(long budget) {
      List<Prompt.Item> all = new ArrayList<>(facts);
      all.addAll(excerpts);
      all.addAll(children);
      // The headings of all three sections and the longest part number, written once.
      long overhead =
          Tokens.estimate(
              Prompt.user(
                  node,
                  facts.isEmpty() ? List.of() : List.of(new Prompt.Item("F1", "")),
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
