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

- Four composer classes still branch on a fact's predicate name, through the record
  `ArchitectureModel.statement(fact)`: `C4Views` (the relation's verb), `ExplorerSections` (entry
  point kinds) and `ClaimContradictions` (wording a fact). The rule holds, since the composer
  calls no `Fact.predicate()`, but the names are still read by the composer. Replacing those
  switches with typed queries is a follow-up (not in #761).
- The model keeps public pass-throughs of the index's queries (`about`, `attributes`,
  `withPredicate`, `relations`, `withOrigin`). Composer code filters through them. Narrowing
  them is a follow-up.

- The composer still depends on `lantern-derive` and `lantern-summarize` (for `Describer.Phrase`
  and `FlowJudgements.Flow`, and for the stage's descriptions in the document model). Moving
  derivation out of composition is the epic's later milestones (#554 waits for that).
- Derive gained public records; a derive query that a composer needs now has a model overload.
