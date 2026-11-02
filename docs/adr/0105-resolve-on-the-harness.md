# 0105: Resolve on the harness

Status: accepted (#666); amends ADRs 0045 (prompt, answer, checks, storage, conversation) and 0081
(tool references, `find_in_code`)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.1) moves Resolve onto
the conventions every role shares (ADR 0103) and the harness's agent loop (ADR 0100): the common
system prompt, its own block 2 and submit schema, a system map and the caller's address facts as
shared context, one conversation per calling unit with at most four open links, the read-only
tools plus `find_in_code`, answers through `submit` checked at once, and a pick stored as an
observation of the link claim the static matcher would have written. This records how #666 lands
it, and what the epic left open. The agent decided these under the owner's standing go-ahead for
milestone 1F.

## Decisions

- **Items and conversations.** Each `unresolved-link` with candidates (kind `http`, `message` or
  `route`) is an item, its subject the link's claim ID. A calling unit's items are sorted by
  fingerprint; those the cache holds are settled together, the others go in packets of at most
  four, each packet one conversation, the conversations in parallel within the client's limits.
  A conversation runs at most 6 tool turns, 30,000 input tokens in fresh-token equivalents and
  4,000 output tokens a turn (ADR 0100).
- **The request.** Block 1 is the common system prompt; block 2 the epic's instructions and submit
  schema, read from `prompts/resolve.*` and held to the epic character for character by a test;
  the `submit` tool takes that schema as its input schema. Block 3, the system map, lists each
  deployable unit with its application name and the containers that run it, then each link static
  analysis found between units (`source -> target [protocol]`), inside `<facts>` with no
  references: it orients and is never a fingerprint input. The glossary the epic also names is the
  composer's (ADR 0087), built after linking, so it is not in the map. Block 4 is the calling
  unit's facts that bear on addresses: its unit fact, its `application-name` in each view, the
  `deployment-container` facts of the containers that run it (their environment is in their
  attributes), and the `config-value` facts each view gives the keys its links name: their
  placeholders and the keys nothing resolved, an environment variable's key also in Spring's
  relaxed form. Block 5 holds the items: the open link and its expression, the unit and code, why
  static matching stopped and the placeholders no configuration resolves, its own facts (those not
  already in block 4), then each candidate `[K1]` to `[Kn]` in unit-ID order with its unit fact,
  application name, deployment containers and the endpoints or listeners that would be reached.
  Facts are fact lines numbered `F..` across the request (ADR 0103).
- **Tools.** `facts_about`, `search_facts`, `read_file` and the new `find_in_code(repo?, text)`,
  which gives up to 30 lines that hold the text as `repo:path:line` with the line, secrets masked,
  by `git grep` at the snapshot's commit. A fact a tool shows is numbered `F..` after the request's
  own (it was `T..`), so it is cited like any fact; a file read is cited as `repo:path:from-to`.
  Every file a tool shows, `find_in_code`'s included, is recorded with its blob, so a cached answer
  holds while those files keep it. A model without tool use is not asked: its links stay open and
  are counted not asked.
- **Checks.** Each submitted entry passes the standard pipeline (its `reason` is the text, at most
  30 words, its `cites` its citations; the Names check holds the reason's code-like names to what
  it cites) and a check of the role's own, `Sides`: the pick is one of the item's candidates or
  `unknown`, the cites include the caller's side (a fact of block 4 or of the item, a fact about
  the caller's code, or a range in its repository) and the candidate's (a fact listed under the
  picked candidate, a fact about its code, or a range in its repository; for `unknown`, any
  candidate's). A rejected entry is sent back in the submit result; a second rejection, or a
  rejected entry never submitted again, drops the item. The harness lets a role add checks and set
  the submit schema (`PacketRole.pipeline`, `PacketRole.submitSchema`), and asks for each item's
  check inputs at each submit, so references a tool showed by then count.
- **Stored.** An accepted entry is stored with the unit it picks (or `unknown`), its confidence,
  its reason and its cites, a fact by its claim ID (a fact not yet stored by the hash of its line)
  and a range as written; a cached answer is reused only while every fact it cites holds. A pick
  becomes `CALLS_HTTP` or `SENDS_MESSAGES_TO` of origin `llm/resolve` (the rule's name is now
  `resolve`) with the attributes ADR 0045 gives it, so with the cited endpoint it is the claim a
  static match to that endpoint would be; its confidence is as before (0.5, 0.35 or 0.2 times the
  open link's), and the model's text is the descriptive attribute `reason` (it was `rationale`,
  which the vocabulary keeps, no longer given, since a type may only grow).
  `unknown`, an omitted entry and a conversation that never submits leave the link open and are
  cached, so a warm rerun does not ask again. A dropped, refused or failed item keeps the last pick
  stored for the link if that unit is still a candidate, as before.
- **The fake model** submits `unknown` for every item, citing the first fact of block 4 and the
  first under the first candidate, so fake runs leave links open as before and exercise the
  conversation; its models declare tool use.

## Consequences

- Every link left open is asked again once (prompt version 5), in fewer conversations: a unit's
  open links share one.
- A conversation costs at least two calls, the submit turn and the turn that ends it, so a run's
  Resolve calls are no longer its links asked.
- The thinking budget is the model's setting (ADR 0009), set for the recordings in #529.
