# 0100: The LLM harness

Status: accepted (#525), amended for #42 (run budget, estimates), #357 (cache warm-up) and #349 (per-turn caching, compaction)

## Context

Each LLM role kept its own cache table (`summaries`, `unit_wordings`, `link_resolutions`), keyed by
a hash of its whole prompt, and its own call and check code. A warm rerun was free, but any change
a prompt showed, even a fact ID or the order of two lines, re-asked the item, and nothing planned or
budgeted a run as a whole. The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section
10) fixes the replacement: one harness that every role goes through, with the rule "cache per item,
batch by evidence". This records how #525 builds its core and what the epic left open. The agent
decided these under the owner's standing go-ahead for milestone 1E. It supersedes the caching parts
of ADRs 0010, 0055 and 0045, and amends ADR 0081 (caps, submit tool).

## Decisions

- **One module, `lantern-harness`, on `lantern-llm`.** A `Harness` holds the client, the answer
  store, the run budget and a blob lookup, and opens one `RoleSession` per role. A role is a
  `RoleSpec`: name, model role, prompt version, budget group, run mode (bulk or interactive),
  packet size and tool definitions. Two specs under one name in a run are an error.
- **An item is a subject, its fingerprint inputs and its rendering.** Its fingerprint is the SHA-256
  of `lantern-item 1`, the role, prompt version, provider and model, a hash of the tool definitions,
  the sorted inputs, and a hash of the item's rendering. The inputs are of four kinds:
  - a **claim**: its claim ID (or, for a fact without one, a hash of subject, predicate and object),
    with the repository and path it was observed in;
  - a **blob**: the git blob of a file whose text the prompt shows, and only then;
  - an **answer**: the hash of a child answer's text as shown, so a child re-asked to the same text
    leaves its parent alone;
  - a **text**: anything else the prompt shows, such as a README claim.

  Claim IDs and blobs are stable across commits (ADR 0091), so an item whose inputs did not change
  keeps its fingerprint. The rendering hash keeps the old guarantee that a prompt change re-asks.
- **`llm_answers` is the only answer cache** (migration V22): fingerprint, role, subject, prompt
  version, provider, model, whether the model omitted the item, the answer as JSON, the files a tool
  read with their blobs, and the inputs. It replaces the three role tables, which V22 drops; their
  answers are not migrated, as a fingerprint cannot be rebuilt from a stored prompt hash. A tool
  answer is reused only while every file it read still has the same blob, and, for Resolve, while
  every fact a tool showed still holds. When it no longer holds, the item is asked again under the
  same fingerprint and the new answer replaces the stored one, so the next run reuses it.
- **Legacy roles keep their prompts word for word.** Summarize, the four Describe roles (unit,
  element, flow narrative, purpose) and Resolve move onto the harness with their prompts, checks and
  repair turns unchanged, so the fake-model documents hold. Each keeps its own check, run inside the
  harness together with its Secrets check. The harness's eight checks (Schema, References, Ranges,
  Names, Coverage, Form, Conflict, Secrets) and its packet envelope are built and tested now and
  used by the new role prompts of milestone 1F; the legacy prompts ask one item per request.
- **Two rendering fixes the incrementality gate found.** A summary's fact sheet showed the fact IDs
  that a link fact lists in its `facts` attribute, and a flow narrative listed each step's facts in
  fact-ID order. Fact IDs change with extractor versions and commits, so a one-file change re-asked
  items it did not touch. Summary prompts now strip the same ID attributes as wording prompts
  (`PromptAttributes`), and a step's facts are sorted by their rendered line.
- **The plan prints the estimate before a role's first call.** `Plan <role>: n items, h cached, m to
  ask, about t tokens, $c`, from uncached input, one cache write of the shared prefix, cache reads
  for the other requests, and expected output. The run budget and the printed estimates are
  in the amendment for #42 below.
- **A budget refusal means not asked.** An item its role's share refuses, or whose call a budget
  refuses, is recorded as not asked in the run record and the log, and not stored, so the next run
  asks it. This changes Summarize: a budget refusal used to fail the whole run; now the unit is left
  without a summary, as a unit whose answer was rejected twice already is (ADR 0010, #76), and its parents are summarized without it until it is
  asked. The Describe roles already behaved this way.
- **Packets.** Items of one role, sorted by locality (repository, then unit) and fingerprint; a new
  repository starts a new packet. The answer is `{"items": {...}}` with an entry for every item ID.
  All failures go back in one repair turn; an item missing from the answer is asked again alone
  once, then recorded as not asked. The epic says "stored as not-asked"; it is kept in the run
  record but not cached in `llm_answers`, since a cached marker would stop every later run from
  asking it, and documents state it as unknown as they do any item without an answer. The first packet goes alone, so it writes the shared prefix,
  the rest in parallel. Packet sizes stay at 1 until 1F calibrates them.
- **Prompt layout.** A `PromptLayout` puts blocks 1 and 2 in the system prompt, with a cache break
  after block 2, and blocks 3 to 5 in the user turn, with breaks after block 3 and after block 4 when
  packets share it. Breaks are abstract `CacheBreak` hints, at most 4 a request; the Anthropic
  provider maps them to `cache_control` (a long-lived one to the 1-hour TTL), and they are dropped
  for a model without prompt caching. They are part of the replay key only when a request sets any,
  so existing recordings still replay.
- **The agent loop's caps.** `ConversationLimits` caps tool turns, input in fresh-token equivalents
  (input + 1.25 × five-minute cache write + 2 × one-hour cache write + 0.1 × cache read) and output per turn. At 75% of a cap a note after
  the tool results asks the model to submit what it has; at the cap the conversation ends as
  `CAPPED`. A reply the caller sends after the model's answer, such as a repair, is not held to the
  caps. Resolve keeps its tools and gets the epic's 6 tool turns and 4,000 output tokens a turn, but
  an input cap of 120,000 instead of 30,000: the epic's figure assumes each turn reads its history
  from the prompt cache, which #349 adds and which then sets 30,000. A capped Resolve conversation
  stores nothing and leaves its link open, as a conversation stopped at the old safety cap did. A
  `submit` tool verifies each item as it arrives; a second failure drops it, as does a first failure
  never submitted again; an item never submitted at all counts as omitted and is cached. Resolve keeps its own answer turn until its 1F
  prompt; per-turn breakpoints and compaction are #349.
- **No secret values yet.** The Secrets check rejects a text holding a value the masking marked as
  secret; the run's values are added to every check's inputs. The masking (ADR 0054) does not yet hand the values it masked to the harness, so the set
  is empty for now (#645).
- **No observations of origin `llm` for summaries and wordings yet.** They are stored as answers and
  read by the composer as before; Resolve already stores its links as facts. Storing accepted
  answers as observations comes with the roles that 1F rebuilds and the composer of 1G, which read
  them.
- **The run record shows each role**: items planned, cached, asked, answered, omitted, dropped, not
  asked and failed; calls and batch calls; tokens (fresh, five-minute and one-hour cache write, cache read); cost; and the
  estimate. It also lists the subjects asked, which the incrementality test reads.

## Consequences

- A warm rerun of an unchanged snapshot makes no call, and a one-file change re-asks only the items
  whose inputs hold that file's blob or a claim observed in it, plus their parents whose child
  answer changed (`Project0EndToEndIT`). A change that alters an extracted fact, such as a renamed
  endpoint path, also re-asks the items that show the facts derived from it, such as a link the
  rename leaves open and the flows it changes; the test lists exactly those (#654).
- The first run after V22 re-asks every item once, since the old caches are dropped.
- A new answer under a new fingerprint is a new row of `llm_answers`; one under a stored
  fingerprint replaces it and becomes the newest. The newest row per role and subject is what a
  fallback to an earlier answer reads.
- Open for 1F: the Conflict check rejects any proposal whose subject and predicate already have a
  static object, which would block a second connection of a many-valued predicate (a unit's HTTP
  calls). Investigate's ADR decides which predicates it applies to before Investigate uses it.

## Amendment for #42: the run budget and its estimate

- **Settings.** `lantern.llm.run-budget-usd` sets a budget per analysis run, none by default;
  `lantern.llm.run-budget-shares.<group>` changes a group's share. The defaults are the epic's
  (section 10): investigate 40, describe 20, summarize 18, name-groups 2, flows-and-purpose 10,
  resolve 5, model-checks 5. Issue #42 listed Describe at 22% and no Name groups; the epic's split
  wins. A share of an unknown group, or shares adding up to more than 100%, stop the app at start.
- **A share belongs to a group, not a role.** The roles of one group (the describe roles; flow
  narrative and purpose) plan against, and spend from, one share.
  - **Which items fit is decided in rounds, in a fixed order** (#657, #659). Each role queues its
    misses in its own order (Summarize's tree, parts before the units that contain them;
    Describe's questions; the screen's chunks) on its group's queue, the roles in the order they
    plan. A round admits items from the head of the queue while their estimates fit what is left
    of the share: what the group has spent, less the estimates of what it has admitted and not
    settled. Once every item of the round has an outcome (or has ended without one), the next
    round admits from what is really left, until the first item that does not fit even then: it
    and every item after it are not asked. A round is decided only when nothing of the group is in
    flight, so which items are asked never depends on which thread asks first, and two runs at the
    same budget ask the same items and spend the same. Answers shorter than estimated leave room
    that the next round takes, so a role spends most of its share; the rounds end within a few
    when estimates are near the real cost, more the shorter the answers. A role waits for an
    item's round when it asks it; Summarize waits before taking one of its slots, and roles that
    group items into requests ask each round's items together.
  - Decisions are kept by each item's fingerprint, since a role may ask several items of one
    subject (a flow shown in two documents), each counted against the share. Summarize plans a
    unit whose parts are all in the cache as the very item it will ask, built from its parts'
    stored summaries, so a rerun after a budget-limited run decides its units in order too. An
    item counts as cached only if its stored answer passes the role's own check; an item the plan
    did not know (a stored answer a role rejects when asked, as Resolve's) is decided when asked,
    as a claim.
  - **A claim is admitted at once, ahead of the queue, if its estimate fits** (#659), and the
    group's rounds do not wait for it; it counts at its estimate until it settles. The system's
    purpose has first claim on the flows-and-purpose share: one cheap call that writes the
    report's first paragraph, asked after the flows on the units' sentences, so its claim is
    planned before the flows on a prompt of the expected size (each unit with a sentence of the
    expected length and the first facts it may cite). Without a share it is planned when asked, as
    before.
  - An item's estimate is priced as the plan prices it: the prompt's start read from the cache, a
    role that groups items paying its share of each request's start, and the batch price when the
    role batches (#654).
  - The warm-up item is the role's first item admitted. It goes first; the others wait for it to
    settle. Summarize's units to be asked wait for it before taking one of their
    slots, so waiting units never hold the slot the warm-up needs; units answered from the cache
    do not wait, since they may be parts of the warm-up unit. However the warm-up item ends (a
    failure, an unreadable file, a run stopped), its role releases the others.
  - The group's guard holds back no worst case: it refuses a call only once the group has spent its
    share (`BudgetGuard.stopOnceSpent`). Estimates below the real cost let the group pass its
    share by at most the calls running when it is reached; the calls after are refused and their
    items not asked. The share is "very close", not "never over".
  - Nothing not asked is cached, so a later run with room asks it.
- **Each role's estimate is printed before its own first call, not all of them before the run's
  first call.** A role's items depend on earlier roles' answers: a parent summary shows its
  children's, Describe shows the summaries, so their fingerprints and cache hits are only known
  once those roles have run. Each role plans every item it needs before it sends anything (a
  parent whose children are not all cached is counted as a miss), logs `Plan <role>: ...`, and
  `analyze` ends with each role's estimate against what it spent. The run's estimate is the sum.
  Whether an estimate is close enough is first checked on a real recording in #529.

## Amendment for #357: the first request warms the prompt cache

- **A role's first item goes alone.** A provider can read a cached prefix only once the request
  that writes it has been answered, so calls of one role sent at the same moment all write the same
  prefix and none reads it. The first item a role asks the model about now runs on its own; every
  other item of that role waits until it is answered (or has failed), then they run as before, in
  parallel. Cached items do not wait. For a tool-using role the first item is a whole conversation.
- **The fake profile simulates the prompt cache.** The fake provider of the `fake-llm` profile
  reports cache writes and reads as a provider with prompt caching would, and the fake models of
  `scripts/score-testsets.sh` declare `prompt-caching` (with zero cache prices for `fake`, and
  1.25x, 2x for a one-hour write, and 0.1x the input price for `fake-priced`). Fake runs then show in their run record what a
  warmed cache saves. The documents do not change: only the reported token split does.
- **Bulk runs.** Writing the prefix with a one-hour lifetime before a batch is submitted comes with
  the batch path (#527).

## Amendment for #349: each turn of a conversation reads its history from the cache

- **A moving breakpoint.** Every turn of a tool conversation carries a cache breakpoint after its
  own last turn, so the next turn finds the whole history before it in the provider's cache and
  pays full price only for what is new. The history is sent byte for byte as before; tools, tool
  choice, thinking and output schema stay fixed through a conversation. The first request's own
  breakpoints stay; when the provider's limit of four is reached (counting a cached system
  prompt), the latest of them gives way to the moving one. One moving breakpoint is enough: the
  previous turn's breakpoint sits only one turn's blocks back (two or three), well within the 20
  blocks a provider looks back for a cached prefix, however long the conversation grows. Models without prompt caching get no breakpoints, as before.
- **Compaction, at most once.** When a conversation's history passes 60% of the model's context
  window, each tool result older than the last four tool-result turns is replaced by one line
  naming the call (`[read_file {...} elided]`), and caching restarts from there. The model can read
  again what it needs to cite. A history with nothing old enough to elide does not spend the one
  compaction. Compaction makes room but does not guarantee a fit: if the last four tool turns alone
  pass the window, the provider refuses the turn and the conversation fails.
- **Resolve's input cap is the epic's 30,000** fresh-token equivalents, now that each turn reads its
  history at the cache-read rate (the 120,000 of #525 was a stopgap).
- **The fake provider looks back too.** Its cache simulation now reads the longest prefix cached
  before that ends at an earlier turn within 20 blocks, as a provider does, and simulates the cache on tool-call
  answers as well. The recorded Resolve tool loop in `LinkResolverTest` was re-recorded from its
  scripted fake model, since its requests now carry breakpoints, which are part of the replay key.
