package io.lantern.harness;

import io.lantern.llm.LlmBatchResult;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Gathers a bulk role's requests, sent one at a time from many threads, into batches for the
 * provider's batch API (knowledge-build epic, section 10, Run modes; ADR 0102). A request waits
 * until no other has come for the quiet time, or the batch is full; then the batch goes as one, and
 * each caller gets its own answer back, or its own error. Batches can be in flight at once: the
 * next gathers while one runs. Thread-safe.
 */
final class BatchCollector {

  /** How long the collector waits for more requests before it sends what it has. */
  static final Duration QUIET = Duration.ofMillis(250);

  /** The most requests one batch holds, well under the providers' limits (50,000 on OpenAI). */
  static final int MAX = 10_000;

  private record Pending(LlmRequest request, CompletableFuture<LlmBatchResult> result) {}

  private final LlmClient client;
  private final Duration quiet;
  private final int max;
  private final List<Pending> pending = new ArrayList<>();
  private long lastAdded;
  private boolean flushing;

  BatchCollector(LlmClient client) {
    this(client, QUIET, MAX);
  }

  BatchCollector(LlmClient client, Duration quiet, int max) {
    this.client = Objects.requireNonNull(client, "client");
    this.quiet = Objects.requireNonNull(quiet, "quiet");
    this.max = max;
  }

  /**
   * Sends the request in the next batch and waits for its answer.
   *
   * @throws LlmException the request's own error, such as a refusal for the budget
   */
  LlmResponse call(LlmRequest request) {
    Pending mine = new Pending(request, new CompletableFuture<>());
    synchronized (this) {
      pending.add(mine);
      lastAdded = System.nanoTime();
      if (!flushing) {
        flushing = true;
        Thread.ofVirtual().name("batch-collector").start(this::flushLoop);
      } else {
        notifyAll();
      }
    }
    LlmBatchResult result;
    try {
      result = mine.result().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while waiting for a batch", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw e.getCause() instanceof LlmException llm
          ? llm
          : new LlmException("Batch failed: " + e.getCause(), e.getCause());
    }
    if (result.error() != null) {
      throw result.error();
    }
    return result.response();
  }

  /** Sends batches while requests keep coming; ends when none is left. */
  private void flushLoop() {
    while (true) {
      List<Pending> batch;
      synchronized (this) {
        while (true) {
          if (pending.isEmpty()) {
            flushing = false;
            return;
          }
          long waited = System.nanoTime() - lastAdded;
          if (pending.size() >= max || waited >= quiet.toNanos()) {
            break;
          }
          try {
            long left = quiet.toNanos() - waited;
            wait(Math.max(1, left / 1_000_000), (int) (left % 1_000_000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LlmException error = new LlmException("Interrupted while gathering a batch", e);
            pending.forEach(p -> p.result().completeExceptionally(error));
            pending.clear();
            flushing = false;
            return;
          }
        }
        int size = Math.min(max, pending.size());
        batch = new ArrayList<>(pending.subList(0, size));
        pending.subList(0, size).clear();
      }
      Thread.ofVirtual().name("batch").start(() -> send(batch));
    }
  }

  private void send(List<Pending> batch) {
    try {
      List<LlmBatchResult> results = client.batch(batch.stream().map(Pending::request).toList());
      for (int i = 0; i < batch.size(); i++) {
        batch.get(i).result().complete(results.get(i));
      }
    } catch (RuntimeException | Error e) {
      // Whatever ended the batch, every caller waiting on it hears of it.
      batch.forEach(p -> p.result().completeExceptionally(e));
      if (e instanceof Error error) {
        throw error;
      }
    }
  }
}
