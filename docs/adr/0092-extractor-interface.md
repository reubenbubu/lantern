# 0092: One extractor interface, run in order of requirements, cached by declared unit

Status: accepted (#279); amends ADR 0016 (how the analysis run calls the extractors)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 7, Contract) puts every
extractor behind one interface. The extractors are found through `ServiceLoader`, run in the
order their requirements give, and their claims are cached per file, keyed by the file's git blob
plus declared companion files. Until now the analysis run called seventeen extractors by hand, in
a fixed order, with different signatures, and extracted everything on every run.

Most extractors' facts depend on more than their own file. Module config resolves the
placeholders in code. The secret keys the identity extractor finds mask values in config, docs and
code. Java type and call resolution, Maven's choice between internal and third-party dependencies,
and Spring Cloud Config read across repositories. A cache keyed by one file's blob would serve
stale facts after a config change. So the owner decided on #279 to cache by a unit that each
extractor declares.

## Decisions

- **The interface.** `io.lantern.extract.Extractor`:
  - `info()` gives the extractor's name and version, the origin name and version of its facts.
  - `requires()` names the extractors whose facts it reads.
  - `unit()` is its cache unit.
  - `appliesTo(repo)` filters repositories.
  - `extract(context, unit, sink)` emits the facts of one unit.

  `ExtractionContext` gives the snapshot number, its repositories with their builds, the shared
  code model (`JavaAnalysis`, built once, and only when an extractor that needs it misses the
  cache), and the facts of the required extractors. `ClaimSink` takes the facts and refuses one of
  another origin. Every extractor implements the interface. Their existing `extract` methods stay
  for tests and fixtures.
- **Discovery and order.** `Extractors.load()` finds the extractors through `ServiceLoader`
  (`META-INF/services/io.lantern.extract.Extractor`). It sorts them topologically by `requires`,
  with ties broken by name, and refuses a missing requirement or a cycle. Today the only
  requirement is on the identity extractor, whose JWT secret keys mask values. Config, docs,
  Spring, persistence, HTTP client, messaging and gateway require it (ADR 0032, amendment for
  #335). The extractors run in waves: each wave holds every extractor whose requirements earlier
  waves met, and runs them at the same time, one platform thread each (git reads its output on
  virtual threads, which must find a free carrier while extractors wait on the shared code model).
  Their facts are concatenated in the sorted order, so the result does not depend on timing.
  An extractor's several units are merged in the order the extractor gives the facts of several
  repositories (`Extractor.order()`), so a run gives each extractor's facts exactly as its own call
  over all repositories did.
- **Units.** An extractor declares one of three units:
  - **file**: one file of one repository, from the files it lists (`files(context, repo)`). The
    docs extractor, whose facts come from one README, ADR or document each.
  - **repository**: one repository with its build: deployment, frontend, SQL and test, which read
    only their own repository, and Maven and config (amendment for #547, below).
  - **snapshot**: every repository of the snapshot: Java, identity, Spring, persistence, HTTP
    client, messaging, WebSocket, mail, Redis, people and gateway. They read the code model, which
    stays a shared input across repositories (the epic's section 7).

  Extractors move to smaller units as their cross-file inputs go. #519 keeps values as written.
- **The cache key** is the SHA-256 of the canonical JSON of:
  - the extractor's name and version, and a digest of the code extraction runs: the JDK's version
    and every class path entry (a class folder by content, a jar by path, size and modification
    time; under `java -jar` that is the one application jar). So a change to any of Lantern's code
    or libraries, not only to an extractor, never serves old facts even if no version was bumped;
  - the unit (a snapshot unit is keyed on every repository of the snapshot, not only those its
    extractor applies to):
    - **file**: the repository's name, the path and the file's blob.
    - **repository**: the repository's name, its git tree SHA at the commit, and its build's
      fingerprint; plus what it declares it reads of the other repositories (amendment
      for #547).
    - **snapshot**: every repository's name, tree SHA and build fingerprint.
  - the IDs of the required extractors' facts.

  A build's fingerprint is its status, JDK and image, the content hash of its effective POM, and
  its modules: their coordinates, dependencies and classpath, plus a hash of the content of their
  generated sources. Folders that change with every build are left out. A tree SHA, unlike a
  commit, stays the same when a commit changes no file. Since evidence names no commit (ADR 0091),
  cached facts are the facts a new extraction would give, valid from the new snapshot.
- **The store** is the table `extraction_cache` (migration V20): the extractor, its version, the key,
  the facts as envelope JSON, and when the entry was last used. An entry not used for 30 days is
  deleted after each run. The tests use an in-memory cache. An entry that no longer reads as facts (stored by an older Lantern whose fact format changed) is deleted and its unit extracted again. `ExtractionCache.prune()` does the deleting; the JDBC cache answers only for the extractor's current version.
- **Carried-forward repositories** are extracted as before: snapshot-unit extractors see them, and
  only the named repositories' facts are recorded.

- **Not built here:** the epic's `ExtractorInfo` also names each extractor's vocabulary, and its
  `ClaimSink` validates each fact against it. Here the sink checked only the origin; #551 built the
  rest (amendment for #551 below).

## Consequences

- A second run of an unchanged snapshot extracts nothing. It reads every unit from the cache, and
  even the code model is not built.
- After a change to one README, only that file is extracted again. After a change to one
  repository's deployment files, only that repository's deployment, frontend, SQL and test units
  are extracted again, along with every snapshot-unit extractor. The test is `ExtractorsTest`.
- The fake-model scorecards on project-1 to project-4 and project-8 are unchanged (#279's PR).

## Amendment for #547: repository units that read other repositories

#547 set out to move the extractors' cross-repository decisions to the linker, so that Maven,
config, WebSocket and Java could be cached per repository. Reading the code showed two things the
plan missed:
- Maven reads parent POMs in other repositories for a repository whose build fails (properties,
  managed versions, plugins, and through them which modules are deployable). Moving that to
  linking needs new fact types.
- Java and WebSocket read the code model, shared across repositories, so no move makes them
  repository units.

The owner chose the narrower step on #547:
- **Maven and config are repository units** whose key also covers what they read of the other
  repositories, declared by `Extractor.readsElsewhere(path)` and `readsOtherBuilds()`. The key
  adds, for each other repository, the blobs of the files it reads (every XML file for Maven,
  since a module's POM need not be named `pom.xml`; those and Spring `application` and
  `bootstrap` files for config), and for Maven the paths, not the contents, of the `.java` files
  under `src/main/java`, whose count decides which library a service packages
  (`readsPathElsewhere`), and the build fingerprint, whose effective POM gives other modules'
  coordinates.
- **Each unit gives its own repository's facts.** The extractor computes the snapshot's facts once
  per run (`ExtractionContext.memo`) and each unit keeps those whose evidence is in its
  repository; the facts and documents are those of before.
- **Effect.** Editing code in one repository re-extracts only that repository's Maven and config
  units. Adding or removing a source file re-extracts every repository's Maven units; a change to
  a POM or Spring config file re-extracts every repository's units that read it.
- **Java and WebSocket stay snapshot units.** Moving the joins into linking, as the epic intends,
  comes with value resolution (#283).

## Amendment for #551: each extractor declares its vocabulary, and the sink validates

The epic's contract (`docs/epics/001-knowledge-build.md`, section 7, Contract) has `ExtractorInfo`
name the extractor's vocabulary and its key attributes, and a sink that validates each fact
against them. The agent decided the points below under the owner's standing go-ahead for 1B.

- **`ExtractorInfo` carries the vocabulary.** Besides name and version, it holds the source the
  types are registered under (`maven-extractor`), the types themselves, read from the extractor's
  resource in `vocabulary/`, each with its key attributes (ADR 0090), and the types it
  **borrows**: those another extractor's vocabulary defines that it also emits. Config and
  Deployment borrow Spring's `cors-policy`, the HTTP client extractor messaging's `payload-type`
  (ADR 0096), WebSocket messaging's `SENDS_TO`, and SQL Java's `kind`. A type stays defined once,
  by one source, so the registry's ownership and the additive-change rule are unchanged.
- **`ExtractorVocabularies` is gone.** `Extractors.vocabularies()` gives the sources of the
  extractors `ServiceLoader` finds, ordered so that each type's parent is built in or comes from
  an earlier source; the app, the migration that needs the vocabulary (V19) and the tests use it.
- **The runner's sink validates every fact** an extractor emits, before it is cached: its
  predicate must be built in, or a type the extractor's vocabulary defines or borrows, and the fact
  must pass the vocabulary's check over the built-in types and every extractor's (entity types
  known, the object of the right kind, the attributes as the type's schema declares them, every
  required one present). A refused fact fails the extraction with the extractor's name, as an
  origin mismatch already did. A borrowed type no vocabulary defines fails when the runner is built.
- **"A missing key attribute" means a required one.** An optional key attribute that is absent is
  part of the claim as written (absent is a value: the default view, no profile), so only the
  schema's required attributes are enforced. Making every key attribute required would change most
  vocabularies, which the additive-change rule refuses on existing databases.
- **The sink still takes finished facts.** The epic's sink builds each fact from its parts and fills
  in its origin. The extractors already build facts through their own helpers, with the origin from
  their `info()`, which the sink checks; rewriting eighteen extractors' helpers to hand over parts
  would change no fact, so it waits until an extractor is rewritten for another reason (out-of-process
  extractors and learned rules, epic section 7, will need it).
- **Consequences.** No extractor's output changes, so no version rises and the scores stay the
  same. A new extractor fact type that its vocabulary does not define now fails the tests that run
  extractors through the runner, rather than the first database write.
