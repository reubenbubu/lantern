package io.lantern.app;

import io.lantern.llm.BudgetGuard;
import io.lantern.llm.CallLimiter;
import io.lantern.llm.LlmCallLog;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.ModelSettings;
import io.lantern.llm.RetryPolicy;
import io.lantern.llm.Role;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The app's way to the LLM. When the LLM is configured, {@link #client()} gives a client that uses
 * the configured models, records every call in the database and counts it against the monthly
 * budget. When it is not, the app still runs and {@link #client()} refuses with the reason.
 *
 * <p>The monthly budget covers calendar months in UTC. Its spending is read from the stored call
 * log when the month's budget is first needed, so it holds across runs and restarts. Callers add
 * run and scope budgets with {@link LlmClient#withBudget}.
 *
 * <p>All clients share one {@link CallLimiter}, so each model's concurrency and rate limits hold
 * for the whole process.
 *
 * <p>Thread-safe.
 */
public final class LlmAccess implements AutoCloseable {

  private final ModelSettings settings;
  private final String notConfigured;
  private final List<LlmProvider> providers;
  private final JdbcLlmCallLog storedLog;
  private final LlmCallLog log;
  private final BigDecimal monthlyBudgetUsd;
  private final Clock clock;
  private final CallLimiter limiter;

  private final List<AutoCloseable> owned = new ArrayList<>();

  private YearMonth month;
  private BudgetGuard monthBudget;

  private LlmAccess(
      ModelSettings settings,
      String notConfigured,
      List<LlmProvider> providers,
      JdbcLlmCallLog storedLog,
      LlmCallLog log,
      BigDecimal monthlyBudgetUsd,
      Clock clock,
      CallLimiter limiter) {
    this.settings = settings;
    this.notConfigured = notConfigured;
    this.providers = providers;
    this.storedLog = storedLog;
    this.log = log;
    this.monthlyBudgetUsd = monthlyBudgetUsd;
    this.clock = clock;
    this.limiter = limiter;
  }

  /**
   * Access with the given models and providers.
   *
   * @param settings which model each role uses
   * @param providers the providers the settings refer to
   * @param storedLog where calls are stored and month-to-date spending is read from
   * @param log where calls are recorded, including the stored log
   * @param monthlyBudgetUsd the most a calendar month's calls may cost, or {@code null} for no
   *     limit
   * @param clock for call times and the current month
   * @param retryPolicy how calls that failed for a passing reason are retried
   * @throws IllegalArgumentException if a role's model refers to a provider not given
   */
  public static LlmAccess configured(
      ModelSettings settings,
      List<LlmProvider> providers,
      JdbcLlmCallLog storedLog,
      LlmCallLog log,
      BigDecimal monthlyBudgetUsd,
      Clock clock,
      RetryPolicy retryPolicy) {
    LlmAccess access =
        new LlmAccess(
            Objects.requireNonNull(settings, "settings"),
            null,
            List.copyOf(providers),
            Objects.requireNonNull(storedLog, "storedLog"),
            Objects.requireNonNull(log, "log"),
            monthlyBudgetUsd,
            Objects.requireNonNull(clock, "clock"),
            new CallLimiter(Objects.requireNonNull(retryPolicy, "retryPolicy")));
    // Checks now, at startup, that every role's provider is there.
    new LlmClient(settings, access.providers, BudgetGuard.unlimited("check"), log, clock);
    return access;
  }

  /** Access that refuses every request, saying why. */
  public static LlmAccess notConfigured(String reason) {
    return new LlmAccess(
        null, Objects.requireNonNull(reason, "reason"), List.of(), null, null, null, null, null);
  }

  /** Closes the given resource, for example a provider made for this access, on {@link #close}. */
  synchronized LlmAccess owning(AutoCloseable resource) {
    owned.add(Objects.requireNonNull(resource, "resource"));
    return this;
  }

  /** Closes the resources this access owns. */
  @Override
  public synchronized void close() throws Exception {
    Exception failure = null;
    for (AutoCloseable resource : owned) {
      try {
        resource.close();
      } catch (Exception e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    owned.clear();
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * The model each role uses, in the roles' order.
   *
   * @throws LlmNotConfiguredException if the LLM is not configured
   */
  public Map<Role, ModelConfig> models() {
    if (settings == null) {
      throw new LlmNotConfiguredException(notConfigured);
    }
    Map<Role, ModelConfig> models = new EnumMap<>(Role.class);
    for (Role role : Role.values()) {
      models.put(role, settings.forRole(role));
    }
    return Collections.unmodifiableMap(models);
  }

  /** Whether the LLM can be used. */
  public boolean isConfigured() {
    return settings != null;
  }

  /**
   * A client that counts calls against this month's budget.
   *
   * @throws LlmNotConfiguredException if the LLM is not configured
   */
  public LlmClient client() {
    if (settings == null) {
      throw new LlmNotConfiguredException(notConfigured);
    }
    return new LlmClient(settings, providers, monthBudget(), log, clock, limiter);
  }

  /** The current month's budget, with what earlier calls this month spent. */
  synchronized BudgetGuard monthBudget() {
    YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    if (!now.equals(month)) {
      BudgetGuard budget = new BudgetGuard("month " + now, null, monthlyBudgetUsd);
      JdbcLlmCallLog.Spending spent =
          storedLog.spentSince(now.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
      // Counts the month's earlier calls as one finished call, which is never refused.
      budget.reserve(0, BigDecimal.ZERO).settle(spent.tokens(), spent.costUsd());
      month = now;
      monthBudget = budget;
    }
    return monthBudget;
  }
}
