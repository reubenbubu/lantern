package io.lantern.llm;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The way into the LLM. For each request it picks the role's model, checks that the model can do
 * what the request asks, waits for a free slot in the model's lane of the {@link CallLimiter},
 * reserves the call's worst case against every budget, sends it through the model's provider, and
 * logs it with its purpose, whatever the outcome.
 *
 * <p>A call that fails with a {@link TransientLlmException} is sent again by the limiter's {@link
 * RetryPolicy}, keeping its slot and its reservation; every attempt is logged. Calls from many
 * threads run in parallel up to each model's {@link CallLimits}, and the budget is reserved only
 * once a call has its slot, so calls waiting for one hold no budget.
 *
 * <p>Thread-safe if its providers and call log are.
 */
public final class LlmClient {

  private final ModelSettings settings;
  private final Map<String, LlmProvider> providers;
  private final List<BudgetGuard> budgets;
  private final LlmCallLog log;
  private final Clock clock;
  private final CallLimiter limiter;

  /**
   * A client with its own {@link CallLimiter} that retries by the {@linkplain RetryPolicy#DEFAULT
   * default policy}.
   *
   * @param settings which model each role uses
   * @param providers the providers the settings refer to, each with a distinct name
   * @param budget the budget every call counts against, for example the run's
   * @param log where every call is recorded
   * @param clock for the call log's times
   */
  public LlmClient(
      ModelSettings settings,
      List<LlmProvider> providers,
      BudgetGuard budget,
      LlmCallLog log,
      Clock clock) {
    this(settings, providers, budget, log, clock, new CallLimiter(RetryPolicy.DEFAULT));
  }

  /**
   * @param settings which model each role uses
   * @param providers the providers the settings refer to, each with a distinct name
   * @param budget the budget every call counts against, for example the run's
   * @param log where every call is recorded
   * @param clock for the call log's times
   * @param limiter bounds, paces and retries the calls; share it between the clients of a process
   */
  public LlmClient(
      ModelSettings settings,
      List<LlmProvider> providers,
      BudgetGuard budget,
      LlmCallLog log,
      Clock clock,
      CallLimiter limiter) {
    this(
        Objects.requireNonNull(settings, "settings"),
        byName(providers),
        List.of(Objects.requireNonNull(budget, "budget")),
        Objects.requireNonNull(log, "log"),
        Objects.requireNonNull(clock, "clock"),
        Objects.requireNonNull(limiter, "limiter"));
    for (Role role : Role.values()) {
      provider(settings.forRole(role));
    }
  }

  private LlmClient(
      ModelSettings settings,
      Map<String, LlmProvider> providers,
      List<BudgetGuard> budgets,
      LlmCallLog log,
      Clock clock,
      CallLimiter limiter) {
    this.settings = settings;
    this.providers = providers;
    this.budgets = budgets;
    this.log = log;
    this.clock = clock;
    this.limiter = limiter;
  }

  private static Map<String, LlmProvider> byName(List<LlmProvider> providers) {
    Map<String, LlmProvider> byName = new HashMap<>();
    for (LlmProvider provider : providers) {
      if (byName.put(provider.name(), provider) != null) {
        throw new IllegalArgumentException("Two providers are named " + provider.name());
      }
    }
    return Map.copyOf(byName);
  }

  /**
   * A client that also counts calls against the given budget, for example a scope's, on top of this
   * client's budgets. Both clients share spending on the budgets they have in common.
   */
  public LlmClient withBudget(BudgetGuard budget) {
    Objects.requireNonNull(budget, "budget");
    if (budgets.stream().anyMatch(existing -> existing == budget)) {
      return this;
    }
    List<BudgetGuard> all = new ArrayList<>(budgets);
    all.add(budget);
    return new LlmClient(settings, providers, List.copyOf(all), log, clock, limiter);
  }

  /** The model the given role uses, so callers can check its capabilities. */
  public ModelConfig model(Role role) {
    return settings.forRole(role);
  }

  /**
   * Sends a request, waiting for a free slot in its model's lane and retrying transient failures.
   *
   * @throws UnsupportedCapabilityException if the role's model cannot do what the request asks
   * @throws BudgetExceededException if the call could go over a budget
   * @throws LlmException if the provider fails, after the last attempt for a transient failure, or
   *     if the thread is interrupted while waiting
   */
  public LlmResponse call(LlmRequest request) {
    Objects.requireNonNull(request, "request");
    ModelConfig model = settings.forRole(request.role());
    Instant start = clock.instant();
    try {
      check(model, request);
    } catch (LlmException e) {
      log(start, request, model, LlmCall.Outcome.REFUSED, TokenUsage.NONE, BigDecimal.ZERO, e);
      throw e;
    }
    CallLimiter.Lane lane = limiter.lane(model);
    try {
      lane.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LlmException refused =
          new LlmException("Interrupted while waiting to call " + model.model(), e);
      log(
          start,
          request,
          model,
          LlmCall.Outcome.REFUSED,
          TokenUsage.NONE,
          BigDecimal.ZERO,
          refused);
      throw refused;
    }
    try {
      return send(lane, model, request, start);
    } finally {
      lane.release();
    }
  }

  private LlmResponse send(
      CallLimiter.Lane lane, ModelConfig model, LlmRequest request, Instant start) {
    List<BudgetGuard.Reservation> reservations;
    try {
      reservations = reserve(model, request);
    } catch (LlmException e) {
      log(start, request, model, LlmCall.Outcome.REFUSED, TokenUsage.NONE, BigDecimal.ZERO, e);
      throw e;
    }
    RetryPolicy policy = limiter.retryPolicy();
    Instant attemptStart = start;
    for (int attempt = 1; ; attempt++) {
      LlmResponse response;
      try {
        lane.awaitTurn();
        response = provider(model).complete(model.model(), request);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw failed(
            attemptStart,
            request,
            model,
            reservations,
            new LlmException("Interrupted while waiting to call " + model.model(), e));
      } catch (RuntimeException e) {
        if (!(e instanceof TransientLlmException transientFailure)) {
          throw failed(attemptStart, request, model, reservations, e);
        }
        if (attempt >= policy.maxAttempts()) {
          throw failed(
              attemptStart,
              request,
              model,
              reservations,
              policy.maxAttempts() == 1
                  ? transientFailure
                  : new LlmException(
                      "Gave up after %d attempts: %s"
                          .formatted(attempt, transientFailure.getMessage()),
                      transientFailure));
        }
        Duration delay = policy.delay(attempt, transientFailure.retryAfter());
        transientFailure.retryAfter().ifPresent(lane::pause);
        record(
            attemptStart,
            request,
            model,
            LlmCall.Outcome.FAILED,
            TokenUsage.NONE,
            BigDecimal.ZERO,
            "%s (attempt %d of %d, retrying in %d ms)"
                .formatted(e.getMessage(), attempt, policy.maxAttempts(), delay.toMillis()));
        try {
          TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          reservations.forEach(BudgetGuard.Reservation::release);
          throw new LlmException(
              "Interrupted while waiting to retry a call to " + model.model(), interrupted);
        }
        attemptStart = clock.instant();
        continue;
      }
      BigDecimal cost = model.pricing().cost(response.usage());
      for (BudgetGuard.Reservation reservation : reservations) {
        reservation.settle(response.usage().total(), cost);
      }
      log(attemptStart, request, model, LlmCall.Outcome.SUCCEEDED, response.usage(), cost, null);
      return response;
    }
  }

  /** Releases the call's budget, logs the failed attempt and returns the error to throw. */
  private LlmException failed(
      Instant attemptStart,
      LlmRequest request,
      ModelConfig model,
      List<BudgetGuard.Reservation> reservations,
      RuntimeException error) {
    reservations.forEach(BudgetGuard.Reservation::release);
    log(
        attemptStart,
        request,
        model,
        LlmCall.Outcome.FAILED,
        TokenUsage.NONE,
        BigDecimal.ZERO,
        error);
    return error instanceof LlmException e
        ? e
        : new LlmException("LLM call failed: " + error, error);
  }

  private static void check(ModelConfig model, LlmRequest request) {
    if (request.cacheSystem() && !model.supports(Capability.PROMPT_CACHING)) {
      throw new UnsupportedCapabilityException(
          "Model " + model.model() + " does not support prompt caching");
    }
    long worstCase = request.estimatedInputTokens() + request.maxOutputTokens();
    if (worstCase > model.contextWindow()) {
      throw new UnsupportedCapabilityException(
          "Request of about %d tokens (input estimate plus max output) exceeds the context window of %s (%d)"
              .formatted(worstCase, model.model(), model.contextWindow()));
    }
  }

  private List<BudgetGuard.Reservation> reserve(ModelConfig model, LlmRequest request) {
    long input = request.estimatedInputTokens();
    // With caching, the system prompt may be written to the cache; the rest is normal input.
    long cached = request.cacheSystem() ? Math.min(input, Tokens.estimate(request.system())) : 0;
    TokenUsage worstCase = new TokenUsage(input - cached, request.maxOutputTokens(), cached, 0);
    BigDecimal cost = model.pricing().cost(worstCase);
    List<BudgetGuard.Reservation> reservations = new ArrayList<>();
    try {
      for (BudgetGuard budget : budgets) {
        reservations.add(budget.reserve(worstCase.total(), cost));
      }
    } catch (RuntimeException e) {
      reservations.forEach(BudgetGuard.Reservation::release);
      throw e;
    }
    return reservations;
  }

  private LlmProvider provider(ModelConfig model) {
    LlmProvider provider = providers.get(model.provider());
    if (provider == null) {
      throw new IllegalArgumentException(
          "No provider named " + model.provider() + " for model " + model.model());
    }
    return provider;
  }

  private void log(
      Instant start,
      LlmRequest request,
      ModelConfig model,
      LlmCall.Outcome outcome,
      TokenUsage usage,
      BigDecimal cost,
      Exception error) {
    record(start, request, model, outcome, usage, cost, error == null ? null : error.getMessage());
  }

  private void record(
      Instant start,
      LlmRequest request,
      ModelConfig model,
      LlmCall.Outcome outcome,
      TokenUsage usage,
      BigDecimal cost,
      String error) {
    log.record(
        new LlmCall(
            start,
            Duration.between(start, clock.instant()),
            request.role(),
            request.purpose(),
            model.provider(),
            model.model(),
            outcome,
            usage,
            cost,
            error));
  }
}
