# 0119. The composer reads only the typed model

Status: accepted (#761)

## Context

Composition reads the model (`docs/design.md`, Composition; knowledge-build epic, section 12):
the composer should read only `ArchitectureModel`'s queries. In practice it still read raw
facts: about 60 calls to `Fact.attributes()` and `Fact.predicate()`, about 80 calls into the fact
index (`Knowledge`), and fields read by key through helpers such as `RepoSections.attribute`.
The end-of-life support of runtimes and frameworks (ADR 0039, #619), the test evidence of the
brief, the investigated risks and the configuration values were worked out in the composer.
`Wordings`, a bundle of the LLM's texts, was passed around although the texts are stored facts
the model reads already (ADR 0116).

## Decisions

- **The composer reads through `ArchitectureModel` only.** It has no `knowledge()` method. The
  index is package-private to `lantern-derive`, and the derive query classes read it. The
  composer imports no `Knowledge` class and calls no `Fact.attributes()` or `Fact.predicate()`.
- **What the composer shows is decided in derive, as typed records.** `TechnologyCurrency`
  (versions in use, their end of free support, the summary and risk sentences), `TestEvidence`
  (test classes, coverage tools and the minimums JaCoCo enforces), `InvestigatedRisks`,
  `ConfigValues`, `DeploymentInstances.deployed` (the deployment table's rows),
  `ArchitectureModel.contradictions` (README claims the code contradicts), `buildDependencies`,
  `statement` (a fact's relation or value, for wording it), and typed `Findings`,
  `ComponentInteractions` and `Elements` records. The composer keeps presentation: callouts,
  tables, sorting of what it draws, and the confidence of a risk.
- **`Groups.of`, `Findings.of` and the end-of-life text are read from the model.** The model
  overloads of these queries take an `ArchitectureModel`; the end-of-life sentences moved
  into `TechnologyCurrency`.
- **`Wordings` is removed.** Its texts are the stored LLM facts, which the model reads. The
  describer's result keeps what a run did, plus the set of facts that showed Javadoc (`documented`,
  ADR 0069), which the stored sentences do not record. The flow narratives' type, `FlowJudgements.Flow`,
  and the intent phrase type, `Describer.Phrase`, replace the nested types it held. Tests read a run's
  texts back through the model in `TestTexts`.
- **An architecture rule enforces it.** `COMPOSER_READS_THE_MODEL` in `lantern-archtest` fails a
  composer class that depends on `io.lantern.derive.Knowledge` or calls `Fact.attributes()` or
  `Fact.predicate()`. Its fixture, `ComposerReadingFactFields`, makes the rule fail in the tests.
- **The output does not change.** The fake-model documents of project-0, project-1, project-2,
  project-3, project-4 and project-8 are byte-identical before and after (123 files, compared
  after masking the snapshot number and the template version, which change on every run).

## Consequences

- The composer depends on `lantern-derive` alone among the stages; `lantern-summarize` is a
  test dependency, for the tests that run the roles whose texts the composer reads.
- Derive gained public records; a derive query that a composer needs now has a model overload.

## Amendment for #761's review (5 Nov 2026): no predicate names in the composer

The first PR left the composer switching on a fact's predicate through
`ArchitectureModel.statement(fact)` and filtering through the model's public pass-throughs to its
index. Both are gone:

- What the composer branched on is now a typed answer in `lantern-derive`: an entry point's kind
  and wording (`EntryPoints`), the decision records (`Decisions`), how a contradicting fact reads
  (`ArchitectureModel.reading`), a profiled link's verb (`Elements.alternativeVerb`), how a
  schema is migrated (`ArchitectureModel.migration`), and small queries named for what they answer
  (`readmeClaims`, `documentationTexts`, `linksOf`, `packagingFacts`, `buildDependencyFacts`,
  `moduleBuildDependencies`, `mainClassFacts`, `groupMembers` and the like). Values the composer
  printed from a fact's object are read through record accessors (`ConfigValues.Value.value()`,
  `Unknowns.call`, `UnitInteractions.Held.pushedTo()`, `InvestigatedRisks.Risk.key()`).
- `ArchitectureModel.Statement` and `statement(fact)` are removed, and the pass-throughs `about`,
  `attributes`, `attribute`, `withPredicate`, `relations` and `withOrigin` are package-private to
  `lantern-derive`, so the compiler keeps the composer off them.
- `COMPOSER_READS_THE_MODEL` now also fails on `Fact.object()` and on any dependency on a stage
  package (`io.lantern.extract`, `link`, `summarize`, `harness`, `llm`, `investigate`), the rule
  ADR 0093's amendment for #554 handed to this issue. Fixtures for `Fact.object()`, the
  `Knowledge` import and each stage package (Investigate's through a stand-in package, since it
  does not exist yet) are rejected by tests.
- `linksOf(from, interaction)` and `describing(element)` stay public as typed queries: the links of
  one interaction kind an element has, and what the stage stored about an element. The composer
  names the interaction it draws, not a fact's predicate it switches on.
- The output does not change: the fake-model documents of project-0 to project-4 and project-8
  are byte-identical before and after (#761's second PR).
