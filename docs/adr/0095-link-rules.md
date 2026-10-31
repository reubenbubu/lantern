# 0095: Link rules, their order and re-running on change

Status: accepted (#573, a slice of #521), amended for #575

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 8, Rules) makes the linker
a set of rules that declare what they read and write, run in the order those give, and re-run only
when what they read changed. Until now the linker ran a fixed list of steps, each handed every fact
of the snapshot, and ran all of them on every analysis. This record says how the framework is built;
#574 to #576 split today's steps into the epic's rules and move them to typed queries.

## Decisions

- **`LinkRule`.** A rule has `info()` (its name, version and origin kind, `RuleInfo`), `reads()`,
  `writes()` and `derive(ClaimQuery, ClaimSink, View)`, as the epic gives it. `reads` names
  predicates, or `LinkRule.EXTRACTED` for every fact the extractors gave; the epic's entity-type
  reads come with the first rule that needs one. `LinkStep` goes; today's steps implement
  `LinkRule.OverFacts`, which hands `derive(List<Fact>, long)` the facts the rule reads.
- **Reads and writes are enforced.** A rule is handed only the facts of what it reads: the
  extracted ones (all of them under `EXTRACTED`, else those of the predicates named), then those of
  the predicates named that the rules before it gave, rule by rule, each rule's in ID order. Asking
  `ClaimQuery` for a predicate not declared fails, and `ClaimSink` rejects a fact of another origin
  or of a predicate the rule does not write. A wrong declaration therefore fails or shows in the
  output, rather than leaving a rule stale.
- **Typed queries.** `ClaimQuery.all(Class)` and `from(subject, Class)` read facts as the generated
  attribute records (ADR 0090), found by their `TYPE` and `from`. `all(predicate)` and `facts()` give
  facts as they are, for rules without a record and for the steps until they are ported.
- **`EXTRACTED` covers only extracted facts.** Under it a rule may ask for any extracted
  predicate, but asking for one another rule writes, without naming it, fails like any undeclared
  read.
- **A rule's name is not an extractor's.** The linker tells a rule's facts from the extracted ones
  by origin name, so a rule named as an extractor would take that extractor's facts for its own
  (#574: the people rule is `persons`, the extractor `people`); a test checks the standard rules.
- **Order.** A rule runs after every other rule that writes a predicate it reads, and otherwise in
  the order the linker is given. A rule never reads its own facts, so a predicate it both reads and
  writes (`technology`, which the units and the cross-repo links both give) is read from the others.
  Two rules may write one predicate. Two rules with one name, or rules that read each other's facts
  in a cycle, fail when the linker is built, naming the rules.
- **The order changes in one place.** The README claim checks read the link predicates the LLM
  resolver also writes, so they now run after it, where the epic puts them (in derivation, 1D),
  rather than before it. With the fake model the resolver picks nothing on the development sets, so
  no document changes. With a real model the claim checks also see the links the LLM picked, as the
  epic intends, and the resolver's graph tools (ADR 0081) no longer see `claim-contradiction`
  facts, which they had no use for.
- **Derived facts in ID order.** The rules after a rule read its facts in ID order, as the store
  gives facts, rather than in the order it gave them, so a rule not run again hands on the same
  order a run would. The fake-model scorecards are unchanged by it.
- **Re-running.** The linker remembers, per system (its sorted repository names) and rule, the
  rule's version, the digest of Lantern's code (`LanternCode`, the class-path digest the extraction
  cache keys on, ADR 0092) and SHA-256 digests of the facts it read and of those it gave as stored
  (each fact's ID, claim ID and confidence), in a new table `link_rule_runs` (`JdbcRuleRuns`), written in the
  transaction that records the facts. A rule is not run again when its version and input digest are
  those of its last run and the facts of its origin that hold are still those it gave; its facts then
  keep holding, and the rules after it read them as before. A fact's ID covers its evidence and
  attributes, so a fact added, ended or changed changes the digest; the claim ID covers a change to
  a type's key attributes. Versions are not bumped for every change to a rule or the helpers it
  shares, so the code digest is what keeps a new Lantern from serving an old rule's facts: any new
  build runs every rule once. A rule whose facts depend on
  more than what it reads says so (`pure()` false) and runs every time: the LLM resolver, whose
  answers and model live outside the facts (it asks the LLM only about links it has no answer for).
  `RuleRuns.NONE` remembers nothing, for the tests that link directly.
- **Views.** `derive` takes a `View`; until #522 runs rules per view, the linker runs each rule once
  in the default view and the rules find every profile's links themselves, as before.

## Consequences

- A second analysis of an unchanged system with the same build runs only the LLM resolver; one that changes a file
  runs the rules whose input that changes, and those downstream whose input then changes.
- `Linker.Result` lists the rules that ran, and the analysis log names them.
- The fake-model scorecards on project-1 to project-4 and project-8 are unchanged (#573's PR).

## Amendment (#575): one rule per protocol

The cross-repo links step is split into seven rules (`ProtocolLinks`), each with its own origin:
`http-links` (HTTP calls, gateway routes and frontend calls, kept together because the routes read
the HTTP links found before them and frontend calls reuse the HTTP and route matching),
`messaging-links`, `deployment-links` (the containers units run in), `data-links`, `mail-links`,
`identity-links` and `redis-links`. Each builds its own index of what it reads and finds its links
in every view as the step did. A link left open stays the `unresolved-link` of the rule that left it
until #576.

- **Reads.** Until views are explicit (#522), each rule reads what the step read, every extracted
  fact and the values' and units' facts, because the profiles a rule links in are those every fact
  names. The units' `technology` is left out: several protocol rules give `technology` too (of
  brokers, databases, frontends), no matcher reads it, and reading it would make the rules wait on
  each other in a cycle.
- **Writes.** Several rules give `deployed-in` (frontends, identity providers, data stores and the
  units' containers), `display-name` and `technology`, each for the entities it makes; on project-0
  to project-4 no fact is given twice.
- `CrossRepoLinks` keeps the vocabulary and constants, and its `derive(facts, snapshot)` runs the
  seven rules together for tests.
