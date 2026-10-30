# 0008: LLM provider interface and test fake

Status: accepted (#10), amended by ADR 0021 (#44), ADR 0022 (#69), ADR 0081 (#169) and the amendments for #298 and #303, pending the user's review (#24)

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
  requests cannot ask for them yet; they come with the first role that needs them (Resolve;
  tool use came with ADR 0081).
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
- **The call log is an interface.** Every call, refused, failed, successful or (since ADR 0022) truncated, is recorded with
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
  The fake answers with a fixed text or a function of the request (cut off at the output limit
  since ADR 0022), estimates usage from text
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

## Amendment (#298): cache tokens of a model without prompt caching

A local Anthropic-compatible server (llama.cpp style) reports a call's whole prompt as
`cache_creation_input_tokens` and `input_tokens=1`, although Lantern asked for no caching. The four
counts were stored as reported, so the call log showed one input token, and since a model without
the `prompt-caching` capability needs no cache prices (ADR 0009), which then default to zero, the
prompt cost nothing against the budget.

- **For a model without `prompt-caching`, the Anthropic provider counts cache writes and reads as
  input** (`TokenUsage.cacheAsInput()`): input tokens are input plus cache-creation plus cache-read,
  priced at the input price, and the cache counts are zero. Lantern never asks such a model to
  cache, so any cache tokens it reports are the server's own split of the prompt.
- **A model with `prompt-caching` keeps the split as reported**, with its cache prices, as before;
  totals already sum all four counts. Real Anthropic responses therefore do not change: without
  caching asked for they report no cache tokens, and with it the split is priced as configured.

## Amendment (#303): the system prompt is cached on every model with prompt caching

A real run of 228 calls reported no cache writes and no cache reads. The cause was not the prompts
but that caching was never asked for: `cache_control` is sent only for a request whose
`cacheSystem` is set, and no caller set it (the summarizer, the describer and the resolver all
pass `false`). The prefixes themselves are fit to cache: tools render first, then the system
prompt, and both are constants of their role (the resolver's prompt has two forms, with and
without tools, and its tool list is fixed for a conversation and a run), with nothing per call
such as a time or an ID. The summarizer's system prompt is some 700 tokens, above the 512-token
minimum of the newest models though below the 1,024 of some older ones; a prefix under a model's
minimum is not cached and costs nothing extra.

- **A model with `prompt-caching` caches every request's system prompt**
  (`ModelConfig.cachesSystem`), whether or not the request asks: the caller does not know the
  model, and calls of one role share their system prompt, so the first call writes it and the
  others within the cache's five minutes read it at a tenth of the input price. A request that
  asks for caching on a model without the capability is still refused.
- **One breakpoint, at the end of the system prompt.** It covers the tools and the system prompt,
  which every call of a role shares; the turns after it differ from call to call.
- **The budget reserves the system prompt at the cache-write price** on such a model, as it did
  for a request that asked.
- **Record/replay keys are unchanged**: the request's own flag is what they hold, so existing
  recordings keep their names.
- **Tests** check the request shape: two calls of one role send byte-identical tools and system
  prompt with one breakpoint after them, and a model without the capability marks nothing.
  Whether a real model then reads from the cache is shown only by a real run's
  `cache_read_input_tokens`.

