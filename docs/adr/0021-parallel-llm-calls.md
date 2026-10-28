# 0021: Parallel LLM calls with limits and retries

Status: accepted (#44), pending the user's review (#24)

## Context

`docs/design.md` (LLM integration, Cost and reliability) asks for calls in parallel on virtual
threads, with rate limiting and retry with backoff per provider. ADR 0008 left this to the work
that uses it, and ADR 0010 summarized one unit at a time. On the OpenWMS test set about 95% of an
analysis is sequential summary calls (733 calls of about 1.5 s, some 18 minutes). Results must stay
the same whatever order calls finish in, and budgets must hold when calls race.

## Decisions

- **The summarizer runs units in parallel, children first.** Every unit is a task on a virtual
  thread that starts once the units it contains are done (type, package, module, repository).
  Cycles are found before any call. The summarizer itself puts no bound on tasks; the model's
  limits below bound the calls. A unit's prompt and cache key depend only on its own facts and
  its parts' summaries (ADR 0010), so finishing order cannot change any summary, and the result
  lists units in the order of the old sequential walk. If a unit fails, units not yet started are
  skipped, running ones finish (so every call is logged before the summarizer returns), and the
  failure of the first unit in walk order is thrown.
- **Limits are per model, in a shared `CallLimiter`.** Each model (provider plus model identifier)
  has a lane: at most `max-concurrency` calls in flight (default 8) and, optionally, at most
  `requests-per-minute` calls started, spread evenly over the minute. The limits are part of
  `ModelConfig` (`CallLimits`), set with `lantern.llm.models.<name>.max-concurrency` and
  `.requests-per-minute`. Two names for the same model must agree, or startup fails. `LlmAccess`
  gives all its clients one limiter, so the limits hold for the whole process; a client made
  without one gets its own. Limits are per model rather than per provider because providers'
  rate limits are per model.
- **Retry with exponential backoff and jitter, honoring retry-after.** Providers report failures
  that may pass (HTTP 429, 408, 5xx including Anthropic's 529, and connection errors) as
  `TransientLlmException`, with the wait the API asked for if it said (`retry-after-ms`, or
  `retry-after` in seconds or as a date, capped at 10 minutes). `LlmClient` retries those, up to
  `lantern.llm.retry.max-attempts` attempts in all (default 5), waiting a random time between half
  and all of `initial-backoff` (default 1 s) doubled per attempt up to `max-backoff` (default 1
  minute); with a wait from the API it waits that plus up to `initial-backoff`, and holds back
  every other call to that model until the wait is over. Other failures are not retried. After the
  last attempt the call fails with "Gave up after N attempts". The Anthropic SDK's own retries are
  turned off for providers Lantern creates, so retries are counted and logged in one place.
- **Every attempt is logged.** A retried attempt is logged as `FAILED` with its error and "attempt
  N of M, retrying in T ms"; no new outcome value, so the call log's table is unchanged.
- **Budgets are reserved once a call has its slot** and kept across its retries; a call that
  finally fails releases them (as in ADR 0008). Calls waiting for a slot hold no budget, so a
  queue of waiting calls cannot make the budget refuse calls it could afford. `BudgetGuard` is
  already atomic, so concurrent calls never reserve more than the limit together.
- **The fake can be slow and fail.** `FakeLlmProvider` takes a latency, can fail the first N
  attempts of each distinct request (so which attempts fail does not depend on order), and
  reports the most calls it had in flight at once. Tests with it show the speed-up (on 57 units at
  20 ms a call, about 6x with 8 at once), that the limit is never passed, that retries work and
  give up, that budgets hold, and that parallel and sequential runs give identical summaries.
- **The JDK's own concurrency** (virtual threads, `CompletableFuture`, `Semaphore`); no new
  library.

## Not in this issue

Adapting the concurrency to the rate-limit headers a provider returns, limits on tokens per
minute, and parallel builds or extraction.
