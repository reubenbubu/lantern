# Investigate: a cheap, verifiable conversation with a code-reading model

5 Nov 2026, for #889 (milestone 2.9 LLM investigate overhaul).

Researched by a read-only `opus` subagent from web sources only; written up and judged by the chat.

**Depth: high.** This is an open design question with several competing approaches: up-front context or look-it-up, narrow tools or a shell, caching or clearing, how to force an answer, and how to sandbox. It also needs a way to measure the result. So it takes official documentation, tools that solve the same problem, and research papers, compared on their trade-offs. 21 pages were opened and read.

## Question

How should a multi-turn, tool-using LLM conversation that investigates code (a "lead") be built so it spends as few tokens and dollars as possible and still ends with a correct verdict that Lantern can verify? The target is Claude Haiku 5.5 on the Anthropic Messages API through OpenRouter (the owner's choice for this milestone).

## Sources

All were opened with WebFetch and read. Very long pages were read for their relevant sections.

1. Anthropic, Prompt caching. https://platform.claude.com/docs/en/docs/build-with-claude/prompt-caching. Read (first 100k of 166k characters). Contributed: breakpoint rules, the 4-breakpoint limit, the 20-block lookback, minimum sizes (Haiku 5.5 and Sonnet 5.5: 512 tokens), TTLs and prices, the table of what invalidates the cache, Haiku 5.5's price tiers (more than 100K tokens costs 5×), mid-conversation `system` messages that do not break the cache, and pre-warming.
2. Anthropic, Tool use with prompt caching. https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-use-with-prompt-caching. Read. Contributed: changing `tool_choice` or `disable_parallel_tool_use` invalidates the messages cache, changing tool definitions invalidates all of it, put the breakpoint on the last tool, and place breakpoints before a planned `tool_choice` change.
3. Anthropic, Context editing. https://platform.claude.com/docs/en/docs/build-with-claude/context-editing. Read (first 100k of 107k). Contributed: `clear_tool_uses_20250919` and its parameters, how it interacts with caching (each clear means a new cache write; `clear_at_least` exists to make a clear worth that), thinking clearing, server-side compaction `compact_20260112`, and that client-side compaction is deprecated.
4. Anthropic, Define tools (tool_choice, forcing). https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use, which redirects to define-tools. Read. Contributed: **Sonnet 5.5 and Opus 5.5 return a 400 on `tool_choice` `any` or `tool`**. Haiku 5.5 accepts forcing, but a forced call skips thinking. Also: descriptions are the most important factor, at least 3 to 4 sentences each; `input_examples` costs 20 to 200 tokens; ask for an explanation, not for reasoning.
5. Anthropic, Parallel tool use. https://platform.claude.com/docs/en/agents-and-tools/tool-use/parallel-tool-use. Read (saved copy searched). Contributed: all results go back in one user message with no text before them, the `<use_parallel_tool_calls>` system prompt, and the semantics of `disable_parallel_tool_use`.
6. Anthropic, Strict tool use. https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use. Read. Contributed: grammar-constrained sampling guarantees required fields and types, on all models including Haiku 5.5 and Sonnet 5.5.
7. Anthropic Engineering, Writing tools for agents. https://www.anthropic.com/engineering/writing-tools-for-agents. Read. Contributed: consolidate tools; return high-signal fields; truncate with sensible defaults and steer the agent toward small targeted searches; Claude Code caps a tool response at 25,000 tokens; a `response_format` concise/detailed switch used about a third of the tokens; make errors actionable; measure tokens, tool calls and errors.
8. Anthropic Engineering, Effective context engineering for AI agents. https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents. Read. Contributed: aim for "the smallest possible set of high-signal tokens"; load context just in time; Claude Code's hybrid (small fixed context, then glob/grep); tool-result clearing is "the safest lightest touch form of compaction".
9. OpenRouter, Prompt caching. https://openrouter.ai/docs/features/prompt-caching. Read. Contributed: automatic (top-level) and explicit breakpoints; the same prices (1.25×, 2×, 0.1×); sticky routing applies to explicit breakpoints; the session key is a hash of the first system message and the first non-system message, or `session_id`; a session expires after 10 minutes idle; if the sticky provider fails, the fallback goes uncached.
10. OpenRouter, Anthropic Messages, create a message. https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-a-message. Read (first 100k of 246k). Contributed: `/api/v1/messages` accepts `cache_control` (top-level and per block), `strict`, `tool_choice` with `disable_parallel_tool_use`, `context_management` (clear, thinking, compact), `session_id`, and `provider`. The response gives `cache_read_input_tokens`, `cache_creation_input_tokens` and `cost`. The visible part did not mention an `anthropic-beta` header.
11. Yang et al., SWE-agent: Agent-Computer Interfaces (NeurIPS 2024). https://arxiv.org/html/2405.15793v3. Read (first 100k of 204k). Contributed: the four interface principles, the ablations (100-line viewer 18.0% beats 30 lines 14.3% and the whole file 12.7%; summarized search capped at 50 results 18.0% beats iterative search 12.0%; keeping the last 5 observations 18.0% beats the full history 15.0%), a $4 cap per instance, and cost of solved against unsolved runs.
12. Lindenbauer et al., The Complexity Trap (2025). https://arxiv.org/html/2508.21433v3. Read (first 100k of 136k). Contributed: masking old observations about halves cost and matches LLM summaries. Summaries make runs longer and break cache reuse. Masking settings: window M = 10, 250-turn runs.
13. Kapoor et al., AI Agents That Matter (2024). https://arxiv.org/html/2407.01502. Read (first 100k of 145k). Contributed: evaluate cost and accuracy together (a Pareto frontier); simple baselines often match costly agents; report dollars and tokens; run 5 times and report confidence intervals; keep held-out sets.
14. Xia et al., Agentless (2024). https://arxiv.org/html/2407.01489. Read (first 100k of 125k). Contributed: a fixed pipeline that localizes on a compressed "skeleton" costs $0.70 an issue, against $1.6 to $2.5 for SWE-agent, and resolves more. Its critique of agents: long trajectories, and early errors that compound.
15. Aider, Repository map. https://aider.chat/docs/repomap.html. Read. Contributed: a ranked map of the repository's symbols on a 1k-token default budget, sent up front, from which the model asks for files.
16. Claude Code, Sandboxing. https://code.claude.com/docs/en/sandboxing. Read (first 100k of 110k). Contributed: Linux uses bubblewrap plus a network namespace with no route out (seccomp optional); macOS uses Seatbelt; "treat the sandbox as containment for accidents … use a container or VM for stronger guarantees"; domain allow-lists are not isolation.
17. OpenAI Codex, Sandboxing. https://learn.chatgpt.com/docs/sandboxing (redirected from developers.openai.com/codex/sandboxing). Read. Contributed: read-only, workspace-write and full-access modes; bwrap on Linux and Seatbelt on macOS; the sandbox covers child processes too.
18. OpenHands, Runtime architecture. https://docs.openhands.dev/usage/architecture/runtime. Read. Contributed: a Docker container per session with an action server inside; read-only or overlay bind mounts.
19. bubblewrap README. https://github.com/containers/bubblewrap. Read. Contributed: unprivileged namespaces, `--ro-bind`, seccomp filters, `--new-session` (because of CVE-2017-5226); "not a complete, ready-made sandbox"; the caller defines the policy.
20. gVisor docs. https://gvisor.dev/docs/. Read. Contributed: a user-space kernel (Sentry, with a Gofer for files); `runsc` plugs into Docker; stronger than seccomp rules; costs more per system call and is less compatible.
21. Codex security page. https://learn.chatgpt.com/docs/security. Read. Off-topic (Codex Security scanning). It only pointed to source 17.

**Not read** (no weight): Ramp router cache docs, the smithery and openskillindex OpenRouter skill pages, openrouter-rs source, the nsjail README, Landlock docs, Anthropic's token-efficient tool use page, cache-diagnostics page, thinking page, server-side compaction page, and the SWE-agent configuration docs.

## Findings

### 1. First message

- Anthropic recommends just-in-time context: keep light references (paths, names) and let tools load the detail. Claude Code is a hybrid: a small fixed context up front, then grep and glob (8).
- Up-front context works when it is small and ranked. Aider sends a symbol map under a 1k-token budget (15). Agentless localizes on compressed skeletons and beats agents on cost (14). Neither sends the whole system's facts.
- The prompt is cached as a prefix: tools, then system, then messages (1). Anything the same for every lead (instructions, tool definitions) belongs before anything that differs. Then all leads in a run share one cached prefix. The minimum is 512 tokens on Haiku 5.5 and Sonnet 5.5 (1, 9).
- Haiku 5.5 costs 5× more for a prompt over 100K tokens (1). A conversation must stay well under 100K.

### 2. Tools

- **Shape.** Anthropic says to use few, non-overlapping, consolidated tools: "if a human can't tell which tool to use, neither can the agent" (4, 7, 8). SWE-agent found that purpose-built commands with short docs beat raw shell options (11). None of the sources measures a read-only shell against narrow tools for reading code. Claude Code and Codex both give the model a shell but sandbox it (16, 17).
- **Read size.** SWE-agent's ablation: a 100-line window (18.0%) beats 30 lines (14.3%) and the whole file (12.7%). Showing a whole file hurts accuracy, not only cost. The viewer shows line numbers, the total line count and how many lines are hidden (11).
- **Search.** At most 50 results. If there are more, return none and ask the model to narrow the query. That (18.0%) beat iterative paging (12.0%) (11). Anthropic: truncate with defaults and tell the agent what to do next, for example "many small targeted searches" (7). Concise output modes cut tokens about 3× (7).
- **Errors.** Errors should be actionable and show a correct input (7). After one failed action, SWE-agent's success rate drops from 90.5% to 57.2% (11).
- **Definitions cost.** Tools are rendered into the system prompt on every request (4). They cost little because they sit in the cached prefix. But any change to a definition invalidates the whole cache (2). So does unstable JSON key order (1).

### 3. The growing conversation

- **Caching.** Each request reads the prefix up to its breakpoint from cache at 0.1× the base price, and writes the new segment at 1.25× (1). Haiku 5.5 under 100K: base $0.10, 5-minute write $0.125, read $0.01 per million tokens (1). Up to 4 breakpoints. Automatic caching (top-level `cache_control`) uses one slot and moves forward each turn. The lookback is 20 block positions, and consecutive `tool_use` or `tool_result` blocks count as one position (1). A 5-minute entry is refreshed for free whenever it is read (1).
- **OpenRouter.** Top-level automatic caching works only when routed to Anthropic itself, so OpenRouter then excludes Bedrock and Vertex (search lead, confirmed in part by 9). Explicit block breakpoints work on all Anthropic-compatible providers and enable sticky routing (9). The sticky session is keyed on the first system and first non-system message, or on `session_id`. It expires after 10 minutes idle. If the sticky provider fails, the fallback runs uncached (9). The usage fields show cache reads and writes (10).
- **What breaks the cache mid-conversation** (2):
  - changing `tool_choice` or `disable_parallel_tool_use` invalidates the messages cache;
  - changing tools invalidates everything;
  - changing thinking settings or effort invalidates the messages cache.
  - Adding a `{"role":"system"}` message to `messages` does not break the cache on Haiku 5.5 and Sonnet 5.5 (1).
- **Clearing old results.** `clear_tool_uses` replaces old results with a placeholder (defaults: trigger at 100K tokens, keep 3). Each clear forces a new cache write of everything after it, so `clear_at_least` exists (3). The research on masking (12) and SWE-agent's "last 5" (11) measured long runs: 250 turns in 12, about 12 to 21 steps in 11. Masking halved cost there. LLM summaries made runs longer and cost cache reuse (12). Anthropic calls clearing the lightest form of compaction (8).
- **Thinking blocks.** Haiku 5.5 keeps them by default, so the cache stays valid (1, 3). They become part of the cached prefix (1).

### 4. Turns and answers

- **Forcing.** `tool_choice: tool` forces the call on Haiku 5.5, but skips thinking. **On Sonnet 5.5 it returns a 400.** There, the docs say to use `auto` plus `strict` plus prompting (4). Switching `tool_choice` mid-conversation costs one rewrite of the messages cache (2).
- **Strict tools.** Required fields are guaranteed when the tool is called. Strict mode does not make the model call it (6).
- **Nudging.** A system message added mid-conversation does not invalidate the cache on Haiku 5.5 and Sonnet 5.5 (1). This is the cheap way to say "answer now".
- **Budgets.** SWE-agent caps each instance at $4 and auto-submits when the cap is hit. Solved runs were short (median 12 steps, $1.21). Failed runs were long (mean 21 steps, $2.52). 93% of solved runs submitted before the budget ran out (11). Long runs mostly burn money on failures.
- **Parallel calls.** Parallel calls are on by default for Claude 4 and later. A system prompt raises their use. All results go in one user message with no text before them (5). They cut turns without pushing earlier cache entries out of the 20-block lookback (1).

### 5. Sandboxing a read-only shell

- **bubblewrap** is unprivileged, mounts read-only with `--ro-bind`, unshares the network, and accepts a seccomp filter. It "is not a complete sandbox": the caller writes the policy (19). Claude Code and Codex use it on Linux (16, 17).
- **Containers.** OpenHands runs a Docker container per session, with read-only or overlay mounts (18). **gVisor** (`runsc`) adds a user-space kernel under Docker. It is stronger than seccomp rules, but slower per system call and less compatible (20).
- Claude Code says plainly that its OS sandbox is containment for accidents and prompt injection, and that a container or VM gives stronger guarantees. Allow-lists of domains or commands are not isolation (16).

### 6. Measuring

- Report cost next to accuracy and pick from the Pareto frontier. Report dollars and tokens, not proxies. Run each setting 5 times and give 95% confidence intervals. Keep held-out sets (13).
- Track accuracy, tool calls, tokens, errors and runtime. Read transcripts. Redundant calls are a sign that limits are sized wrong (7). Common units in the papers are cost per instance, cost of solved against unsolved runs, and steps (11, 12, 14).

## Options compared

| Topic | Option | Gains | Costs / risks |
| --- | --- | --- | --- |
| Caching | None | Simple | Every turn pays full price for the whole history |
| | Automatic, top-level (1 slot) | One field; moves forward by itself | On OpenRouter, routes only to Anthropic itself (no Bedrock or Vertex fallback) |
| | Explicit: end of system (shared by all leads) plus last block of each request | Shared prefix across leads; works on all providers; sticky routing | Must keep tools and system byte-stable; must move the breakpoint yourself |
| | 1-hour TTL | Survives gaps over 5 minutes | Write costs 2× instead of 1.25×; only worth it if traffic pauses |
| Context clearing | None, with tight output caps | Cache never broken; nothing lost | History grows; fine for short runs |
| | Server `clear_tool_uses` | No client code; shows what it cleared | Beta; each clear means a new cache write; OpenRouter pass-through unverified |
| | Client masking (keep last N) | Proven on long runs (about half the cost) | Every mask shifts the prefix, so the cache rewrites |
| | LLM summary or compaction | Very long runs | Extra calls; longer runs; cache reuse lost (12) |
| Tool set | Narrow purpose-built tools | Predictable output; easy to cap; easy to cite | Coverage gaps; more definitions |
| | Read-only shell only | Flexible (grep, sed, jq) | Output hard to bound; needs a sandbox; parsing for citations |
| | Narrow tools plus one capped shell | Both | Two paths to maintain |
| Sandbox | Pure Java (no shell) | No process risk | Must reimplement grep and slicing |
| | Allow-list parser only | Cheap | Not isolation (16) |
| | bubblewrap | Fast, unprivileged | Policy is ours to get right; host-dependent (AppArmor, user namespaces) |
| | Docker: no network, read-only, all capabilities dropped | Already used by Lantern; strong enough for read-only work | Startup cost (amortized with one container per run) |
| | Docker plus gVisor | Strongest | Extra install; slower system calls |
| Turn / answer scheme | Single phase, N turns | Simple | Often ends with no verdict |
| | Exploration turns, then answer turns, with a system-message nudge | Cache kept; works on Sonnet 5.5 | Model may still not call submit |
| | Forced `tool_choice` on the last turn | Guarantees a call on Haiku | 400 on Sonnet 5.5; one cache rewrite; skips thinking |

## The owner's decisions (5 Nov 2026), which this note's recommendations follow

- **Many small turns, no tight turn limit.** The point of the rework is to stop sending large files, which forced today's 6-turn limit. Small tool answers keep each turn a small increment on a cached conversation, so turns are cheap: a safety cap of about 100 turns, with the conversation's size and the cost per lead as the guards.
- **Small, targeted tool calls one after another.** Every tool answer starts capped at about 1,000 characters, cut to whole lines with a note of what was left out and the narrower call that asks for it. Measurement may tune the figure within small answers; SWE-agent's 100-line window does not apply.
- **Tests on Claude Haiku 5.5 through OpenRouter only**, starting with small budgets raised bit by bit.

## Evidence from Lantern

Today's Investigate (6 tool turns, 60,000 input tokens, whole-file reads) on the fixed panel of 24 leads of project-1 to project-4, on Haiku 5.5 (#890): 20 of 24 leads reach a verdict, for $0.117. On project-4 (12 leads, $0.062) the input was 178 fresh tokens, 226,859 written to the cache and 1,268,668 read from it, and 42,209 output: most of the cost is writing the large first message (up to 44,000 tokens a turn) and reading it back each turn, then output. The leads without a verdict ran out of turns, or their answer was rejected twice.

An earlier proof of concept outside the repository (Python, other models, reference only) showed the target shape: a first message of about 4,000 characters instead of 50,000, and a conversation growing from 4,200 to 5,400 tokens over four turns of small tool calls. Its failures are lessons, not results: a turn that spent its whole output limit thinking passed an empty submit, and an answer written as text was lost (noted on #892).

## Recommendations

**1. First message.**
- Put the fixed instructions and tool definitions in `system` and `tools`, the same bytes for every lead. Mark a breakpoint at the end of `system`.
- The first user message holds only the lead and the facts about it: the claim, the static evidence with file:line, the names of the units involved. Budget about 1 to 2k tokens. No unrelated system facts.
- Everything else is looked up with tools.

**2. Tools.** Keep about 5 tools, with stable order, stable JSON key order, and `strict: true`. Each description is 3 to 4 sentences.
- `read_lines(path, start, count)`:
  - returns at most about 1,000 characters, cut to whole lines;
  - each line is prefixed with its number;
  - a header says "lines a-b of N";
  - a footer gives the next call (`start=b+1`).
- `search(pattern, path_glob)`:
  - returns at most 50 hits, each `path:line: text` with the enclosing method or class name;
  - beyond 50, it returns only the count and "narrow the pattern".
- `find_files(glob)`, capped the same way.
- `shell(command)`, read-only and run in the sandbox (5 below). Output is capped at about 1,000 characters, cut to whole lines, with "output truncated: N more lines; use grep or sed -n to narrow".
- `submit_verdict`, with required fields: lead id, verdict enum, cited ranges (`path`, `start`, `end`), and a short explanation. Ask for an explanation, never for "reasoning" (4).
- Errors say what to fix and give a valid example.
- Every tool's answer, searches and file lists included, keeps to the same cap of about 1,000 characters; measurement may tune it within small answers.

**3. Conversation and caching.**
- Use explicit breakpoints, not top-level ones, so OpenRouter can fall back to Bedrock or Vertex and sticky routing applies:
  - breakpoint 1 at the end of `system`;
  - breakpoint 2 on the last content block of each request (move it every turn);
  - keep 2 slots spare.
- Set `session_id` to the run id plus the lead id.
- Use the 5-minute TTL. It refreshes on every read, and a lead finishes in minutes.
- Do not clear or summarize: each clear costs a cache rewrite, and with small tool answers a lead's history grows slowly. The size guard (about 60,000 tokens, see Open risks) ends a lead instead. If many leads reach it, measure clearing old tool results first.
- Log `cache_read_input_tokens`, `cache_creation_input_tokens` and `cost` for every call, and alert when cache reads are 0 after turn 1.

**4. Turns and answers.**
- Explore until the model submits or a guard is near: about 100 turns, about 60,000 tokens of conversation, the cost cap per lead. Then up to 3 answer turns.
- Keep `tool_choice: auto` throughout, so the cache survives and Sonnet 5.5 works.
- At the start of the answer phase, append a `{"role":"system"}` message: "Exploration is over. Call submit_verdict now with what you have; use verdict unclear if the evidence is insufficient."
- In the answer phase, any exploration call gets a tool result that refuses it and repeats the instruction.
- Do not force the call (`tool_choice` `tool`) even on the last answer turn: it breaks the cache, skips thinking and is rejected on Sonnet 5.5 (the design keeps `auto`).
- If the model answers in plain text, try to parse it as the verdict before recording "no verdict".
- Put the `<use_parallel_tool_calls>` guidance in the system prompt. Return all results in one user message.

**5. Sandbox.** This takes the place of bubblewrap, which the proof of concept used, because Docker is already a dependency. It is a judgement: no source read compares the two for this use; both are used by tools like this one (16, 17, 18).
- Run the shell in one Docker container per analyze run, using Lantern's existing Docker use:
  - `--network none`, `--read-only`, the checkout mounted `:ro`, `--cap-drop ALL`, `--security-opt no-new-privileges`;
  - a non-root user, pids and memory limits, a small tmpfs;
  - one `docker exec` per call, with a timeout of a few seconds, output cut at the cap in Java while streaming.
- Add a Java-side parser of allowed commands (grep, sed -n, awk, head, tail, wc, jq, find, ls), with no redirection to files. It is a second layer and keeps output predictable. It is not the security boundary.
- Choose gVisor only if untrusted repositories become a requirement. Choose bubblewrap only if Docker is unavailable.

**6. Measuring.**
- On the development sets, report per setting:
  - share of leads with a verdict;
  - verdict accuracy against the answer key;
  - share of citations that check out;
  - input tokens (fresh, cache write, cache read);
  - dollars per lead and per correct verdict;
  - turns and tool calls per lead.
- Run each setting 3 to 5 times, with intervals, before choosing. Keep the cheapest setting on the Pareto frontier. Read a sample of transcripts.

## What it changes

- `docs/design.md`, the Investigate step: what the first message holds, the tool set and its caps, the guards in place of a turn limit and the answer phase, and the caching rule (explicit breakpoints, `session_id`).
- ADR 0100 (LLM harness): breakpoint placement and model capability flags (whether forced tool choice works, mid-conversation system messages); tool definitions must be byte-stable.
- The sandbox module: a read-only shell container profile.
- The prompts folder: the Investigate system prompt and tool descriptions. Its prompt version goes up.
- Measurement: cost per correct verdict next to the existing scores.

## Open risks

- How a refutation should be backed: no source read addresses it. Lantern's own rule (cite where the code runs: build and deploy files when the lead is about them) is #894's to settle and measure.

- Whether OpenRouter's Anthropic endpoint passes mid-conversation `system` messages, and `context_management`, unchanged to every provider. It needs a smoke test.
- Sticky routing fails over uncached. A provider failure makes one lead cost full price.
- The tool-answer cap of about 1,000 characters comes from the proof of concept; SWE-agent's caps come from other tasks and models. Measure on Haiku 5.5, within small answers.
- Haiku 5.5's adaptive thinking adds output tokens and, kept in the history, grows the conversation: the size guard counts it, and a turn cut off at its output limit must not pass a partial answer.
- The 100K price tier: a runaway lead could cross it. Stop a lead when its input passes about 60K tokens.
- Docker exec latency per call. Measure it, and fall back to a Java grep if it dominates.