# 0103: Describe on the harness, and the conventions every role shares

Status: accepted (#528), amends ADR 0083 (frontends), supersedes the unit and element prompts of ADRs 0050, 0056 and 0069 and
the wording of their answers' checks in ADRs 0065, 0066 and 0068; amends ADR 0100 (repair turn) and
ADR 0101 (tiers); amended for #529 (elements asked ten a request)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, sections 11.0 and 11.4) fixes how
every role renders its inputs and answers, and what Describe asks: a responsibility for every
unit, component, data store, destination, external system and person, and an intent for every
interaction, stored as facts, not only what one report draws. The old Describer asked one prompt
per unit and per data store or destination (ADRs 0050, 0056, 0069) with its own check, and kept
what it got only for the report of the run. This records how #528 lands the epic's shared
conventions and Describe on them, and what the epic left open. The agent decided these under the
owner's standing go-ahead for milestone 1F.

## Decisions

- **Shared rendering, in `lantern-harness` (`render`).**
  - A fact line is subject, predicate, object, the attributes as compact JSON with sorted keys,
    and `(repo:path)`. It leaves out the attributes that name other facts by ID, link
    candidates and line numbers, so an edit elsewhere in a file does not change it. An object that
    is a claim ID can be shown by a readable key the caller gives. Facts are sorted by their line.
  - Code is shown as `<code ref="E1" repo= path= lines="a-b">` with numbered lines.
  - Each item numbers its own references (`F`, `E`, `S`, `A` cited; `C`, `R` keys) and renders
    them as tokens. Laying out a request numbers the cited letters of its items one after
    another, so an item's text, and its fingerprint, do not depend on which items share its
    request, and a reference never means two things in one request. Keys stay per item, since
    each item's answer holds its own. Each item's answer is checked against its own references
    only, so it cannot cite what another item of its request shows. A role cites only the
    letters its task names: units `F`, `E`, `S`; elements `F`, `A`.
- **The answer envelope.** Items answer `{"items": {"I1": {...}}}`, with `{"omitted": true}` for an
  item the model leaves out. Text fields are `{text, cites}`. `basis` is computed, never asked: a
  text is `evidenced` when it cites what people wrote (a README claim, code with Javadoc, or a
  summary whose purpose is evidenced), otherwise `inferred`.
- **A field-level repair (amends ADR 0100).** The repair turn lists each rejected field. The
  answer kept is the first answer without its rejected fields, with the correction laid over
  it: a corrected field replaces the rejected one, a field rejected again or withdrawn is left out,
  and the accepted fields stay. An item with no field left is dropped. A schema violation names
  the text it breaks (`components.C3` for a bad `components.C3.text`; a whole list for a
  violation inside one; the whole item for one at its top), so it too is repaired field by field.
- **LLM facts.** An accepted text is stored as a fact of origin `llm` (role name, prompt version),
  with the attributes `text`, `cites` (the IDs of the facts it cites), `basis` and `model`, the
  cited facts' places as cited evidence, the cited claims as its lineage, and confidence 0.6. A
  responsibility is `(element, responsibility, "c4")`; a component's subject is the component
  entity. An intent is `(source, intent, <the interaction's claim ID>)`, or the interaction
  fact's ID when it has no claim. A person's use of a frontend rests on the person's one fact,
  shared by every frontend they use, so its object adds the frontend (`<claim ID>/<frontend>`), and
  the report keeps those lines' labels. The vocabulary types are `responsibility` and `intent`. Each
  run's facts replace the role's earlier ones (`recordDerived`, by origin), so a new prompt version
  leaves no stale texts.
- **The bulk tier.** `bulk` joins `high-volume` and `reasoning`. Left unset, it uses the
  high-volume model. The injection screen moves to it (ADR 0101 said it would). Summarize's types
  move to it with #665.
- **Describe, as the epic gives it.** Two roles, both bulk and in the `describe` share:
  `describe-unit` (`Role.INFER_INTENT`, reasoning tier, prompt version 5, one unit a request) and
  `describe-element` (`Role.DESCRIBE_ELEMENTS`, a new role on the high-volume tier, prompt version
  4, at most 10 elements of one kind a request: the item's locality is its type, so the packer
  never mixes kinds). Block 1 is the common prompt, block 2 the epic's task and schema word for
  word. The shared pipeline checks every answer; Form uses the epic's rules (word limits, banned
  openings, protocol words, restating the name).
- **What the epic left open.**
  - *Order.* Units are asked first, while the flows are asked alongside them. Elements are asked
    after, showing the responsibilities of the units that use them as `[A..]` answers they may
    cite; citing one cites the facts it rests on.
  - *Large units.* A unit with more than 25 components or 60 interactions becomes several items
    over the same unit context: the unit with its first 25 components, its other components in
    groups of 25, then its interactions in groups of 60. Parts after the first say "say nothing
    about the unit itself", and only the first is asked about the unit.
  - *Labels.* An intent phrase labels a line, so a trailing full stop is dropped.
  - *Counts.* An item the model leaves out counts as asked; one left out in an earlier run counts
    as reused.
  - *The report.* Documents still take the run's texts from Describe directly, as before; the
    stored facts are what later roles and documents read. A frontend with a responsibility
    sentence is described by it, and by what its manifest says only without one (amends ADR
    0083). A person keeps the description of what the code shows of them (ADR 0074).
  - *Its own output is not its input.* A run leaves out the `responsibility` and `intent` facts
    of origin `llm` from the facts it reads for Describe and the system's documents, so a rerun
    does not show the model its earlier words, re-ask every item they are about, or compose a
    different document from the same snapshot.
  - *Room to answer.* A unit request's output cap counts a sentence for the unit and each component
    and a phrase for each interaction, so the largest item is not cut off.
  - *Code.* Every component shows the first 4,000 characters of its main type's code, as the
    epic says, whatever the others show. README claims are sorted as fact lines.
  - *What "every element and interaction" covers.* The container model's: every deployable unit
    and frontend, its components, every data store, destination and external system a unit links
    to, every person; and each unit's links to other containers, the lines between its components
    and the people who use it. A profile-only link that replaces a default one under its profile
    is a variant of that interaction, not another; a route between an exchange and a queue is not
    a unit's interaction; a data store no unit links to is not in the container model.
  - *Unknown.* A unit whose facts show nothing to word from is not asked about and is counted
    unknown, with every unit, component, element and interaction the model left out.
  - *Summaries.* A summary an item shows is a fingerprint input by the hash of its text, as
    Summarize keys a part's summary, so a summary written again to the same text re-asks nothing.
- **The fake model** (`fake-llm`) answers Describe in the epic's form: a placeholder
  responsibility for every unit, component and element and a placeholder intent for every
  interaction, each citing the item's first fact, and `{"omitted": true}` for an item with none.
- **The judge's tier (amends ADR 0101).** With the epic's defaults, element texts are written on the
  high-volume tier, the judge's own. The judge still checks them; whether that sample stays is for
  the judge's rules (#668).
- **What is retired.** The unit and element prompts of `WordingPrompt`, their versions and checks;
  flows and the system's purpose keep theirs until #272 and #274.

## Consequences

- Every element and interaction of the model gets a stored responsibility or intent, or is listed
  as unknown, whatever a report draws.
- Moving a commit, or a fact ID, re-asks nothing; reordering the items of a request re-asks nothing.
- The restating rule rejects a sentence that uses the name's words early, which for a one-word name
  such as a topic `orders` means most sentences that name what it carries. The recordings of #529
  show how often the repair turn has to correct it.
- Packet sizes are starting sizes, calibrated in #529.

## Amendment for #529: ten elements a request

Measured on DeepSeek V4.1 Flash on project-1 to project-4 (2026-11-02); how, the noise between two runs and what the recordings share are in ADR 0112, amendment for #529. Per-item scores 0 to 100; the bound is the one-sided 90% bootstrap lower bound of the mean difference against size 1, trial a.

| Size | Mean difference | Lower bound | Changed (limit 5%) | Worst set | Elements' cost, four sets | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 1, trial b | 0.0 | 0.0 | 0% of 216 (one element is not in trial b) | 0.0 | $0.008 | the noise |
| 5 | 0.0 | 0.0 | 0% | 0.0 | $0.005 | passes |
| 10 | 0.0 | 0.0 | 0% | 0.0 | $0.004 | passes |
| 20 | 0.0 | 0.0 | 0% | 0.0 | $0.002 (two sets cached) | passes, capped |

Size 1 trial a cost $0.009. 217 elements were scored.

- **Kept: 10 elements a request** (`lantern.harness.items-per-request.describe-element=10`). Every size passes, but the score only says whether an element was described, not how well. Describe writes prose, and the epic caps such a role below 20 unless the middle positions of a size-20 request are shown no worse than its ends; this score cannot show that, so the size stays at 10. The two size-1 runs never differed, so a size truly equal to 1 is never rejected by this score: it has no power, which is why it needs the wording judge's scores.
- To be measured again with the wording judge's scores once they are validated (#668).

## Amendment (#760): limits in the role's contract

This ADR's word limits and margins now live in the role's response contract, `prompts/<role>.contract.json`, which the checks read (ADR 0118). Change them there.

## Amendment (#739): every stored intent is drawn

The 1F and 1G review found intents Describe stored that never reached the page. Now:

- **People's lines.** A person's line to a frontend shows the intent Describe stored for that use
  (`<claim ID>/<frontend>`), and the system context's line from the person shows the most common of
  the person's intents for the frontends they use. This replaces the choice above that kept those
  lines' labels; "Uses" stays only where Describe wrote none.
- **Component lines.** A line between two components shows the intent stored for its
  `COMPONENT_LINK`.
- **Lines to stores.** A component's line to its unit's store shows the unit's intent for that
  store, not "Reads and writes".
- **Merged lines.** A line that merges several links shows the most common intent among those that
  have one (the first alphabetically on a tie), once any has one. Before, one link without an
  intent sent the whole line back to its static label.

The scorecard's drawn coverage (#742) now also counts the generic labels on lines whose own link
has a stored intent. The count should be 0.

## Amendment (#738): every interaction a view can draw

"Every interaction" above meant seven fixed kinds of link. It now means every interaction a view
can draw, the set the scorecard's model coverage counts (#742). This replaces "a route between an
exchange and a queue is not a unit's interaction". A unit's or frontend's item asks, after its
fixed links, about:

- **Every other line of the container diagrams** (`Elements.lines()`), other than a person's: what
  Investigate found, its other interactions drawn by what they descend from, the unknown caller's
  lines, and routes between exchanges and queues. A line belongs to the unit its link starts
  from. Failing that, it belongs to the unit or frontend at either end. A route between two
  destinations belongs to the first unit, in ID order, with a line to either end.
- **Every other interaction with another unit or frontend**, which the flows draw: chiefly messages
  one unit sends that reach a listener of another (`SENDS_MESSAGES_TO`), shown with the queue or
  topic they go through.

A component's use of a store stays out: it takes the unit's intent for that store (#739). A
project-0 test (`Project0EndToEndIT`) checks that the interactions Describe asks about
(`Describer.interactionsAsked`) are exactly those a view of the system report can draw, counted
the way the scorecard counts them.

## Amendment (#741): one list of the interactions a unit holds

Which interactions each unit holds, as the amendment for #738 says, is now a model query in
`lantern-derive` (`UnitInteractions`). Describe asks about it, and the unknowns page lists from it
those without a stored intent (ADR 0046, amendment for #741), so the two cannot drift apart.
Describe's prompts are unchanged by the move. `Describer.LINKS` is `UnitInteractions.LINKS`.
