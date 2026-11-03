# 0116. The LLM roles leave the composer

Status: accepted (#698)

## Context

Describe, the flow significance and narrative roles, Purpose and key fact, and Name groups lived
in `lantern-compose`. They built the composer's own diagram code (`C4Views`, `SequenceFlows`, the
`Assembler`) to decide what to ask about, and the composer module depended on `lantern-summarize`,
the harness and the LLM client. The knowledge-build epic (sections 5 and 12) and `docs/design.md`
(Architecture overview, stage order; ADR 0093) put the roles before composition: the roles store
their texts, and the composer only reads the store. #717 and #718 moved the queries the roles
share with the composer (container lines, elements and names; flow candidates) into
`lantern-derive` (ADR 0115), so the roles no longer need the composer.

## Decisions

- **The roles live in `lantern-summarize`, package `io.lantern.summarize.describe`**, beside
  Summarize: `Describer`, `DescribeRole`, `DescribeItems`, `FlowJudgements`, `PurposeRole`,
  `NameGroups`, `WordingPrompt` and `Wordings`. `lantern-summarize` depends on `lantern-derive`
  for the model queries; nothing in it depends on the composer.
- **What they ask about comes from the model.** Elements and lines from `Elements`, names from
  `ElementNames` (named as the documents name them, with the system named "The system"), flow
  candidates, rank, choice and the flows to narrate from `ScopeFlows`. The narrated flows and
  their wording keys moved from `SequenceFlows` into `FlowJudgements`; a flow's content hash is
  `FlowModel.content`.
- **The composer depends on no LLM, harness or role code.** `lantern-compose` keeps
  `lantern-summarize` only as a test dependency, so its own code cannot reach a role, the harness
  or an LLM client; the architecture rule `COMPOSER_CALLS_NO_LLM` checks it (design principle 10).
  What the composer read from the harness and summarize now comes from the model:
  `ArchitectureModel.Text.evidenced()` and `Summary.evidenced()` in place of the harness's basis,
  `ArchitectureModel.SYSTEM` and `ArchitectureModel.REPO_ENTITY_TYPE` (which `SummaryLevel` now
  reads). A flow's wording reaches the composer as `Assembler.FlowWording`, read from the stored
  narrative.
- **The describe-then-compose tests stay with the composer.** `DescriberTest` runs the roles on a
  fake model and checks the documents composed from what they stored, so it needs both modules; it
  stays in the composer's tests, and the role members it uses are public. The role tests that need
  no composer (`NameGroupsTest`, `PurposeRoleTest`, `WordingPromptTest`) moved with the roles,
  with a copy of the composer tests' fact builder.
- **Nothing changes in what the roles are asked or what documents show.**

## Consequences

- The composer can be built and tested without an LLM client on its class path.
- A role that needs something new to ask about adds a query to `lantern-derive`, where the
  composer can use it too, rather than reaching into the composer.
