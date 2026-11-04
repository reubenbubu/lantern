# 0107: Name groups on the harness

Status: accepted (#536)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.8) fixes the Name groups
role: one item per group the derive stage formed (ADR 0099), packed up to 8 groups of one kind, its
prompt and answer schema, its checks and what it stores. This records how #536 lands it and what
the epic left open. The agent decided these under the owner's standing go-ahead for milestone 1F.

## Decisions

- **Where it runs.** After Describe, in the analysis run, on the snapshot's facts with what
  Describe stored this run, so a member's responsibility and a member line's intent are earlier
  answers `[A..]`. The epic places it before Flows; flows are narrated inside Describe today and
  read no group name, so running after them changes nothing. Nothing reads the names yet: drawing
  at group level is #530.
- **Items and packets.** A group is an item, its subject the group's key, its locality its scope
  and kind, so a request holds the feature areas of one unit or the system's domains, at most 8.
  The role is interactive, in the budget group `name-groups`, on the high-volume tier
  (`Role.NAME_GROUPS`).
- **What an item shows.** The group's key and kind; its `group-of` fact and its `GROUPS` facts (the
  basis and how each member joined) as fact lines `[F..]`; each member by name with its
  responsibility as `[A..]`, or none where Describe gave it none (a component by its anchor
  class, as the component diagram names it); each line leaving the group
  `[R..]` (from `Groups.lines`, ADR 0099) with its other end and protocol, and under it each member
  line with its intent as `[A..]`; and up to 30 other groups of its kind and scope `[G..]`, those
  sharing the most lines with it first, then by key. The member lines are, for feature areas, the
  `COMPONENT_LINK` facts between the unit's components (their `kind` is the protocol), and for
  domains the units' links to other containers (`Describer.LINKS`, the predicate is the protocol).
- **Checks.** The shared pipeline, with the group's key's last part as the name a responsibility
  must not restate; the name is checked as prose of at most 4 words, the responsibility as a
  responsibility of 25, each line as an intent of 8 and a merge reason as prose of 20. A role check,
  `Group`, rejects a name equal to a member's name after the Form check's tokenising, a name holding
  a protocol word or a product or framework, and a merge whose `with` is not one of the item's
  `[G..]` or is the group itself. The products and frameworks are the values of the snapshot's
  `technology`, `broker-technology` and `database-technology` facts, split at `/` and commas and
  without versions ("Spring Boot 3.5.14 / Java 21" names Spring Boot and Java): the vocabulary
  lists no technology values of its own, the facts are where they are. A rejected merge is
  rejected as the field `merge`, so a repair turn that still names no group leaves the suggestion
  out and keeps the group's name. The members' names, the `[G..]` keys and
  the group's own key reach it through the item's check inputs.
- **Stored.** `(group, display-name, "c4")` and `(group, responsibility, "c4")` as the roles' text
  facts (text, cites, basis, model); `display-name` gains those attributes, all descriptive, since
  its other uses keep the name as the value. An intent per line leaving the group is
  `(group, intent, "<other end> <protocol>")`: a line between groups is computed, not stored, so it
  has no claim ID, and its ends and protocol name it. A merge suggestion is a finding
  `finding:group-merge/<a>/<b>`, the two keys sorted, with `about` and `target` the two groups, the
  model's `reason` (a new descriptive attribute of `finding-raised`), the rule
  `findings/group-merge` and the facts it cites; it is never applied, and the same suggestion made
  from both sides is stored once. Its basis is evidenced when it cites an evidenced answer. The stored answer holds
  its citations as the item numbers them and a merge's group by key, so it does not depend on the
  items it shared a request with.
- **Not an input.** An earlier run's group names are left out of Describe's and Name groups'
  inputs with the other texts the roles wrote (`Describer.withoutTexts`), so the model never words
  from its own words.
- **Failure.** A group whose answer is omitted, dropped or not asked, or that gives no name, is
  listed as unnamed in the run's log; a line without an intent has none. How they are drawn is
  #530's.
- **The fake model** names every group `{{placeholder: group name}}`, gives it a placeholder
  responsibility and each line a placeholder intent, citing the item's first fact, and suggests no
  merge.

## Consequences

- A run with groups makes Name groups calls: a packet per scope and kind, cached per group, so a
  rerun over unchanged facts makes none.
- The recordings on project-4 and project-8 that the issue's acceptance names need a real model;
  they come with #529.

## Amendment (#760): limits in the role's contract

This ADR's word limits and margins now live in the role's response contract, `prompts/<role>.contract.json`, which the checks read (ADR 0118). Change them there.

## Amendment for #765

- A group no role named is drawn with its key's last part followed by "(not named)", as the
  knowledge-build epic (section 11.8) asks, so a reader can tell it from a name the model chose.
- A line drawn into a group (a queue to the units that consume it) takes the intent the group
  stored for that line, looked up by its other end, as a line leaving the group does: the group
  stores each line from its own side, as its members' facts run.
- Merge suggestions are listed on the risks and findings page of the C4 report and the other
  templates that have one, in an "Other findings" table, opening with "Found by the LLM:": all of
  them in the system's documents, a repository's own feature areas' in its documents.
