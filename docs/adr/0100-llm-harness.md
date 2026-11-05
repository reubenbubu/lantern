# 0100: The LLM harness

Status: accepted (#525), amended for #42 (run budget, estimates), #357 (cache warm-up) and #349 (per-turn caching, compaction), amended by ADR 0103 (#528: field-level repair), amended by ADR 0104 (#665: an item rests on items of its budget group)

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
- **Legacy roles kept their prompts word for word until 1F.** Summarize, the four Describe roles
  (unit, element, flow narrative, purpose) and Resolve moved onto the harness with their prompts,
  checks and repair turns unchanged, so the fake-model documents held, each with its own check run
  inside the harness together with its Secrets check, until milestone 1F rebuilt them. The harness's eight checks (Schema, References, Ranges,
  Names, Coverage, Form, Conflict, Secrets; a ninth, Generic, since ADR 0106) and its packet
  envelope are used by every role prompt since milestone 1F rebuilt them; no role keeps a legacy
  prompt.
- **Two rendering fixes the incrementality gate found.** A summary's fact sheet showed the fact IDs
  that a link fact lists in its `facts` attribute, and a flow narrative listed each step's facts in
  fact-ID order. Fact IDs change with extractor versions and commits, so a one-file change re-asked
  items it did not touch. Summary prompts now strip the same ID attributes as wording prompts
  (`FactLines.shown`), and a step's facts are sorted by their rendered line. Amended for #881: an
  attribute is left out by its value, not its name, when it holds only fact or claim IDs, such as a
  flow trigger's `trigger`.
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
  the rest in parallel. Each role's packet size is a constant in its code, set from calibration where it has been
  calibrated (ADR 0112).
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
  never submitted again; an item never submitted at all counts as omitted and is cached. Resolve submits through the harness's
  `submit` tool like the other conversations (ADR 0105); per-turn breakpoints and compaction are #349.
- **No secret values yet.** The Secrets check rejects a text holding a value the masking marked as
  secret; the run's values are added to every check's inputs. Since #645 the masking (ADR 0054) hands the values it masked to the harness
  (amendment for #645 below).
- **No observations of origin `llm` for summaries and wordings yet.** They are stored as answers and
  read by the composer as before; Resolve already stores its links as facts. Storing accepted
  answers as observations came with the roles 1F rebuilt and the composer of 1G, which reads them;
  each LLM fact records the stored answer it came from (ADR 0124).
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
- Open: the Conflict check rejects any proposal whose subject and predicate already have a
  static object, which would block a second connection of a many-valued predicate (a unit's HTTP
  calls). No role passes it static objects yet, Investigate included, so it rejects nothing; a role
  that uses it decides in its ADR which predicates it applies to.

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
    plan. A round walks the queue and admits each item whose estimate fits what is left of the
    share: what the group has spent, less the estimates of what it has admitted and not settled.
    An item that does not fit waits for a later round, and the smaller items behind it that fit
    are admitted (#661); an item that rests on one that waits waits with it, so Summarize never
    asks a unit while one of its parts waits, since its prompt would lack that part's summary. Under a tight share Summarize therefore spreads sideways: a large unit that does not fit, and the units above it, wait while smaller units of other subtrees take the share, so the parts of a unit left out may be summarized without it.
    Once every item of the round has an outcome (or has ended without one), the next round walks
    what still waits, from what is really left. A round that admits nothing ends the group's
    rounds: what still waits is not asked. A round is decided only when nothing of the group is in
    flight (claims aside, below), so which items are asked never depends on which thread asks
    first, and two runs at the same budget ask the same items and spend the same. An item that
    ends before its round is decided, such as a unit whose part failed, is passed over. Answers shorter than estimated leave room
    that the next round takes, so a role spends most of its share; the rounds end within a few
    when estimates are near the real cost, more the shorter the answers. A role waits for an
    item's round when it asks it; Summarize waits before taking one of its slots, and roles that
    group items into requests ask each round's items together. A round prices a grouping role's
    items as they will be sent: packed per repository, each request reading the prompt's start, or
    the role's first writing it. A small round packs fewer items a request than the plan's average,
    and priced at the average it passed the screen's share.
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
    expected length and its first three facts, as many as a sentence is expected to cite). Without a share it is planned when asked, as
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
- **Bulk runs.** Writing the prefix with a one-hour lifetime before a batch is submitted came with
  the batch path (#527, ADR 0102).

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

## Amendment for #682

A rerun on the same inputs makes no call, drops included. Before, an item whose answer failed the
checks twice was not stored, so every rerun asked it again; when the retry passed, everything
resting on it changed too, and a warm rerun of project-4 made 241 calls.

- **A drop is stored.** `llm_answers` gets a `dropped` column holding why (V25). Such a row has no
  answer and is not omitted. A rerun on the same fingerprint repeats the drop without a call; the
  run record counts it as cached, and the role sees it as dropped, as before. It is asked again
  when its fingerprint changes: its inputs, the prompt version or the model. Resolve's fallback to
  the last answer for a subject skips drops.
- **Not every drop is stored.** These still ask again on a rerun:
  - an answer whose repair was cut off at its length limit, which a larger limit may let through;
  - an item rejected once in a conversation that a cap, a provider error or the budget ended before
    the model could correct it;
  - provider errors and items the budget left unasked, as before.
- **A drop lasts as long as an answer.** Like a cached answer, a stored drop is reused until its
  fingerprint changes. A change to the checks that should let earlier drops through bumps the
  role's prompt version, as a prompt change does. A drop the cache repeats has its reason begin
  with `dropped when last asked:`, so the roles count it as reused, not asked.
- **A check that rejects more raises the version** (amendment for #715). A cached answer is reused
  while what it cites still exists; the checks it passed when it was asked are not run again. So a
  change that makes a role's checks reject what they accepted before raises that role's prompt
  version in the same change. A role that falls back to its last stored answer when a new one is
  rejected, refused or fails (Resolve keeps its earlier pick) must also skip answers stored before
  the stricter checks, and the change raises that floor too (`ResolvePrompt.CHECKED_SINCE`). Only
  then does no answer stored before escape the new check. Resolve moved to version 8 for this
  reason, with its floor at 8.
- **Every rejection is written down.** Each answer the checks rejected is listed with the run's
  documents as `rejections.jsonl.gz`: the role, the subject, the attempt (1 the first answer, 2
  after the repair turn), the text rejected and the reasons. It covers the first answer rejected
  then repaired as well as each drop (attempt 0, with why), in every path: single items, packets
  and agent conversations. `scripts/score-testsets.sh` copies it beside `run.json`.
- **Fixed orders.** Two lead rules looped over a `Set.of`, whose order changes from one JVM to the
  next, and kept the first match, so a rerun could cite another link and ask its lead again. They
  loop over lists.

## Amendment (#702): a reworded answer re-asks what shows it.

On #529's project-4 recording, a one-line comment in one class (`EmailService`) made 89 calls on DeepSeek V4.1 Flash. A fresh run is 550. On the fake model, in a single run of a copy of project-4 with the same change, it made 3 calls: the class's summary, its unit's description and the file's injection screen. That is the gate above, and `Project0EndToEndIT.aOneFileChangeReAsksOnlyTheItemsRestingOnThatFile` checks it on project-0.

The rest come from rewording. An answer is a fingerprint input of every item that shows it (knowledge-build epic, section 10), and a real model rewords a re-asked answer entirely, sentences that do not rest on the change included. Comparing the #529 answers before and after, role by role (calls, items):

| Role | Calls (items) | Why asked again |
| --- | --- | --- |
| summarize-type, describe-unit, injection screen | 3 (3) | The file's blob: the first level, as on the fake model. |
| summarize | 4 (3) | The package and repository summaries show the type's summary, which was reworded. |
| investigate-units | 15 (1) | Notification-service's conversation rests on its accepted summaries. |
| investigate-system | 15 (1) | It rests on every unit's risks. Notification-service's 15 risks came back reworded. |
| describe-element | 3 (4) | Elements show the unit's reworded sentences. |
| flow-significance | 12 (6) | Scopes show the reworded sentences: all 6 re-asked had shown one, and the 1 kept from cache had not. |
| flow-narrative | 35 (29) | 28 of the 29 re-asked had shown a reworded sentence. The 29th was not narrated in the base run. None of the 12 kept from cache had shown one. |
| purpose, support judge | 2 (2) | The purpose shows every unit's sentence. The support judge judges the new texts. |

The repairs make up the difference between calls and items. The re-asked notification-service description reworded its unit sentence, 9 component sentences and 16 interaction phrases, almost none of which cite `EmailService`.

The harness follows its rule, and the epic's gate now names the cascade. It is a known limit, not the aim. Reruns stay costly on a real model until a sentence keeps its wording while what it cites is unchanged (#722).

## Amendment (#694): the estimate counts thinking and every turn of a conversation.

On #529's DeepSeek V4.1 Flash runs, the plan's estimate was about half the spend: $0.38 against $0.77 on project-4. Two things were not counted.
- **Thinking.** A model that thinks before it answers is billed for the thinking as output. It is several times the answer: 200 to 4,300 tokens a request, depending on the role.
- **Turns.** Each turn after the first in a tool conversation sends the whole conversation again. The plan counted only the first turn. An Investigate conversation takes about 15 turns.

What changes:
- **The role says what to expect.** Each `RoleSpec` gives, beside the answer's tokens per item:
  - `expectedThinkingTokens`: the tokens thought before each request, or each turn;
  - for a role that converses, `expectedTurns` (turns per conversation) and `expectedTurnTokens` (tokens each later turn adds, its tool results and the output before it).

  Each value is measured from #529's recorded calls on project-1 to project-4, at DeepSeek's default effort, and rounded. Thinking per request is the measured output, less the answer's estimate. The turns and turn tokens are averaged over that role's conversations. Injection screen's answer estimate drops from 60 to 25 tokens an item, as measured. The wording judge, not yet measured, takes the support judge's 650.
- **The model says whether it thinks.** A model with thinking headroom (`thinking-headroom-tokens`) is one that thinks even when asked not to, so its estimate counts the role's thinking. A model without headroom is expected to answer alone, so a model that thinks by default must be given headroom to be estimated right.
- **A conversation's cost.** A role that converses expects, for each conversation of first turn S, with T turns adding g tokens each:
  - uncached input of (T−1)·g;
  - cache reads of (T−1)·S + g·(T−1)(T−2)/2, or the same as uncached input on a model without a prompt cache;
  - T times the thinking.

  The share's rounds price items the same way, so a budget no longer admits more than it can pay for. A batched request is reserved at its thinking as well, up to the output limit with its headroom.

Modelled on the four recorded runs, the new estimate is 0.95, 0.92, 1.09 and 0.83 of the spend on project-1 to project-4, against 0.44 to 0.55 before. That is in-sample.

- **A model's own tokens.** Lantern counts four characters as a token. That matches DeepSeek's tokenizer, but Claude's counts about 1.6 times as many tokens for the same prompts. A model's `estimate-token-scale` setting, 1 if not set, multiplies every token count of its estimates and reservations. For Claude models it is 1.55, measured on the Haiku run below.

New runs of project-4, each with the rounds fully asked:

| Model | Estimate | Spent | Spent / estimate |
| --- | --- | --- | --- |
| DeepSeek V4.1 Flash (scale 1) | $0.70 | $0.63 | 0.90, was 2.0 on #529 |
| Haiku 5.5, before the scale | $0.43 | $0.67 | 1.56 |
| Haiku 5.5 at scale 1.55, budget $0.70 (fully asked roles) | $0.092 | $0.102 | 1.11 |

On DeepSeek no share waited and nothing was skipped. The Haiku run had a $1.50 budget, so its shares were small. At scale 1 its low estimate let the flows use their share before the purpose and 16 narratives were asked, the failure #694 names. Its counts above are of the roles that were fully asked, and the scale was fitted on them, so 1.55 is in-sample. A second Haiku run at 1.55 had a $0.70 budget, to stay within the owner's $1.50 for this check. Most roles stopped at their shares, as a budget that tight must, and summarize spent 7% over its share. The roles it asked in full spent 1.11 of their estimate, and the purpose, first on its share, was asked. Haiku spend: $1.18.

## Amendment (#754): an output scale per model, and the scales for Haiku and Sonnet

One scale per model was not enough. On #754's project-4 runs through OpenRouter, Haiku 5.5 for the
high-volume roles and Sonnet 5.5 for the reasoning roles and the support judge, the estimate
(before this amendment, both at 1.55):
- under-counted input by 10 to 40%;
- over-counted output, answers and thinking: Sonnet wrote and thought 0.30 times what the roles
  expect, Haiku 0.71 times.

Investigate's conversations also write most of each turn to the one-hour cache, at twice the input
price.

- **A model's `estimate-output-scale`** scales the output an estimate expects, apart from
  `estimate-token-scale`, which now scales only input, cache writes and cache reads. Unset, it
  equals the token scale, so a model set before keeps its estimate.
- **The fitted scales.** Fitted so the worst role group comes closest to its spend over the role
  groups fully asked in the two runs:
  - Haiku: token scale 1.86, output scale 1.24.
  - Sonnet: token scale 2.48, output scale 0.31.
  - Opus is unmeasured, so Sonnet's scales stand in.

  They are in `.claude/skills/lantern-commands/llm-openrouter.properties`.

| Role group (run) | Estimate before | Estimate now | Spent |
| --- | --- | --- | --- |
| summarize (1) | $0.194 | $0.185 | $0.181 |
| describe (1) | $1.180 | $0.792 | $0.853 |
| model checks (1) | $0.101 | $0.066 | $0.060 |
| name groups (1) | $0.003 | $0.003 | $0.003 |
| investigate (2) | $3.035 | $2.471 | $2.327 |
| flows and purpose (2) | $0.935 | $0.888 | $0.918 |
| model checks (2) | $0.034 | $0.040 | $0.044 |

Every group is within 10%; before, describe was off by 28% and the model checks by 41%. The scales
come from one set, fitted on the same runs they are judged by. #756's baselines on all five sets
check them.

## Amendment (#645): the masking hands its values to the harness

The Secrets check now has values to look for. Where the masked text of a file leaves for a role, a
source file Summarize or Describe is shown (`SourceFiles.shown`) or a file a tool shows Resolve or
Investigate (`GraphTools.Graph.shown`), `MaskedValues` compares each line before and after masking
and hands on what masking replaced or dropped: the whole value, and, for a URL masked for its
credentials, its password alone. Its scheme, user, host and database are not looked for: they are
ordinary names (a first fake run on project-8 rejected every summary that named the billing
service, since a masked URL held it). The run keeps them in one set that the
harness reads live, so a value found in a file shown late still counts for every later answer.
Values shorter than six characters are not looked for (`Harness.SHORTEST_SECRET`), and masked
prose, such as an exception message under a secret's name, counts whole, never word by word, so
words like "password" stay usable. An answer holding one is sent back once with the reason and
dropped if it holds it again. A value counts as a whole word, not inside a longer one, so a masked
`Postgres` does not reject an answer naming PostgreSQL. The values are never written: the run's log
gives their count only, and a rejected answer is logged and kept in the rejections file with each
value replaced by `******`; the LLM cache keeps the model's answers as returned, like every other
answer. The set grows as files are shown, so an answer accepted before a value was first shown is
not checked again for it, and which answers are sent back can vary with the order roles run in.


## Amendment (#744): text after the answer's object, and drops of unreadable answers

In the 1F cold run, an answer whose JSON object was followed by more text (a note, or a closing
brace too many) failed whole, and after one repair its items were dropped, and the drops were
stored, so every rerun repeated them.

- **The first complete object is read.** The harness (`Envelope.parse`) and Summarize read the
  first complete JSON object the answer opens with (`JsonFaults.first`), fences allowed. Text after
  it is logged and left out, with no repair turn; in a fenced answer, so is text after the
  closing fence. An object that never closes, or an answer that does not open with one, still
  goes back once with its fault named (#685).
- **A drop of an unreadable answer is not stored.** When the second answer holds no readable JSON
  object, the item is dropped for this run but not stored, as an answer cut off at its limit is
  not: the next run asks again. A drop for what the checks rejected in a readable answer is stored
  as before (#682).

## Amendment (#779): a claim seen in several files is shown from the same one

On project-3, a one-line comment in one file re-asked three items resting on other files. A link
read in two files is one claim with two sightings, and each sighting's fact ID rests on the
endpoint fact it matched. The comment gave that endpoint a new ID, so both sightings got new IDs,
and the one with the lower ID was now the other file's. The derive rules read facts ordered by ID
and cite the first of a claim's sightings, so a component line showed the claim from the other
file, and a lead cited it, which changed the lead's content and so its claim ID. Each item showing
them counted as changed.

- **Facts are ordered by claim and place.** The knowledge the derive rules read
  (`Knowledge.STABLE`) orders facts by claim ID, then by the repository, path and first line of
  their evidence, and only then by fact ID; the component lines (`ComponentInteractions`) read the
  links in that order too. Which sighting a rule cites now changes only when the sightings do.
- **What an item shows is unchanged otherwise.** A claim input still names the file it is shown
  from, so a change to that file still re-asks the item.
- The scorecard's one-file change gate on project-3 now re-asks only the three items resting on the
  changed file.

## Amendment (#872): the answer format per role, starting with TOML for Summarize

Summarize asks for its answers in TOML instead of JSON, to cut output tokens: per sentence, JSON
repeats quoted keys, braces and commas, and TOML does not (`docs/research/toml-model-answers.md`).
Every other role still answers in JSON until it is switched and measured.

- **The format is the role's.** `AnswerFormat` (JSON or TOML) fills the two places of the common
  system prompt that name the format (`${omitted}` and `${answer}` in `prompts/common.system.txt`)
  and the repair turn. Filled for JSON, the common prompt is byte for byte what every role was
  sent before, so no JSON role is asked again.
- **Requests.** Summarize writes each item as a TOML document inside `<items>`, so the common rule
  that what `<items>` holds is data still covers it. Repository text is a literal string where it
  can be, and an escaped basic string where it holds what a literal string cannot, so it reads
  back exactly and can never close a string early.
- **Answers.** `Toml.read` (`jackson-dataformat-toml`) reads the answer into the tree a JSON answer
  gives, so the schema check and every other check are unchanged. A fence is taken off, and prose
  before the first table or key, and up to five lines of prose after the document, are left out
  and logged, as text after a JSON object is (#744). A table written twice, or an unescaped quote,
  is a fault the repair turn names with its line. A JSON answer is still read.
- **Drops.** A TOML document counts as readable, as a JSON object does, when the harness decides
  whether a drop is stored (#744).


## Amendment (#874): packed requests for roles whose items ask alone

A role whose items each make their own request, such as Summarize's types, may have the harness
join their first requests into packed requests (`PacketCollector`), once the harness packs
(`packingUpTo`, set by `lantern.harness.packets.*`) and the role says how (`Harness.packs`, a
`Packing` that joins the turns and splits the answer). It is the same pattern as the batch collector:

- A first request waits until no other has come for 250 ms, or a packet is full. Packets take the
  waiting requests in the order they came, as many as fit both the item limit and the input ceiling,
  which is in the model's tokens and compared with Lantern's estimate times the model's
  `estimate-token-scale` (`docs/research/haiku-request-pricing.md`).
- A packed request asks for each item's output limit plus room to think per item, at most 64,000
  tokens. It carries the items' system-prompt breakpoint and none after it, so it writes nothing to
  the cache that will not be read again.
- Each item gets its own part of the answer, renumbered as its own request numbers it, with a share
  of the call's tokens by the length of its turn (input) and its part (output); the shares add up to
  the call. An item the answer leaves out, or whose part cannot be read, or the last item of an
  answer cut off at its limit, is asked again alone; every item of a packet whose call fails is
  too. A repair turn is never packed: it goes alone with only its own item's conversation, so a
  failed item never resends the others. A packed answer is read as each role's packing reads it
  (Summarize's, TOML); a role that answers in JSON is not packed. Whatever fails while a packet is
  sent, its items are answered by asking alone, never left waiting.
- The harness's `promptCeiling()` gives roles the same ceiling, so a unit too large for it is
  split in parts under it rather than sent past the provider's price tier.
- A role that batches does not pack.

## Amendment (#892): answer turns, a guard on a turn's size, a dry-run check and text answers

For conversations that take many small turns (Investigate's leads, ADR 0110, amendment for #860,
#892, #893):

- **A guard on a turn's size.** `ConversationLimits.contextTokens` caps what one turn sends, all of
  the conversation so far, cached or not, so a conversation stays below a provider's price line. It
  counts toward the nudge like the other caps.
- **Answer turns.** With `answerTurns` and `answerTools`, the conversation does not end at the
  nudge or a cap: from the nudge on, only the answer tools run (any other call is answered "No
  exploring is left", with those tools named), and it ends when the model answers or those turns are
  spent. So exploring ends at the nudge, three quarters of the caps; the caps themselves stop
  only a conversation without answer turns, and no cap applies to the answer turns. A cap reached
  before the nudge sends the nudge then. The safety cap on turns leaves room for them.
- **A turn cut off at its output limit** is never taken as the answer: each tool call it made is
  answered that it was not run, and the model asked to make it again with less thinking.
- **A dry-run check.** A role that `offersCheck()` gets a `check` tool beside `submit`, which runs
  the same checks and returns their verdicts, recording nothing.
- **Text answers.** A role that `textAnswers()` has an answer written as text instead of a call
  read as if submitted (its first JSON object holding `items`); with none, the model is told once to
  call submit, and a rejected one gets the verdicts once.
- The limits keep their answer tools sorted, so they read the same in every run.
