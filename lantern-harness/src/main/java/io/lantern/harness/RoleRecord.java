package io.lantern.harness;

import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmCallLog;
import io.lantern.llm.LlmConversation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What one role did in a run (knowledge-build epic, section 10, Observability): items planned,
 * cache hits, items asked, accepted, repaired, dropped, not asked and failed, calls, tokens by kind
 * and dollars, batch and interactive apart. Thread-safe.
 */
public final class RoleRecord implements LlmCallLog {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String role;
  private final String provider;
  private final String model;
  private int planned;
  private int packetSize = 1;
  private int cached;
  private int answered;
  private int omitted;
  private int repaired;
  private int dropped;
  private int notAsked;
  private int failed;
  private int calls;
  private int batchCalls;
  private int conversations;
  private long input;
  private long output;
  private long cacheWrite;
  private long cacheWriteLong;
  private long cacheRead;
  private BigDecimal usd = BigDecimal.ZERO;
  private BigDecimal batchUsd = BigDecimal.ZERO;
  private Estimate estimate;
  private String warmUp;
  private final List<String> asked = new ArrayList<>();
  private final List<String> unasked = new ArrayList<>();
  private final List<String> failedSubjects = new ArrayList<>();
  private final List<String> reused = new ArrayList<>();
  private final Map<String, AskedWhy> why = new HashMap<>();
  private final Map<String, String> unaskedWhy = new java.util.LinkedHashMap<>();
  private final Map<String, int[]> fields = new java.util.TreeMap<>();

  /**
   * How many fields of one kind, such as {@code interactions}, the model left out of an item it
   * answered (or omitted whole), withdrew in a repair turn after they were rejected, or had dropped
   * after they were rejected twice (#741).
   */
  public record FieldCounts(int omitted, int withdrawn, int dropped) {}

  RoleRecord(String role, String provider, String model) {
    this.role = role;
    this.provider = provider;
    this.model = model;
  }

  public String role() {
    return role;
  }

  @Override
  public synchronized void record(LlmCall call) {
    if (call.outcome() == LlmCall.Outcome.REFUSED) {
      return;
    }
    calls++;
    add(
        call.usage().input(),
        call.usage().output(),
        call.usage().cacheWrite(),
        call.usage().cacheRead());
    cacheWriteLong += call.usage().cacheWriteLong();
    usd = usd.add(call.costUsd());
    if (call.batch()) {
      batchCalls++;
      batchUsd = batchUsd.add(call.costUsd());
    }
  }

  @Override
  public synchronized void recordConversation(LlmConversation conversation) {
    conversations++;
  }

  private void add(long in, long out, long write, long read) {
    input += in;
    output += out;
    cacheWrite += write;
    cacheRead += read;
  }

  synchronized void planned(Estimate estimate) {
    planned += estimate.items();
    this.estimate = this.estimate == null ? estimate : sum(this.estimate, estimate);
  }

  private static Estimate sum(Estimate a, Estimate b) {
    return new Estimate(
        a.items() + b.items(),
        a.cached() + b.cached(),
        a.toAsk() + b.toAsk(),
        a.inputTokens() + b.inputTokens(),
        a.cacheWriteTokens() + b.cacheWriteTokens(),
        a.cacheReadTokens() + b.cacheReadTokens(),
        a.outputTokens() + b.outputTokens(),
        a.usd().add(b.usd()),
        b.shareUsd());
  }

  /**
   * Counts an item the cache settled without a call: its answer, an omission, or a drop it repeats
   * (#682); each counts as cached.
   */
  synchronized void reused(String subject, Outcome outcome) {
    cached++;
    reused.add(subject);
  }

  synchronized void outcome(String subject, Outcome outcome, boolean wasRepaired) {
    switch (outcome.kind()) {
      case CACHED -> cached++;
      case ANSWERED -> answered++;
      case OMITTED -> omitted++;
      case DROPPED -> dropped++;
      case NOT_ASKED -> notAsked++;
      case FAILED -> failed++;
    }
    if (wasRepaired) {
      repaired++;
    }
    switch (outcome.kind()) {
      case CACHED -> reused.add(subject);
      case NOT_ASKED -> {
        unasked.add(subject);
        unaskedWhy.putIfAbsent(subject, outcome.reason() == null ? "not asked" : outcome.reason());
      }
      case FAILED -> {
        unasked.add(subject);
        unaskedWhy.putIfAbsent(
            subject,
            "the request failed" + (outcome.reason() == null ? "" : ": " + outcome.reason()));
        failedSubjects.add(subject);
      }
      default -> asked.add(subject);
    }
  }

  /** Notes why an item the cache did not answer is asked (#755). */
  synchronized void why(String subject, AskedWhy reason) {
    why.put(subject, reason);
  }

  /** Why the item of the subject was asked, if the cache did not answer it this run. */
  public synchronized Optional<AskedWhy> why(String subject) {
    return Optional.ofNullable(why.get(subject));
  }

  /** How many calls the role made, batched ones included. */
  public synchronized int calls() {
    return calls;
  }

  /** How many of the role's calls went through the provider's batch API. */
  public synchronized int batchCalls() {
    return batchCalls;
  }

  /** How many items the role planned. */
  public synchronized int planned() {
    return planned;
  }

  /**
   * How many items landed in a count: cached, accepted (omitted included), dropped, not asked and
   * failed. Every planned item lands in exactly one, so this equals {@link #planned()} once the
   * role is done.
   */
  public synchronized int settled() {
    return cached + answered + omitted + dropped + notAsked + failed;
  }

  /** How many items the cache answered. */
  public synchronized int cached() {
    return cached;
  }

  /** How many items were not asked for lack of budget. */
  public synchronized int notAsked() {
    return notAsked;
  }

  /**
   * The subjects of the items the model was asked about and that it answered, left out or had
   * rejected, in the order they finished; failed ones are {@link #unasked()}.
   */
  public synchronized List<String> asked() {
    return List.copyOf(asked);
  }

  /**
   * The subjects of the items left without an answer of this run: not asked for budget or a model
   * without what they need, or failed; the scorer counts them apart (ADR 0072).
   */
  public synchronized List<String> unasked() {
    return List.copyOf(unasked);
  }

  /**
   * The subjects of the items whose request failed, a part of {@link #unasked()}, so the scorer can
   * count them apart from those not asked (#734).
   */
  public synchronized List<String> failed() {
    return List.copyOf(failedSubjects);
  }

  /** The subjects of the items the answer cache answered. */
  public synchronized List<String> reused() {
    return List.copyOf(reused);
  }

  /** What the role's calls cost. */
  public synchronized BigDecimal costUsd() {
    return usd;
  }

  /** The role's plan, summed over its plans in the run, or null if it planned nothing. */
  public synchronized Estimate estimate() {
    return estimate;
  }

  /** Records the item that went first and warmed the prompt cache. */
  synchronized void warmUp(String subject) {
    if (warmUp == null) {
      warmUp = subject;
    }
  }

  /** The item that went first and warmed the prompt cache, or null if none did. */
  public synchronized String warmUp() {
    return warmUp;
  }

  /** Records how many items the role sent a request. */
  synchronized void packetSize(int packetSize) {
    this.packetSize = packetSize;
  }

  /** The record as the run record writes it. */
  /** Why each item not asked, a failed one included, was not, by its subject (#741). */
  public synchronized Map<String, String> unaskedReasons() {
    return Map.copyOf(unaskedWhy);
  }

  /** Counts fields of one kind left out, withdrawn or dropped (#741). */
  synchronized void fields(String kind, int omitted, int withdrawn, int dropped) {
    if (omitted + withdrawn + dropped == 0) {
      return;
    }
    int[] counts = fields.computeIfAbsent(kind, k -> new int[3]);
    counts[0] += omitted;
    counts[1] += withdrawn;
    counts[2] += dropped;
  }

  /** The fields left out, withdrawn or dropped, by kind (#741). */
  public synchronized Map<String, FieldCounts> fields() {
    Map<String, FieldCounts> result = new java.util.TreeMap<>();
    fields.forEach((k, c) -> result.put(k, new FieldCounts(c[0], c[1], c[2])));
    return result;
  }

  public synchronized ObjectNode toJson() {
    ObjectNode node = JSON.createObjectNode();
    node.put("role", role);
    node.put("provider", provider);
    node.put("model", model);
    node.put("packetSize", packetSize);
    ObjectNode items = node.putObject("items");
    items.put("planned", planned);
    items.put("cached", cached);
    items.put("asked", answered + omitted + dropped + failed);
    items.put("accepted", answered + omitted);
    items.put("omitted", omitted);
    items.put("repaired", repaired);
    items.put("dropped", dropped);
    items.put("notAsked", notAsked);
    items.put("failed", failed);
    if (!fields.isEmpty()) {
      ObjectNode byKind = node.putObject("fields");
      fields.forEach(
          (k, c) -> {
            ObjectNode counts = byKind.putObject(k);
            counts.put("omitted", c[0]);
            counts.put("withdrawn", c[1]);
            counts.put("dropped", c[2]);
          });
    }
    if (warmUp != null) {
      node.put("warmUp", warmUp);
    }
    node.put("calls", calls);
    node.put("batchCalls", batchCalls);
    node.put("conversations", conversations);
    ObjectNode tokens = node.putObject("tokens");
    tokens.put("input", input);
    tokens.put("output", output);
    tokens.put("cacheWrite", cacheWrite);
    tokens.put("cacheWriteLong", cacheWriteLong);
    tokens.put("cacheRead", cacheRead);
    node.put("costUsd", usd.toPlainString());
    node.put("batchCostUsd", batchUsd.toPlainString());
    node.put("interactiveCostUsd", usd.subtract(batchUsd).toPlainString());
    if (estimate != null) {
      ObjectNode e = node.putObject("estimate");
      e.put("toAsk", estimate.toAsk());
      e.put("inputTokens", estimate.inputTokens());
      e.put("cacheWriteTokens", estimate.cacheWriteTokens());
      e.put("cacheReadTokens", estimate.cacheReadTokens());
      e.put("outputTokens", estimate.outputTokens());
      e.put("usd", estimate.usd().toPlainString());
      if (estimate.shareUsd() != null) {
        e.put("shareUsd", estimate.shareUsd().toPlainString());
      }
    }
    return node;
  }
}
