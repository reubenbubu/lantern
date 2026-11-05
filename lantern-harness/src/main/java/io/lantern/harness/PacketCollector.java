package io.lantern.harness;

import io.lantern.llm.CacheBreak;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.Tokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins the first requests of a role's items, sent one at a time from many threads, into packed
 * requests (#874, {@code docs/research/haiku-request-pricing.md}): a request waits until no other
 * has come for the quiet time, or a packet is full; then the waiting requests are packed in the
 * order they came, each packet holding as many as fit the input ceiling and the item limit, and
 * sent as one request; each caller gets its own part of the answer, as its own request would have
 * had it. An item the answer leaves out, or whose part cannot be read, is asked again alone, as is
 * every item of a packet whose call fails. A repair turn is never packed: it goes alone, with only
 * its own item's conversation. The packed request caches what the items' own requests cache of the
 * system prompt, and nothing of the items. Thread-safe.
 */
final class PacketCollector {

  private static final Logger LOG = LoggerFactory.getLogger(PacketCollector.class);

  /** How long the collector waits for more requests before it packs what it has. */
  static final Duration QUIET = Duration.ofMillis(250);

  /** The most output a packed request may ask for, well under the models' limits. */
  static final int MAX_OUTPUT = 64_000;

  /** The stop reason a part of a packed answer reports: it ended where the item's part did. */
  static final String END_TURN = "end_turn";

  private record Pending(LlmRequest request, CompletableFuture<LlmResponse> result) {}

  private final LlmClient client;
  private final Packing packing;
  private final Packing.Limits limits;
  private final double tokenScale;
  private final Duration quiet;
  private final List<Pending> pending = new ArrayList<>();
  private long lastAdded;
  private boolean flushing;

  /**
   * @param tokenScale how many of the model's tokens one token of Lantern's estimate is, since the
   *     ceiling is in the model's tokens
   */
  PacketCollector(LlmClient client, Packing packing, Packing.Limits limits, double tokenScale) {
    this(client, packing, limits, tokenScale, QUIET);
  }

  PacketCollector(
      LlmClient client, Packing packing, Packing.Limits limits, double tokenScale, Duration quiet) {
    this.tokenScale = tokenScale;
    this.client = Objects.requireNonNull(client, "client");
    this.packing = Objects.requireNonNull(packing, "packing");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.quiet = Objects.requireNonNull(quiet, "quiet");
  }

  /** Whether the request can share a packet: an item's first turn, with no tools. */
  static boolean packable(LlmRequest request) {
    return request.messages().size() == 1 && request.tools().isEmpty();
  }

  /**
   * Sends the request in the next packet and waits for its part of the answer; if the packet leaves
   * it out, or it fits no packet with others, sends it alone.
   */
  LlmResponse call(LlmRequest request) {
    if (!packable(request) || !packing.packs(request.messages().getFirst().text())) {
      return client.call(request);
    }
    Pending mine = new Pending(request, new CompletableFuture<>());
    synchronized (this) {
      pending.add(mine);
      lastAdded = System.nanoTime();
      if (!flushing) {
        flushing = true;
        Thread.ofVirtual().name("packet-collector").start(this::flushLoop);
      } else {
        notifyAll();
      }
    }
    LlmResponse part;
    try {
      part = mine.result().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while waiting for a packet", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw new LlmException("Packet failed", e);
    }
    // Left out of the packet's answer, or alone: asked as its own request.
    return part != null ? part : client.call(request);
  }

  /** Packs and sends while requests keep coming; ends when none is left. */
  private void flushLoop() {
    while (true) {
      List<Pending> packet;
      synchronized (this) {
        while (true) {
          if (pending.isEmpty()) {
            flushing = false;
            return;
          }
          long waited = System.nanoTime() - lastAdded;
          if (full() || waited >= quiet.toNanos()) {
            break;
          }
          try {
            long left = quiet.toNanos() - waited;
            wait(Math.max(1, left / 1_000_000), (int) (left % 1_000_000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.forEach(p -> p.result().complete(null));
            pending.clear();
            flushing = false;
            return;
          }
        }
        packet = next();
      }
      List<Pending> sent = packet;
      Thread.ofVirtual().name("packet").start(() -> send(sent));
    }
  }

  /** Whether the waiting requests fill a packet already. */
  private boolean full() {
    return pending.size() >= limits.items() || inputTokens(pending) > limits.inputTokens();
  }

  /** The next packet: the waiting requests, in the order they came, as many as fit. */
  private List<Pending> next() {
    List<Pending> packet = new ArrayList<>();
    for (Pending p : pending) {
      List<Pending> with = new ArrayList<>(packet);
      with.add(p);
      if (!packet.isEmpty()
          && (with.size() > limits.items() || inputTokens(with) > limits.inputTokens())) {
        break;
      }
      packet.add(p);
    }
    pending.subList(0, packet.size()).clear();
    return packet;
  }

  /**
   * The input of a packed request of these items in the model's tokens, as estimated: one system
   * prompt, every turn.
   */
  private long inputTokens(List<Pending> packet) {
    if (packet.isEmpty()) {
      return 0;
    }
    String system = packet.getFirst().request().system();
    long tokens = system == null ? 0 : Tokens.estimate(system);
    for (Pending p : packet) {
      tokens += Tokens.estimate(p.request().messages().getFirst().text());
    }
    return (long) Math.ceil(tokens * tokenScale);
  }

  private void send(List<Pending> packet) {
    if (packet.size() == 1) {
      packet.getFirst().result().complete(null);
      return;
    }
    List<String> turns =
        packet.stream().map(p -> p.request().messages().getFirst().text()).toList();
    LlmRequest first = packet.getFirst().request();
    long output = 0;
    for (Pending p : packet) {
      output += p.request().maxOutputTokens() + (long) limits.thinkingPerItem();
    }
    int maxOutput = (int) Math.min(MAX_OUTPUT, output);
    // Only the system prompt is cached, as each item's request would cache it: the items are never
    // sent again.
    List<CacheBreak> system =
        first.cacheBreaks().stream().filter(at -> at.message() == CacheBreak.SYSTEM).toList();
    LlmRequest packed =
        new LlmRequest(
            first.role(),
            packing.purpose(packet.size()),
            first.system(),
            List.of(LlmMessage.user(packing.join(turns))),
            maxOutput,
            first.cacheSystem(),
            List.of(),
            system);
    LOG.info(
        "{}: {} items, about {} input tokens (ceiling {}), at most {} output tokens",
        packed.purpose(),
        packet.size(),
        inputTokens(packet),
        limits.inputTokens(),
        maxOutput);
    LlmResponse answer;
    try {
      answer = client.call(packed);
    } catch (RuntimeException e) {
      // Each item is asked alone, and hears of its own error if that fails too.
      LOG.warn("{} failed, so its items are asked alone: {}", packed.purpose(), e.getMessage());
      packet.forEach(p -> p.result().complete(null));
      return;
    } catch (Error e) {
      packet.forEach(p -> p.result().completeExceptionally(e));
      throw e;
    }
    List<String> parts;
    try {
      parts = packing.split(answer.text(), turns);
    } catch (RuntimeException e) {
      LOG.warn("{}: its answer could not be split: {}", packed.purpose(), e.getMessage());
      parts = java.util.Collections.nCopies(packet.size(), null);
    }
    if (answer.cutOff()) {
      // The last part read may have been cut short: it is asked again alone.
      parts = new ArrayList<>(parts);
      for (int i = parts.size() - 1; i >= 0; i--) {
        if (parts.get(i) != null) {
          parts.set(i, null);
          break;
        }
      }
    }
    List<TokenUsage> shares = shares(answer.usage(), turns, parts);
    int missing = 0;
    for (int i = 0; i < packet.size(); i++) {
      String part = i < parts.size() ? parts.get(i) : null;
      if (part == null) {
        missing++;
        packet.get(i).result().complete(null);
      } else {
        packet
            .get(i)
            .result()
            .complete(
                new LlmResponse(
                    part, END_TURN, shares.get(i), List.of(), List.of(), answer.servedBy()));
      }
    }
    if (missing > 0) {
      LOG.warn(
          "{} left out {} of its {} items{}; they are asked alone",
          packed.purpose(),
          missing,
          packet.size(),
          answer.cutOff() ? " (cut off at its output limit)" : "");
    }
  }

  /**
   * Each item's share of the packed call's tokens: input by the length of its turn, output by the
   * length of its part of the answer; the shares add up to the call's usage.
   */
  static List<TokenUsage> shares(TokenUsage usage, List<String> turns, List<String> parts) {
    int n = turns.size();
    long[] in = new long[n];
    long[] out = new long[n];
    for (int i = 0; i < n; i++) {
      in[i] = turns.get(i).length();
      String part = i < parts.size() ? parts.get(i) : null;
      out[i] = part == null ? 0 : part.length();
    }
    long[] input = split(usage.input(), in);
    long[] output = split(usage.output(), out);
    long[] write = split(usage.cacheWrite(), in);
    long[] read = split(usage.cacheRead(), in);
    long[] writeLong = split(usage.cacheWriteLong(), in);
    List<TokenUsage> shares = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      shares.add(new TokenUsage(input[i], output[i], write[i], read[i], writeLong[i]));
    }
    return shares;
  }

  /** The total split by the weights, the remainder to the last; evenly when they are all zero. */
  private static long[] split(long total, long[] weights) {
    long sum = 0;
    for (long w : weights) {
      sum += w;
    }
    long[] parts = new long[weights.length];
    long given = 0;
    for (int i = 0; i < weights.length - 1; i++) {
      parts[i] = sum == 0 ? total / weights.length : total * weights[i] / sum;
      given += parts[i];
    }
    parts[weights.length - 1] = total - given;
    return parts;
  }
}
