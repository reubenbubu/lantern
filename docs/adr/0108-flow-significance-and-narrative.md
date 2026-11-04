# 0108: Flow significance and narrative on the harness

Status: accepted (#272)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.5) fixes two roles for
the main flows: significance, which judges which of a scope's traced flows are worth drawing and
in what order, and narrative, which writes the title, walkthrough and pre- and post-conditions of
each flow drawn. Before #272 the composer drew the highest ranked flows (ADR 0047) and a
free-form Compose prompt narrated each (ADR 0058). This records how #272 lands the two roles and
what the epic left open. The agent decided these under the owner's standing go-ahead for
milestone 1F.

## Decisions

- **Where they run.** Inside Describe, after the units and elements are worded and before the
  system's purpose: the significance item shows the units' responsibilities as `[A..]`, the
  narrative item the units' responsibilities and the interactions' intents. Flows were narrated
  alongside the units before; they now wait for them.
- **Scopes.** One significance item per scope whose documents draw flows: the system, from the
  snapshot's facts, and each repository with a deployable unit, from the facts its documents are
  composed from (as the composer traces them, #631). Its subject and locality are the scope's key,
  `system` or the repository's name. A scope with no candidate flow is not asked about.
- **Candidates.** A scope's candidates are the stored flows that leave their unit (ADR 0097) and
  touch the scope, one for each `flow-shape`: the flow standing for a shape is the one whose ID
  sorts first, as `SAME_SHAPE_AS` points to it, and the item lists them by what starts them, then
  their unit and ID, so a new commit that changes nothing the item shows asks nothing again. Each is shown as `[W..]` with what starts it,
  its entry fact, its containers in order, its steps with the facts behind each, and where it
  hands work on asynchronously; then the responsibility of each unit the flows cross.
- **Two passes.** A scope of more than 60 candidates is judged first unit by unit (subject
  `<scope>/<unit>`, "the flows that start in" the unit, listed in the rank order of ADR 0047),
  each keeping its significant flows in the model's order, at most 15, or its 15 highest ranked if
  unjudged; then once over what was kept. The second pass is planned with the first, ahead of the
  smaller scopes, as an item expected to show the 15 highest ranked of each unit and resting on
  the first pass's items.
- **Checks.** The shared pipeline on each reason (prose of at most 20 words), and a role check:
  every `[W..]` answered with a boolean `significant`; `order` holding exactly the significant
  flows; and, on the first attempt only, at least one significant flow when some flow crosses a
  unit boundary, else the epic's repair text. Its rejections are of the whole item, so the repair
  turn answers it again; a second answer of none significant is accepted. A reason the shared
  checks reject is rejected as `flows.W<n>.reason`, so the repair turn may correct or withdraw it
  and the flow stays judged; the answer is checked against the epic's schema with the reason
  optional for this (the prompt shows the epic's).
- **Stored answer.** Each flow and the order by its `W..` reference, so a cached answer holds for
  the same item whatever IDs its flows have, and citations as the item numbers them.
- **Claims.** `(flow, flow-significance, "<scope>")` for every flow judged, with `significant`,
  `category`, `rank` (of a significant flow, from 1), the reason's `text` and `cites`, `basis`
  (inferred), `model` and the flow-shape's `content` hash it holds for; and
  `(flow, flow-narrative, "c4")` with the texts written, `cites`, `basis`, `model` and `content`.
  Both are new vocabulary types of the roles (`llm-types.json`), all their attributes
  descriptive. Like the other texts the roles wrote, they are left out of the roles' inputs
  (`Describer.withoutTexts`).
- **What documents draw.** A scope the model judged draws its significant flows that are in
  scope, in the model's order, with no cap. A scope that judged none says so ("the model judged
  none of them worth drawing"). A scope whose item was not answered (omitted, dropped, failed or
  not asked) draws the highest ranked, at most 7, as before, under a note that says they were
  chosen by rank, not judged.
- **Narrative items.** One per flow drawn in any scope, each flow once, keyed as before by how it
  is traced (`Wordings.flowKey`), its locality its unit. It shows what starts the flow and its
  entry fact, its steps with the facts behind each, the responsibilities of the units it crosses
  and the intent of each of its interactions Describe worded, as `[A..]`; a citation of an `[A..]`
  stands for the facts that answer cites. Word limits: title 6, walkthrough 80, conditions 25
  each, by the Form check. A narrative not answered leaves the flow's own prose, as before; a
  dropped one counts the flow as rejected, one not asked or failed as skipped.
- **Roles and tiers.** Significance is `Role.FLOW_SIGNIFICANCE` on the reasoning tier, run
  interactively, in the budget group `flows-and-purpose`, where thinking is on as the reasoning
  models' settings configure it. Narrative stays `Role.COMPOSE`, on the high-volume tier, in bulk.
  Under a budget the purpose keeps its first claim on the group's share (#659), and the
  significance items are planned before the narratives.
- **The old flow prompt** (ADR 0058) is no longer sent; its user text only names a flow's key.
- **The fake model** judges no flow, so every scope draws its highest ranked and says so, and
  titles each flow `{{placeholder: scenario title}}`, citing the item's first fact.

## Consequences

- A run makes one significance call per scope with flows (and a first pass per unit for a large
  one), and one narrative call per flow drawn; cached per item, so a rerun over unchanged facts
  makes none. Each repository's scope shows the system's flows that touch it, so a fact change
  re-asks every scope that shows the flows it changes.
- Without a real model, documents still draw the ranked flows, now with the note.
- The recordings that accept the judgement on the test sets need a real model; they come with
  #529.
- Supersedes ADR 0058 for how flows are narrated, and ADR 0047 for which flows a judged scope
  draws; the ranking of ADR 0047 stays the fallback and the order of the first pass.

## Amendment for #530: documents read the stored judgements

Documents read the stored claims instead of what the run kept in memory (knowledge-build epic,
section 12). A scope draws the flows whose `flow-significance` claim for it is significant, in
rank order; a scope with no such claim falls back to the ranking, as before. A flow shows the
narrative stored for it, one per flow as section 11.5 says, while the narrative's `content` is the
content hash of the flow's steps as they are; it is no longer looked up by the prompt that asked
for it. The narrative stores what each of its texts cites in `fieldCites`, so each text keeps its
own citations. Where one flow holds several narratives, the most confident is shown, the first by
fact ID on a tie.

## Amendment (#730): room for a reason a flow

A scope's significance answer had 120 tokens of output a flow, plus 500. On the 1F drop's DeepSeek runs, the whole system's answer on project-2 (51 flows) and project-4 (41 flows) used its whole limit and was cut off, so those documents drew by rank. A reason with its citations runs past 120 tokens: both answers were still writing reasons when the limit stopped them, and how much of the limit thinking took is not reported, so the exact length a reason needs is not measured. The room is now 250 tokens a flow plus 1,000 for the order, at least 1,500, with the model's thinking headroom on top. The prompt version goes from 5 to 6, so the judgements dropped for a cut-off answer are asked again rather than repeated from the cache.

## Amendment (#760): limits in the role's contract

This ADR's word limits and margins now live in the role's response contract, `prompts/<role>.contract.json`, which the checks read (ADR 0118). Change them there.

## Amendment (#767): a flow page is titled by its scenario

A flow page's title is its scenario alone; its Overview table's Trigger names the request,
message or schedule, where it runs and its handler (ADR 0122).

## Amendment (#744): the order is repaired, not sent back

An order that left out a significant flow or held one not marked significant failed the whole
answer; after one repair the scope fell back to rank (4 of 7 scopes on project-4 and 4 of 10 on
project-8 in the 1F cold run). The order is now made to hold exactly the flows marked
significant before the checks: an entry not marked significant is removed, and a significant flow
it leaves out is added after the others, by the scope's rank, whatever order the item lists the
flows in. The
fix is logged; no repair turn is spent on it. A flow left unanswered still goes back to the model.

## Amendment (#743): the fewest flows, none inside another, judged on today's steps

Once #730 let the significance answer land, the model drew 14 flows on project-2 and 19 on
project-4, against references of 6. Its list also kept flows that are part of another drawn flow.
Now:

- **Prompt (version 8).** It says that almost every listed flow leaves its service, so that alone
  does not make a flow significant. It asks for the fewest flows that together explain what the
  system is for and how it works: one per scenario, not one per endpoint, and none that another
  flow it marks already contains or repeats with other data. "There is no target number" is gone.
- **Containment.** The model's list skips any flow whose entry point an earlier chosen flow
  reaches, as the rank path does (`ScopeFlows.chosen`). The flow is drawn within the one that
  reaches it.
- **Stale judgements.** A significance judgement stores the content hash of the flow's steps it
  saw. It is read only while that hash matches the flow's steps now, as a narrative's is. A
  judgement that names no steps, or whose flow's steps are not known, is read as before. A scope
  left with no judgement it can read is not judged, so the rank decides.

The five sets' flow-selection rows (#746), judged and on the rank fallback, before and after,
come from the 1H test drop's real runs on Haiku 5.5 and Sonnet 5.5. Fake runs judge no flow
significant, so they show only the rank fallback, and this change leaves it unchanged.
