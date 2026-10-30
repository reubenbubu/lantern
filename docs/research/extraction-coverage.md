# Extraction coverage

Research for #380, the first issue of the milestone "Extractor revisit", redone at high depth for
#410. It leads to `docs/adr/0089-extraction-coverage.md` (amended for #410) and the extraction
coverage check in `docs/design.md`, Checking the quality bar.

**Depth: high.** How to measure extraction is a rule the milestone measures itself by, and the
first version of this note leaned on five sources seen only in search results. Thirteen sources
were read in full this time, and seven more in part.

## Question

How do architecture-recovery, code-intelligence and static-analysis tools measure whether fact
extraction is complete and correct against a ground truth?

- **Matching.** How do they match an extracted item to a ground-truth item: by location or by
  name?
- **Stages.** How do they score the stages of a pipeline separately?
- **Ground truth.** How do they build and validate their ground truths?
- **Pitfalls.** What do they report?

For Lantern, the question is whether a fact of the right area, with evidence on the code a
reference item cites, splits the scorecards' losses into extraction losses and linking losses.

## What we learned

**Matching by location or by name**

- **CodeQL's inline expectation tests** match on file and start line. Both the tag and the value
  must equal the actual result. Differences are reported as "Missing result" or "Unexpected
  result". Known gaps are marked `MISSING:` or `SPURIOUS:` and show as "Fixed …" once they go.
  Location alone is not enough there: the content has to match too.
  <https://raw.githubusercontent.com/github/codeql/main/shared/util/codeql/util/test/InlineExpectationsTest.qll>
- **GitHub code scanning** matches results across runs by fingerprint, not line number:
  `primaryLocationLineHash` with the rule and the file path. The hash covers the first 100
  characters of the line without whitespace, so a match survives lines moving but not a changed
  line. A changed path makes a new alert.
  <https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning>,
  <https://raw.githubusercontent.com/github/codeql-action/main/src/fingerprints.ts>
- **The OWASP Benchmark** matches by test-case file and vulnerability type, not line. It scores
  with Youden's index (true-positive rate minus false-positive rate). Rules tuned to the benchmark
  must be disclosed; the wiki calls one such tuning "a bit like cheating".
  <https://wiki.owasp.org/index.php/Benchmark>
- **Habib and Pradel** (ASE 2018) matched bug-detector warnings to fixed bugs by line overlap with
  the fix. Overlap gave coincidental matches (a "Missing @Override" warning on a changed method),
  so every candidate was checked by hand as full, partial or mismatch.
  <https://www.sambuz.com/doc/how-many-of-all-bugs-do-we-find-a-study-of-static-bug-ppt-presentation-864150>
- **NIST SATE IV** matched warnings to CVE locations by hand and calls the procedure limited.
  <https://www.nist.gov/itl/ssd/software-quality-group/static-analysis-tool-exposition-sate-iv>
- **The 2024 comparison of nine microservice-recovery tools** (Schneider, Bakhtin, Li, Soldani,
  Brogi, Cerny, Scandariato, Taibi) counted matches by hand, two authors and a third for
  disagreements, as "the safest method". It scores components, connections and endpoints
  separately (Code2DFD: components F1 0.98, connections F1 0.87). Deployment files alone gave high
  precision and low recall. <https://arxiv.org/html/2412.08352v1>
- **Relation extraction** names three strictness levels:
  - Strict: span boundaries and type right.
  - Boundaries: span boundaries only.
  - Relaxed: one correct token.

  Comparing a Boundaries score with an earlier Strict one overstated results by 2.5 to 3 F1
  points. The authors ask that every paper report Strict and name its setting.
  <https://arxiv.org/html/2009.10684v3>

**Scoring stages separately**

- **SWE-bench** separates retrieval from generation with an "oracle" setting that hands the model
  the files the reference patch edits. Claude 2 solved 4.8% of tasks with the oracle and 1.96%
  with BM25 retrieval. It also measures retrieval alone: BM25 found none of the oracle files for
  almost half the tasks. The oracle is called "less realistic". <https://arxiv.org/html/2310.06770>
  (read in part)
- **Lutellier et al.** (TSE 2017) hold recovery techniques fixed and vary only the input facts:
  include versus symbol dependencies, granularity and dynamic binding. "Using accurate symbol
  dependencies has a major influence on recovery quality." Extraction errors carry into the
  architecture, so extraction is worth measuring on its own.
  <https://www.cs.purdue.edu/homes/lintan/publications/archrec-tse17.pdf>

**Ground truth**

- **Garcia et al.** split the work between a recoverer and a certifier, an engineer of the
  system. The recoverer follows mapping principles in eight steps, and the certifier corrects the
  result.
  <https://icsa-conferences.org/series/WICSA/2012/wp/assets/WEB-A-Framework-for-Obtaining-the-Ground-Truth-in-Architectural-WICSA-2012-Presentation-v.03.pdf>
- **Lutellier et al.** on ground truths:
  - Effort: about 100 hours for 70K to 280K lines of code.
  - Pinning: each is pinned to a version. A stale Bash ground truth was redone.
  - MoJoFM: assumes both sides hold the same files, and can score 100% "completely
    artificially".
  - c2c_cvg: counts a ground-truth cluster as covered past an overlap threshold.
  - Several metrics: a ground truth may not be unique, so they use more than one.

  <https://www.cs.purdue.edu/homes/lintan/publications/archrec-tse17.pdf>
- **Code2DFD** (Schneider and Scandariato, JSS) records each item's evidence as file and lines,
  but its evaluation matches by identity: nodes by name, flows by sender and receiver. The
  evidence lines are not scored. One author wrote its ground truth. Three others checked a 10%
  stratified sample. The authors had seven of the 17 apps while building the tool.
  <https://arxiv.org/html/2304.12769> (body read in full, references not)
- **microSecEnD** publishes those diagrams with "full traceability" but documents no format, pins
  or validation. <https://tuhh-softsec.github.io/microSecEnD/>

**Pitfalls**

- **Structurizr** says it is "almost impossible to create a generic set of rules" for finding
  components. Rules are specific to each codebase. Rules tuned to the sets they are tested on are
  the risk. <https://docs.structurizr.com/java/component/introduction>
- **The 2024 study** chose tool combinations by F1 on the same 17 apps it reports them on, so
  those scores are likely optimistic. <https://arxiv.org/html/2412.08352v1>

## What the first note missed or got wrong

- **Not new.** It said no source scores evidence against cited lines. Bug-detector studies and
  SATE do, and they report coincidental matches that needed a hand check. Lantern's match is not
  new, and its weakness is known.
- **A looser rule than its precedent.** CodeQL anchors expectations to a line and also requires
  the content to match, so ADR 0089's "not the fact's content" is looser than the precedent it
  cited.
- **By hand, not by name.** The 2024 comparison matched by hand.
- **Structurizr.** The page the note cited is gone. The current docs describe generic matchers.
- **Recall only.** It never said that coverage measures recall only.
- **No rule for editing the maps.** The first baseline's 66 citation fixes moved citations to
  where extractors record things. Fixing a map from extractor output fits the map to the
  extractor. The ADR's sentence "Every miss left is a thing no extractor finds" overclaims.

## Evidence from the test sets

The references of project-1 to project-4 cite file:line for every item in their `facts/*.md`.
#380's baseline (on #380) backs most data stores and connections that the scorecards miss:
project-1 has 4/4 data stores backed but 1/4 in the report. So the losses are mostly linking. The
matches behind those counts were not checked by hand.

## Options

1. **Overlap with any area.** Cheap, but too lenient.
2. **Match by name per kind** (Code2DFD). Brittle, and it ignores the citations.
3. **End-to-end recall only.** Cannot separate the stages.
4. **Overlap restricted to the expected area** (the current rule). It separates the stages
   cheaply and diagnoses misses. It is lenient: content is not checked, and a fact without lines
   covers its whole file.
5. **Two strictness levels.** Add a strict level that also requires the fact to name the item (a
   service, key, table, topic or URL). This costs a name per need.
6. **Oracle linking**, as in SWE-bench. Feed facts written from the reference into the linker to
   measure its ceiling. It is direct, but writing facts by hand is costly and unrealistic.
7. **Known-gap markers**, as in CodeQL. These make before-and-after comparisons read "fixed
   missing". They add a line per known gap.

## Recommendation

Keep option 4, and tighten it where the sources show its weakness:

- **Recall only.** Coverage measures recall. Precision stays with the scorecards.
- **Weak matches.** A need met only by a fact or citation without lines is weak. It is reported
  apart, not counted as backed where the area records lines (#411).
- **Line hashes.** `coverage.mjs check` compares a hash of each cited first line, so a pin move
  flags drifted citations (#411).
- **Hand check.** At each baseline and test drop, check a sample of backed items by hand. Record
  each as full, partial or mismatch.
- **Map edits.** A citation changes only on the authority of the reference's `facts/*.md`, never
  because an extractor records the thing elsewhere. Each change gives its reason.
- **Held-out sets.** No maps for the held-out sets before 2D.2. Extractors are never tuned to a
  map.
- **Deferred.** The strict level (option 5) waits in `Later` (#412), since the hand check covers
  coincidental matches for now. Oracle linking and known-gap markers are not adopted.

## What it changes

- `docs/adr/0089-extraction-coverage.md`, amended for #410.
- `docs/design.md`, Checking the quality bar: coverage measures recall only, with a sampled hand
  check.
- `docs/quality-bar.md`: nothing. Coverage measures Lantern's extraction, not its output.

## Sources read in full

- CodeQL, `InlineExpectationsTest.qll`
- GitHub, SARIF support for code scanning
- codeql-action, `fingerprints.ts`
- OWASP Benchmark wiki
- Lutellier et al., TSE 2017 (approach, ground truth, metrics and threats; most result tables
  skimmed)
- Garcia et al., WICSA 2012 slides
- Schneider et al. 2024, the nine-tool comparison
- Taillé et al., "Let's Stop Incorrect Comparisons in End-to-end Relation Extraction!"
- Habib and Pradel, ASE 2018 talk slides
- NIST SATE IV page
- Structurizr component docs
- microSecEnD site and repository
- ICSE 2013 page of Garcia et al. (an abstract only)

## Sources not read or read only in part (carry no weight)

- **Read in part:**
  - Code2DFD, arXiv 2304.12769: the body, not the references.
  - SWE-bench, arXiv 2310.06770: the first half, including the oracle sections.
  - Chen et al., "Development of Robust Traceability Benchmarks": the abstract and parts only.
  - The SARIF 2.1.0 standard: §3.27 and the glossary.
- **Not reachable:**
  - Garcia, Krka, Mattmann and Medvidovic, ICSE 2013 (the full text).
  - Hayes et al. on IR tracing.
  - Structurizr's old Spring strategies page (404).
  - Habib and Pradel's paper itself.
- **Abstracts and search results only:** Garcia, Ivkovic and Medvidovic, ASE 2013; the OWASP
  BenchmarkUtils README; the Juliet and SATE V reports; SecBench.js.
- **Not reopened:** Prophet, jQAssistant and Backstage, cited by the first note.
