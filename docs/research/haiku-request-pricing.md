# Haiku 5.5: what a request costs by its size, and what caches

5 Nov 2026, for #874.

**Depth: medium.** Prices, tiers and caching rules are documented by the providers; no design
question is open. Researched by a read-only `sonnet` subagent, seven pages opened (two returned only
summaries, listed below), then checked against real calls on the Summarize test.

## Question

Summarize is to join several types' requests into one. How large may a request be before Claude
Haiku 5.5 charges more, what counts toward that size, and when does a request write to the prompt
cache, which costs more than plain input?

## Sources

- Anthropic, pricing, https://platform.claude.com/docs/en/about-claude/pricing. Read (searched
  within). Prices, the "Long context pricing" section, batch prices.
- Anthropic, prompt caching, https://platform.claude.com/docs/en/build-with-claude/prompt-caching.
  Read, the first 100,000 of 166,000 characters. When writes happen, multipliers, the minimum
  cacheable prompt.
- Anthropic, models overview, https://platform.claude.com/docs/en/about-claude/models/overview.
  Read. Context window and output limit.
- Anthropic, extended thinking, https://platform.claude.com/docs/en/build-with-claude/extended-thinking.
  Read. Thinking is billed as output and counts toward `max_tokens`.
- OpenRouter, prompt caching,
  https://openrouter.ai/docs/guides/best-practices/prompt-caching. Opened; only a summary came back.
- OpenRouter, Haiku 5.5, https://openrouter.ai/anthropic/claude-haiku-5.5. Opened; only a summary
  came back.
- Not read, so no weight: Anthropic's adaptive-thinking and thinking-cost pages; OpenRouter's API
  reference.

## Findings

1. **One price tier by prompt size.** "Claude Haiku 5.5 is priced by prompt length: a request whose
   prompt is over 100,000 tokens pays higher prices." The prompt counts every input token, cache
   reads and writes included; output and thinking do not count. Over the line, the whole request
   costs five times as much, input and output, not only the excess.
2. **Prices per million tokens**, up to 100,000 prompt tokens: input $0.10, output $0.50 (thinking
   is output), cache write $0.125 (five minutes) or $0.20 (an hour), cache read $0.01. Over 100,000:
   $0.50, $2.50, $0.625, $1.00 and $0.05.
3. **Caching is opt-in.** A request writes to the cache only where it marks a breakpoint
   (`cache_control`); OpenRouter adds none of its own for Anthropic models. Haiku 5.5 caches nothing
   shorter than 512 tokens.
4. **Limits.** A 1M-token context window and 128K tokens of output; thinking counts toward the
   output limit. Haiku 5.5 thinks adaptively: a fixed thinking budget is rejected, so a caller
   controls only the room it leaves.

## Evidence from Lantern

On the Summarize test, Lantern's character-based token estimate runs about 1.86 times under
Haiku's count (`estimate-token-scale` in the model settings), so a ceiling written in the model's
tokens must be compared with the estimate times that scale. A first packed run without the scale
sent requests of 9,800 tokens under a 6,000-token ceiling.

Lantern's requests mark one breakpoint, at the end of the system prompt, about 1,500 tokens. The
first request of a run writes it; the others read it at a tenth of the input price. Packed requests
carry the same breakpoint and nothing after it: on all 143 project-4 types, packed four a request,
they wrote nothing to the cache and read the system prompt 36 times.

## Options

| | Ceiling below 100,000 with headroom | No ceiling |
| --- | --- | --- |
| Cost of a large request | the cheaper tier always | five times more past the line |
| Large classes | split into parts, merged | one request, however large |
| Risk | the estimate's error eats the headroom | one huge class can cost as much as a run |

## Recommendation

Keep every request's prompt below a ceiling of 90,000 of the model's tokens (the owner's figure, 5
Nov 2026), compared with Lantern's estimate times the model's token scale; a unit larger than that
is summarized in parts. Leave each packed request room to think in its output limit, per item.
Cache only the system prompt, as today: it is the one part every request sends again.

## What it changes

`docs/design.md`, LLM integration: Summarize's types share requests under the ceiling, and a unit
over it goes in parts. ADR 0100, amendment for #874 (packed requests in the harness); ADR 0104,
amendment for #874 (Summarize's packing and parts).
