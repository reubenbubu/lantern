# 0009: LLM configuration, stored call log and monthly budget

Status: accepted (#37), amended by ADR 0021 (#44) and ADR 0022 (#69)

## Context

ADR 0008 built the LLM layer but left out where model settings come from, keeping the call log
across runs, and the monthly budget. `docs/design.md` (Open questions) decided on a $100 monthly
budget, and the design keeps all persistent state in PostgreSQL. Issue #37 wires these into the
app; model identifiers, prices and keys must not be in the code.

## Decisions

- **Settings are Spring properties under `lantern.llm`**, bound to `LlmProperties`. Models are
  defined once under a name of the operator's choosing and referred to by that name:
  - `lantern.llm.models.<name>.provider` (`anthropic`), `.id` (the provider's model identifier),
    `.capabilities` (a list of `tool-use`, `structured-output`, `prompt-caching`),
    `.context-window`, and `.prices.input`, `.prices.output`, `.prices.cache-write`,
    `.prices.cache-read` in US dollars per million tokens. Cache prices may be left out for a
    model without prompt caching.
  - `lantern.llm.tiers.high-volume` and `lantern.llm.tiers.reasoning` name each tier's model;
    `lantern.llm.roles.<role>` (for example `roles.summarize`) gives one role its own model.
  - `lantern.llm.anthropic.api-key` (set from `ANTHROPIC_API_KEY` by default) and
    `lantern.llm.anthropic.base-url`.
  - `lantern.llm.monthly-budget-usd`, set to 100 in `application.properties` from the design.
  - Per-model call limits and `lantern.llm.retry.*`, which have defaults: see ADR 0021.
  - Per-model `disable-thinking`, `thinking-off-switch` and `thinking-headroom-tokens`, for
    models that think by default: see ADR 0022.
  No model has a default anywhere in the repository. Properties can come from any Spring source,
  such as environment variables or a file outside the repository.
- **Unconfigured means refused, not broken.** With no tier set, or when the models in use need
  Anthropic and no key is set, the app starts and `LlmAccess.client()` throws
  `LlmNotConfiguredException`, whose message names the missing settings. Settings that are set but
  wrong (a missing tier, an unknown model name, a missing price, an unknown provider) stop the app
  at startup, so mistakes show at once rather than at the first call.
- **The key is read from configuration for now.** The design asks for keys to be stored encrypted
  once providers are managed in the app by administrators; until then the key comes from the
  environment and is never logged. Encrypted storage is #41.
- **`LlmAccess` is the app's way in.** It makes the providers the configured models need (only
  Anthropic so far; OpenAI is #38) and also uses any `LlmProvider` bean, which is how tests put
  the fake in. `client()` returns an `LlmClient` whose budget is the monthly one; callers add run
  and scope budgets with `withBudget`.
- **Every call is stored** in the `llm_calls` table (time, duration, role, purpose, provider,
  model, outcome, the four token counts, cost and error) and also written to the
  `io.lantern.llm.calls` logger as before, the log line first. If storing fails, the error is
  logged and the call still returns its answer or its own error: it has already happened and may
  have been paid for, so failing it would lose the answer or hide the real cause. The in-process
  monthly budget still counts it; only a restart would miss it.
- **The monthly budget is a dollar limit per calendar month in UTC.** When a month's budget is
  first needed, its spending so far is summed from `llm_calls`, so the limit holds across runs
  and restarts. Within the process the same budget object then counts every call. A client made in
  one month keeps counting against that month's budget if used after the month ends, so a run
  that crosses into a new month is not limited by the new month's budget for the rest of the
  run (the stored log still counts those calls for the new month after a restart). Checking the
  current month on every call would need a change to `LlmClient`, which is not worth it for one
  run a month. The month has no token limit, since the design sets only a dollar amount. Several
  app instances sharing one database would each count only their own calls after startup; the
  design runs one instance.

## Not in this issue

The cost estimate before a run and run budgets from configuration (#42), and encrypted key
storage with provider management in the app (#41).
