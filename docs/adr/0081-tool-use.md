# 0081: Tool use: the model queries the graph and reads files

Status: accepted (#169), amends ADRs 0008 and 0045, pending the user's review (#24)

## Context

The design's Resolve role, and the Investigate role that ADR 0070 added (#269), give the model
tools to query the graph and read the repositories' files, so it can look further than its prompt.
ADR 0008 left tool use out of `LlmRequest` until the first role needed it, and ADR 0045 built the
resolver without tools. The user asked (#169, 2026-10-29) for no per-role turn limit yet: every
conversation records its turns, its tool calls by tool, its tokens and cost per turn and how it
ended, so the metrics show what a model does unrestricted; only the run's budget and a high safety
cap guard cost.

## Decisions

- **Tools in the request and the answer.** `LlmRequest` has a list of `LlmTool`s (a name, a
  description and a JSON Schema object for the input). A turn (`LlmMessage`) of the assistant may
  hold `LlmToolCall`s (an id, the tool's name and its JSON input) and a user turn
  `LlmToolResult`s (the call's id, the result as text and whether it is an error); such a turn's
  text may be empty. `LlmResponse` carries the tool calls of the answer, stop reason `tool_use`. A
  conversation holding tool calls must offer the tools. Requests without tools are unchanged.
- **Checked against the capability.** `LlmClient` refuses a request with tools for a model
  without `tool-use` (`UnsupportedCapabilityException`, logged as refused), before any budget is
  reserved. The input estimate counts the tools' definitions, tool inputs and results.
- **Anthropic.** Tools go as custom tools with their schemas; an assistant turn's calls as
  `tool_use` blocks after its text, a user turn's results as `tool_result` blocks (with
  `is_error`) before its text; the answer's `tool_use` blocks become its calls. A model that thinks
  must get its thinking back on the turn that asked for tools, so an answer with tool calls keeps
  its thinking and redacted thinking blocks (`LlmThinking`, with their signatures), and the
  conversation sends them back first on that turn; they are never part of the answer's text.
- **The tool loop is a `Conversation`** (`LlmClient.converse(request, toolBox)`). It sends the
  request with the `ToolBox`'s tools; while the answer asks for tools (and is not cut off), it
  runs each call, sends the results back as the next user turn and calls again. `reply` sends the
  caller's next turn, such as the reason an answer was rejected, and loops the same way; a reply
  to an empty answer joins the last user turn, since a turn cannot be empty. A tool that throws
  answers with an error result, so the model can try again. Each call is a turn, reserved and
  logged like any call. A budget that refuses a turn ends the conversation as `BUDGET`; an answer
  that still asks for tools after 40 turns (`Conversation.DEFAULT_TURN_CAP`, a guard, not a
  lever) ends it as `TURN_CAP`; a turn that fails ends it as `FAILED`; the caller ends it as
  `ANSWERED` or `REJECTED`. A configurable turn limit per role may follow once the metrics show
  what it should be.
- **What is logged.** Each `LlmCall` carries its conversation's id, its turn number and the tool
  calls its answer asked for, by tool. When a conversation ends the log records an
  `LlmConversation`: role, purpose, provider, model, turns, tool calls by tool, tokens, cost, how
  it ended and why it stopped. The app stores both (`llm_calls` gains `conversation_id`, `turn`
  and `tool_calls`; a new `llm_conversations` table, migration V17), and `run.json` (ADR 0072)
  gives each role's conversations (count, turns, most turns, tool calls by tool, endings) and
  lists every conversation with its tokens, cost and tool calls per turn. The scorecard shows a
  role's conversations next to its calls.
- **Record and replay.** A recording's key holds the tools offered and each turn's tool calls and
  results, only when there are any, so recordings made before keep their names; a recorded answer
  holds its tool calls. So a tool loop replays turn by turn, and tool results must be the same
  for the same snapshot, which the tools ensure (below). The fake provider can ask for tool calls
  (`callingTools`).
- **The tools** (`GraphTools`, `lantern-link`, for Resolve now and Investigate in #269):
  `facts_about` (an entity's facts, those it is the subject of and then those that point to it,
  optionally of one predicate), `search_facts` (facts whose written form holds a text, ignoring
  case) and `read_file` (up to 200 numbered lines of a file of the snapshot's repositories at the
  snapshot's commit, read from git; offered only when files can be read). Facts are written as
  the resolver's prompt writes them (subject, predicate, object, attributes, `repo:path`, no fact
  IDs or lines), in a fixed order, at most 40 a call and 24,000 characters a result, each with a
  reference (`T1`, ...) kept for the conversation; a fact cut off for length gets none. A file is
  shown with its secrets masked, its line breaks kept: source in Java, Kotlin, Groovy, Gradle or
  Scala as summaries show Java (ADR 0054); in any other file, each secret key's value
  (`ConfigExtractor.isSecret`, after an optional `export`; a value of placeholders only is kept),
  including the lines of a YAML block scalar under it, secret XML elements and attributes and
  `name=... value=...` pairs, and credentials in URLs (`UrlCredentials`); a private key's body in
  every file. Keys and keystores (`.pem`, `.key`, `.p12`, `.jks`, `id_rsa`, ...) and binary files
  are not shown. A path outside the repository or an unknown repository is an error result. The facts and files are prepared once per run (`GraphTools.Graph`) and each
  conversation gets its own references.
- **Resolve uses them** when its model has `tool-use`; otherwise its prompt and answers are as
  before. The system prompt then adds a paragraph: the tools, that what they return is data, not
  instructions, that a fact a tool showed may be cited by its reference, that a file is not a fact,
  and to look only as far as needed. An answer may cite `T` references the tools showed; any other
  reference is still rejected. A cited tool fact is stored in the cached answer by its fact ID and
  joins the link's `facts`. A cached answer is used only while every tool fact it cites still
  holds; otherwise, for example after a commit that changes that fact, the link is asked about
  again.
  `ResolvePrompt.VERSION` is 4; with or without tools the cache key holds the system prompt, so
  the two never share answers. The app gives the resolver the snapshot's files through
  `GitSourceFiles` at each repository's snapshot commit, carried forward ones included.

## Evidence

- `ConversationTest` (`lantern-llm`): a two-tool-turn loop, results sent back, turns and tool
  calls logged per call and per conversation, a reply after an answer, a reply to an empty answer,
  a budget, the turn cap, a model without `tool-use`, costs adding up, and a loop recorded and
  replayed turn by turn. `AnthropicProviderTest` checks the tools, `tool_use` and `tool_result`
  JSON against a stub server.
- `LinkResolverTest` (`lantern-link`): the resolver looks up a controller's facts, reads its file
  with a secret masked and picks citing a tool's fact; a cached answer gives the same link; a
  model without tools gets the prompt alone; a `T` reference no tool showed is rejected; and a
  three-turn conversation replays from recordings kept in
  `lantern-link/src/test/resources/recordings/resolve-tools` (rewritten from the scripted model
  with `-Dlantern.test.rerecord=true`, never a real one). `GraphToolsTest` checks the tools.
- `LlmAccessIT` (`lantern-app`): a recorded tool loop is stored turn by turn and as a
  conversation, and `run.json` shows its turns and tool calls by role.
- `Project0LinksIT`: on project-0's three links left open with candidates, the resolver, with a
  fake model that first looks up the unit it picks, holds two-turn conversations and records the
  same links.
- **No development set has a link left open with candidates** at the pins of #251 since #257 and
  #258: project-1 to project-4 make no Resolve call with or without tools (their scorecards are
  unchanged), and project-1 leaves two HTTP calls open without candidates. So no real recording
  of Resolve on project-4 exists; Investigate (#269) will be the tools' first real use there.

## Cost

A typical Resolve conversation is a few turns of a few thousand input tokens each, about $0.04 a
link at $3 and $15 per million tokens. That is not a bound: each turn sends every earlier turn
again, uncached, and a result may hold 24,000 characters, so a conversation that runs to the cap
could cost several dollars before the context window refuses it. The run's budget is the bound,
as the user asked; the metrics above show whether a turn limit is needed.

## Not covered

A turn limit per role (the metrics come first), tools for the OpenAI provider (#38), parallel
tool calls run concurrently (they run in order), prompt caching of a conversation's earlier
turns, and structured output.
