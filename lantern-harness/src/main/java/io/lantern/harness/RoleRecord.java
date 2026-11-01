package io.lantern.harness;

import io.lantern.llm.LlmCall;
import io.lantern.llm.LlmCallLog;
import io.lantern.llm.LlmConversation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
  private final List<String> asked = new ArrayList<>();

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
    if (outcome.kind() != Outcome.Kind.CACHED && outcome.kind() != Outcome.Kind.NOT_ASKED) {
      asked.add(subject);
    }
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

  /** The subjects of the items the model was asked about, in the order they finished. */
  public synchronized List<String> asked() {
    return List.copyOf(asked);
  }

  /** What the role's calls cost. */
  public synchronized BigDecimal costUsd() {
    return usd;
  }

  /** The role's plan, summed over its plans in the run, or null if it planned nothing. */
  public synchronized Estimate estimate() {
    return estimate;
  }

  /** The record as the run record writes it. */
  public synchronized ObjectNode toJson() {
    ObjectNode node = JSON.createObjectNode();
    node.put("role", role);
    node.put("provider", provider);
    node.put("model", model);
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
