# 0102: The batch API and an OpenAI provider

Status: accepted (#527)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 10, Run modes and Prompt
layout, Portability; criterion 11) asks two things of the harness (ADR 0100):

- Bulk roles go through the provider's batch API, at half price, once a role has more than 50 cache
  misses in a run. One request first writes the shared prefix with a one-hour lifetime.
- The roles run unchanged on a second provider, OpenAI. Its provider maps the harness's cache hints
  to a stable-prefix order and `prompt_cache_key`, and records usage in one normalised form.

`docs/design.md` (Provider interface) already names the official OpenAI Java SDK. This records how
#527 builds both. The agent decided these under the owner's standing go-ahead for milestone 1E.
No real call was made: every test answers from a fake through a stub of the provider's API.

## Decisions

- **A capability and a provider method.**
  - A model may batch when its settings list the capability `batch` and its provider has a batch
    API (`LlmProvider.batches()`, `completeBatch`).
  - `LlmClient.batch` sends one role's requests as one batch. Each request is still checked,
    reserved against every budget and logged on its own, as a call is.
  - The batch price is half the usual price, with the cache prices halved alike (`Pricing.batchCost`).
  - The call log, its table (`llm_calls.batch`) and each role's run record mark batched calls. The
    run record also splits batch and interactive spend.
  - A request the batch fails, cancels or lets expire gets its own error, and the others go on. A
    batch that cannot be sent is retried like a call. A batch takes no lane slot: batch APIs have
    limits of their own.
- **When a role batches.**
  - A bulk role batches when it plans more misses than `lantern.llm.batch-above`, 50 by default as
    the epic says, and its model batches. The bulk roles are Summarize, Describe's unit, data and
    external roles, the support judge and the injection screen.
  - From then on, the role's first request goes alone and interactively. The others wait for it,
    then go through `BatchCollector`. It gathers the role's requests from all its threads into a
    batch, which goes once no new request has come for 250 ms, or once it holds 10,000.
  - Every request of a batching role carries long-lived breakpoints. The system prompt gets one at
    its end, so the warm-up's prefix outlives the wait and the batch reads it. Repair turns go
    through the batch too.
  - The Summarizer lifts its cap of parallel units while batching. A batched request waits for its
    batch, not for a slot. Each level of the tree then becomes one batch.
  - Agentic roles (Resolve, and Investigate when it comes) always run interactively.
  - `lantern.llm.batch-above=0` batches every miss of a bulk role. The parity check below uses it.
- **The Anthropic batch** goes through the SDK's Message Batches.
  - Each request is sent as `complete` would send it. Its body is copied into the batch request's
    params.
  - The batch is read every 30 seconds until it has ended, then its results are read back by
    custom id.
- **The OpenAI provider** (`io.lantern.llm.openai.OpenAiProvider`, provider name `openai`) uses
  the official SDK (`com.openai:openai-java`), as the design names, over Chat Completions:
  - The system prompt goes first as a `system` message, then the turns in order.
  - Tool calls become `tool_calls`. Tool results become `tool` messages before the turn's text, an
    error result marked "Error:". Tools go as functions with their schemas.
  - Breakpoints are not sent. The API caches the longest stable prefix on its own, which the
    harness's layout already keeps first.
  - For a model with prompt caching, requests that share their tools and system prompt carry one
    `prompt_cache_key`: `lantern-<role>-<digest of the tools and system prompt>`.
  - Usage is normalised: `cached_tokens` are cache reads, the rest of the prompt is uncached input,
    and there are no cache writes.
  - Finish reasons map to the harness's: `length` to `max_tokens`, `tool_calls` to `tool_use`,
    `stop` to `end_turn`.
  - A rate limit, timeout or server error is transient, with the wait the server asked for, as on
    Anthropic.
  - Batches go through the Batch API: a JSONL file of `/v1/chat/completions` requests, read back
    from the output and error files by custom id.
  - It is configured with `lantern.llm.openai.api-key` (or `OPENAI_API_KEY`) and
    `lantern.llm.openai.base-url`, which also serves a gateway that speaks the same API.
- **The fake.** `FakeLlmProvider` has a batch API that answers each request in turn. Its
  `model(...)` does not batch unless a test asks for `batchingModel(...)`, so tests of interactive
  behaviour stay interactive. The fake models of `scripts/score-testsets.sh` declare `batch`, as
  the deployment's models will, so fake scoring runs take the batch path where a role misses more
  than 50.
- **Evidence without a real call.**
  - `OpenAiStub`, a test server, speaks OpenAI's Chat Completions, Files and Batch APIs. It reads
    each request back into the harness's request and answers with a fake.
  - `OpenAiBatchParityIT` analyzes project-0, or the set `lantern.test.set` names, twice: first on
    the fake interactively, then with every role on the OpenAI provider through the stub, every
    bulk role batching all its misses. The test requires both runs to store the same answer for
    every role's every item, and to write the same documents.
  - `AnthropicBatchTest` checks the Message Batches round trip against a stub.

## Departures from the epic, until the step named

- **Thinking settings are not sent to OpenAI.** Its reasoning models take no off switch Lantern
  sets. Requests carry no output schema, since the harness checks every answer against its role's
  schema itself. So OpenAI's structured output (`response_format`) is not used. A role that wants it
  can add a schema to its request later.
- **The one-hour write costs more than estimated.** A long-lived cache write costs twice the input
  price, not 1.25 times. Only the warm-up request writes it, and the price settings have one
  cache-write price, so the run record undercounts that one write. It is small next to a batch's
  savings.
- **Requests are not cut at 256 MB.** A batch is cut only by count (10,000), far below both
  providers' limits (100,000 requests on Anthropic, 50,000 on OpenAI). A role whose prompts were
  very long could still pass the size limits; none does today.
- **Acceptance on OpenAI models is not run.** The owner chooses that later (#527, Scope).

## Consequences

- A cold run's bulk roles cost half once they miss more than 50 items. An incremental run that
  misses few stays interactive and fast.
- A batch can take up to 24 hours, usually under one. A cold bulk run waits for it.
- Changing provider is a settings change: a model's `provider` is `anthropic` or `openai`.
