# 0008: LLM provider interface and test fake

Status: accepted (#10), amended by ADR 0021 (#44), pending the user's review (#24)

## Context

`docs/design.md` (LLM integration) asks for a thin, capability-aware interface over the official
SDKs, a model per role, token budgets, and that tests never pay for LLM calls. Issue #10 adds an
Anthropic implementation, a fake, record/replay for tests, a budget guard and a log of every call
with its purpose. The design leaves open the shape of the interface, how budgets are counted, and
where model settings come from.

## Decisions

- **Two layers in `lantern-llm`.** `LlmProvider` only translates and sends one request to one
  model. Callers use `LlmClient`, which picks the role's model, checks capabilities, reserves the
  budget, calls the provider and logs the call. Providers stay small, and every provider, fake or
  real, gets the same checks and logging.
- **Requests are plain text for now.** A request has a role, a purpose (for the log only, never
  sent), an optional system prompt, user and assistant turns, an output limit and a flag to cache
  the system prompt. Tool use and structured output are capability flags callers can check, but
  requests cannot ask for them yet; they come with the first role that needs them (Resolve).
- **Capabilities are checked before sending.** A request that caches its system prompt on a model
  without prompt caching, or whose estimated input plus output limit exceeds the context window,
  is refused with `UnsupportedCapabilityException` and logged as refused.
- **Models per role.** Each `Role` has a tier from the design: high-volume (Summarize, Name groups,
  Compose, Translate) or reasoning (Resolve, Infer intent, Propose rules). `ModelSettings` maps
  each tier to a model and lets any single role use its own. A model's settings are its provider,
  identifier, capabilities, context window and prices (US dollars per million tokens for input,
  output, cache writes and cache reads).
- **No model identifiers or prices in the code.** Model names and prices change often and must be
  configured. Nothing reads configuration yet; the first user of the LLM (#11) wires it up (#37).
- **Budgets count tokens and dollars.** A `BudgetGuard` has an optional token limit and an optional
  dollar limit. Before a call the client reserves its worst case (input estimated at four
  characters per token, plus the output limit) and refuses the call if any budget would go over;
  afterwards the reservation is replaced by real usage. Input estimates are rough, so spending can
  pass a limit slightly, after which every call is refused. With prompt caching, the system
  prompt's share of the estimate is priced as a cache write. A failed call releases its
  reservation, so a call that failed after being billed (for example a timeout) is not counted;
  the provider's own usage report remains the authority. The client has a run budget;
  `withBudget` adds a scope budget on top.
- **The call log is an interface.** Every call, refused, failed or successful, is recorded with
  time, duration, role, purpose, provider, model, tokens, cost and error. Two implementations: one
  line per call on the `io.lantern.llm.calls` logger, and an in-memory list. Persisting the log
  and a monthly budget across runs are left to #37.
- **Anthropic through the official SDK** (`com.anthropic:anthropic-java` 3.3.0, the latest
  release), with its OkHttp client. (Its default retries were turned off in favour of
  `LlmClient`'s own, see ADR 0021.) SDK errors become `LlmException`. Tests
  point the SDK at a stub HTTP server on the loopback interface with a dummy key, so they check
  the real request and response JSON without touching the network.
- **The real-provider test** (`AnthropicProviderRealTest`) runs only when
  `LANTERN_LLM_REAL_TEST=true`, and reads the key and model from the environment. See Commands in
  `CLAUDE.md`.
- **Test doubles live in the main code** of `lantern-llm` (`FakeLlmProvider`,
  `RecordReplayLlmProvider`), so tests in other modules can use them without a test-jar.
  The fake answers with a fixed text or a function of the request, estimates usage from text
  length and remembers what it was sent.
- **Record/replay keys on what the model sees.** A recording is a JSON file named by the SHA-256
  of the provider, model, system prompt, caching flag, turns and output limit, so changing a
  prompt needs a new recording, while role and purpose do not matter. Replaying fails on a
  missing recording rather than calling out. Recording replays what is already recorded and
  calls the real provider only for the rest, so rerunning it does not pay twice; it still needs
  the user's go-ahead like any real call. Files are written to a temporary file and moved into
  place, so parallel calls never read half a recording.

## Not in this issue

An OpenAI provider (#38), caching results by input hash, parallel calls with rate limiting, and
the cost estimate shown before a run belong with the work that uses them.
