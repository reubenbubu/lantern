# OpenRouter's Anthropic Messages endpoint: provider pinning, who served a call, BYOK, caching

3 Nov 2026, for #754.

**Depth: medium.** This is documented API behaviour of one service, so the official pages and a live check
settle it. It is not an open design question. Researched by a read-only `sonnet` subagent (five
pages read), then checked by two live calls of a few tokens each.

## Question

Lantern reaches Claude Haiku, Sonnet and Opus 5.5 through OpenRouter's Anthropic-compatible
Messages endpoint (`POST https://openrouter.ai/api/v1/messages`; the Anthropic SDK with base URL
`https://openrouter.ai/api`), on the owner's Anthropic key as BYOK. This note answers four
questions:
1. Can a request be kept on Anthropic, so Amazon Bedrock or Google Vertex never serve it?
2. How does a response say which provider served it, and whether the owner's key or OpenRouter's
   credits paid?
3. What happens when the BYOK key fails?
4. Does routing or fallback break the one-hour prompt cache?

## Findings

1. **Provider routing works on `/messages`.**
   - The endpoint's request schema has a `provider` object, `ProviderPreferences`. Its fields are
     `only`, `order`, `ignore` and `allow_fallbacks`; `allow_fallbacks` defaults to true.
     ([create a message](https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-a-message.md))
   - The routing guide's prose covers only Chat Completions and Responses
     ([provider routing](https://openrouter.ai/docs/features/provider-routing.md)), and no header
     sets routing.
   - **Live check, 3 Nov:** `"provider": {"only": ["anthropic"], "allow_fallbacks": false}` was
     served by `Anthropic`. The same with `only: ["amazon-bedrock"]` was served by
     `Amazon Bedrock`. So the field is enforced on this endpoint, and the slug `anthropic` is
     right.
2. **Each response says who served it.**
   - The body carries `provider` (live: `"Anthropic"`).
   - `usage` carries `is_byok`, plus `cost`, the OpenRouter credits charged (live: 0 under BYOK),
     and `cost_details.upstream_inference_cost`.
   - The header `X-OpenRouter-Metadata: enabled` adds `openrouter_metadata`, with the endpoints
     tried and the selected one.
   - The header `X-Generation-Id` (`gen-...`) is the id for
     `GET /api/v1/generation?id=...`, which also reports `provider_name` and `is_byok`
     ([get a generation](https://openrouter.ai/docs/api-reference/get-a-generation.md)). The
     Messages `id` (`msg_...`) is not that id.
3. **BYOK falls back to OpenRouter's capacity by default.**
   - "OpenRouter tries your key first, then falls back to OpenRouter endpoints on failures."
     A per-key setting can forbid that.
   - BYOK costs a fee of 5% of the list price, charged to credits, after a free allowance that
     depends on the plan. ([BYOK](https://openrouter.ai/docs/use-cases/byok.md))
   - The fallback is silent. Only `is_byok` tells it apart.
4. **Caching.**
   - `cache_control` with `"ttl": "1h"` works. A one-hour write costs 2 times the input price.
   - Requests for one model stick to one provider, so the cache stays warm. Sticky routing is
     off when `provider.order` is given, and lapses after 10 minutes without requests.
   - Caches are per provider, so a fallback to Bedrock or Vertex starts cold.
   - Pinning with `only` and no fallbacks keeps every request on Anthropic's cache.
   - ([prompt caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching.md);
     the usage fields are Anthropic's own, as #689 found.)

## Options

- **A. Pin with `only: ["anthropic"]` and `allow_fallbacks: false`.** The provider and the cache
  are fixed. A call can fail while Anthropic is down or rate limited. Lantern retries a
  rate-limited call itself.
- **B. `order: ["anthropic"]` with fallbacks.** Calls rarely fail, but sticky routing is off and a
  fallback provider starts cold.
- **C. No routing, only check afterwards.** Bedrock or Vertex may serve calls unnoticed.

## Recommendation

Option A. Send `provider: {"only": ["anthropic"], "allow_fallbacks": false}` with every request,
from a setting, so Anthropic's own API gets none. Read `provider` and `usage.is_byok` from each
response and record them with the call.

Keep the key's default fallback to OpenRouter's Anthropic capacity, as the owner decided on 3 Nov
(#737). It is the same model on the same provider, only with a cold cache, and `is_byok` shows when
it happened.

## What it changes

ADR 0102, amendment for #754:
- the routing setting `lantern.llm.anthropic.only-providers`;
- no batching at any base URL other than Anthropic's own;
- the `served_by` record of each call.

The `lantern-commands` skill gets the settings file and its setup.

## Sources read

- https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-a-message.md
- https://openrouter.ai/docs/features/provider-routing.md
- https://openrouter.ai/docs/use-cases/byok.md
- https://openrouter.ai/docs/api-reference/get-a-generation.md
- https://openrouter.ai/docs/guides/best-practices/prompt-caching.md

Not read (search leads only, no weight): third-party guides to the base URL `https://openrouter.ai/api`.
