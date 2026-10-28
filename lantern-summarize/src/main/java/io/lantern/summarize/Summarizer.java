package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.llm.LlmClient;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Summarizes units bottom-up: a unit's summary is written from its facts and the summaries of the
 * units it contains, so each level builds on the one below.
 *
 * <p>Every summary is cached under a key that hashes its entity and level, the IDs of its input
 * facts, the text of its contained units' summaries, the prompt version and the model (provider and
 * identifier). A contained unit's summary counts with both its key and its text. A unit whose key
 * is already in the store is not sent to the model again. When a unit's facts change its key
 * changes, so it is summarized again; its new text then changes its parent's key, and so on up to
 * the repository. Units whose inputs did not change are reused.
 *
 * <p>Every summary must cite the facts or contained summaries it used. An answer that cites
 * nothing, or cites a reference that was not in the prompt, is sent back once with the reason; if
 * the second answer is no better the unit fails with {@link SummaryRejectedException} and nothing
 * is stored for it.
 *
 * <p>Units that do not contain each other are summarized in parallel, each on its own virtual
 * thread; a unit starts once the units it contains are done. How many calls run at once is bounded
 * by the model's limits in the {@link LlmClient}. The results do not depend on the order calls
 * finish in: each unit's prompt and key depend only on its own inputs, and the result lists units
 * children first, in entity ID order, as a sequential walk would. If a unit fails, no new units are
 * started, the ones already running finish, and the failure of the first unit in that order is
 * thrown.
 *
 * <p>Not thread-safe; use one summarizer per run.
 */
public final class Summarizer {

  /** The stop reason of an answer cut off at its output limit, as Anthropic reports it. */
  private static final String MAX_TOKENS = "max_tokens";

  private final LlmClient client;
  private final SummaryStore store;

  /**
   * @param client the LLM client, with the run's budgets
   * @param store where summaries are cached
   */
  public Summarizer(LlmClient client, SummaryStore store) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
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
    Run run = new Run(client.model(Role.SUMMARIZE));
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

  /** The cache key of a unit with the given contained summaries, for the given model. */
  static String key(SummaryNode node, List<Summary> children, ModelConfig model) {
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
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private final class Run {
    private final ModelConfig model;
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicInteger reused = new AtomicInteger();
    private final AtomicBoolean failed = new AtomicBoolean();

    Run(ModelConfig model) {
      this.model = model;
    }

    /** Summarizes a unit whose parts are done. */
    Optional<Summary> visit(SummaryNode node, List<CompletableFuture<Optional<Summary>>> parts) {
      if (failed.get()) {
        throw new Skipped();
      }
      try {
        List<Summary> children = new ArrayList<>();
        for (CompletableFuture<Optional<Summary>> part : parts) {
          part.join().ifPresent(children::add);
        }
        return node.facts().isEmpty() && children.isEmpty()
            ? Optional.empty()
            : Optional.of(summary(node, children));
      } catch (RuntimeException | Error e) {
        failed.set(true);
        throw e;
      }
    }

    private Summary summary(SummaryNode node, List<Summary> children) {
      String key = key(node, children, model);
      Optional<Summary> cached = store.find(key);
      if (cached.isPresent()) {
        reused.incrementAndGet();
        return cached.get();
      }
      Summary summary = generate(key, node, children);
      store.save(summary);
      generated.incrementAndGet();
      return summary;
    }

    private Summary generate(String key, SummaryNode node, List<Summary> children) {
      List<String> factIds = node.facts().stream().map(Fact::id).toList();
      String purpose =
          "summarize " + node.level().name().toLowerCase(Locale.ROOT) + " " + node.entity();
      List<LlmMessage> messages = new ArrayList<>();
      messages.add(LlmMessage.user(Prompt.user(node, children)));
      LlmResponse first = ask(purpose, node, messages);
      Citations.Checked checked;
      try {
        checked = check(first, factIds, children);
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
          checked = check(second, factIds, children);
        } catch (Citations.Rejected again) {
          throw new SummaryRejectedException(
              "The summary of "
                  + node.entity()
                  + " was rejected twice, the second time because "
                  + again.getMessage());
        }
      }
      return new Summary(
          key,
          node.entity(),
          node.level(),
          checked.text(),
          checked.facts(),
          checked.summaries(),
          factIds,
          children.stream().map(Summary::key).toList(),
          Prompt.VERSION,
          model.provider(),
          model.model());
    }

    private Citations.Checked check(
        LlmResponse response, List<String> factIds, List<Summary> children) {
      if (MAX_TOKENS.equals(response.stopReason())) {
        throw new Citations.Rejected("it was cut off at the length limit; write a shorter one.");
      }
      return Citations.check(response.text(), factIds, children);
    }

    private LlmResponse ask(String purpose, SummaryNode node, List<LlmMessage> messages) {
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
}
