# How long a run takes, and how to make it short

This is the research for #688, which asks how to bring a cold run on a mid-size system from about 9.5 minutes down to something people will wait for. **Depth: high.** It is an open question that means choosing between several approaches (scheduling, concurrency, hedging, progressive output, model choice, incremental reruns), and the answer brings in a new pattern: one scheduler for all roles. Pages read in full: 22. Read in part: 6. Looked for and not opened: 12. The research is generic and uses only web sources; the only test-set evidence is the timings measured in #688 (project-4, 8 repositories, Claude Haiku 5.5 through OpenRouter, cold cache) and the model benchmark of #689.

The owner has not set a target yet. This note designs for a first document under 2 minutes on a system of that size and under 30 seconds after a one-file change, and says under "Under other targets" what changes for other targets. The per-role request settings are being changed by #529 while this is written, so the per-call times below may move; the reasoning does not depend on them.

## Question

What decides how long Lantern's LLM phase takes, and which mix of scheduling, concurrency, tail handling, progressive rendering, model choice and incremental reruns gets the first document under 2 minutes, and a rerun after a one-file change under 30 seconds?

Sub-questions:
- How much of today's 8.5 minutes is real work, and how much is waiting at role barriers?
- What is the shortest time possible if every item started as soon as its inputs were ready (the critical path)?
- What do the providers allow (requests, tokens and concurrency), and how should Lantern size its concurrency from that?
- Does hedging help LLM calls, at what cost, and how do results stay reproducible?
- What can be shown early, and how should pending parts look?
- What changes for slower models, and under other targets?

## What the measurements show

**Wall time against the sum of the work.** Adding up the roles' "Takes" column in #688 gives 105+56+59+36+55+10+61+55+31+9 = 477 s. The role start times account for the rest: summarize starts at 0:00 and the injection screen ends at about 8:09 (489 s). There are 517 calls (215+103+53+15+14+5+10+57+5+40). Their call times sum to 996+475+215+35+256+20+187+579+33+84 = 2,880 s.

At 12 calls at a time with no waiting, 2,880 / 12 = 240 s, which is 4 minutes. Today's 477 s is about twice that. So about 237 s, roughly 4 minutes, goes to barriers, tails and sequential chains, not to work.

**Each role waits for its slowest item.** Each role's sum of call time divided by 12 (the best it could do at today's cap), against what it actually took:

- summarize: 83 s ideal, 105 s actual
- investigate-leads: 40 s ideal, 56 s actual
- investigate-units: 18 s ideal, 59 s actual
- investigate-system: 3 s ideal, 36 s actual
- describe-unit: 21 s ideal, 55 s actual
- describe-element: 2 s ideal, 10 s actual
- flow-significance: 16 s ideal, 61 s actual
- flow-narrative: 48 s ideal, 55 s actual
- purpose, judge, name-groups: 3 s ideal, 31 s actual
- injection-screen: 7 s ideal, 9 s actual

Only summarize and flow-narrative are limited by the cap. The other roles are limited by one long item, or by a chain of calls inside the role:
- investigate-system: 15 calls, 35 s of call time, 36 s of wall time. In effect it is one sequential conversation, about 2.3 s per turn.
- purpose, judge and name-groups: 5 calls, 33 s of call time, 31 s of wall time. Also a sequence.
- describe-unit averages 256/14 = 18 s per call, and flow-significance 187/10 = 19 s per call. These are long-output calls, four times the 4.3 s typical call. Output length drives latency [2, 6, 20].

The fan-out effect from Dean and Barroso [11] explains why every barrier pays the tail. If one call in ten takes over 10 s, the chance that a role of n calls has none over 10 s is 0.9^n. That is 0.9^14 ≈ 23% for describe-unit and 0.9^215 ≈ 0 for summarize. So in practice every barrier waits for a slow call. There are 10 role barriers and 3 more inside summarize (types → packages → modules → repositories), so a run pays that wait about 13 times.

**Lower bound with an item-level graph and enough concurrency.** This is an estimate. It assumes the dependencies of `docs/epics/001-knowledge-build.md` (section "Order") and typical call times taken from the averages above. The longest chain of individual calls is:

- type summary → package → module → repository: 4 × 4.3 ≈ 17 s
- a lead, if the unit has one: about 5 s
- one investigate-units conversation: 53 calls over about 8 units ≈ 6–7 turns × 4 s ≈ 27 s
- investigate-system, 15 turns in sequence: 36 s
- describe-unit: 18 s
- name-groups: about 6 s
- flow-significance: 19 s
- flow-narrative: 579/57 ≈ 10 s
- purpose: about 6 s
- judge: about 6 s

Total: about 17 + 5 + 27 + 36 + 18 + 6 + 19 + 10 + 6 + 6 ≈ 150 s.

So with the current chain, even unlimited concurrency and no tails gives about 2.5 minutes of LLM time on Haiku. A chain of about 12 steps has a 1 − 0.9^12 ≈ 72% chance that at least one call goes over 10 s, which adds another 10–30 s. Scheduling alone does not reach 2 minutes. The chain itself has to get shorter.

**Throughput is not the limit once the barriers go.** Little's law gives the concurrency needed: total call time / target wall time. For 2,880 s of call time in 90 s, that is 32 calls in flight on average. The graph is narrow at the start and the end, so the peak would be about 2× that, around 64.

**Slower models.** Total LLM time is 8 min on Haiku, 16 on Ling (2×), 31 on GLM (3.9×) and 37 on DeepSeek (4.6×) (#689). Their slowest calls are 127 s, 93 s and 281 s, against Haiku's 37 s. The critical path grows by roughly the same factor, because these models write 2–3× more output and output dominates latency [6, 20]. So the 150 s chain becomes about 300 s on Ling and 580–690 s on GLM or DeepSeek. The call-time sum becomes about 10,000–13,000 s; at 64 in flight that is 160–210 s, still below the chain. On slower models the chain decides the run time, and the tail (single calls of 100–280 s) decides it even more.

## Summary of findings

- Anthropic, Haiku 4.5 at the Start tier: 1,000 requests per minute (RPM), 2,000,000 input tokens per minute (ITPM), 400,000 output tokens per minute (OTPM); Haiku 5.5 has 4,000 RPM there. Build tier: 5,000–10,000 RPM, 1M OTPM. The limits are token buckets. Cached input tokens do not count toward ITPM, and `max_tokens` does not count toward OTPM. Short bursts can trip limits, because 60 RPM may be enforced as 1 per second, and sharp increases can hit "acceleration limits" [1].
- OpenAI counts the larger of `max_tokens` and the estimated input against its token limit, says to ramp by at most 50% every 15 minutes once at 1M tokens per minute, and counts failed requests too [7].
- OpenRouter has no platform request cap for paid models. Its limits are an in-flight spending budget, a fraction of the account balance held against the estimated cost of open requests, and the upstream providers' own limits. It can sort providers by latency or throughput, and prefer them by p50–p99 cut-offs [9, 10].
- Output length dominates latency. "Cutting 50% of your output tokens may cut ~50% of your latency," while cutting 50% of the prompt gains 1–5% [6]. In Splitwise's traces, a conversational answer of 129 tokens spent about 97% of its time generating and about 3% before the first token [20]. Anthropic's advice is a faster model, a lower `effort`, shorter outputs and streaming [2].
- Hedging: send a second copy only after the first has waited past the 95th percentile. That adds about 5% load; in Google's BigTable test it cut the 99.9th percentile from 1,800 ms to 74 ms with 2% more requests. It works when slowness is not inherent in the request [11]. gRPC allows at most 5 attempts, with a token-bucket throttle, and only for idempotent methods; the first answer wins and the rest are cancelled [12].
- LLM practice reports suggest a hedge budget of 5–10% of traffic, timed on the first body byte rather than the headers, to a different provider. During a provider-wide slowdown every hedge fires, so the budget must cut them off [14, 15].
- Scheduling LLM calls as a graph rather than one after another: LLMCompiler dispatches each call as soon as its inputs are ready, with speed-ups of 1.8–3.7× [16]. Parrot cut end-to-end latency 2.4× by batching map tasks and prioritising the reduce step, and reports up to 11.7× overall [17]. Autellix prioritises calls by a program's critical path and reports 4–15× throughput at equal latency [19].
- Build and dataflow tools: Dask runs critical-path children first [25]. Bazel reports the critical path and says to shorten it [24]. Greedy list scheduling is within 2 − 1/m of optimal, and longest-path-first ordering improves it [27]. LangGraph runs in Pregel-style super-steps [23], the barrier pattern Lantern has today.
- Salsa "backdates" a recomputed value that equals the old one, so its dependents do not run again [26].
- People's limits: under 0.1 s feels instant, 1 s keeps the flow of thought, and past 10 s people lose attention and need a progress indicator and a way to cancel [21]. React Suspense reveals nested parts as they arrive and keeps old content visible while new content loads [22].
- The batch API costs half. Most batches finish within 1 hour, and they can take up to 24 hours [3]. OpenAI's Flex is also cheaper and slower, and may return "Resource Unavailable" [8]. Anthropic no longer sells Priority Tier [4].

## Sources read

1. **Anthropic: Rate limits.** <https://platform.claude.com/docs/en/api/rate-limits>. Read in full. Limits are per model class, in RPM, ITPM and OTPM, enforced as a token bucket; 60 RPM may be enforced as 1 per second. Haiku 4.5: Start 1,000 RPM / 2M ITPM / 400k OTPM; Build 5,000 / 5M / 1M; Scale 10,000 / 10M / 2M. Haiku 5.5 has 4,000, 10,000 and 13,000 RPM. Only uncached input counts toward ITPM, and OTPM counts only tokens actually produced. A 429 may carry `retry-after`, sharp ramps can trip acceleration limits, and response headers give the remaining requests and tokens.
2. **Anthropic: Reducing latency.** <https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/reduce-latency>. Read in full. Three levers: choose a faster model (Haiku 5.5 at `effort: low`), cut prompt and output length (ask for a number of sentences, set `max_tokens`), and stream for perceived speed. Defines baseline latency and time to first token.
3. **Anthropic: Batch processing.** <https://platform.claude.com/docs/en/build-with-claude/batch-processing>. Read in part: the full text was searched for timing, pricing and limits. 50% off all tokens. "Most batches completing within 1 hour"; unfinished requests expire after 24 hours; results are kept 29 days. Processing may slow down with demand.
4. **Anthropic: Service tiers.** <https://platform.claude.com/docs/en/api/service-tiers>. Read in full. Priority Tier is legacy and no longer sold; it targeted 99.5% uptime. Standard tier is best-effort. There is no paid latency tier to rely on.
5. **Anthropic: Prompt caching.** <https://platform.claude.com/docs/en/build-with-claude/prompt-caching>. Read in part (the first 100,000 of 166,000 characters). Minimum cacheable prompt: Haiku 5.5 512 tokens, Haiku 4.5 4,096. Default lifetime 5 minutes; cache write 1.25×, read 0.1×. "A cache entry becomes available only after the first response starts": parallel requests that share a prefix should wait for the first one to begin.
6. **OpenAI: Latency optimization.** <https://developers.openai.com/api/docs/guides/latency-optimization>. Read in full. Seven principles. "Cutting 50% of your output tokens may cut ~50% of your latency"; halving the input gains only 1–5%. Make fewer requests, run independent steps in parallel, speculate on a likely outcome, and stream so people wait less.
7. **OpenAI: Rate limits.** <https://developers.openai.com/api/docs/guides/rate-limits>. Read in full. Limits are RPM, RPD, TPM, TPD and IPM. The larger of `max_tokens` and the estimated input counts toward TPM. Example tiers: 5,000 RPM / 1M TPM; 15,000–30,000 RPM higher up. Honour `Retry-After` plus jitter, count retries, and ramp at most 50% every 15 minutes once at 1M TPM.
8. **OpenAI: Flex processing.** <https://developers.openai.com/api/docs/guides/flex-processing>. Read in full. Priced at batch rates, slower, sometimes unavailable (a 429 "Resource Unavailable", not charged). For work that is not urgent.
9. **OpenRouter: API rate limits.** <https://openrouter.ai/docs/api-reference/limits>. Read in full. Paid models have no platform request cap. An in-flight spending budget (a fraction of the balance) can return 402 even with credit. Upstream providers have their own 429s; fallbacks try other providers, and a 429 in the middle of a stream arrives as a stream event.
10. **OpenRouter: Provider selection.** <https://openrouter.ai/docs/guides/routing/provider-selection>. Read in full. By default it weighs price, throughput, time to first token, 429 rate and errors. `sort: "latency"` or `"throughput"` (or the `:nitro` suffix) changes the order. `preferred_max_latency` and `preferred_min_throughput` take p50–p99 cut-offs over a rolling 5-minute window and deprioritise providers rather than block them. Follow-up messages stay on the same provider while its cache is warm.
11. **Dean and Barroso, "The Tail at Scale", CACM 56(2), 2013.** <https://www.barroso.org/publications/TheTailAtScale.pdf>. Read in full. With 100 parallel parts, each slow 1 time in 100, 63% of requests are slow. In a Google service the slowest 5% of leaves made up half of the 99th-percentile latency (70 ms of 140 ms). Hedged requests deferred to the 95th percentile add about 5% load; in a BigTable test of 1,000 keys a 10 ms hedge cut the 99.9th percentile from 1,800 ms to 74 ms with 2% more requests. Tied requests cut the median 16% and the 99.9th percentile about 40%. Hedging helps when slowness comes from interference, not from the request itself.
12. **gRPC: gRFC A6, client retries and hedging.** <https://github.com/grpc/proposal/blob/master/A6-client-retries.md>. Read in full. A hedging policy has `maxAttempts` (capped at 5) and a `hedgingDelay`. A throttle bucket (at most 1,000 tokens; −1 per failure, +`tokenRatio` per success) stops hedges below half full. Hedging is only for idempotent methods; the first good response wins and the others are cancelled. Server pushback can stop hedges.
13. **Envoy: HTTP routing, request hedging and retries.** <https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/http/http_routing>. Read in part (the hedging and retry sections). Hedging fires on a per-try timeout without cancelling the first request, and a late first response may still win. A retry budget caps retries as a share of active requests, to prevent retry storms.
14. **Tian Pan, "The tail-tolerant retry policy your LLM gateway doesn't have".** <https://tianpan.co/blog/2026-05-02-tail-tolerant-retry-policy-llm-gateway-latency-cliff>. Read in full. A practitioner's post. Hedge at an adaptive p90, to another provider, timed on the first body byte. Cap hedges with a bucket at 5–10% of traffic. Aborted calls still bill the input and often the partial output. Propagate cancellation.
15. **Tian Pan, "LLM tail latency: p99 and heavy-tailed distributions".** <https://tianpan.co/blog/2026/05/07/llm-tail-latency-p99-heavy-tailed-distributions>. Read in full. Output length is heavy-tailed, and chains of calls compound the tail. One example has p50 800 ms and p99 28 s; a hedge at 2× p50 duplicates about 1 request in 10. Streaming hides the tail from people but does not shorten it. Practitioner figures, not verified; little weight.
16. **Kim et al., "An LLM Compiler for Parallel Function Calling" (LLMCompiler), ICML 2024.** <https://arxiv.org/html/2312.04511>. Read in part (about 85%). A planner builds a graph of calls, and a task-fetching unit dispatches each as soon as its inputs resolve. Latency 1.8× better on HotpotQA (7.12 s → 3.95 s) and 3.74× on Movie Recommendation (20.47 s → 5.47 s). Limits: planning and joining steps that cannot run in parallel, and stragglers (the slowest search 1.13 s against a 0.61 s average).
17. **Lin et al., "Parrot: Efficient Serving of LLM-based Applications with Semantic Variable", OSDI 2024.** <https://www.usenix.org/system/files/osdi24-lin-chaofan.pdf>. Read in part (pages 1–8: problem, design, scheduling). Optimising each request on its own is wrong for end-to-end latency. In a map-reduce summary, batching the map stage and prioritising the reduce stage cut latency 2.4× (2,700 ms → 1,100 ms). It derives each request's priority by walking the graph backwards from the latency-critical outputs. 30–50% of call latency, over 70% in the worst cases, comes from outside the engine (network and queueing between dependent calls). Up to 11.7× speed-up.
18. **Ning et al., "Skeleton-of-Thought", ICLR 2024.** <https://arxiv.org/html/2307.15337>. Read in part (the first half). Decoding is sequential, so latency grows with output length. Writing a short outline and then expanding each point in parallel gives up to 2.39× (Claude: 22 s → 12 s). Quality drops on writing, maths and code, where points depend on each other, and it costs more input tokens.
19. **Luo et al., "Autellix: An Efficient Serving Engine for LLM Agents as General Programs", 2025.** <https://arxiv.org/html/2502.13965>. Read in full. Programs are graphs of calls that suffer head-of-line blocking. One policy prioritises calls by service already received; another by each thread's critical path, so stragglers do not hold a program up. 4–15× throughput at equal latency over vLLM, and 10–40% shorter makespan for offline batches.
20. **Patel et al., "Splitwise: Efficient generative LLM inference using phase splitting", ISCA 2024.** <https://arxiv.org/html/2311.18677>. Read in full. Reading the prompt is compute-bound; generating tokens is bound by memory bandwidth. Llama-70B on A100, conversation trace (median 129 output tokens): time to first token 155 ms, end to end 4,957 ms, so about 97% of the time is generation. About 52 ms per token on the coding trace and 40 ms on conversation.
21. **Nielsen, "Response Times: The 3 Important Limits".** <https://www.nngroup.com/articles/response-times-3-important-limits/>. Read in full. 0.1 s feels instant, 1 s keeps the flow, and 10 s is the limit of attention. Past 10 s, show percent done and a way to cancel; if the total is unknown, name each item as it is processed.
22. **React: Suspense.** <https://react.dev/reference/react/Suspense>. Read in full. Nested boundaries reveal content in stages, streaming server rendering sends the outer shell first, reveals are throttled to every 300 ms, and `useDeferredValue` or transitions keep old content visible, dimmed, while new content loads.
23. **LangGraph: Graph API.** <https://docs.langchain.com/oss/python/langgraph/graph-api>. Read in full. Execution moves in Pregel-style super-steps: nodes in one super-step run in parallel, and `Send` fans out map tasks. A step-synchronous model, the barrier pattern.
24. **Bazel: JSON trace profile.** <https://bazel.build/advanced/performance/json-trace-profile>. Read in full. The profile shows the critical path as its own row, and the concurrent action count should reach `--jobs`. To speed up a build, split or shorten actions on the critical path.
25. **Dask: Scheduling in depth.** <https://docs.dask.org/en/stable/scheduling-policy.html>. Read in full. Ready tasks go on a last-in-first-out stack; ties are broken by a depth-first numbering that visits critical-path children first, so long chains do not stall. About 1 ms of overhead per task.
26. **Salsa: The red-green algorithm.** <https://salsa-rs.github.io/salsa/reference/algorithm.html>. Read in full. Cached values are checked against their dependencies' change revisions. Backdating: if a recomputed value equals the old one, its dependents are not re-run. Durability levels skip checking inputs that rarely change.
27. **Wikipedia: List scheduling.** <https://en.wikipedia.org/wiki/List_scheduling>. Read in full. Greedily giving ready jobs to free machines is within 2 − 1/m of optimal; orderings by critical path or longest path improve it. Graham's anomalies: adding a machine or shortening a job can make the total longer.
28. **A. Colyer, The Morning Paper on "The Tail at Scale".** <https://blog.acolyer.org/2015/01/15/the-tail-at-scale/>. Read in full. A secondary summary that confirms the numbers in [11]; no extra weight.

## Not read (no weight)

- OpenAI, "Priority processing" guide (returned 404).
- The CACM web page for "The Tail at Scale" (returned 403; the PDF [11] was read instead).
- Microsoft Azure OpenAI "priority processing" page (search lead only).
- DistServe, Orca, vLLM/PagedAttention, Teola and ALTO papers (not opened; [19, 20] cover the same ground).
- Hamilton, Prefect and Dagster concurrency docs (not opened; [23, 25] cover the patterns).
- Writing on the "Doherty threshold" (not opened; [21] covers response-time limits).
- The `crucible_hedging` README and a news article on adaptive hedging (search leads; their 75–96% p99 claims carry no weight).

## Options

**1. One item-level graph instead of role barriers.** Every item, whether a type summary, a lead, a unit conversation, a describe or a flow, starts as soon as the items it reads are done. That is the dispatch model of [16, 17, 19] and the opposite of super-steps [23].
- Gain: it removes the about 237 s of barrier waiting. On today's 12-call cap the run drops from about 477 s towards the larger of 240 s (throughput) and about 150 s (chain), so about 4 minutes. With enough concurrency it reaches the chain, about 2.5 minutes.
- It also lets the injection screen and leads start at once, and lets summarize start on a repository as soon as that repository is extracted, overlapping the 1-minute build.
- Cost: no extra money. Medium complexity: dependencies must be declared per item, and the budget, packets and progress have to work without role boundaries.
- Reproducibility: unchanged if prompt inputs are assembled in a fixed order and packets are formed from a fixed list, not from whatever is ready.
- Risk: today's packets group items of one role; grouping by readiness would make fingerprints depend on timing.

**2. More calls at a time, sized from the provider's limits.** A cap per model taken from limits and headers [1, 7, 9] instead of a fixed 12.
- Gain on its own, with barriers: summarize goes from about 105 s towards its internal chain plus tails (4 levels × about 15 s ≈ 60 s); other roles barely change, because they are tail-bound. About 1.5–2 minutes saved. Combined with option 1 it is what lets the graph reach its chain.
- Cost: no extra money. Low complexity.
- Risks: acceleration limits on sharp ramps [1, 7]; OpenRouter's in-flight spending budget can return 402 [9]; the prompt cache only serves parallel requests once the first one has started [5].

**3. Hedge the tail.** Re-send a call that is well past the role's p95, to another provider where possible, with a budget [11, 12, 14].
- Gain: on a 12-step chain with a 72% chance of at least one slow call, it cuts a 20–37 s outlier to about p95 plus a typical call (an estimate of 10–20 s on Haiku), so about 10–25 s saved on the critical path. On slow models, where single calls reach 127–281 s, it saves minutes.
- Cost: about 5% more calls if fired at p95 [11]; about 10% at p90 or 2× p50 [15].
- Risks: hedging helps only when slowness is not inherent in the request [11]. A long LLM answer is inherently slow, so a hedge on elapsed time just pays twice. Hedge on lack of progress instead (no first token by the p95 time to first token, or an output rate far below the model's usual), which streaming makes visible. During a provider-wide slowdown the budget must stop hedging [14].
- Reproducibility: a race picks which of two valid answers is stored. A first run's answer is already one sample, so this is no worse than today; once cached, a rerun is identical.

**4. A progressive document.** Render the parts that need no LLM (units, dependencies, diagrams from facts) as soon as linking ends, then fill in summaries and narratives as they arrive, with pending parts marked. On a rerun, show the old text, dimmed and marked "updating", until the new text arrives [21, 22].
- Gain: a first useful view at about the end of extraction (about 1 minute on a cold build; seconds on a warm rerun). Total time is unchanged, but the wait people feel moves under Nielsen's limits [21], or past them with a visible progress count.
- Cost: no money. Medium complexity in rendering: partial document models, placeholders and a stable layout.
- Risk: a reader trusts a half-done document. Every pending or stale part must say so, and the model checks (judge, injection screen) gate the final state, not the early view.

**5. A model and effort choice per role, and shorter outputs.**
- Gain: describe-unit and flow-significance average 18–19 s per call against 4.3 s for a typical call. Halving their output saves about 18 s on the chain [6]. Running investigate-system as a few parallel questions instead of a 15-turn sequence cuts about 36 s to about 10 s (an estimate: 2–3 sequential steps of about 4 s).
- `effort: low` and a fast model on the bulk roles [2].
- Cost: may save money. Low complexity for model choice; medium for reshaping a role.
- Risks: answer quality, which must be re-judged on the test sets. Every model or prompt change also invalidates the cache, because both are in the fingerprint.

**6. Incremental reruns that re-ask only what a change touches, and stop early.**
- Gain: a one-file change re-asks one type summary, its package, module and repository, one describe-unit, a few flows, the purpose and the judge. That is a chain of about 4.3 × 4 + 18 + 19 + 10 + 6 + 6 ≈ 76 s on Haiku, with only about 10 calls. The depth of the chain, not the number of calls, decides it.
- With Salsa-style backdating [26] the walk stops wherever a re-asked answer equals the old one. The design already does this for summaries (`docs/design.md`, "On a new snapshot", step 5), but LLM text rarely repeats word for word, so the stop will seldom fire unless the compared value is more stable than prose.
- Showing the old text until the new one arrives makes the change visible within about 5–10 s (the type summary) while the rest refreshes.
- Cost: no money. The mechanism exists; this needs a "stale while refreshing" rule and a way to make the early stop fire.
- Risk: a stale parent sentence shown next to a new child. It must be marked.

**7. The batch API.**
- Gain on latency: none; it makes runs slower ("most within 1 hour", up to 24 hours [3]). Half price.
- Use it only for runs nobody waits on (nightly or background).

| Option | Gain on first run (Haiku) | Gain on rerun after one change | Extra cost | Complexity | Effect on reproducibility |
| --- | --- | --- | --- | --- | --- |
| 1 Item-level graph | 8 → about 4 min at cap 12; about 2.5 min with option 2 | A chain of about 76 s instead of barrier waits | None | Medium | None, if inputs and packets are formed in a fixed order |
| 2 Concurrency from limits | About 1.5–2 min alone; needed for option 1 | Small | None | Low | None |
| 3 Hedging the tail | About 10–25 s on the chain; minutes on slow models | About 5–15 s | About 5–10% more calls | Low–medium | First run: a race between two valid answers; reruns identical |
| 4 Progressive document | First view at about 1 min (cold) | First view in seconds | None | Medium | None; pending parts marked |
| 5 Model, effort, shorter outputs | About 40–60 s off the chain (estimate) | About 20 s | May save money | Low–medium | Changes answers; re-judge |
| 6 Incremental, stale while refreshing | None | About 76 s down to a first update at about 5–10 s | None | Low–medium | None |
| 7 Batch API | Negative (hours) | Negative | −50% | Low | None |

## Recommendation

Build one scheduler for all LLM work, and shape the slow roles so the critical path is short.

1. **One item-level scheduler.** Each item (a call or a packet) declares the items whose answers it reads, and becomes ready when they are done, as in LLMCompiler and Parrot [16, 17]. The role order of the epic stays as the dependency rule, but it applies per item: a unit's describe waits for that unit's summaries and investigation, not for every unit.
   - The injection screen of a blob starts when that blob is first shown in a prompt.
   - Leads start from facts as soon as derivation ends.
   - Summarize for a repository starts when that repository's extraction finishes, overlapping the build.
2. **Critical path first.** Among ready items, run first the one with the longest remaining chain below it, each step weighted by its role's measured median call time, as Dask and Autellix do [19, 25] and list scheduling's longest-path rule says [27]. Long-output roles on the chain (describe-unit, flow-significance) go first; bulk leaves off the chain (the injection screen, type summaries whose parents are not on it) fill the gaps.
3. **Concurrency per model, sized from limits.** For a model with typical call time L, average output tokens o and uncached input tokens i, measured per role:
   `cap = 0.8 × min(RPM × L/60, OTPM × L/(60 × o), ITPM × L/(60 × i))`
   For example, Haiku 4.5 at the Start tier with L = 4.3 s and an assumed o = 500: by requests 1,000 × 4.3/60 ≈ 72, by output tokens 400,000 × 4.3/(60 × 500) ≈ 57, so a cap of about 46 calls in flight.
   Read the `anthropic-ratelimit-*-remaining` headers where the provider sends them [1]. On OpenRouter, which does not [9], start at the configured cap and halve it on a 429 or 402. Ramp up over the first seconds rather than bursting [1, 7]. The cap stays a setting, because through a gateway the upstream limits and the spending budget are not visible. Keep the existing retry with backoff, jitter and `Retry-After` (ADR 0021).
4. **Hedge only the tail.** Only calls on the critical path, all of which are read-only and cached under their fingerprint.
   - Stream responses, and hedge when no first token has arrived by the role's p95 time to first token, or when the output rate stays far below the model's median. Never on total elapsed time, because a long answer is inherently slow [11].
   - At most one hedge per call, to another provider where routing allows [10].
   - A token-bucket budget of 5% of the run's calls, which also stops hedging when many calls are slow at once [12, 14].
   - Hedge spending counts against the run's dollar budget.
   - The first complete answer that passes the checks is stored under the fingerprint, and the other request is cancelled; its answer is discarded, never merged.
5. **A document that fills in.** After linking, render the parts made from facts at once. Then fill in each LLM part as its answer lands, with throttled reveals, about one per 300 ms [22]. Past 10 s, show a count ("41 of 120 descriptions written") rather than a spinner [21]. Mark every pending part.
   - On a rerun, show the previous answer marked "updating" until the new one arrives [22].
   - The final document is the one that has passed the judge and the injection screen; the early view is labelled a draft.
   - Streaming tokens into the page is not needed: sections are short, and streaming serves hedging and progress, not display.
6. **Shorten the chain.**
   - Run investigate-system as a few parallel questions with a short join, instead of a 15-turn conversation.
   - Cap the turns of a unit's investigation.
   - Ask describe-unit and flow-significance for shorter answers [6], or split long ones into an outline and parallel expansion where the parts do not depend on each other [18].
   - Choose the model and effort per role [2], and re-judge quality on the test sets.
   - Run the judge after the document is shown, gating only its final state.
7. **Incremental reruns.** Keep fingerprint caching, and add:
   - "Stale while refreshing": show the old text until the new text arrives.
   - An early stop that fires more often: when a re-asked child's answer equals the old one after normalising whitespace, the parent is not re-asked (backdating, [26]). Measure how often it fires; if rarely, consider having parents cite their children's structured claims rather than their prose, so rewording does not ripple upward.

**Determinism.** Finishing order must never reach a prompt:
- Answers are keyed by fingerprint.
- A prompt's inputs are sorted by item identity, not by arrival.
- Packets are formed from the full, fixed list of items, so their fingerprints do not depend on timing.
- A hedged duplicate is discarded.
- The budget is reserved per call before dispatch, so concurrency cannot overspend it, and the same budget still asks the same items.
- A rerun with an unchanged cache makes no calls, as today.

**Estimated times. These are estimates from the numbers above, not measurements.**

- Haiku, cold cache:
  - Options 1 and 2 (graph, cap of about 46–64): the chain of about 150 s plus about 15 s of tail, about 2.75 min of LLM time. The build overlaps by some tens of seconds, so about 3.3 min end to end.
  - Plus hedging on the chain: about 2.5 min of LLM time.
  - Plus a shorter chain (system 36 → 10 s, describe 18 → 9 s, flow-significance 19 → 9 s, judge off the critical path): 17 + 5 + 27 + 10 + 9 + 6 + 9 + 10 + 6 ≈ 99 s, plus about 10 s of tail, about 1.8 min of LLM time.
  - End to end with the build overlapped: about 2.3–2.8 min for the complete, judged document; a first view of the fact-based parts at about 1 min, and most text in by about 2 min.
  - So "first document under 2 minutes" is reachable on Haiku if "first document" means the view after extraction filling in. A fully finished document in 2 minutes also needs the build under about 30 s, or the summaries of early repositories finished during the build.
- A 3–4× slower model: the shorter chain is about 99 × 3.5 ≈ 350 s, about 6 min. Throughput, 2,880 × 3.5 / 64 ≈ 160 s, is not the limit. 2 minutes is out of reach; the progressive view still appears at about 1 minute.
- One-file change on Haiku: the re-ask chain is about 76 s, about 60 s with a shorter describe and flow-significance. 30 s for the whole chain is not reachable. 30 s for "the changed part updated, the rest visibly refreshing" is: the type summary in about 5 s and the package in about 10 s, with the rest marked "updating" until about 60–75 s. The early stop cuts the rerun wherever answers repeat.

## Under other targets

- **5 minutes, complete document.** Options 1 and 2 alone reach it on Haiku (about 3.3 min); hedging and a shorter chain are optional. On a 3–4× slower model it takes about 6 min even with the shorter chain, so it needs hedging, the shorter chain and probably a faster model for the long-output roles.
- **1 minute, complete document.** Not reachable with today's dependency depth, even on Haiku: the shorter chain alone is about 99 s. It would need type, package and module summaries merged into fewer levels, flows that do not wait on describe, investigation that does not wait on summaries, and the build under 20 s (warm reruns only). That is a change of design, not of scheduling.
- **First useful view in 10 seconds.** Only on a rerun, or with the build and extraction cached: show the previous document at once, dimmed where it is being refreshed [22]. On a cold run the first view comes after extraction (about 1 min), unless extraction itself is shown repository by repository.
- **Slower models in general.** The chain, not throughput, decides the time, and the tail calls (100–280 s) matter most. Hedging on lack of progress, a fast model for the roles on the chain and a cap on output length give the most. A model whose first token is fast but which writes 2–3× more gains nothing from that fast start [6, 20].

## What it changes in the bar or the design

Nothing changes yet: the owner sets the target first, and the issues that build each part change the design and write the ADR in the same PR. What they will change:

- **`docs/design.md`, LLM integration, "Cost and reliability":**
  - Replace the role-by-role order and the fixed cap with one item-level scheduler, critical-path-first priority and per-model concurrency computed from limits and headers.
  - Add the hedging rules: lack of progress as the trigger, at most one hedge, a 5% budget, the loser discarded, its spending counted in the run's budget.
  - Add the per-call budget reservation.
  - Say that the batch API is for background runs only, because its half price comes with completion times of up to 24 hours, mostly under 1 hour [3]; it conflicts with any interactive target.
- **"Versioning and incremental computation":**
  - Add the fixed-order rule: prompt inputs sorted by identity, packets formed from fixed lists.
  - Add "stale while refreshing" and a stronger early stop when a re-asked answer is unchanged (backdating).
  - State a rerun target as two numbers: time to the first updated part, and time to fully refreshed.
- **Rendering:** a document can be partial. Fact-based parts render first, LLM parts fill in, pending and stale parts are marked, and only the checked document is final.
- **The epic's role order:** investigate-system becomes parallel questions with a join, and the judge moves off the critical path. Each is its own issue, with quality re-judged on the test sets.
- **The bar:** once the owner fixes a target (for example a first view within about 1 minute and a complete document within about 3 minutes on Haiku), it goes into `docs/quality-bar.md` as a measured property of a run, linked to this note.
- **ADR:** one ADR records the scheduler: item-level dependencies, critical-path priority, the concurrency formula, hedging and its budget, the determinism rules, and batch limited to background runs. It links to this note.
