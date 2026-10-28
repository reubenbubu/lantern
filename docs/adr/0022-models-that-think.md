# 0022: Models that think by default

Status: accepted (#69), pending the user's review (#24)

## Context

Some models think before they answer even when not asked to, and their thinking tokens count
against the call's output limit (`max_tokens`). The summarizer's limits are sized for the answer
alone (ADR 0010), so such a model often stops at the limit and its summary is rejected as cut off.
DeepSeek's `deepseek-flash` thinks by default on its Anthropic-compatible endpoint: 10 of 44
summary calls were cut off. GLM through OpenRouter's Anthropic-compatible endpoint also thinks by
default, and accepts both `thinking: {type: "disabled"}` and OpenRouter's
`reasoning: {enabled: false}` while still thinking. So turning thinking off cannot be the only fix.

## Decisions

- **Two optional per-model settings**, in `ModelConfig` as `Thinking` and set under
  `lantern.llm.models.<name>`:
  - `disable-thinking` (default `false`): the Anthropic provider sends
    `thinking: {type: "disabled"}` through the SDK. For providers that honor it, such as DeepSeek.
  - `thinking-headroom-tokens` (default 0): added to every call's output limit, for a model that
    thinks anyway, such as GLM through OpenRouter. The callers' limits stay sized for the answer.

  For example, GLM through OpenRouter (`lantern.llm.anthropic.base-url=https://openrouter.ai/api`):
  `lantern.llm.models.glm.disable-thinking=true` and
  `lantern.llm.models.glm.thinking-headroom-tokens=2000`. The headroom a model needs is found by
  watching its output tokens in the call log. Settings are per model name, not per model
  identifier, so two names for the same model may differ.
- **The headroom is part of the call's worst case.** `LlmClient` sends the request's limit plus
  the headroom, and checks the context window and reserves the budget against that sum, so budgets
  stay exact. Thinking is billed as output, and the call's real usage replaces the reservation as
  before (ADR 0008).
- **Providers see the model's settings.** `LlmProvider` gained a default
  `complete(ModelConfig, LlmRequest)`, which `LlmClient` calls; it falls back to the existing
  `complete(String, LlmRequest)`, so providers that cannot turn thinking off need no change. The
  request it receives already carries the raised limit.
- **A cut-off answer is a distinct outcome.** An answer whose stop reason is `max_tokens`
  (`LlmResponse.cutOff()`) is still returned, since the caller decides what to do with it, but is
  logged as `TRUNCATED` instead of `SUCCEEDED`, at warning level, with its tokens, its cost and the
  limit it hit. The `llm_calls` table accepts the new value (migration V10). The summarizer keeps
  rejecting a cut-off answer and asking once more (ADR 0010), now through `cutOff()`.
- **Thinking is never part of the answer.** The Anthropic provider reads only text blocks, so
  thinking and redacted thinking blocks are left out.
- **Record/replay keys on the setting only when set.** `disable-thinking` changes what is sent, so
  it is part of a recording's key, but only when on, so existing recordings keep their names. The
  headroom is already in the key as part of the output limit.
- **Tests use fakes.** `FakeLlmProvider.thinking(tokens, canBeDisabled)` thinks before every
  answer, counts the thinking against the output limit and cuts the answer off when it does not
  fit, optionally honoring `disable-thinking`. A stub of the Messages API on the loopback interface
  writes thinking blocks the same way, both as DeepSeek (honors the setting) and as GLM (ignores it).

## Not in this issue

Turning thinking off through provider-specific fields other than Anthropic's `thinking`, and sizing
the headroom automatically from observed usage.
