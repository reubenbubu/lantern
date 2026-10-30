# 0022: Models that think by default

Status: accepted (#69), amended for #301

## Context

Some models think before they answer even when not asked to, and their thinking tokens count
against the call's output limit (`max_tokens`). The summarizer's limits are sized for the answer
alone (ADR 0010), so such a model often stops at the limit and its summary is rejected as cut off.
DeepSeek's documented default model thinks by default on its Anthropic-compatible endpoint: 10 of 44
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

  For example, for a model configured under the name `glm` that thinks anyway:
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
- **A cut-off answer is a distinct outcome.** An answer whose stop reason is `max_tokens` or
  `model_context_window_exceeded` (`LlmResponse.cutOff()`) is still returned, since the caller
  decides what to do with it, but is logged as `TRUNCATED` instead of `SUCCEEDED`, at warning
  level, with its tokens, its cost and the limit it hit. The `llm_calls` table accepts the new value (migration V10). The summarizer keeps
  rejecting a cut-off answer and asking once more (ADR 0010), now through `cutOff()`.
- **Thinking is never part of the answer.** The Anthropic provider reads only text blocks, so
  thinking and redacted thinking blocks are left out.
- **Record/replay keys on the setting only when set.** `disable-thinking` changes what is sent, so
  it is part of a recording's key, but only when on, so existing recordings keep their names. The
  headroom is already in the key as part of the output limit.
- **Tests use fakes.** `FakeLlmProvider.thinking(tokens, canBeDisabled)` thinks before every
  answer, counts the thinking against the output limit and cuts the answer off when it does not
  fit, optionally honoring `disable-thinking`. Even without thinking, the fake now cuts off any
  answer longer than the output limit, where it used to return it whole. A stub of the Messages
  API on the loopback interface writes thinking blocks the same way, both as DeepSeek (honors the
  setting) and as GLM (ignores it).

## Not in this issue

Turning thinking off through provider-specific fields other than Anthropic's `thinking`, and sizing
the headroom automatically from observed usage.

## Amendment (#301): models that turn thinking off another way, or cannot

One provider's models differ in how thinking is turned off. Some current Anthropic models reject
`thinking: {type: "disabled"}` with a 400 and take `{type: "between_tools"}`, with no other field
in the object, as their off switch; others cannot turn thinking off at all, and only a lower
effort reduces it. So a model configured with `disable-thinking=true` failed every call on the
first kind, and could never work on the second.

- **A third per-model setting, `thinking-off-switch`**, says how the model turns thinking off, so
  the behavior comes from configuration and the code names no model:
  - `disabled` (the default, so existing settings are unchanged): `{type: "disabled"}`.
  - `between-tools`: `{type: "between_tools"}` and nothing else in the thinking object. It takes
    effect only at effort `high` or below; Lantern sends no effort, and these models' default is
    `high`.
  - `none`: the model cannot turn thinking off.

  For example, for a model under the name `sonnet` that turns thinking off between tools:
  `lantern.llm.models.sonnet.disable-thinking=true` and
  `lantern.llm.models.sonnet.thinking-off-switch=between-tools`.
- **Turning thinking off where it cannot be is refused at startup.** `disable-thinking=true` with
  `thinking-off-switch=none` stops the app with a message naming both properties and pointing to
  `thinking-headroom-tokens`, the way to run such a model with thinking on. Mapping the request to
  the lowest effort instead was rejected: effort also changes how well the model answers, Lantern
  has no effort setting yet, and silently trading quality for a setting that asked for something
  else would hide the mismatch. `Thinking` refuses the same combination, so no other way in can
  build it.
- **Record/replay keys on the off switch only when thinking is off and the switch is not
  `disabled`**, so existing recordings keep their names, and a switch that is set but not used
  sends nothing more and keys nothing more.
- **Tests**: the loopback stub also plays a model that rejects `disabled` with a 400 and turns
  thinking off on `between_tools`; the provider tests check the thinking object each setting
  sends.

