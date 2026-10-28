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

/**
 * The way into the LLM. For each request it picks the role's model, checks that the model can do
 * what the request asks, reserves the call's worst case against every budget, sends it through the
 * model's provider, and logs it with its purpose, whatever the outcome.
 *
 * <p>Thread-safe if its providers and call log are.
 */
public final class LlmClient {

  private final ModelSettings settings;
  private final Map<String, LlmProvider> providers;
  private final List<BudgetGuard> budgets;
  private final LlmCallLog log;
  private final Clock clock;

  /**
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
    this(
        Objects.requireNonNull(settings, "settings"),
        byName(providers),
        List.of(Objects.requireNonNull(budget, "budget")),
        Objects.requireNonNull(log, "log"),
        Objects.requireNonNull(clock, "clock"));
    for (Role role : Role.values()) {
      provider(settings.forRole(role));
    }
  }

  private LlmClient(
      ModelSettings settings,
      Map<String, LlmProvider> providers,
      List<BudgetGuard> budgets,
      LlmCallLog log,
      Clock clock) {
    this.settings = settings;
    this.providers = providers;
    this.budgets = budgets;
    this.log = log;
    this.clock = clock;
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
    List<BudgetGuard> all = new ArrayList<>(budgets);
    all.add(budget);
    return new LlmClient(settings, providers, List.copyOf(all), log, clock);
  }

  /** The model the given role uses, so callers can check its capabilities. */
  public ModelConfig model(Role role) {
    return settings.forRole(role);
  }

  /**
   * Sends a request.
   *
   * @throws UnsupportedCapabilityException if the role's model cannot do what the request asks
   * @throws BudgetExceededException if the call could go over a budget
   * @throws LlmException if the provider fails
   */
  public LlmResponse call(LlmRequest request) {
    Objects.requireNonNull(request, "request");
    ModelConfig model = settings.forRole(request.role());
    Instant start = clock.instant();
    List<BudgetGuard.Reservation> reservations;
    try {
      check(model, request);
      reservations = reserve(model, request);
    } catch (LlmException e) {
      log(start, request, model, LlmCall.Outcome.REFUSED, TokenUsage.NONE, BigDecimal.ZERO, e);
      throw e;
    }
    LlmResponse response;
    try {
      response = provider(model).complete(model.model(), request);
    } catch (RuntimeException e) {
      reservations.forEach(BudgetGuard.Reservation::release);
      log(start, request, model, LlmCall.Outcome.FAILED, TokenUsage.NONE, BigDecimal.ZERO, e);
      throw e instanceof LlmException ? e : new LlmException("LLM call failed: " + e, e);
    }
    BigDecimal cost = model.pricing().cost(response.usage());
    for (BudgetGuard.Reservation reservation : reservations) {
      reservation.settle(response.usage().total(), cost);
    }
    log(start, request, model, LlmCall.Outcome.SUCCEEDED, response.usage(), cost, null);
    return response;
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
    TokenUsage worstCase =
        request.cacheSystem()
            ? new TokenUsage(0, request.maxOutputTokens(), input, 0)
            : new TokenUsage(input, request.maxOutputTokens(), 0, 0);
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
            error == null ? null : error.getMessage()));
  }
}
