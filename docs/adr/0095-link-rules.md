# 0095: Link rules, their order and re-running on change

Status: accepted (#573, a slice of #521), amended for #575, #580 and #576

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
- **Retired rules.** A store analyzed before holds facts of origin `cross-repo-links`. The linker
  lists retired rule names (`Linker.RETIRED`): recording extracted facts leaves them alone, rules
  never read them, and each link ends them, so a carried-forward repository's old links do not
  linger beside the new rules' facts.
- **Links no longer merged across rules.** The step merged a link found by two matchers with the
  same ends and code into one. Now two rules would each give it. Only data stores and Redis both
  give `USES_DATABASE`, to databases named differently (`<server>/<database>`, `<server>/redis`), so
  only a database named `redis` could be given twice.
- `CrossRepoLinks` keeps the vocabulary and constants, and its `derive(facts, snapshot)` runs the
  seven rules together for tests.

## Amendment (#580): the matchers read typed facts

The matchers, the helpers they share (`LinkIndex`, `Addresses`, `Deployments`, `CodeValues`) and the
values rule read a fact's attributes only through the query layer, `Claims`:

- `Claims.of(fact, Record.class)` reads a fact as its type's generated record, and fails for a fact
  of another type, so a wrong record cannot silently give nulls.
- A few attributes many types share are read whatever the type, by name in `Claims` alone:
  `profile`, `written`, `masked`, `unresolved`, `key` and `source`. The views and code sites group
  facts of several types by them.
- Where a matcher reads facts of several types alike, a small record of its own reads each type's
  record: `Endpoints` (HTTP and WebSocket endpoints), `MessageLinker.Message` (sends, listeners,
  bindings, declarations), `DataLinker.Store` (datasources, data stores), `FrontendLinker.Web`
  (calls, proxies).
- A record read fails on a declared attribute of the wrong JSON type, where a read by name gave
  null. Stored facts are checked against their schema; a matcher's link drafts must match it too.
- The README claim checks (moving to derivation in 1D) and the LLM resolver's prompt and tools read
  typed facts too since #576.

The linker's facts on project-0 to project-4 are unchanged.

## Amendment (#576): the link ID, and an architecture rule

- **The claim ID is the link ID.** Links and links left open no longer carry `link`; their claim ID
  is their stable ID, and the LLM resolver names the link it resolves by it (ADR 0042 and 0045,
  amendments for #576).
- **The rest of the linker reads typed facts.** The README claim checks and the LLM resolver read
  records through `Claims`; `Claims.ofOrNull` reads a fact that may be of another type, and
  `Claims.json` gives a copy of a fact's attributes to show or copy whole (a prompt line, a fact
  rebuilt with other evidence, lineage), never to read one by name.
- **Architecture rule.** `LINKER_READS_TYPED_FACTS` (`lantern-archtest`) fails any class of
  `io.lantern.link` but `Claims` and `ClaimQuery` that calls `Fact.attributes()`; a fixture that
  does is rejected.
- **Open links** as a rule of their own wait for views (#583, after #522).

## Amendment (#590): rules per view; `view` and `absentIn`

Decided by the agent under the owner's delegation of 2026-11-01 (milestones 1C, 1B, 1D), following
the knowledge-build epic (section 8, Rules and Views).

- **The linker runs a rule per view.** `LinkRule.perView()` says whether the linker runs a rule
  once in each view: the default view, then each profile the facts it reads name (a fact's
  `profile`, the names in a `profile-gate`), as the matchers' own loop did (`View.of`). The
  protocol rules are per view: their index is narrowed to the one view (`LinkIndex.in`), so a
  matcher finds only that view's links, and finding one in another view fails.
- **The linker merges the views** (`ViewMerge`). A rule gives a claim found in a view with the link
  that makes it the same claim in every view (`ClaimSink.add(fact, link)`; the protocol rules give
  their matchers' link identities, which name the ends and the code on both, never a commit). A
  claim found in the default view is one fact, citing the facts it comes from in every view and
  listing in `absentIn` the views it is not found in; one found only in other views is one fact per
  view, its `view` a key attribute. A fact given without a link holds whatever the view and is kept
  once. This is `FoundLinks`' rule of ADR 0042 moved into the linker, with `profile` named `view`
  and `exceptProfiles` named `absentIn`.
- **A rule may describe a claim across views** once they are merged (`LinkRule.merged`): the HTTP
  rule names in `alternates` the other units and systems a call reaches in other views, which a
  single view no longer shows. The HTTP matcher gives each call's links a group, the unit making
  the call and its code site, which the merge carries through (`ClaimSink.add(fact, link, group)`).
- **The epic** (section 8, Rules and Views) is brought in line: a link's views are the profiles;
  deployment environments are views of value resolution and address resolution only.
- **Not per view: value resolution and address resolution.** Their views include deployment
  environments, whose overlay is a container's environment (ADR 0094, amendment for #588), and
  address resolution answers per profile and per deployment that fills an address in, so each
  resolves its views in one pass, as before. Their claims name the view the same way: value
  resolution's literal for a profile or a deployment carries `view` (a profile's name, or the
  deployment's `<repository>|<platform>|<environment>[|<compose file>]|<container>`), its default
  view's lists both in `absentIn`, profiles first; address resolution's answers carry `view` for
  the profile asked in and keep `deployment` for the container that filled the address in, which is
  part of the answer, not a view: one answer may hold under a profile and be filled by a deployment.
  The other rules (units, people, README claim checks, the LLM resolver) find no links per view;
  the units' `application-name` per profile carries `view` too.
- **Readers.** The composer reads a fact's view as `view`, else an extracted fact's `profile`
  (`Knowledge.view`), and `absentIn` where it read `exceptProfiles`; the LLM resolver reads an open
  link's `view` and `absentIn` and gives the link it picks the same. The prompt's text is unchanged.
- **Vocabulary.** `view` and `absentIn` are declared on every link-stage type that had `profile`
  and `exceptProfiles`; `view` replaces `profile` among the key attributes. `profile`,
  `exceptProfiles` and value resolution's `exceptDeployments` stay declared, no longer given, so
  facts stored before stay valid; they are descriptive. Extracted facts keep `profile`.

On project-0 to project-4 and project-8 every link and every other derived fact is the same once
the names are mapped; only the IDs that README claim checks cite change with the links they cite
(#590's PR).

## Amendment (#591): placeholders resolved in one place; a narrow re-run

Decided by the agent under the owner's delegation of 2026-11-01, finishing #522.

- **Architecture rule.** `PLACEHOLDERS_RESOLVED_BY_VALUE_RESOLUTION` (`lantern-archtest`) fails any
  class but value resolution's (`ValueResolution`, `CodeValues`, `Literals`, `EnvironmentValues`),
  the config extractor's package and the SQL extractor (with its `Settings`) that calls what resolves placeholders
  against config or reads config to resolve with (`Placeholders.resolve`,
  `ConfigExtractor.resolve`, `resolveWithoutConfig` and `read`, `ModuleConfig.resolve` and `values`, `ModuleValues.of`,
  `CodeConfig.forModule`, `CodeConfig.View.resolve`), or that implements `ValueLookup` or
  `GatewayExtractor.Resolver`, the config the extractors' resolving functions are given. Those
  functions stay in the extractors, next to the code they read, but only value resolution can give
  them a config. A fixture that resolves a placeholder and one with a config of its own are
  rejected.
- **What stays.** The config extractor still resolves a setting's placeholders from the same
  module's config when it records the setting (`config-value`'s object, with `written` as
  written), as the epic's section 7 keeps it ("as now"); the SQL extractor still reads config to
  find its migrations (ADR 0094, amendment for #569). Neither resolves a value written in code.
- **A narrow re-run.** A rule may say what of a fact it reads its facts depend on
  (`LinkRule.inputOf`); the linker digests that part instead of the whole fact when deciding
  whether to run the rule again. The units depend on a `config-value` only for
  `spring.application.name` and for which module has config of which family, so a change to any
  other setting runs value resolution and the rules that read config or what value resolution
  writes again, and neither the units nor the people (`LinkRulesIT`). The units are the rule this
  narrows: the others that read config read every extracted fact and run again on any change, until
  1B's narrower reads. A rule's facts must cite only
  facts whose whole it depends on; the units cite only the name settings.
