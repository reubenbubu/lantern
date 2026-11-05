package io.lantern.harness;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.CheckedText;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.BudgetExceededException;
import io.lantern.llm.Capability;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.Tokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One role's work in a run (knowledge-build epic, section 10). For each item it looks the
 * fingerprint up in the answer cache, where a hit is reused once revalidated at no cost; it plans
 * the misses against the role's share of the run budget, so an item past the share is not asked; it
 * asks the rest through {@link Calls} and stores each accepted answer under its fingerprint. It
 * records all of it in the role's {@link RoleRecord}. Thread-safe: a role may ask items in
 * parallel.
 */
public final class RoleSession {

  private static final Logger LOG = LoggerFactory.getLogger(RoleSession.class);

  private final Harness harness;
  private final RoleSpec spec;
  private final ModelConfig model;
  private final LlmClient client;
  private final RoleRecord record;
  private final BigDecimal share;
  // The first item the role asks the model about goes alone; the others wait until it is answered,
  // so its request writes the shared prompt prefix to the cache and theirs read it (#357).
  private final java.util.concurrent.atomic.AtomicBoolean warming =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.CompletableFuture<Void> warmed =
      new java.util.concurrent.CompletableFuture<>();
  // Once the role plans more cache misses than the harness's threshold, its requests go through the
  // batch API (knowledge-build epic, section 10, Run modes): the first alone and interactively, to
  // write the shared prefix with a long lifetime, the rest in batches that read it.
  private volatile BatchCollector batch;
  // Once the harness packs and the role says how, its items' first requests are joined (#874).
  private PacketCollector packets;
  private volatile long prefixTokens;
  private volatile double requestsPerItem;
  // Under a budget share, whether each planned item fits, decided in rounds in the group's order,
  // so thread timing never picks the items (#657, #659); the keys of items not decided yet; and the
  // keys of each subject's items.
  private final Map<String, Boolean> admitted = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Set<String> undecided =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Map<String, List<String>> keysBySubject =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final ShareRounds rounds;
  // Whether the role has asked the model anything yet: until it has, its first request writes the
  // prompt's start to the cache.
  private final java.util.concurrent.atomic.AtomicBoolean asking =
      new java.util.concurrent.atomic.AtomicBoolean();
  // The item that goes first and warms the cache: the plan's first miss in the role's order, by its
  // key (see key) and its subject.
  private volatile String warmUpKey;
  private volatile String warmUpSubject;
  private final java.util.concurrent.atomic.AtomicBoolean prefixSent =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile Thread warmer;
  private final java.util.concurrent.CompletableFuture<Void> prefixWritten =
      new java.util.concurrent.CompletableFuture<>();

  RoleSession(Harness harness, RoleSpec spec, LlmClient client, RoleRecord record) {
    this.harness = harness;
    this.spec = spec;
    this.model = client.model(spec.modelRole());
    this.share = harness.budget().share(spec.budgetGroup());
    this.rounds = harness.rounds(spec.budgetGroup());
    // The roles of one group share its guard, so together they never spend past its share.
    LlmClient budgeted =
        share == null ? client : client.withBudget(harness.guard(spec.budgetGroup()));
    this.client = budgeted.alsoLogging(record);
    this.record = record;
    record.packetSize(spec.packetSize());
  }

  /** The role. */
  public RoleSpec spec() {
    return spec;
  }

  /** The model the role uses. */
  public ModelConfig model() {
    return model;
  }

  Harness harness() {
    return harness;
  }

  /** The item's fingerprint for this role and model. */
  public String fingerprint(Item item) {
    String fingerprint =
        Fingerprints.of(spec, cacheVersion(), model.provider(), model.model(), item);
    String reason = harness.retryReason(fingerprint);
    if (reason == null) {
      return fingerprint;
    }
    // An item asked again after the support judge rejected its answer shows why, so its answer is
    // cached apart from the rejected one (#751).
    String retried =
        Fingerprints.of(
            spec,
            cacheVersion(),
            model.provider(),
            model.model(),
            new Item(
                item.subject(),
                item.locality(),
                item.inputs(),
                item.rendering() + "\n" + retryNote(null, reason)));
    harness.retriedAs(retried);
    return retried;
  }

  /** Why the item is asked again after the support judge rejected its answer, or null. */
  private String retryReason(Item item) {
    return harness.retryReason(
        Fingerprints.of(spec, cacheVersion(), model.provider(), model.model(), item));
  }

  /** What tells the model why an item is asked again (prompts/support-retry.txt). */
  static String retryNote(String id, String reason) {
    return (id == null ? "<retry>\n" : "<retry item=\"" + id + "\">\n")
        + Prompts.text(RETRY_PROMPT).strip().replace("{reason}", reason)
        + "\n</retry>";
  }

  /** The prompt file that tells the model why an item is asked again (#751). */
  static final String RETRY_PROMPT = "support-retry.txt";

  /**
   * The request with a note, at the end of its first turn, for each of the packet's items asked
   * again after the support judge rejected its answer, saying why (#751); the request as it is when
   * none is, or when it already holds the notes.
   *
   * @param ids the items' local IDs, or null for a request about one item that names none
   */
  LlmRequest noted(LlmRequest request, List<Item> packet, List<String> ids) {
    StringBuilder notes = new StringBuilder();
    for (int i = 0; i < packet.size(); i++) {
      String reason = retryReason(packet.get(i));
      if (reason != null) {
        notes.append("\n\n").append(retryNote(ids == null ? null : ids.get(i), reason));
      }
    }
    return noted(request, notes.toString());
  }

  /**
   * The request with the note at the end of its first turn, unless it is empty or already there.
   */
  static LlmRequest noted(LlmRequest request, String note) {
    if (note == null || note.isEmpty()) {
      return request;
    }
    List<io.lantern.llm.LlmMessage> messages = new ArrayList<>(request.messages());
    io.lantern.llm.LlmMessage first = messages.getFirst();
    if (first.text().endsWith(note)) {
      return request;
    }
    messages.set(
        0,
        new io.lantern.llm.LlmMessage(
            first.author(),
            first.text() + note,
            first.toolCalls(),
            first.toolResults(),
            first.thinking()));
    return request.withMessages(messages);
  }

  /**
   * The item's fingerprint as the role's own, whatever calibration it runs under: what a sample
   * drawn by fingerprint reads, so a calibration asks the same items as the role's usual size.
   */
  public String sampleFingerprint(Item item) {
    return Fingerprints.of(spec, model, item);
  }

  /**
   * The prompt version the role's answers are cached under: its own, with a calibration's size and
   * trial beside it when it runs under one.
   */
  String cacheVersion() {
    return harness
        .calibration(spec.name())
        .map(c -> spec.promptVersion() + " " + c.tag())
        .orElse(spec.promptVersion());
  }

  /**
   * Plans items before any is asked: counts the cache's hits and estimates the tokens and dollars
   * of the misses, logs the estimate and adds it to the run record.
   *
   * @param items the items whose inputs are known now
   * @param predictedHits items not yet known that are expected to be cached, such as summaries of
   *     units none of whose parts needs asking
   * @param predictedMisses items not yet known that are expected to be asked, such as summaries of
   *     units whose parts are still to be summarized
   * @param predictedTokens the input tokens those items are expected to take, in all
   * @param prefixTokens the tokens of the prompt every request of the role starts with, written to
   *     the prompt cache once and read by the requests after the first. A role that asks one item a
   *     request includes them in each item's rendering; a role that groups items into requests does
   *     not, and its plan counts them once a request
   */
  public Estimate plan(
      Collection<Item> items,
      int predictedHits,
      int predictedMisses,
      long predictedTokens,
      long prefixTokens) {
    List<Planned> planned = new ArrayList<>();
    items.forEach(item -> planned.add(Planned.of(item)));
    for (int i = 0; i < predictedMisses; i++) {
      long tokens =
          predictedTokens / predictedMisses + (i == 0 ? predictedTokens % predictedMisses : 0);
      planned.add(Planned.predicted(null, tokens));
    }
    return plan(planned, predictedHits, prefixTokens);
  }

  /**
   * An item of a role's plan: one whose input is known now, or one expected to be asked whose input
   * is not known yet, such as the summary of a unit whose parts are still to be summarized.
   *
   * @param subject the item's subject, as it will be asked; {@code null} if not known yet
   * @param item the item, or {@code null} for one expected to be asked
   * @param tokens the input tokens an item expected to be asked is expected to take
   * @param after the subjects of the items of its budget group this one rests on, such as a unit's
   *     parts: under a budget, it is not admitted while one of them waits for a later round (#661)
   */
  public record Planned(String subject, Item item, long tokens, Set<String> after) {

    public Planned {
      after = Set.copyOf(after);
    }

    /** An item whose input is known now. */
    public static Planned of(Item item) {
      return new Planned(item.subject(), item, 0, Set.of());
    }

    /** An item expected to be asked, of the given subject and input tokens. */
    public static Planned predicted(String subject, long tokens) {
      return new Planned(subject, null, tokens, Set.of());
    }

    /** This item, resting on the items of its budget group of the given subjects. */
    public Planned after(Collection<String> subjects) {
      return new Planned(subject, item, tokens, Set.copyOf(subjects));
    }
  }

  /**
   * Plans the role's items, in the role's order, before any is asked (knowledge-build epic, section
   * 10, Plan): counts the cache hits, estimates the misses, logs the estimate and adds it to the
   * run record. Under a budget share it also decides, walking the items in that order, which fit
   * the share by their estimates; one that does not fit waits for a later round while the items
   * after it that fit are asked, and what never fits is not asked. The first miss that is asked
   * warms the cache. Neither depends on which thread asks first (#657).
   *
   * @param planned the role's items in its order, known and expected
   * @param predictedHits items not yet known that are expected to be cached
   * @param prefixTokens the tokens of the prompt every request of the role starts with (see {@link
   *     #plan(Collection, int, int, long, long)})
   */
  public Estimate plan(List<Planned> planned, int predictedHits, long prefixTokens) {
    return plan(planned, predictedHits, prefixTokens, false);
  }

  /**
   * Plans one item expected to be asked, and gives it first claim on its group's share: it is
   * admitted at once if its estimate fits, ahead of every item the group plans after it, and the
   * group's rounds do not wait for it (#659). For an item asked after the rest of its group, such
   * as the system's purpose, which rests on the units' sentences. Without a share it is planned as
   * any other item.
   *
   * @param subject the item's subject, as it will be asked
   * @param tokens the input tokens it is expected to take
   * @param prefixTokens the tokens of the prompt every request of the role starts with
   */
  public Estimate claim(String subject, long tokens, long prefixTokens) {
    return plan(List.of(Planned.predicted(subject, tokens)), 0, prefixTokens, true);
  }

  /** Whether the role counts against a share of the run budget. */
  public boolean underShare() {
    return share != null;
  }

  private Estimate plan(
      List<Planned> planned, int predictedHits, long prefixTokens, boolean claim) {
    this.prefixTokens = prefixTokens;
    int cached = predictedHits;
    long input = 0;
    int known = 0;
    int predictedMisses = 0;
    List<Item> misses = new ArrayList<>();
    List<Planned> toAskInOrder = new ArrayList<>();
    for (Planned entry : planned) {
      if (entry.item() != null) {
        known++;
        if (cached(entry.item())) {
          cached++;
          continue;
        }
        input += Tokens.estimate(entry.item().rendering());
        misses.add(entry.item());
      } else {
        predictedMisses++;
        input += entry.tokens();
      }
      toAskInOrder.add(entry);
    }
    int toAsk = misses.size() + predictedMisses;
    long write = 0;
    long read = 0;
    int requests = toAsk;
    if (spec.packetSize() > 1) {
      // A role that groups items into requests renders each item without the prompt's start,
      // which every request sends once: the plan counts requests, not items, packed as they will
      // be sent (a new request at every change of repository).
      requests =
          Packer.pack(misses, spec.packetSize(), this::sampleFingerprint).size()
              + (predictedMisses + spec.packetSize() - 1) / spec.packetSize();
      this.requestsPerItem = toAsk == 0 ? 0 : (double) requests / toAsk;
      if (requests > 0 && model.supports(Capability.PROMPT_CACHING) && prefixTokens > 0) {
        write = prefixTokens;
        read = prefixTokens * (requests - 1L);
      } else {
        input += prefixTokens * requests;
      }
    } else if (toAsk > 0 && model.supports(Capability.PROMPT_CACHING) && prefixTokens > 0) {
      // A role that asks one item a request renders each item with the prompt's start.
      write = prefixTokens;
      read = prefixTokens * (toAsk - 1L);
      input = Math.max(0, input - prefixTokens * toAsk);
    }
    TokenUsage expected = expected(input, write, read, toAsk, requests);
    input = expected.input();
    write = expected.cacheWrite();
    read = expected.cacheRead();
    long output = expected.output();
    BigDecimal usd;
    if (batchIfBulk(toAsk)) {
      // A role that batches pays the batch price, except for its warm-up request's one-hour cache
      // write, which goes interactively at the long-lived write's price (ADR 0102).
      usd =
          model
              .pricing()
              .batchCost(new TokenUsage(input, output, 0, read))
              .add(model.pricing().cost(new TokenUsage(0, 0, 0, 0, write)));
    } else {
      usd = model.pricing().cost(new TokenUsage(input, output, write, read));
    }
    Estimate estimate =
        new Estimate(
            known + predictedHits + predictedMisses,
            cached,
            toAsk,
            input,
            write,
            read,
            output,
            usd,
            share);
    // A pass that only asks again what the support judge rejected plans for the budget, but its
    // items were counted by the first pass (#751).
    if (!harness.retryPass()) {
      record.planned(estimate);
    }
    LOG.info("Plan {}", estimate.line(spec.name()));
    decide(toAskInOrder, claim);
    return estimate;
  }

  /**
   * Queues the role's misses, in its order, for its group's rounds to decide, or claims them;
   * without a share, every item is asked and the first goes first. An item of unknown subject is
   * decided when it is asked.
   */
  private synchronized void decide(List<Planned> inOrder, boolean claim) {
    List<ShareRounds.Entry> entries = new ArrayList<>();
    for (Planned entry : inOrder) {
      if (entry.subject() == null) {
        continue;
      }
      String key = key(entry);
      if (admitted.containsKey(key) || undecided.contains(key)) {
        continue;
      }
      if (share == null) {
        if (warmUpKey == null && !warming.get()) {
          warmUpSubject = entry.subject();
          warmUpKey = key;
        }
        continue;
      }
      keysBySubject
          .computeIfAbsent(entry.subject(), s -> new java.util.concurrent.CopyOnWriteArrayList<>())
          .add(key);
      entries.add(
          new ShareRounds.Entry(
              this,
              key,
              entry.subject(),
              entry.item(),
              entry.item() != null ? itemCost(entry.item()) : cost(entry.tokens()),
              entry.after()));
    }
    if (claim) {
      entries.forEach(entry -> decided(entry, rounds.claim(entry)));
    } else if (!entries.isEmpty()) {
      entries.forEach(entry -> undecided.add(entry.key()));
      rounds.queue(entries);
    }
  }

  /**
   * Records the group's decision on an item; the first item admitted goes first and warms the
   * cache. Called by the group's rounds, which hold their own lock, never this session's.
   */
  void decided(ShareRounds.Entry entry, boolean fits) {
    admitted.put(entry.key(), fits);
    undecided.remove(entry.key());
    if (fits && warmUpKey == null && !warming.get()) {
      warmUpSubject = entry.subject();
      warmUpKey = entry.key();
    }
  }

  /** Whether the item kept under the key waits for its group's next round. */
  boolean undecided(String key) {
    return undecided.contains(key);
  }

  /**
   * Waits until the group has decided whether the subject's items fit its share. A role that bounds
   * how many items it asks at once calls this before taking a slot, so items waiting for a later
   * round never hold the slots the current round needs.
   */
  public void awaitTurn(String subject) {
    if (rounds == null) {
      return;
    }
    for (String key : keysBySubject.getOrDefault(subject, List.of())) {
      rounds.await(this, key);
    }
  }

  /**
   * Tells the group's rounds that the item has an outcome, or ended without one, so the next round
   * can be decided once the current one is settled.
   */
  private void ended(Item item) {
    if (rounds != null) {
      rounds.settle(this, fingerprint(item));
      rounds.settle(this, "subject " + item.subject());
    }
  }

  /** As {@link #ended(Item)}, for every item of a subject. */
  private void ended(String subject) {
    if (rounds != null) {
      for (String key : keysBySubject.getOrDefault(subject, List.of())) {
        rounds.settle(this, key);
      }
    }
  }

  /**
   * What the plan's decisions are kept under: a known item's fingerprint, since a role may ask
   * several items of one subject (a flow shown in two documents), or the subject of an item
   * expected to be asked, whose fingerprint is not known yet.
   */
  private String key(Planned entry) {
    return entry.item() != null ? fingerprint(entry.item()) : "subject " + entry.subject();
  }

  /** The item that goes first and warms the cache, if the plan named one. */
  public Optional<String> warmUpItem() {
    return Optional.ofNullable(warmUpSubject);
  }

  /**
   * Waits, unless the subject is the role's warm-up item, until the warm-up item is settled. A role
   * that bounds how many items it asks at once calls this before taking a slot, so the items
   * waiting for the warm-up never hold the slots it needs.
   */
  public void awaitWarmUp(String subject) {
    String first = warmUpSubject;
    if (first != null && !first.equals(subject)) {
      awaitWarmed();
    }
  }

  /**
   * Sends the role's requests through the batch API from now on if it is a bulk role with more
   * planned misses than the harness's threshold and its model batches.
   *
   * @return whether the role batches
   */
  private synchronized boolean batchIfBulk(int misses) {
    if (batch == null
        && spec.runMode() == RunMode.BULK
        && misses > harness.batchAbove()
        && client.batches(spec.modelRole())) {
      batch = new BatchCollector(client);
      LOG.info("{} sends its {} cache misses through the batch API", spec.name(), misses);
    }
    return batch != null;
  }

  /**
   * Whether the role's items' first requests are joined into packed requests in this run (#874):
   * the harness packs, the role says how, and it does not batch.
   */
  public synchronized boolean packing() {
    if (packets == null && batch == null) {
      Packing.Limits limits = harness.packetLimits();
      Packing packing = harness.packing(spec.name());
      if (limits != null && packing != null) {
        packets = new PacketCollector(client, packing, limits);
        LOG.info("{} joins its items' requests into packets of at most {}", spec.name(), limits);
      }
    }
    return packets != null && batch == null;
  }

  /** Whether the role's requests go through the batch API in this run. */
  public boolean batching() {
    return batch != null;
  }

  /** Plans items whose inputs are all known now. */
  public Estimate plan(Collection<Item> items, long prefixTokens) {
    return plan(items, 0, 0, 0, prefixTokens);
  }

  /** Whether the cache holds the item's answer, revalidated. */
  public boolean cached(Item item) {
    Optional<StoredAnswer> stored = harness.answers().find(fingerprint(item));
    return stored.isPresent() && revalidated(stored.get());
  }

  /**
   * The answer the cache holds for the item and would answer it with, if any: an omitted answer has
   * none.
   */
  public Optional<String> cachedAnswer(Item item) {
    return harness
        .answers()
        .find(fingerprint(item))
        .filter(this::revalidated)
        .map(StoredAnswer::answer);
  }

  /**
   * Opens the gate the other items wait at, if the subject is the warm-up item's: for a role whose
   * warm-up item ended before it was asked, such as a unit whose files could not be read, so a
   * failure fails the run instead of leaving the others waiting.
   */
  public void releaseWarmUp(String subject) {
    settledWithoutAsking(subject);
    ended(subject);
  }

  /** As {@link #releaseWarmUp(String)}, for the item itself. */
  public void releaseWarmUp(Item item) {
    settledWithoutAsking(item);
    ended(item);
  }

  /**
   * Answers an item: from the cache when it holds the item's fingerprint and every file the answer
   * read still has its blob; otherwise, within the role's budget share, by asking the model.
   */
  public Outcome ask(Item item, Asker asker) {
    return ask(item, asker, stored -> true);
  }

  /**
   * As {@link #ask(Item, Asker)}, reusing a cached answer only while the role's own check of it
   * holds too, such as that the facts a tool showed it still hold.
   */
  public Outcome ask(Item item, Asker asker, java.util.function.Predicate<StoredAnswer> holds) {
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(asker, "asker");
    try {
      return askOrReuse(item, asker, holds);
    } finally {
      ended(item);
    }
  }

  private Outcome askOrReuse(
      Item item, Asker asker, java.util.function.Predicate<StoredAnswer> holds) {
    String fingerprint = fingerprint(item);
    Optional<StoredAnswer> stored = harness.answers().find(fingerprint);
    if (stored.isPresent() && revalidated(stored.get()) && holdsOrDropped(stored.get(), holds)) {
      Outcome outcome = reused(stored.get());
      if (!harness.retryPass()) {
        record.reused(item.subject(), outcome);
      }
      settledWithoutAsking(item);
      return outcome;
    }
    missed(item, stored);
    if (!admitted(item)) {
      Outcome outcome =
          Outcome.of(
              Outcome.Kind.NOT_ASKED,
              fingerprint,
              null,
              "the " + spec.budgetGroup() + " share of the run budget is spent");
      LOG.warn("Not asked about {} for {}: {}", item.subject(), spec.name(), outcome.reason());
      record.outcome(item.subject(), outcome, false);
      settledWithoutAsking(item);
      return outcome;
    }
    return askAdmitted(item, asker, fingerprint);
  }

  private Outcome askAdmitted(Item item, Asker asker, String fingerprint) {
    if (rounds != null) {
      rounds.started();
    }
    asking.set(true);
    boolean first = warmUp(item);
    if (first) {
      record.warmUp(item.subject());
    }
    String reason = retryReason(item);
    Calls calls =
        new Calls(
            this, client, item.subject(), reason == null ? null : "\n\n" + retryNote(null, reason));
    Outcome outcome;
    try {
      Answer answer = asker.ask(calls);
      save(item, answer.json(), answer.filesRead());
      outcome =
          answer.json() == null
              ? Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "omitted")
              : Outcome.of(Outcome.Kind.ANSWERED, fingerprint, answer.json(), null);
    } catch (ItemDropped e) {
      outcome = dropped(item, e.getMessage(), List.of(), e.kept());
    } catch (BudgetExceededException e) {
      outcome = Outcome.of(Outcome.Kind.NOT_ASKED, fingerprint, null, e.getMessage());
      LOG.warn("Not asked about {} for {}: {}", item.subject(), spec.name(), e.getMessage());
    } catch (LlmException e) {
      outcome = Outcome.of(Outcome.Kind.FAILED, fingerprint, null, e.getMessage());
    } finally {
      if (first) {
        warmed.complete(null);
      }
    }
    record.outcome(item.subject(), outcome, calls.repaired());
    return outcome;
  }

  /**
   * Whether this is the role's first item to ask the model about; any other waits until the first
   * is answered (or has failed), so the requests that share its prompt prefix read it from the
   * provider's cache instead of all writing it at once (#357).
   */
  private boolean warmUp(Item item) {
    String first = warmUpKey;
    if ((first == null || isWarmUp(item)) && warming.compareAndSet(false, true)) {
      return true;
    }
    awaitWarmed();
    return false;
  }

  private void awaitWarmed() {
    try {
      warmed.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while " + spec.name() + " warmed the prompt cache", e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    }
  }

  /** Whether the item is the one the plan named to go first. */
  private boolean isWarmUp(Item item) {
    String first = warmUpKey;
    return first != null
        && (first.equals(fingerprint(item)) || first.equals("subject " + item.subject()));
  }

  /** Lets the others go when the warm-up item is settled without being asked. */
  private void settledWithoutAsking(Item item) {
    if (isWarmUp(item)) {
      warming.set(true);
      warmed.complete(null);
    }
  }

  /** As {@link #settledWithoutAsking(Item)}, for an item known only by its subject. */
  private void settledWithoutAsking(String subject) {
    if (subject.equals(warmUpSubject)) {
      warming.set(true);
      warmed.complete(null);
    }
  }

  /**
   * Answers items that share requests (knowledge-build epic, section 10): each from the cache when
   * it holds the item's fingerprint; the misses within the role's budget share packed into requests
   * of at most the role's packet size. Every item's entry goes through the standard pipeline on its
   * own, so one bad item keeps the others; all rejections of a request go back in one repair turn,
   * and what fails again is dropped. An item missing from an answer is asked again alone, once;
   * missing again, it is not asked.
   *
   * @return the outcome of each item, by subject, in the order given
   */
  public Map<String, Outcome> askPackets(List<Item> items, PacketRole role) {
    Map<String, Outcome> outcomes = new LinkedHashMap<>();
    items.forEach(item -> outcomes.put(item.subject(), null));
    List<Item> misses = new ArrayList<>();
    for (Item item : items) {
      String fingerprint = fingerprint(item);
      Optional<StoredAnswer> stored = harness.answers().find(fingerprint);
      if (stored.isPresent() && revalidated(stored.get())) {
        settleReused(outcomes, item, reused(stored.get()));
      } else {
        missed(item, stored);
        misses.add(item);
      }
    }
    inRounds(
        misses,
        admittedNow -> askPlannedPackets(admittedNow, role, outcomes),
        item -> settle(outcomes, item, notAdmitted(item), false));
    return outcomes;
  }

  /**
   * Asks items that share requests round by round: those the group has admitted now, then, once
   * they are settled and the group decides its next round, those it admits then, until every item
   * is asked or not admitted.
   */
  private void inRounds(
      List<Item> items,
      java.util.function.Consumer<List<Item>> ask,
      java.util.function.Consumer<Item> notAdmitted) {
    List<Item> pending = items;
    while (!pending.isEmpty()) {
      List<Item> now = new ArrayList<>();
      List<Item> later = new ArrayList<>();
      for (Item item : pending) {
        Optional<Boolean> fits = decision(item);
        if (fits.isEmpty()) {
          later.add(item);
        } else if (fits.get()) {
          now.add(item);
        } else {
          notAdmitted.accept(item);
        }
      }
      if (!now.isEmpty()) {
        if (rounds != null) {
          rounds.started();
        }
        asking.set(true);
        ask.accept(now);
      } else if (!later.isEmpty()) {
        awaitDecision(later.getFirst());
      }
      pending = later;
    }
  }

  private Outcome notAdmitted(Item item) {
    return Outcome.of(
        Outcome.Kind.NOT_ASKED,
        fingerprint(item),
        null,
        "the " + spec.budgetGroup() + " share of the run budget is spent");
  }

  private void askPlannedPackets(
      List<Item> misses, PacketRole role, Map<String, Outcome> outcomes) {
    // Grouped by the role's own fingerprint, so a calibration keeps the same neighbours in order.
    List<List<Item>> packets = Packer.pack(misses, spec.packetSize(), this::sampleFingerprint);
    List<Item> missing = java.util.Collections.synchronizedList(new ArrayList<>());
    run(packets, packet -> missing.addAll(askPacket(packet, role, outcomes)));
    List<List<Item>> alone = missing.stream().map(List::of).toList();
    List<Item> missingTwice = java.util.Collections.synchronizedList(new ArrayList<>());
    run(alone, packet -> missingTwice.addAll(askPacket(packet, role, outcomes)));
    for (Item item : missingTwice) {
      settle(
          outcomes,
          item,
          Outcome.of(
              Outcome.Kind.NOT_ASKED, fingerprint(item), null, "missing from the answer twice"),
          false);
    }
  }

  /**
   * Runs the packets: the first alone, the rest in parallel once it is done. The role's first
   * packet of all goes before any other, also of the role's other calls running at the same time
   * (one per scope, for flow significance), so every later request reads the prompt's start from
   * the provider's cache instead of all writing it at once (#357, #754).
   */
  private void run(List<List<Item>> packets, java.util.function.Consumer<List<Item>> ask) {
    if (packets.isEmpty()) {
      return;
    }
    if (warming.compareAndSet(false, true)) {
      record.warmUp(packets.getFirst().getFirst().subject());
      try {
        ask.accept(packets.getFirst());
      } finally {
        warmed.complete(null);
      }
    } else {
      awaitWarmed();
      ask.accept(packets.getFirst());
    }
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (List<Item> packet : packets.subList(1, packets.size())) {
        futures.add(executor.submit(() -> ask.accept(packet)));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while asking " + spec.name(), e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
  }

  /** Asks one packet and settles its items; returns the items missing from the answer. */
  private List<Item> askPacket(List<Item> packet, PacketRole role, Map<String, Outcome> outcomes) {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= packet.size(); i++) {
      ids.add("I" + i);
    }
    LlmRequest request =
        noted(
            role.layout(packet, ids)
                .request(
                    spec.modelRole(),
                    spec.name() + " " + packet.size() + " items: " + packet.getFirst().subject(),
                    role.maxOutputTokens(packet),
                    List.of()),
            packet,
            ids);
    List<CheckInputs> inputs = new ArrayList<>();
    for (int i = 0; i < packet.size(); i++) {
      inputs.add(role.inputs(packet, ids, i).withSecrets(harness.secrets()));
    }
    List<Item> missing = new ArrayList<>();
    try {
      long expected = expectedOutput(packet.size());
      LlmResponse first = send(client, request, expected);
      Map<String, ItemAnswer> accepted = new LinkedHashMap<>();
      Map<String, List<Rejection>> rejected = new LinkedHashMap<>();
      Optional<Map<String, JsonNode>> entries = Envelope.entries(first.text());
      Map<String, JsonNode> firstEntries = entries.orElse(Map.of());
      for (int i = 0; i < packet.size(); i++) {
        String id = ids.get(i);
        if (entries.isEmpty()) {
          rejected.put(
              id,
              List.of(
                  new Rejection(
                      id,
                      "",
                      "Schema",
                      "The answer is not one JSON object with an entry per item"
                          + Optional.ofNullable(JsonFaults.of(first.text(), null))
                              .map(f -> ": " + f + ".")
                              .orElse("."))));
          continue;
        }
        JsonNode entry = entries.get().get(id);
        if (entry == null) {
          missing.add(packet.get(i));
          continue;
        }
        check(id, packet.get(i), entry, role, inputs.get(i), accepted, rejected);
      }
      for (Map.Entry<String, List<Rejection>> each : rejected.entrySet()) {
        JsonNode entry = firstEntries.get(each.getKey());
        rejected(
            packet.get(ids.indexOf(each.getKey())).subject(),
            1,
            entry == null ? first.text() : entry.toString(),
            reasons(each.getValue()));
      }
      Map<String, Boolean> repairedIds = new LinkedHashMap<>();
      Map<String, List<Rejection>> droppedFields = new LinkedHashMap<>();
      if (!rejected.isEmpty()) {
        List<Rejection> all = rejected.values().stream().flatMap(List::stream).toList();
        List<LlmMessage> turns = new ArrayList<>(request.messages());
        turns.add(LlmMessage.assistant(first.text().isBlank() ? "{}" : first.text()));
        turns.add(LlmMessage.user(Pipeline.repairTurn(all)));
        LlmResponse second =
            send(
                client,
                request.withMessages(turns).withPurpose(request.purpose() + " (repair)"),
                expected);
        Map<String, JsonNode> corrected = Envelope.entries(second.text()).orElse(Map.of());
        for (String id : List.copyOf(rejected.keySet())) {
          Item item = packet.get(ids.indexOf(id));
          JsonNode entry = repaired(firstEntries.get(id), rejected.get(id), corrected.get(id));
          Map<String, List<Rejection>> again = new LinkedHashMap<>();
          if (entry != null) {
            check(id, item, entry, role, inputs.get(ids.indexOf(id)), accepted, again);
            if (again.containsKey(id)) {
              rejected(item.subject(), 2, entry.toString(), reasons(again.get(id)));
            }
            // A field rejected again is left out, and the rest of the item kept.
            if (again.containsKey(id) && !Entries.whole(again.get(id))) {
              droppedFields.put(id, again.get(id));
              JsonNode kept = Entries.without(entry, again.get(id));
              again.remove(id);
              if (Entries.empty(kept)) {
                entry = null;
              } else {
                check(id, item, kept, role, inputs.get(ids.indexOf(id)), accepted, again);
              }
            }
          }
          if (entry == null || again.containsKey(id)) {
            countFields(role, item, null, rejected.get(id), again.getOrDefault(id, List.of()));
            settle(
                outcomes,
                item,
                dropped(
                    item,
                    entry == null
                        ? "rejected and not corrected: "
                            + String.join(" ", reasons(rejected.get(id)))
                        : "rejected twice: " + String.join(" ", reasons(again.get(id))),
                    List.of(),
                    // Not stored when the repair was cut off, since a larger limit may let it
                    // through, or held no readable JSON object or TOML document, which a new call
                    // may
                    // (#744, #872).
                    !second.cutOff() && Toml.readable(second.text())),
                false);
          } else {
            repairedIds.put(id, true);
          }
        }
      }
      accepted.forEach(
          (id, answer) -> {
            Item item = packet.get(ids.indexOf(id));
            countFields(
                role,
                item,
                answer,
                rejected.getOrDefault(id, List.of()),
                droppedFields.getOrDefault(id, List.of()));
            String json = stored(item, role, answer, inputs.get(ids.indexOf(id)));
            save(item, json, List.of());
            String fingerprint = fingerprint(item);
            settle(
                outcomes,
                item,
                json == null
                    ? Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "omitted")
                    : Outcome.of(Outcome.Kind.ANSWERED, fingerprint, json, null),
                repairedIds.containsKey(id));
          });
    } catch (BudgetExceededException e) {
      packet.forEach(
          item ->
              settle(
                  outcomes,
                  item,
                  Outcome.of(Outcome.Kind.NOT_ASKED, fingerprint(item), null, e.getMessage()),
                  false));
      return List.of();
    } catch (LlmException e) {
      packet.forEach(
          item ->
              settle(
                  outcomes,
                  item,
                  Outcome.of(Outcome.Kind.FAILED, fingerprint(item), null, e.getMessage()),
                  false));
      return List.of();
    }
    return missing;
  }

  /**
   * Counts the fields of an item the model left out, withdrew after they were rejected, or had
   * dropped after they were rejected twice, by kind (#741): the part of a field's name before its
   * first dot, such as {@code interactions}.
   *
   * @param accepted the accepted answer, or null when the item was dropped whole
   * @param first what the first answer had rejected
   * @param dropped what was rejected again and left out, or for a whole drop rejected again
   */
  private void countFields(
      PacketRole role,
      Item item,
      ItemAnswer accepted,
      List<Rejection> first,
      List<Rejection> dropped) {
    List<String> expected = role.askedFields(item);
    java.util.Set<String> said = new java.util.HashSet<>();
    if (accepted != null && !accepted.omitted()) {
      accepted.texts().forEach(t -> said.add(t.field()));
    }
    java.util.Set<String> lost = new java.util.LinkedHashSet<>();
    dropped.stream()
        .map(Rejection::field)
        .filter(f -> f != null && !f.isEmpty() && !said.contains(f))
        .forEach(lost::add);
    if (accepted == null) {
      // A whole drop loses every field the item asked for.
      lost.addAll(expected);
    }
    java.util.Set<String> withdrawn = new java.util.LinkedHashSet<>();
    if (accepted != null) {
      first.stream()
          .map(Rejection::field)
          .filter(f -> f != null && !f.isEmpty() && !said.contains(f) && !lost.contains(f))
          .forEach(withdrawn::add);
    }
    Map<String, int[]> counts = new java.util.TreeMap<>();
    java.util.function.Function<String, int[]> of =
        f ->
            counts.computeIfAbsent(
                f.contains(".") ? f.substring(0, f.indexOf('.')) : f, k -> new int[3]);
    if (accepted != null) {
      for (String field : expected) {
        if (!said.contains(field) && !withdrawn.contains(field) && !lost.contains(field)) {
          of.apply(field)[0]++;
        }
      }
    }
    withdrawn.forEach(f -> of.apply(f)[1]++);
    lost.forEach(f -> of.apply(f)[2]++);
    counts.forEach((kind, c) -> record.fields(kind, c[0], c[1], c[2]));
  }

  /**
   * An item's entry after a repair turn: the first answer without its rejected fields, with the
   * correction over it; the correction alone when the whole item was rejected; {@code null} when
   * nothing is left.
   */
  private static JsonNode repaired(
      JsonNode first, List<Rejection> rejections, JsonNode correction) {
    if (correction != null && correction.isObject() && correction.has("omitted")) {
      return correction;
    }
    if (first == null || !first.isObject() || Entries.whole(rejections)) {
      return correction;
    }
    tools.jackson.databind.node.ObjectNode merged =
        Entries.overlay(Entries.without(first, rejections), correction);
    return Entries.empty(merged) ? null : merged;
  }

  private static void check(
      String id,
      Item item,
      JsonNode entry,
      PacketRole role,
      CheckInputs inputs,
      Map<String, ItemAnswer> accepted,
      Map<String, List<Rejection>> rejected) {
    ItemAnswer answer = role.read(id, item, entry);
    List<Rejection> found = answer.omitted() ? List.of() : role.pipeline().check(answer, inputs);
    if (found.isEmpty()) {
      accepted.put(id, answer);
    } else {
      rejected.put(id, found);
    }
  }

  /**
   * Answers items in one agent conversation (knowledge-build epic, section 10, Agent loop): the
   * model explores with the role's read-only tools and submits through the {@code submit} tool,
   * which verifies each item at once. The conversation keeps the role's caps; at a cap it ends and
   * what was submitted and accepted is kept. An item the model never submitted counts as omitted,
   * so a warm rerun does not ask it again; one it submitted wrongly twice is dropped. Items the
   * cache holds are not asked.
   *
   * @param tools the role's read-only tools
   * @param filesRead the files the tools read during the conversation, with their blobs, recorded
   *     with each answer
   */
  public Map<String, Outcome> converse(
      List<Item> items,
      PacketRole role,
      io.lantern.llm.ToolBox tools,
      java.util.function.Supplier<List<FileRead>> filesRead) {
    return converse(items, role, tools, filesRead, stored -> true);
  }

  /**
   * Answers items in one agent conversation, as {@link #converse(List, PacketRole,
   * io.lantern.llm.ToolBox, java.util.function.Supplier)} does, reusing a cached answer only while
   * it also holds by the role's own test, such as every fact a tool showed it still holding.
   */
  public Map<String, Outcome> converse(
      List<Item> items,
      PacketRole role,
      io.lantern.llm.ToolBox tools,
      java.util.function.Supplier<List<FileRead>> filesRead,
      java.util.function.Predicate<StoredAnswer> holds) {
    Map<String, Outcome> outcomes = new LinkedHashMap<>();
    List<Item> packet = new ArrayList<>();
    for (Item item : items) {
      String fingerprint = fingerprint(item);
      Optional<StoredAnswer> stored = harness.answers().find(fingerprint);
      if (stored.isPresent() && revalidated(stored.get()) && holdsOrDropped(stored.get(), holds)) {
        settleReused(outcomes, item, reused(stored.get()));
      } else {
        missed(item, stored);
        packet.add(item);
      }
    }
    inRounds(
        packet,
        admittedNow -> conversePlanned(admittedNow, role, tools, filesRead, outcomes),
        item -> settle(outcomes, item, notAdmitted(item), false));
    return outcomes;
  }

  private void conversePlanned(
      List<Item> packet,
      PacketRole role,
      io.lantern.llm.ToolBox tools,
      java.util.function.Supplier<List<FileRead>> filesRead,
      Map<String, Outcome> outcomes) {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= packet.size(); i++) {
      ids.add("I" + i);
    }
    SubmitTool submit =
        new SubmitTool(
            tools,
            packet,
            ids,
            role,
            index -> role.inputs(packet, ids, index).withSecrets(harness.secrets()),
            this::rejected);
    LlmRequest request =
        noted(
            role.layout(packet, ids)
                .request(
                    spec.modelRole(),
                    spec.name() + " conversation: " + packet.getFirst().subject(),
                    role.maxOutputTokens(packet.size()),
                    submit.tools()),
            packet,
            ids);
    io.lantern.llm.Conversation conversation = client.converse(request, submit, spec.limits());
    LlmException failure = null;
    boolean capped = false;
    try {
      conversation.answer();
      conversation.end(io.lantern.llm.LlmConversation.Ending.ANSWERED);
    } catch (io.lantern.llm.ConversationCappedException e) {
      capped = true;
      LOG.info("{} kept what was submitted before: {}", request.purpose(), e.getMessage());
    } catch (BudgetExceededException e) {
      failure = e;
    } catch (LlmException e) {
      failure = e;
    }
    Map<String, ItemAnswer> accepted = submit.accepted();
    List<FileRead> read = filesRead.get();
    for (int i = 0; i < packet.size(); i++) {
      Item item = packet.get(i);
      String id = ids.get(i);
      String fingerprint = fingerprint(item);
      ItemAnswer answer = accepted.get(id);
      if (answer != null) {
        countFields(role, item, answer, List.of(), List.of());
        String json =
            stored(item, role, answer, role.inputs(packet, ids, i).withSecrets(harness.secrets()));
        save(item, json, read);
        settle(
            outcomes,
            item,
            json == null
                ? Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "omitted")
                : Outcome.of(Outcome.Kind.ANSWERED, fingerprint, json, null),
            submit.repaired(id));
      } else if (submit.dropped().contains(id)) {
        countFields(role, item, null, List.of(), List.of());
        // Stored when the model got it wrong twice, or left it uncorrected in a conversation that
        // ended on its own; not when a cap ended the conversation before it could correct it.
        settle(
            outcomes,
            item,
            dropped(
                item,
                "rejected twice",
                read,
                submit.rejectedTwice(id) || (!capped && failure == null)),
            false);
      } else if (failure instanceof BudgetExceededException) {
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.NOT_ASKED, fingerprint, null, failure.getMessage()),
            false);
      } else if (failure != null) {
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.FAILED, fingerprint, null, failure.getMessage()),
            false);
      } else {
        // A conversation that ends without submitting an item submitted nothing for it.
        save(item, null, read);
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "not submitted"),
            false);
      }
    }
  }

  /**
   * What a stored answer gives a rerun: the answer, an omission, or the drop it repeats without a
   * call (#682).
   */
  private static Outcome reused(StoredAnswer stored) {
    if (stored.isDropped()) {
      return Outcome.of(
          Outcome.Kind.DROPPED,
          stored.fingerprint(),
          null,
          Outcome.REPEATED_DROP + stored.dropped());
    }
    return stored.omitted()
        ? Outcome.of(Outcome.Kind.CACHED, stored.fingerprint(), null, "omitted")
        : Outcome.of(Outcome.Kind.CACHED, stored.fingerprint(), stored.answer(), null);
  }

  /**
   * Whether a stored answer still holds by the role's own test; a stored drop has no answer to
   * test, and repeats while its fingerprint and files hold.
   */
  private static boolean holdsOrDropped(
      StoredAnswer stored, java.util.function.Predicate<StoredAnswer> holds) {
    return stored.isDropped() || holds.test(stored);
  }

  private void settleReused(Map<String, Outcome> outcomes, Item item, Outcome outcome) {
    synchronized (this) {
      outcomes.put(item.subject(), outcome);
      if (!harness.retryPass()) {
        record.reused(item.subject(), outcome);
      }
    }
    ended(item);
  }

  /**
   * Drops an item whose answer failed the checks twice: stores the drop under its fingerprint, so a
   * rerun on the same inputs repeats it without a call (#682), and notes it for the run's list of
   * rejections.
   */
  private Outcome dropped(Item item, String reason, List<FileRead> filesRead, boolean kept) {
    String fingerprint = fingerprint(item);
    harness.rejected(
        new RejectedAnswer(spec.name(), item.subject(), 0, null, List.of(String.valueOf(reason))));
    if (!kept) {
      return Outcome.unstoredDrop(fingerprint, reason);
    }
    harness
        .answers()
        .save(
            new StoredAnswer(
                fingerprint,
                spec.name(),
                item.subject(),
                cacheVersion(),
                model.provider(),
                model.model(),
                false,
                null,
                reason == null || reason.isBlank() ? "rejected twice" : reason,
                filesRead,
                item.inputs()));
    return Outcome.of(Outcome.Kind.DROPPED, fingerprint, null, reason);
  }

  /** Notes an answer the checks rejected, for the run's list of rejections (#682). */
  void rejected(String subject, int attempt, String answer, List<String> reasons) {
    harness.rejected(new RejectedAnswer(spec.name(), subject, attempt, answer, reasons));
  }

  /** The checks' rejections as readable reasons, each with the field it names. */
  static List<String> reasons(List<Rejection> rejections) {
    List<String> reasons = new ArrayList<>();
    for (Rejection rejection : rejections) {
      String field =
          rejection.field() == null || rejection.field().isEmpty() ? "" : rejection.field() + ": ";
      reasons.add(field + rejection.reason());
    }
    return reasons;
  }

  /**
   * An accepted entry as it is stored, or null when omitted: each text the role lets keep its
   * wording, and whose earlier version on this prompt and model cited only lines the item still
   * shows, keeps that earlier text (#722, ADR 0126).
   */
  private String stored(Item item, PacketRole role, ItemAnswer answer, CheckInputs inputs) {
    if (answer.omitted()) {
      return null;
    }
    if (answer.texts().stream().noneMatch(role::carries)) {
      return role.store(item, answer);
    }
    Map<String, CarryForward.Earlier> earlier = earlier(item.subject()).orElse(Map.of());
    java.util.function.Function<CheckedText, String> about = t -> role.about(item, t);
    List<CheckedText> merged =
        CarryForward.merge(answer.texts(), earlier, inputs.references(), role::carries, about);
    // A kept text passes the checks again, against what the item shows now; one that fails takes
    // the text written this time.
    java.util.Set<String> failed = new java.util.HashSet<>();
    role.pipeline()
        .check(new ItemAnswer(answer.item(), answer.raw(), merged, answer.claims()), inputs)
        .forEach(r -> failed.add(r.field()));
    List<CheckedText> texts = new ArrayList<>(merged.size());
    for (int i = 0; i < merged.size(); i++) {
      texts.add(failed.contains(merged.get(i).field()) ? answer.texts().get(i) : merged.get(i));
    }
    ItemAnswer kept = new ItemAnswer(answer.item(), answer.raw(), texts, answer.claims());
    return CarryForward.mark(
        role.store(item, kept),
        kept.texts().stream().filter(role::carries).toList(),
        inputs.references(),
        about);
  }

  /**
   * The texts of the answer stored last for the subject on this prompt and model, by field, that
   * recorded what they rest on (#722): what a re-asked answer keeps where its evidence is
   * unchanged.
   */
  public Optional<Map<String, CarryForward.Earlier>> earlier(String subject) {
    return harness
        .answers()
        .previous(spec.name(), subject, cacheVersion(), model.provider(), model.model())
        .filter(a -> a.answer() != null)
        // A rejected answer asked again is worded anew, none of its texts kept (#751).
        .filter(a -> harness.retryReason(a.fingerprint()) == null)
        .map(a -> CarryForward.earlier(a.answer()));
  }

  /** Stores an accepted answer under the item's fingerprint. */
  void save(Item item, String json, List<FileRead> filesRead) {
    harness
        .answers()
        .save(
            new StoredAnswer(
                fingerprint(item),
                spec.name(),
                item.subject(),
                cacheVersion(),
                model.provider(),
                model.model(),
                json == null,
                json,
                filesRead,
                item.inputs()));
  }

  private void settle(Map<String, Outcome> outcomes, Item item, Outcome outcome, boolean repaired) {
    synchronized (this) {
      outcomes.put(item.subject(), outcome);
      record.outcome(item.subject(), outcome, repaired);
    }
    ended(item);
  }

  /** The answer stored last for the subject by this role, whatever its fingerprint. */
  public Optional<StoredAnswer> latest(String subject) {
    return harness.answers().latest(spec.name(), subject);
  }

  /**
   * Notes why an item the cache does not answer is asked (#755), before this run's answer takes the
   * place of the one stored last: a stored answer under its fingerprint whose citations no longer
   * hold, or else what changed since the answer, or drop, stored last for its subject on the same
   * prompt and model, or failing that on any.
   *
   * @param stored the answer stored under the item's fingerprint, if any
   */
  private void missed(Item item, Optional<StoredAnswer> stored) {
    if (stored.isPresent() && revalidated(stored.get())) {
      record.why(item.subject(), AskedWhy.UNHELD);
      return;
    }
    Optional<StoredAnswer> previous =
        harness
            .answers()
            .previous(spec.name(), item.subject(), cacheVersion(), model.provider(), model.model())
            .or(() -> harness.answers().latest(spec.name(), item.subject()));
    record.why(
        item.subject(),
        AskedWhy.of(
            previous,
            item.inputs(),
            cacheVersion(),
            model.provider(),
            model.model(),
            harness.blobs()::blob));
  }

  /** Whether every file the answer's tools read still has the blob it read. */
  private boolean revalidated(StoredAnswer answer) {
    for (FileRead file : answer.filesRead()) {
      Optional<String> now = harness.blobs().blob(file.repo(), file.path());
      if (now.isEmpty() || !now.get().equals(file.blob())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the item fits its role's share, waiting for its group's round if it is not decided yet.
   */
  private boolean admitted(Item item) {
    while (true) {
      Optional<Boolean> fits = decision(item);
      if (fits.isPresent()) {
        return fits.get();
      }
      awaitDecision(item);
    }
  }

  /**
   * Whether the item fits its role's share: as its group decided, nothing yet if it waits for a
   * later round, or for an item no plan knew, decided now as a claim on what is left.
   */
  private Optional<Boolean> decision(Item item) {
    if (share == null) {
      return Optional.of(true);
    }
    for (String key : List.of("subject " + item.subject(), fingerprint(item))) {
      Boolean fits = admitted.get(key);
      if (fits != null) {
        return Optional.of(fits);
      }
      if (undecided.contains(key)) {
        return Optional.empty();
      }
    }
    String key = fingerprint(item);
    synchronized (this) {
      Boolean fits = admitted.get(key);
      if (fits == null) {
        fits =
            rounds.claim(
                new ShareRounds.Entry(this, key, item.subject(), item, itemCost(item), Set.of()));
        admitted.put(key, fits);
      }
      return Optional.of(fits);
    }
  }

  private void awaitDecision(Item item) {
    String key = "subject " + item.subject();
    rounds.await(this, undecided.contains(key) ? key : fingerprint(item));
  }

  /**
   * What one item is expected to cost, priced as the plan prices it: the prompt's start read from
   * the cache once the role's first request has written it, a role that groups items into requests
   * paying its share of each request's start, and the batch price when the role batches.
   */
  BigDecimal itemCost(Item item) {
    return cost(Tokens.estimate(item.rendering()));
  }

  /**
   * What a round's items of this role are expected to cost, priced as the role will ask them. A
   * role that groups items into requests asks the round's items together, packed as they will be
   * sent (a new request at every change of repository), so each request reads the prompt's start
   * from the cache, or the first of the role's writes it; a small round packs fewer items a request
   * than the plan's average. Other roles' items cost what each is expected to cost alone.
   */
  BigDecimal roundCost(List<ShareRounds.Entry> entries) {
    int packet = spec.packetSize();
    if (packet <= 1 || entries.stream().anyMatch(entry -> entry.item() == null)) {
      BigDecimal cost = BigDecimal.ZERO;
      for (ShareRounds.Entry entry : entries) {
        cost = cost.add(entry.cost());
      }
      return cost;
    }
    Map<String, Integer> byRepo = new LinkedHashMap<>();
    long input = 0;
    for (ShareRounds.Entry entry : entries) {
      byRepo.merge(Packer.repo(entry.item().locality()), 1, Integer::sum);
      input += Tokens.estimate(entry.item().rendering());
    }
    long requests = 0;
    for (int count : byRepo.values()) {
      requests += (count + packet - 1) / packet;
    }
    long prefix = prefixTokens;
    long write = 0;
    long read = 0;
    if (model.supports(Capability.PROMPT_CACHING) && prefix > 0) {
      write = asking.get() ? 0 : prefix;
      read = prefix * (requests - (asking.get() ? 0 : 1));
    } else {
      input += prefix * requests;
    }
    TokenUsage expected = expected(input, write, read, entries.size(), requests);
    input = expected.input();
    write = expected.cacheWrite();
    read = expected.cacheRead();
    long output = expected.output();
    if (batching()) {
      return model
          .pricing()
          .batchCost(new TokenUsage(input, output, 0, read))
          .add(model.pricing().cost(new TokenUsage(0, 0, 0, 0, write)));
    }
    return model.pricing().cost(new TokenUsage(input, output, write, read));
  }

  /** What an item of the given input tokens is expected to cost (see {@link #itemCost}). */
  private BigDecimal cost(long tokens) {
    long input = tokens;
    long prefix = prefixTokens;
    long read = 0;
    int packet = spec.packetSize();
    // A grouped item pays its share of its requests' starts, as the plan packed them.
    long share = (long) Math.ceil(prefix * (requestsPerItem > 0 ? requestsPerItem : 1.0 / packet));
    if (model.supports(Capability.PROMPT_CACHING) && prefix > 0) {
      if (packet > 1) {
        read = share;
      } else {
        read = Math.min(prefix, input);
        input -= read;
      }
    } else if (packet > 1) {
      input += share;
    }
    // A grouped item pays its share of its requests' thinking and turns, as of their starts.
    double requests = packet <= 1 ? 1 : requestsPerItem > 0 ? requestsPerItem : 1.0 / packet;
    TokenUsage usage = expected(input, 0, read, 1, requests);
    return batching() ? model.pricing().batchCost(usage) : model.pricing().cost(usage);
  }

  /**
   * The tokens the role's requests are expected to use, from what their first turns send: the
   * answers to their items, what a model that thinks thinks before each request, and, for a role
   * that converses, the turns after the first, each sending the conversation so far again, read
   * from the prompt cache where the model has one, with what the turn before added (#694).
   *
   * @param input the uncached input of the requests' first turns
   * @param write the tokens their first turns write to the prompt cache
   * @param read the tokens their first turns read from it
   * @param items the items they answer
   * @param requests how many requests, or conversations, ask them
   */
  TokenUsage expected(long input, long write, long read, int items, double requests) {
    double turns = spec.expectedTurns();
    long output = (long) items * spec.expectedOutputTokens() + thinking(requests * turns);
    if (turns > 1 && requests > 0) {
      double first = (input + write + read) / requests;
      double added = spec.expectedTurnTokens();
      input += Math.round(requests * (turns - 1) * added);
      long resent =
          Math.round(requests * ((turns - 1) * first + added * (turns - 1) * (turns - 2) / 2));
      if (model.supports(Capability.PROMPT_CACHING)) {
        read += resent;
      } else {
        input += resent;
      }
    }
    double scale = model.estimateTokenScale();
    return new TokenUsage(
        Math.round(input * scale),
        Math.round(output * model.estimateOutputScale()),
        Math.round(write * scale),
        Math.round(read * scale));
  }

  /** What the model is expected to think in the given number of requests or turns (#694). */
  private long thinking(double requests) {
    return model.thinking().thinks() ? Math.round(requests * spec.expectedThinkingTokens()) : 0;
  }

  /** The output one request of the given items is expected to take, its thinking included. */
  long expectedOutput(int items) {
    return Math.round(
        ((long) items * spec.expectedOutputTokens() + thinking(1)) * model.estimateOutputScale());
  }

  /**
   * Sends a request of one of the role's items: interactively, or once the role batches, its first
   * request interactively with a long-lived cache and the others, once that is answered, through
   * the batch API.
   *
   * @param expectedOutputTokens the output the plan expects of the request, at which a batched
   *     request is reserved against the budget instead of at its most output (ADR 0102)
   */
  LlmResponse send(LlmClient client, LlmRequest request, long expectedOutputTokens) {
    BatchCollector collector = batch;
    if (collector == null) {
      return packing() ? packets.call(request) : client.call(request);
    }
    LlmRequest longLived = request.withLongLivedCache();
    if (prefixSent.compareAndSet(false, true)) {
      warmer = Thread.currentThread();
      try {
        return client.call(longLived);
      } finally {
        prefixWritten.complete(null);
      }
    }
    if (Thread.currentThread() == warmer) {
      // The warm-up item's repair turn: the role's other items wait for this item to finish, so
      // it goes interactively rather than as a batch of one.
      return client.call(longLived);
    }
    prefixWritten.join();
    return collector.call(longLived, expectedOutputTokens);
  }

  /**
   * Records that a planned item was not asked for a reason of the caller's, such as a unit whose
   * parts were none of them summarized, so that every planned item lands in one count.
   */
  public void notAsked(String subject, String reason) {
    LOG.info("Not asked about {} for {}: {}", subject, spec.name(), reason);
    record.outcome(subject, Outcome.of(Outcome.Kind.NOT_ASKED, null, null, reason), false);
    settledWithoutAsking(subject);
    ended(subject);
  }

  /**
   * Counts a field of a single item the model left out ({@code omitted}) or that was dropped after
   * two rejections, for a role that asks for one text an item outside a packet, such as Summarize's
   * summary (#741); a packet role names its fields through {@link PacketRole#askedFields}.
   */
  public void fieldLeftOut(String field, boolean dropped) {
    record.fields(field, dropped ? 0 : 1, 0, dropped ? 1 : 0);
  }

  /** The role's run record. */
  public RoleRecord record() {
    return record;
  }
}
