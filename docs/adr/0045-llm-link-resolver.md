# 0045: LLM resolver for links left open

Status: accepted (#152), amended by ADR 0067 (#200) and ADR 0081 (#169), amended by ADR 0095 (#573), amended by ADR 0095 (#576)

## Context

`docs/design.md` (Cross-repo linking; LLM integration) sends links static matching cannot resolve
to the LLM's Resolve role with the candidate facts, records what it picks as facts with
`origin=llm` and a confidence, caches results by input hash and sends what stays open to the
questions queue. Grounding says a Resolve result only picks among candidates the code shows. The
cross-repo linker (ADR 0042) records each link it leaves open as `unresolved-link` on the calling
unit, with its reason, unresolved keys and candidates. The design also gives Resolve tools to query
the graph and read files, which needs tool use in `LlmRequest` (ADR 0008); the questions queue is
Phase 3. The design leaves open the prompt, the confidence, how answers are checked and cached, and
where the resolver runs.

## Decisions

- **The linker's last step.** `LinkResolver` (`lantern-link`, origin `llm`, name `link-resolver`,
  version the prompt's) is a `LinkStep` after `ClaimChecks`, so its facts are recorded for the
  whole snapshot like the other steps' (ADR 0039): a link it picked stops holding when the link
  left open does. The app adds it with `Linker.standard(store, resolver)`; `lantern-link` now
  depends on `lantern-llm`. Its calls run inside the transaction that records the facts, so each
  answer is cached in a transaction of its own, and a paid answer survives a later rollback.
- **Only links with candidates are asked about**, of kind `http` or `message`. With nothing to
  pick from (project-0's HTTP-5, whose tax service is outside the set), there is no call; the link
  stays open.
- **Evidence in the prompt, no tools.** The prompt shows the call or send as the link left open
  names it, its unit, its code, why it was left open and the placeholders nothing resolves; then
  the facts about it (its unit's `deployable-unit` and `application-name`, and the facts the link
  comes from: the call or send and the config used) as `[F1]`, `[F2]`, ...; then each candidate
  as `[C1]`, ..., in the order of their unit IDs, not ranked, since models tend to favour the
  first option (#199), and the prompt says the order means nothing; each with its unit's facts
  and at most 10 facts of its code: for HTTP the endpoints a request with the call's method and
  path matches, best first, as the linker ranks them; for
  messages the bindings of the exchange sent to that reach a queue the unit listens on, then those
  listeners, else every listener on the same broker. Facts are written as subject, predicate,
  object, attributes (without `facts`, `link` and `candidates`) and `repo:path`, never with fact
  IDs, which change with every commit, nor line numbers, so an edit above a call costs no new
  call; the recorded link keeps the evidence's line. Tools to look further came with ADR 0081.
- **A JSON answer that must pick a candidate.** The model answers one JSON object: `target` (a
  candidate's reference, or `unknown`), `confidence` (`high`, `medium` or `low`), `cites` (the
  references it rests on) and `rationale` (at most two sentences). It is plain text, not the
  provider's structured output, since requests cannot ask for that yet (ADR 0008), so it runs on
  any model; the client's capability checks (context window, budgets) still apply. The answer is
  rejected if it is not one JSON object, names a target that is not a candidate (a candidate's
  unit ID is accepted as its reference), cites a reference not in the prompt, picks a candidate
  without citing it or one of its facts, has no valid confidence for a pick, has no rationale, or
  was cut off. As with summaries (ADR 0010), a rejected answer is sent back once with the reason
  (purpose suffix "(retry)"); a second rejection logs a warning and caches nothing, so a later run
  asks again; the link stays open unless an earlier pick is kept (below).
- **The link it records** is `CALLS_HTTP` (or `SENDS_MESSAGES_TO`) from the calling unit to the
  candidate, with the evidence of the link left open, `source`, the call's `client`, `httpMethod`
  and `path`, `protocol` (`HTTP` or `HTTPS` by the URL's scheme, `JSON/` before it when the cited
  endpoint is a REST controller's; `AMQP`, `Kafka` or `JMS` by the broker), `matchedBy: llm`,
  `endpoint` and `target` when the answer cites exactly one endpoint or listener of the candidate,
  the link left open's `profile` and `exceptProfiles`, `resolves` (the stable ID of the link left
  open), `rationale`, `model` (`provider/model`), `facts` (the link left open, its facts and the
  cited ones) and a stable `link` from the predicate, `resolves` and the target. The vocabulary
  adds `resolves`, `rationale` and `model` to both relationship types. The `unresolved-link` fact
  stays, so the question and its answer are both visible.
- **Confidence** is that of the link left open times 0.5 for `high`, 0.35 for `medium` and 0.2 for
  `low`: always below the lowest static match (0.6, a call to this machine by path alone), so a
  picked link reads as weaker than any static one.
- **The reasoning model.** Resolve keeps the design's reasoning tier (ADR 0008); answers are short
  (at most 600 output tokens).
- **Cached by what the model sees.** The cache key is the SHA-256 of the prompt version, provider,
  model, system prompt and user turn, so a new commit that changes nothing the prompt shows makes
  no call (unlike summaries, whose keys hold fact IDs). Answers, an unknown included, are kept in
  the `link_resolutions` table (`JdbcResolutionStore`; `InMemoryResolutionStore` for tests) with
  the target unit, confidence, cited references, rationale, prompt version, provider and model.
  The references stand for the same facts whenever the key is the same. `ResolvePrompt.VERSION`
  is raised whenever the prompt changes, which `ResolvePromptTest` enforces.
- **It never fails the run.** A call refused by a budget or a capability check, or failing in the
  provider after retries, leaves nothing cached and a warning; the others go on.
- **A failed attempt keeps the earlier pick.** The resolver's facts are recorded for the whole
  snapshot, so a link it does not derive again stops holding. When a call is refused, fails or is
  rejected twice, the last answer stored for the same stable link ID (`ResolutionStore.latest`),
  whatever its key, is used if it picked a unit that is still a candidate: the link keeps holding
  with that answer's rationale, model and confidence, citing its facts only if it was given for
  the same prompt. With no such answer the link stays open. Calls run in parallel on virtual threads, within the client's per-model limits (ADR 0021),
  and every call is logged with the purpose `resolve <kind> link <call> of <unit>`. Run budgets
  from configuration are still #42; the resolver uses the client and budgets it is given.
- **project-0** (#198). Three links project-0 leaves open have candidates, with builds and
  without, each with an answer its case list gives: fulfilment's call to a host that names no unit
  (HTTP-6) and its call to an unresolvable URL (HTTP-7), both on a path payments and storefront
  serve, and its RabbitMQ send with a routing key known only at run time to an exchange both bind
  to (MSG-5). So a real run makes three Resolve calls there. `Project0LinksIT` checks, with a fake
  model that gives those answers, that each is asked about once, with both candidates in unit-ID
  order and the evidence that decides it, and that each answer is recorded as the link; HTTP-5,
  without candidates, is not asked about. Elsewhere a call costs about 1,400
  characters of system prompt plus about 1,500 for a call with two candidates of one endpoint
  each (`LinkResolverTest`): some 750 input tokens and under 100 output tokens, twice that when
  an answer is rejected once.

## Not in this issue

Tools to query the graph and read files, with tool use in `LlmRequest` (#169, now ADR 0081), and the questions
queue for links that stay open (#170, Phase 3). Database links are not asked about: they have no
candidates.

## Amendment (#573): a link rule that runs every time

The linker's steps became link rules (ADR 0095). `LinkResolver` is a rule whose reads are the
extracted facts, the values', units' and cross-repo links' facts, and whose writes are `CALLS_HTTP`
and `SENDS_MESSAGES_TO`. It is not pure: its answers and model are outside the facts, so it runs on
every analysis, asking the LLM only about links it has no answer for. The README claim checks read
the link predicates it writes, so they now run after it rather than before.

## Amendment (#576): links named by their claim IDs

A pick's `resolves` names the link left open by its claim ID (its fact ID only before it is stored,
as in tests), and the pick no longer carries `link`: its own claim ID is its stable ID. The answers
`ResolutionStore.latest` keeps under the old 16-digit IDs are not found again under the claim IDs;
`find` by the prompt's cache key, which names no link ID, still reuses them. An open link's
candidates and reason are key attributes, so when they change it is a new claim, and `latest` no
longer finds the answer given to the old one; the fallback to the last answer for a link whose
prompt changed then does not apply.
