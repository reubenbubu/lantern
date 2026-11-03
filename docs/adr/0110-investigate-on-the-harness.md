# 0110: Investigate on the harness: leads, units and the system

Status: accepted (#269); supersedes ADR 0084 (PR #328, never merged); amended for #529 (leads asked one a request)

## Context

ADR 0070 lets the LLM find what static analysis misses, only with citations Lantern verifies and
marked as found by the LLM. ADR 0084 (#328) built one open-ended conversation per unit; on
project-4 it found one of three named risks before its budget ran out. The knowledge-build epic
(`docs/epics/001-knowledge-build.md`, section 11.2) replaces it with three modes on the harness
(ADR 0100): Mode A checks the leads derivation stores (ADR 0098), Mode B explores each unit, Mode C
the whole system. The epic fixes the prompts, the submit schema, the checks, what is stored and
the caps. This records how #269 lands them and what the epic left open. The agent decided these
under the owner's standing go-ahead for milestone 1F.

## Decisions

- **Where it runs.** `Investigator` (`lantern-link`, next to Resolve, whose graph tools it uses) runs
  in the analysis after Summarize, so a unit's prompt can show its summaries, and before Describe,
  so what it finds is stored before anything is described or composed. It reads the facts that
  hold in the snapshot and are made before it in a run: the static facts, the summaries and the
  resolved links. What Describe, the wordings and Investigate itself stored in an earlier run is
  left out, so a rerun over the same commits asks nothing again. A lead asked again alone is
  planned as an item of its own, so the run's record counts every item once. What each lead kind asks the model to
  judge comes from the catalog (`leads.json`, `LeadRules.judges()`), passed in by the app, since
  `lantern-derive` depends on `lantern-link`. It never fails the run.
- **Mode A.** Each `lead-trigger` fact is an item: its ID and kind, why it fired, what to judge,
  its units and code, its facts as fact lines without references, and the code those facts cite as
  numbered `<code repo path lines>` excerpts (3 lines before each cited range, 12 after, overlaps
  merged, at most 40 lines each and 120 an item). A unit's leads go four to a conversation (a lead
  with no unit goes with the system's), block 3 the system map (the units by ID and the links
  static analysis found between them), block 4 the unit's facts. Tools: `read_file`,
  `find_in_code`, `facts_about`; caps: 6 tool turns, 60,000 input tokens in fresh-token
  equivalents (the epic sets none for Mode A; Resolve's 30,000 for 6 turns, doubled for the code
  excerpts) and 4,000 output tokens a turn.
- **Unanswered leads.** A lead the conversation never answered, or answered `omitted`, is asked
  again alone, once: its item gets one more fingerprint input, so it is a new item the cache tells
  apart. Unanswered again, its verdict is stored as `unclear` with the reason. A lead whose answer
  was rejected twice is `unclear` too, with that reason, and so is a lead a budget or the provider
  left unasked, whose unit is still explored. A refuted lead is stored refuted. An entry may name
  its lead by its item ID or by the lead's ID. What a lead asks the model to judge is part of its
  fingerprint, so a changed judge text asks it anew.
- **Mode B and C.** One conversation per unit, after every Mode A conversation, block 4 the unit's
  facts and links, its open links, the summaries of its modules and repositories, and its checked
  leads as `<answers>`; then one for the system, block 4 every unit's accepted risks so far
  (its confirmed leads and the risks exploring it found), one line each.
  Every read tool, with the epic's two new ones: `neighbours(entity, depth)`, the facts that link
  an entity to others either way, to a depth of two, and `summary_of(entity)`, its accepted
  summary (`GraphTools.exploreTools`; Resolve keeps its four). Caps 16 tool turns, 120,000 input
  tokens, 4,000 output tokens a turn.
- **The submit schema is the epic's**, a list of entries, which the role turns into the harness's
  envelope (`PacketRole.envelope`): in Mode A each entry under the item its `lead` names; in Modes
  B and C every entry a proposal `P1`, `P2`, ... of the one item, numbered across submissions. The
  submit tool's description says so (`PacketRole.submitDescription`). In Modes B and C an entry is
  kept in part (`PacketRole.partial`): a submission's proposals that pass are accepted at once, the
  others listed with their reasons, and a later submission adds to what was accepted, as the
  epic's instructions promise. A rejection of any field of a proposal rejects the proposal. A
  conversation makes at most 10 proposals: the eleventh and later are sent back; each key is kept
  once.
- **Checks.** The standard pipeline: the schema; the Ranges check (each range in a file of the
  snapshot, within its lines, at most 40 lines); the Coverage check (every name in the cited lines,
  every range holding a name), on the lines as the tools show them, secrets masked; the Names check
  on the title, finding, consequence and description; the Secrets check. Then the role's own,
  `Proposals`: a Mode A entry has a verdict and is a risk; a confirmed one, and every Mode B or C
  risk, has its title, finding, consequence, impact and confidence; a refuted one cites; a risk of
  the system names its units; units are deployable units; names are at least three characters with a letter and not a keyword or word
  that holds on any line; a connection runs from a unit (by default the conversation's) to another
  model element (a unit, or an end a static or resolved link has) or a new `external-system:` or
  `database:`, with its interaction and description, between ends no static or resolved link
  joins.
- **Stored**, all of origin `llm/investigate` at the prompt's version, each with its cited ranges as
  evidence (the file's blob included) and a confidence of 0.6, 0.45 or 0.3 by the model's own:
  - a verdict, `(lead, lead-verdict, confirmed|refuted|unclear)`, with lineage to the lead, so it
    ends with it, keeping its justification (`finding`) and citations;
  - a confirmed lead, or a Mode B or C risk, `(first unit, investigated-risk, key)`, the key the
    lead's claim ID or the risk's names sorted, its title, finding, consequence, impact, units,
    names, citations and mode descriptive; a risk naming no unit is its lead's units', else its
    conversation's unit's, else the system's;
  - a risk that restates a finding of a fixed check, `(finding, finding-wording, key)` instead,
    with lineage to the finding and the lead. It restates one when it concerns the finding's unit
    (its `about`; a finding about nothing, a repository of the unit holding its evidence) and either
    a lead's risk is of the finding kind's lead kind (`version-skew` and `shared-type-skew`,
    `shared-jwt-secret` and `plaintext-secret`, `unrouted-endpoint` and `contract-mismatch`) and
    the finding rests on what the lead shows (a fact of the lead, or a file one names) or a cited
    range overlaps its evidence, or a risk found exploring cites a range that overlaps the
    finding's evidence. A lead's risk never restates a finding of another kind, though their lines
    overlap: on project-4 a before-commit lead in a package of a package cycle is its own risk. The
    findings are walked by ID, and each is worded once;
  - a connection, `(unit, INVESTIGATED_CONNECTION, end)`, keyed by its interaction;
  - a unit or the system a budget refused, the provider failed or a model without tool use left
    out, `(subject, not-investigated, investigate)` with the reason.
  The types are in the roles' vocabulary (`llm-types.json`).
- **The documents.** A new section, risks and findings (`SectionKind.FINDINGS`), is a table of the
  risks in scope (impact, finding, consequence, confidence, evidence as `repo:path:from-to`), most
  serious first, then a note naming the units not investigated, or a note that none was found. The
  Explorer has it after the main flows, both C4 reports after them too, where it is a
  documentation page (ADR 0086). A risk Investigate found opens with "Found by the LLM:",
  emphasized, and its row's facts are all of origin `llm`, so the C4 report's LLM badge marks it; a
  finding it worded opens with "Found by a fixed check, worded by the LLM:" and its row cites the
  finding too, so it is not badged. The Executive Brief lists the risks among its risk callouts,
  marked the same way. A connection is a line labelled "Found by the LLM:" and its description in
  every diagram that draws its ends; an element only its connections name is described by them,
  marked the same way. "LLM" joins the bundled glossary. The findings page of #273 adds the fixed
  checks, decisions and unknowns to this section.
- **Under a budget** the three modes share the `investigate` group (ADR 0100), each planned on its
  items before it asks; a unit refused is not investigated and says so.
- **The fake model** submits nothing, so in fake runs every lead is asked twice and stored unclear,
  and no risk or connection is found.

## Evidence

- `InvestigatorTest` (`lantern-link`): a lead's item shows what to judge, its facts and code, with
  Mode A's tools; a confirmed lead is a verdict and a risk cited to the verified lines; a
  mis-cited verdict is sent back and a corrected one accepted; one mis-cited twice is dropped and
  the lead unclear; an unanswered lead is asked again alone once, then unclear; a refuted lead
  gives no risk; exploring a unit keeps the proposals that hold and sends back the rest; a
  connection a link already joins is sent back; a risk that restates a static finding is its
  wording; the system sees its units' risks; a rerun over unchanged facts and code makes no call;
  a model without tool use is not asked and every unit is not investigated.
  `InvestigatePromptTest` holds the prompts to the epic and guards the version. `PartialSubmitTest`
  (`lantern-harness`) the partial entries.
- `InvestigatedFindingsTest` (`lantern-compose`): the Brief's callout, the Explorer's and the
  system report's table, a worded finding, the units not investigated, a found connection's line
  and its new element, each marked.
- `Project4InvestigateIT` (`lantern-app`, with `-Dlantern.test.project4`): the whole pipeline on
  project-4 at its pins with a scripted model that confirms every lead of the two kinds #269 names,
  citing the code each shows, and leaves the rest unclear. Of project-4's 52 leads it confirms 24,
  among them the header-trust lead of the file service and the before-commit leads; they give 20
  risks, and no wording, since no fixed check finds either kind; every cited range of a stored risk holds its name
  in git at the pins; the system report's findings page badges every risk row; a Brief and an Explorer mark them. It shows that a verdict is checked, stored and marked on real code, not what
  a model finds. It replaces #328's hand-written recordings, which were keyed on the whole prompt
  and so missed at every prompt change.

## Consequences

- A run makes at least two Investigate calls per conversation (the submit turn and the one that
  ends it), as Resolve does (ADR 0105); a rerun over unchanged facts and code makes none.
- What a real model finds, its cost on project-4 against the epic's estimate, and Investigate's
  scoring row (risk recall and precision against the references, epic section 13) come from the
  approved recordings (#529).
- Not covered: proposals of people, components and unconnected elements (#329); the fixed checks,
  decisions and unknowns on the findings page (#273).

## Amendment for #529: one lead a request

Measured on DeepSeek V4.1 Flash on project-1 to project-4 (2026-11-02); how, the noise between two runs and what the recordings share are in ADR 0112, amendment for #529. Per-item scores 0 to 100; the bound is the one-sided 90% bootstrap lower bound of the mean difference against size 1, trial a.

| Size | Mean difference | Lower bound | Changed verdicts (limit 21%) | Worst set | Leads' cost, four sets | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1, trial b | −1.3 | −5.2 | 16% of 103 | −9.1 | $0.31 | the noise |
| 5 | −5.2 | −9.1 | 18% | −13.6 | $0.16 | fails the bound |
| 10 | −1.3 | −3.9 | 19% | −4.5 | $0.17 | not considered |
| 20 | +2.6 | 0.0 | 17% | 0.0 | $0.11 (two sets cached) | not considered |

Size 1 trial a cost $0.32. 77 reference risks and decoys were scored.

- **Kept: 1 lead a request** (`lantern.harness.items-per-request.investigate-leads=1`). Size 5 fails the −7 bound; sizes pass in order, so 10 and 20, which pass, are not considered. A size truly equal to 1 fails that bound about one time in seven with these 77 items (ADR 0112), so size 5's failure may be noise. A size-1 run of leads costs about twice a size-5 run.
- To be measured again on Claude Haiku 5.5 and on more scored leads.

## Amendment (#760): limits in the role's contract

This ADR's word limits and margins now live in the role's response contract, `prompts/<role>.contract.json`, which the checks read (ADR 0118). Change them there.

## Baseline (#756)

The 1H baseline (#756): Haiku 5.5 and Sonnet 5.5 through OpenRouter, Anthropic only, on project-1, project-2, project-3, project-4 and project-8. This role's reference is in `~/workspace/lantern-test-drops/1H/baseline/` (cold run, completion, warm reruns; README there). The minimums re-check is in ADR 0118.
